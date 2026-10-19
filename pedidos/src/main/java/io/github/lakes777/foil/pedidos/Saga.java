package io.github.lakes777.foil.pedidos;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.lakes777.foil.comum.eventos.Saida;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.EstoqueComandos.DevolverUnidade;
import io.github.lakes777.foil.contratos.EstoqueComandos.ReservarUnidade;
import io.github.lakes777.foil.contratos.EstoqueEventos.ReservaRecusada;
import io.github.lakes777.foil.contratos.EstoqueEventos.UnidadeDevolvida;
import io.github.lakes777.foil.contratos.EstoqueEventos.UnidadeReservada;
import io.github.lakes777.foil.contratos.PagamentosComandos.CancelarCobranca;
import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido;
import io.github.lakes777.foil.contratos.PagamentosEventos.CobrancaCancelada;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoAprovado;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoEstornado;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoRecusado;
import io.github.lakes777.foil.contratos.PedidosEventos.PedidoMudouDeEstado;
import io.github.lakes777.foil.contratos.Topicos;
import tools.jackson.databind.json.JsonMapper;

/**
 * O orquestrador (P6). Cada passo trava a linha do pedido, confere o estado atual e, numa transação só, muda o
 * estado e grava na saída os comandos seguintes. Evento que não cabe no estado atual é ignorado: chegou tarde
 * (o prazo venceu antes) e a compensação já está a caminho.
 */
@Service
public class Saga {

	private static final Logger log = LoggerFactory.getLogger(Saga.class);

	private final JdbcClient jdbc;

	private final Saida saida;

	private final JsonMapper json;

	private final Clock relogio;

	private final Duration prazoDaReserva;

	private final Duration prazoDoPagamento;

	private final Duration prazoDaLiberacao;

	Saga(JdbcClient jdbc, Saida saida, JsonMapper json, Clock relogio,
			@Value("${foil.pedidos.prazo-da-reserva:2m}") Duration prazoDaReserva,
			@Value("${foil.pedidos.prazo-do-pagamento:5m}") Duration prazoDoPagamento,
			@Value("${foil.pedidos.prazo-da-liberacao:1m}") Duration prazoDaLiberacao) {
		this.jdbc = jdbc;
		this.saida = saida;
		this.json = json;
		this.relogio = relogio;
		this.prazoDaReserva = prazoDaReserva;
		this.prazoDoPagamento = prazoDoPagamento;
		this.prazoDaLiberacao = prazoDaLiberacao;
	}

	/** Começa a saga: o pedido nasce RESERVANDO, já com o comando de reserva na saída. */
	@Transactional
	public Pedido criar(long edicaoId) {
		var id = UUID.randomUUID();
		jdbc.sql("insert into pedido (id, edicao_id, estado, prazo) values (:id, :edicao, 'RESERVANDO', :prazo)")
			.param("id", id)
			.param("edicao", edicaoId)
			.param("prazo", daquiA(prazoDaReserva))
			.update();
		mandar(Topicos.ESTOQUE_COMANDOS, id, ReservarUnidade.TIPO, ReservarUnidade.VERSAO, new ReservarUnidade(edicaoId));
		contar(id, Estado.RESERVANDO, null, edicaoId);
		return buscar(id).orElseThrow();
	}

