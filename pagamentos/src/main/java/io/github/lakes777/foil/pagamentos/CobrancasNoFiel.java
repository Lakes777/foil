package io.github.lakes777.foil.pagamentos;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.lakes777.foil.comum.eventos.Saida;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.PagamentosEventos.CobrancaCancelada;
import io.github.lakes777.foil.contratos.PagamentosEventos.CobrancaCriada;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoAprovado;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoEstornado;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoRecusado;
import io.github.lakes777.foil.contratos.Topicos;
import tools.jackson.databind.json.JsonMapper;

/**
 * Os passos das cobranças do Fiel no banco (V5). Cada passo confere a situação de agora antes de mudar (outro passo
 * pode ter chegado antes: o aviso do pagamento e o CancelarCobranca correm juntos) e grava a resposta à saga na
 * saída, na mesma transação.
 */
@Service
class CobrancasNoFiel {

	private static final Logger log = LoggerFactory.getLogger(CobrancasNoFiel.class);

	/**
	 * Enquanto o trabalhador fala com o Fiel, ninguém mais pega a linha (se ele cair, ela volta depois disso). Cobre o
	 * lote inteiro no pior caso: cada linha faz até 3 pedidos HTTP de 3 s para conectar + 5 s de resposta, em
	 * sequência, mais folga (a mesma conta do Entregador do Fiel).
	 */
	static final Duration RESERVA = Duration.ofSeconds(3L * (3 + 5) * TrabalhoNoFiel.LOTE + 30);

	/** Uma linha que o trabalhador pegou para fazer agora; {@code traceparent}: o trace da saga (fase 9). */
	record Trabalho(UUID pedidoId, String situacao, Integer valorCentavos, Long fielId, int tentativas,
			String traceparent) {

	}

	private record Situacao(String situacao, int valorCentavos) {

	}

	private final JdbcClient jdbc;

	private final Saida saida;

	private final JsonMapper json;

	private final Clock relogio = Clock.systemUTC();

	CobrancasNoFiel(JdbcClient jdbc, Saida saida, JsonMapper json) {
		this.jdbc = jdbc;
		this.saida = saida;
		this.json = json;
	}

	/** Pega até {@code quantos} trabalhos vencidos e os reserva por {@link #RESERVA}. */
	@Transactional
	List<Trabalho> reservar(int quantos) {
		var lote = jdbc.sql("""
				select pedido_id, situacao, valor_centavos, fiel_id, tentativas, traceparent from cobranca
				where forma = 'fiel' and situacao in ('pendente', 'cancelando', 'estornando') and proxima_em <= now()
				order by proxima_em limit :quantos for update skip locked
				""").param("quantos", quantos).query(Trabalho.class).list();
		if (!lote.isEmpty()) {
			jdbc.sql("update cobranca set proxima_em = now() + make_interval(secs => :segundos) "
					+ "where pedido_id = any(:pedidos)")
				.param("segundos", RESERVA.toSeconds())
				.param("pedidos", lote.stream().map(Trabalho::pedidoId).toArray(UUID[]::new))
				.update();
		}
		return lote;
	}

	/** Falhou de um jeito que passa: tenta de novo com espera crescente (1, 2, 4... até 60 s). */
	@Transactional
	void adiar(Trabalho t) {
		var espera = Math.min(60, 1L << Math.min(t.tentativas(), 6));
		jdbc.sql("""
				update cobranca set tentativas = tentativas + 1, proxima_em = now() + make_interval(secs => :espera)
				where pedido_id = :pedido and situacao = :situacao
				""").param("espera", espera).param("pedido", t.pedidoId()).param("situacao", t.situacao()).update();
	}

	/** Pendente que já existe no Fiel: confere de novo daqui a {@code intervalo} (caso o aviso se perca). */
	@Transactional
	void conferirDeNovoEm(Trabalho t, Duration intervalo) {
		jdbc.sql("""
				update cobranca set proxima_em = now() + make_interval(secs => :segundos)
				where pedido_id = :pedido and situacao = 'pendente'
				""").param("segundos", intervalo.toSeconds()).param("pedido", t.pedidoId()).update();
	}

	/**
	 * A cobrança nasceu no Fiel: o QR vai para a saga. Se a saga cancelou enquanto o Fiel respondia, a cobrança que
	 * acabou de nascer é cancelada lá também (sem QR para ninguém).
	 */
	@Transactional
	void criada(Trabalho t, long fielId, String qr, String link, Duration conferirEm) {
		var mudou = jdbc.sql("""
				update cobranca set fiel_id = :fiel, qr = :qr, tentativas = 0, atualizada_em = now(),
				proxima_em = now() + make_interval(secs => :segundos)
				where pedido_id = :pedido and situacao = 'pendente' and fiel_id is null
				""")
			.param("fiel", fielId)
			.param("qr", qr)
			.param("segundos", conferirEm.toSeconds())
			.param("pedido", t.pedidoId())
			.update();
		if (mudou == 1) {
			responder(t.pedidoId(), CobrancaCriada.TIPO, CobrancaCriada.VERSAO, new CobrancaCriada(qr, link));
			return;
		}
		var cancelou = jdbc.sql("""
				update cobranca set fiel_id = :fiel, situacao = 'cancelando', tentativas = 0, proxima_em = now(),
				atualizada_em = now() where pedido_id = :pedido and situacao = 'cancelada' and fiel_id is null
				""").param("fiel", fielId).param("pedido", t.pedidoId()).update();
		if (cancelou == 1) {
			log.info("pagamentos: pedido {} cancelado enquanto a cobrança nascia no Fiel; cancelando lá", t.pedidoId());
		}
	}