	/** Um evento de estoque ou pagamentos. Exige a transação de quem consome (anotação + efeito juntos). */
	@Transactional
	public void aoReceber(Envelope evento) {
		var pedido = travar(evento.pedidoId());
		if (pedido.isEmpty()) {
			// Evento de um pedido que não é deste serviço (ou foi apagado pela limpeza): não há o que fazer
			log.warn("saga: {} de pedido desconhecido {}", evento.tipo(), evento.pedidoId());
			return;
		}
		var p = pedido.get();
		switch (evento.tipo()) {
			case UnidadeReservada.TIPO -> {
				var dados = dados(evento, UnidadeReservada.class, UnidadeReservada.VERSAO);
				if (p.estado() == Estado.RESERVANDO) {
					jdbc.sql("""
							update pedido set estado = 'AGUARDANDO_PAGAMENTO', preco_centavos = :preco, prazo = :prazo,
							atualizado_em = now() where id = :id
							""").param("preco", dados.precoCentavos())
						.param("prazo", daquiA(prazoDoPagamento))
						.param("id", p.id())
						.update();
					mandar(Topicos.PAGAMENTOS_COMANDOS, p.id(), CobrarPedido.TIPO, CobrarPedido.VERSAO,
							new CobrarPedido(dados.precoCentavos()));
					contar(p.id(), Estado.AGUARDANDO_PAGAMENTO, null, p.edicaoId());
				}
				else {
					ignorar(p, evento);
				}
			}
			case ReservaRecusada.TIPO -> {
				var dados = dados(evento, ReservaRecusada.class, ReservaRecusada.VERSAO);
				if (p.estado() == Estado.RESERVANDO) {
					terminar(p, Estado.SEM_ESTOQUE, dados.motivo().name());
				}
				else {
					// LIBERANDO (prazo da reserva venceu): a resposta ao DevolverUnidade é que fecha a saga
					ignorar(p, evento);
				}
			}
			case PagamentoAprovado.TIPO -> {
				dados(evento, PagamentoAprovado.class, PagamentoAprovado.VERSAO);
				if (p.estado() == Estado.AGUARDANDO_PAGAMENTO) {
					terminar(p, Estado.CONFIRMADO, null);
				}
				else {
					// Aprovado depois do prazo: o CancelarCobranca, que vem depois na mesma partição, estorna
					ignorar(p, evento);
				}
			}
			case PagamentoRecusado.TIPO -> {
				var dados = dados(evento, PagamentoRecusado.class, PagamentoRecusado.VERSAO);
				if (p.estado() == Estado.AGUARDANDO_PAGAMENTO) {
					// RECUSADO -> PAGAMENTO_RECUSADO; CANCELADA -> PAGAMENTO_CANCELADA (CobrarPedido atrasado)
					liberar(p, "PAGAMENTO_" + dados.motivo().name(), false);
				}
				else {
					ignorar(p, evento);
				}
			}
			case UnidadeDevolvida.TIPO -> {
				dados(evento, UnidadeDevolvida.class, UnidadeDevolvida.VERSAO);
				if (p.estado() == Estado.LIBERANDO) {
					terminar(p, Estado.CANCELADO, p.motivo());
				}
				else {
					ignorar(p, evento);
				}
			}
			// Respostas ao CancelarCobranca: a saga já fechou pelo lado do estoque; só fica no log
			case CobrancaCancelada.TIPO, PagamentoEstornado.TIPO -> log.info("saga: pedido {} recebeu {}", p.id(),
					evento.tipo());
			default -> throw new IllegalArgumentException("evento desconhecido: " + evento.tipo());
		}
	}

	/**
	 * Fecha o que passou do prazo: RESERVANDO e AGUARDANDO_PAGAMENTO viram LIBERANDO e a unidade volta. LIBERANDO
	 * vencido reenvia o DevolverUnidade (a resposta pode ter caído na fila de mortos; devolver de novo não devolve
	 * duas vezes). Devolve quantos venceram. {@code skip locked}: um pedido sendo atualizado agora por um evento
	 * fica para a próxima.
	 */
	@Transactional
	public int vencerPrazos() {
		var vencidos = jdbc.sql("""
				select id, edicao_id, estado, motivo, preco_centavos, prazo, criado_em, atualizado_em from pedido
				where prazo < :agora order by prazo limit 100 for update skip locked
				""").param("agora", agora()).query(Pedido.class).list();
		for (var p : vencidos) {
			switch (p.estado()) {
				case RESERVANDO -> liberar(p, "PRAZO_DA_RESERVA", false);
				case AGUARDANDO_PAGAMENTO -> liberar(p, "PRAZO_DO_PAGAMENTO", true);
				case LIBERANDO -> {
					log.warn("saga: pedido {} sem resposta da devolução; reenviando", p.id());
					jdbc.sql("update pedido set prazo = :prazo, atualizado_em = now() where id = :id")
						.param("prazo", daquiA(prazoDaLiberacao))
						.param("id", p.id())
						.update();
					devolver(p);
				}
				default -> throw new IllegalStateException("estado final com prazo: " + p.id());
			}
		}
		return vencidos.size();
	}

	@Transactional(readOnly = true)
	public Optional<Pedido> buscar(UUID id) {
		return jdbc.sql("""
				select id, edicao_id, estado, motivo, preco_centavos, prazo, criado_em, atualizado_em
				from pedido where id = :id
				""").param("id", id).query(Pedido.class).optional();
	}

	@Transactional(readOnly = true)
	public List<Pedido> pedidosDaEdicao(long edicaoId) {
		return jdbc.sql("""
				select id, edicao_id, estado, motivo, preco_centavos, prazo, criado_em, atualizado_em
				from pedido where edicao_id = :edicao order by criado_em
				""").param("edicao", edicaoId).query(Pedido.class).list();
	}

	/**
	 * Desfaz: a unidade volta ao estoque. A ordem na partição garante que o DevolverUnidade chega ao estoque depois
	 * do ReservarUnidade, então mesmo uma reserva ainda não processada é desfeita.
	 */
	private void liberar(Pedido p, String motivo, boolean cancelarCobranca) {
		jdbc.sql("""
				update pedido set estado = 'LIBERANDO', motivo = :motivo, prazo = :prazo, atualizado_em = now()
				where id = :id
				""").param("motivo", motivo).param("prazo", daquiA(prazoDaLiberacao)).param("id", p.id()).update();
		if (cancelarCobranca) {
			mandar(Topicos.PAGAMENTOS_COMANDOS, p.id(), CancelarCobranca.TIPO, CancelarCobranca.VERSAO,
					new CancelarCobranca());
		}
		devolver(p);
		contar(p.id(), Estado.LIBERANDO, motivo, p.edicaoId());
	}

	private void devolver(Pedido p) {
		mandar(Topicos.ESTOQUE_COMANDOS, p.id(), DevolverUnidade.TIPO, DevolverUnidade.VERSAO,
				new DevolverUnidade(p.edicaoId()));
	}

	private void terminar(Pedido p, Estado fim, String motivo) {
		jdbc.sql("""
				update pedido set estado = :estado, motivo = :motivo, prazo = null, atualizado_em = now() where id = :id
				""").param("estado", fim.name()).param("motivo", motivo).param("id", p.id()).update();
		contar(p.id(), fim, motivo, p.edicaoId());
	}

	private void ignorar(Pedido p, Envelope evento) {
		log.info("saga: pedido {} em {} ignorou {} (chegou tarde)", p.id(), p.estado(), evento.tipo());
	}

	private Optional<Pedido> travar(UUID id) {
		return jdbc.sql("""
				select id, edicao_id, estado, motivo, preco_centavos, prazo, criado_em, atualizado_em
				from pedido where id = :id for update
				""").param("id", id).query(Pedido.class).optional();
	}

	private void mandar(String topico, UUID pedido, String tipo, int versao, Object dados) {
		saida.registrar(topico, Envelope.novo(tipo, versao, pedido, json.valueToTree(dados), relogio));
	}

	/** Cada passo vai para pedidos.eventos (painel ao vivo e observabilidade). */
	private void contar(UUID pedido, Estado estado, String motivo, long edicaoId) {
		mandar(Topicos.PEDIDOS_EVENTOS, pedido, PedidoMudouDeEstado.TIPO, PedidoMudouDeEstado.VERSAO,
				new PedidoMudouDeEstado(estado.name(), motivo, edicaoId));
	}

	/** OffsetDateTime: o driver do Postgres não sabe gravar Instant como parâmetro. */
	private OffsetDateTime daquiA(Duration prazo) {
		return agora().plus(prazo);
	}

	private OffsetDateTime agora() {
		return OffsetDateTime.now(relogio).withOffsetSameInstant(ZoneOffset.UTC);
	}

	private <T> T dados(Envelope evento, Class<T> tipo, int versao) {
		if (evento.versao() != versao) {
			throw new IllegalArgumentException(
					"versão " + evento.versao() + " de " + evento.tipo() + " não suportada (espera " + versao + ")");
		}
		return json.treeToValue(evento.dados(), tipo);
	}

}