	/** O Fiel recusou criar (valor fora do limite, por exemplo): não adianta tentar de novo. */
	@Transactional
	void recusadaPeloFiel(Trabalho t, String motivo) {
		var mudou = jdbc.sql("""
				update cobranca set situacao = 'recusada', proxima_em = null, atualizada_em = now()
				where pedido_id = :pedido and situacao = 'pendente' and fiel_id is null
				""").param("pedido", t.pedidoId()).update();
		if (mudou == 1) {
			log.warn("pagamentos: o Fiel recusou a cobrança do pedido {}: {}", t.pedidoId(), motivo);
			responder(t.pedidoId(), PagamentoRecusado.TIPO, PagamentoRecusado.VERSAO,
					new PagamentoRecusado(PagamentoRecusado.Motivo.RECUSADO));
		}
	}

	/**
	 * O visitante pagou (pelo aviso do Fiel ou pela conferência). Pendente vira aprovada. Se a saga já desistiu
	 * (cancelando ou cancelada), o pagamento chegou tarde: vira estorno, e o dinheiro volta (§6 do SPEC).
	 */
	@Transactional
	boolean paga(UUID pedidoId, long fielId) {
		var c = jdbc.sql("""
				select situacao, valor_centavos from cobranca
				where pedido_id = :pedido and forma = 'fiel' and fiel_id = :fiel for update
				""").param("pedido", pedidoId).param("fiel", fielId).query(Situacao.class).optional();
		if (c.isEmpty()) {
			log.warn("pagamentos: aviso de pagamento de cobrança desconhecida (pedido {}, Fiel {})", pedidoId, fielId);
			return false;
		}
		switch (c.get().situacao()) {
			case "pendente" -> {
				mudarPara(pedidoId, "aprovada", false);
				responder(pedidoId, PagamentoAprovado.TIPO, PagamentoAprovado.VERSAO,
						new PagamentoAprovado(c.get().valorCentavos()));
				return true;
			}
			case "cancelando", "cancelada" -> {
				log.warn("pagamentos: pedido {} pago depois do cancelamento; estornando", pedidoId);
				mudarPara(pedidoId, "estornando", true);
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	@Transactional
	void cancelada(Trabalho t) {
		if (deCancelandoPara(t, "cancelada")) {
			responder(t.pedidoId(), CobrancaCancelada.TIPO, CobrancaCancelada.VERSAO, new CobrancaCancelada());
		}
	}

	/** O cancelamento achou a cobrança já paga no Fiel: estorna. */
	@Transactional
	void pagaAntesDoCancelamento(Trabalho t) {
		deCancelandoPara(t, "estornando");
	}

	@Transactional
	void estornada(Trabalho t) {
		var mudou = jdbc.sql("""
				update cobranca set situacao = 'estornada', proxima_em = null, atualizada_em = now()
				where pedido_id = :pedido and situacao = 'estornando'
				""").param("pedido", t.pedidoId()).update();
		if (mudou == 1) {
			responder(t.pedidoId(), PagamentoEstornado.TIPO, PagamentoEstornado.VERSAO,
					new PagamentoEstornado(t.valorCentavos()));
		}
	}

	private boolean deCancelandoPara(Trabalho t, String situacao) {
		return jdbc.sql("""
				update cobranca set situacao = :situacao, tentativas = 0, atualizada_em = now(),
				proxima_em = case when :situacao = 'estornando' then now() end
				where pedido_id = :pedido and situacao = 'cancelando'
				""").param("situacao", situacao).param("pedido", t.pedidoId()).update() == 1;
	}

	private void mudarPara(UUID pedidoId, String situacao, boolean trabalhoAgora) {
		jdbc.sql("""
				update cobranca set situacao = :situacao, tentativas = 0, atualizada_em = now(),
				proxima_em = case when :agora then now() end where pedido_id = :pedido
				""").param("situacao", situacao).param("agora", trabalhoAgora).param("pedido", pedidoId).update();
	}

	/**
	 * No trace da saga, guardado na cobrança: quem chega aqui é o trabalhador (fora de qualquer trace) ou o aviso do
	 * Fiel (num trace próprio, o do pedido HTTP do Fiel).
	 */
	private void responder(UUID pedidoId, String tipo, int versao, Object dados) {
		var trace = jdbc.sql("select traceparent from cobranca where pedido_id = :pedido")
			.param("pedido", pedidoId)
			.query(String.class)
			.optional()
			.orElse(null);
		saida.registrar(Topicos.PAGAMENTOS_EVENTOS,
				Envelope.novo(tipo, versao, pedidoId, json.valueToTree(dados), relogio), trace);
	}

}
