package io.github.lakes777.foil.pedidos;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.github.lakes777.foil.comum.eventos.Rastro;
import io.github.lakes777.foil.comum.eventos.Saida;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.EstoqueComandos.DevolverUnidade;
import io.github.lakes777.foil.contratos.EstoqueComandos.ReservarUnidade;
import io.github.lakes777.foil.contratos.EstoqueEventos.ReservaRecusada;
import io.github.lakes777.foil.contratos.EstoqueEventos.UnidadeDevolvida;
import io.github.lakes777.foil.contratos.EstoqueEventos.UnidadeReservada;
import io.github.lakes777.foil.contratos.PagamentosComandos.CancelarCobranca;
import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido;
import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido.Forma;
import io.github.lakes777.foil.contratos.PagamentosEventos.CobrancaCancelada;
import io.github.lakes777.foil.contratos.PagamentosEventos.CobrancaCriada;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoAprovado;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoEstornado;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoRecusado;
import io.github.lakes777.foil.contratos.PedidosEventos.PedidoMudouDeEstado;
import io.github.lakes777.foil.contratos.Topicos;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * O orquestrador (P6). Cada passo trava a linha do pedido, confere o estado atual e, numa transação só, muda o
 * estado e grava na saída os comandos seguintes. Evento que não cabe no estado atual é ignorado: chegou tarde
 * (o prazo venceu antes) e a compensação já está a caminho.
 */
@Service
public class Saga {

	private static final Logger log = LoggerFactory.getLogger(Saga.class);

	private static final String COLUNAS = """
			select id, edicao_id, estado, motivo, preco_centavos, prazo, criado_em, atualizado_em, forma, qr,
			link_para_pagar, lancamento_id, traceparent from pedido
			""";

	private final JdbcClient jdbc;

	private final Saida saida;

	private final JsonMapper json;

	private final Clock relogio;

	private final Duration prazoDaReserva;

	private final Duration prazoDoPagamento;

	private final Duration prazoDaLiberacao;

	private final Rastro rastro;

	private final MeterRegistry metricas;

	Saga(JdbcClient jdbc, Saida saida, JsonMapper json, Clock relogio, Rastro rastro, MeterRegistry metricas,
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
		this.rastro = rastro;
		this.metricas = metricas;
		zerarContadores();
	}

	/**
	 * Cada combinação de estado final, motivo e origem nasce com zero na partida. Sem isso, a série só aparece na primeira
	 * venda já valendo 1 (ou 10, num lançamento entre duas leituras do Prometheus), e o rate() e o increase() do painel
	 * não contam esse começo: o gráfico de vendas por segundo ficava zerado com 20 vendas.
	 */
	private void zerarContadores() {
		var finais = new ArrayList<String[]>();
		finais.add(new String[] { Estado.CONFIRMADO.name(), null });
		for (var m : ReservaRecusada.Motivo.values()) {
			finais.add(new String[] { Estado.SEM_ESTOQUE.name(), m.name() });
		}
		for (var m : List.of("PRAZO_DA_RESERVA", "PRAZO_DO_PAGAMENTO")) {
			finais.add(new String[] { Estado.CANCELADO.name(), m });
		}
		for (var m : PagamentoRecusado.Motivo.values()) {
			finais.add(new String[] { Estado.CANCELADO.name(), "PAGAMENTO_" + m.name() });
		}
		for (var f : finais) {
			for (var origem : List.of("loja", "robo")) {
				contador(f[0], f[1], origem);
			}
		}
	}

	private Counter contador(String estado, String motivo, String origem) {
		return metricas.counter("foil.pedidos.terminados", "estado", estado, "motivo",
				motivo == null ? "nenhum" : motivo, "origem", origem);
	}

	/**
	 * Começa a saga: o pedido nasce RESERVANDO, já com o comando de reserva na saída. A {@code forma} só importa
	 * depois da reserva: é o CobrarPedido que a leva a pagamentos.
	 */
	@Transactional
	public Pedido criar(long edicaoId, Forma forma) {
		return criar(edicaoId, forma, null);
	}

	/** Um pedido de robô leva o id do lançamento (fase 6); o da loja, null. */
	@Transactional
	public Pedido criar(long edicaoId, Forma forma, UUID lancamentoId) {
		return criar(edicaoId, forma, lancamentoId, null);
	}

	/**
	 * O da loja leva o visitante (o cookie), para aparecer em "Minhas gemas"; o dos robôs, null. Cada pedido abre o
	 * trace da saga dele (o da loja, dentro do POST; o de cada robô, um trace só dele) e o guarda: tudo o que a saga
	 * mandar depois, até um prazo vencido, sai nesse trace.
	 */
	@Transactional
	public Pedido criar(long edicaoId, Forma forma, UUID lancamentoId, UUID visitante) {
		var id = UUID.randomUUID();
		try (var span = lancamentoId == null ? rastro.filho("pedido") : rastro.novoTrace("pedido do robô")) {
			rastro.marcar("foil.pedido", id.toString());
			rastro.marcar("foil.origem", origem(lancamentoId));
			var trace = rastro.atual();
			jdbc.sql("""
					insert into pedido (id, edicao_id, estado, prazo, forma, lancamento_id, visitante, traceparent)
					values (:id, :edicao, 'RESERVANDO', :prazo, :forma, :lancamento, :visitante, :trace)
					""")
				.param("id", id)
				.param("edicao", edicaoId)
				.param("forma", forma.name())
				.param("prazo", daquiA(prazoDaReserva))
				.param("lancamento", lancamentoId)
				.param("visitante", visitante)
				.param("trace", trace)
				.update();
			mandar(Topicos.ESTOQUE_COMANDOS, id, trace, ReservarUnidade.TIPO, ReservarUnidade.VERSAO,
					new ReservarUnidade(edicaoId));
			contar(id, trace, Estado.RESERVANDO, null, edicaoId);
		}
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
					mandar(Topicos.PAGAMENTOS_COMANDOS, p.id(), p.traceparent(), CobrarPedido.TIPO,
							CobrarPedido.VERSAO, new CobrarPedido(dados.precoCentavos(), p.forma()));
					contar(p.id(), p.traceparent(), Estado.AGUARDANDO_PAGAMENTO, null, p.edicaoId());
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
			case CobrancaCriada.TIPO -> {
				// O QR do Fiel: não muda o estado, só dá ao visitante como pagar. Depois do prazo não serve mais
				var dados = dados(evento, CobrancaCriada.class, CobrancaCriada.VERSAO);
				if (p.estado() == Estado.AGUARDANDO_PAGAMENTO) {
					jdbc.sql("""
							update pedido set qr = :qr, link_para_pagar = :link, atualizado_em = now() where id = :id
							""").param("qr", dados.qr()).param("link", dados.linkParaPagar()).param("id", p.id()).update();
				}
				else {
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
		var vencidos = jdbc.sql(COLUNAS + """
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
		return jdbc.sql(COLUNAS + "where id = :id").param("id", id).query(Pedido.class).optional();
	}

	/**
	 * Uma compra do visitante. {@code unidade} só nos confirmados: gravada na confirmação (a ordem entre os confirmados
	 * da mesma edição, robôs incluídos), então não muda depois.
	 */
	public record CompraDoVisitante(Pedido pedido, Integer unidade) {
	}

	static final int COMPRAS_POR_VISITANTE = 50;

	@Transactional(readOnly = true)
	public List<CompraDoVisitante> doVisitante(UUID visitante) {
		var pedidos = jdbc.sql(COLUNAS + "where visitante = :v order by criado_em desc limit :n")
			.param("v", visitante)
			.param("n", COMPRAS_POR_VISITANTE)
			.query(Pedido.class)
			.list();
		var unidades = new HashMap<UUID, Integer>();
		jdbc.sql("select id, unidade from pedido where visitante = :v and unidade is not null")
			.param("v", visitante)
			.query((rs, i) -> unidades.put(rs.getObject("id", UUID.class), rs.getInt("unidade")))
			.list();
		return pedidos.stream().map(p -> new CompraDoVisitante(p, unidades.get(p.id()))).toList();
	}

	@Transactional(readOnly = true)
	public List<Pedido> pedidosDaEdicao(long edicaoId) {
		return jdbc.sql(COLUNAS + "where edicao_id = :edicao order by criado_em").param("edicao", edicaoId).query(Pedido.class).list();
	}

	/**
	 * Desfaz: a unidade volta ao estoque, e o QR do Fiel sai do pedido (pagar agora só daria estorno). A ordem na
	 * partição garante que o DevolverUnidade chega ao estoque depois
	 * do ReservarUnidade, então mesmo uma reserva ainda não processada é desfeita.
	 */
	private void liberar(Pedido p, String motivo, boolean cancelarCobranca) {
		jdbc.sql("""
				update pedido set estado = 'LIBERANDO', motivo = :motivo, prazo = :prazo, atualizado_em = now(),
				qr = null, link_para_pagar = null where id = :id
				""").param("motivo", motivo).param("prazo", daquiA(prazoDaLiberacao)).param("id", p.id()).update();
		if (cancelarCobranca) {
			mandar(Topicos.PAGAMENTOS_COMANDOS, p.id(), p.traceparent(), CancelarCobranca.TIPO,
					CancelarCobranca.VERSAO, new CancelarCobranca());
		}
		devolver(p);
		contar(p.id(), p.traceparent(), Estado.LIBERANDO, motivo, p.edicaoId());
	}

	private void devolver(Pedido p) {
		mandar(Topicos.ESTOQUE_COMANDOS, p.id(), p.traceparent(), DevolverUnidade.TIPO, DevolverUnidade.VERSAO,
				new DevolverUnidade(p.edicaoId()));
	}

	private void terminar(Pedido p, Estado fim, String motivo) {
		if (fim == Estado.CONFIRMADO) {
			// A próxima unidade da edição: a trava por edição faz duas confirmações simultâneas esperarem uma pela
			// outra (o índice único é a última trava, se alguém esquecer desta)
			jdbc.sql("select pg_advisory_xact_lock(hashtextextended('foil.unidade.' || :edicao, 0))")
				.param("edicao", p.edicaoId())
				.query((rs, i) -> 1)
				.single();
		}
		jdbc.sql("""
				update pedido set estado = :estado, motivo = :motivo, prazo = null, atualizado_em = now(),
				unidade = case when :estado = 'CONFIRMADO'
				               then (select coalesce(max(unidade), 0) + 1 from pedido where edicao_id = :edicao) end
				where id = :id
				""").param("estado", fim.name()).param("motivo", motivo).param("edicao", p.edicaoId())
			.param("id", p.id())
			.update();
		contar(p.id(), p.traceparent(), fim, motivo, p.edicaoId());
		terminado(p, fim, motivo);
	}

	/**
	 * Métrica do Grafana (vendas/s, sagas desfeitas por motivo), contada só depois do commit: uma transação desfeita
	 * (o evento volta e é processado de novo) não conta duas vezes.
	 */
	private void terminado(Pedido p, Estado fim, String motivo) {
		var contador = contador(fim.name(), motivo, origem(p.lancamentoId()));
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				contador.increment();
			}
		});
	}

	private static String origem(UUID lancamentoId) {
		return lancamentoId == null ? "loja" : "robo";
	}

	private void ignorar(Pedido p, Envelope evento) {
		log.info("saga: pedido {} em {} ignorou {} (chegou tarde)", p.id(), p.estado(), evento.tipo());
	}

	private Optional<Pedido> travar(UUID id) {
		return jdbc.sql(COLUNAS + "where id = :id for update").param("id", id).query(Pedido.class).optional();
	}

	/** {@code trace}: o da saga, guardado no pedido (ver {@link Saida#registrar(String, Envelope, String)}). */
	private void mandar(String topico, UUID pedido, String trace, String tipo, int versao, Object dados) {
		saida.registrar(topico, Envelope.novo(tipo, versao, pedido, json.valueToTree(dados), relogio), trace);
	}

	/** Cada passo vai para pedidos.eventos (painel ao vivo e observabilidade). */
	private void contar(UUID pedido, String trace, Estado estado, String motivo, long edicaoId) {
		mandar(Topicos.PEDIDOS_EVENTOS, pedido, trace, PedidoMudouDeEstado.TIPO, PedidoMudouDeEstado.VERSAO,
				new PedidoMudouDeEstado(estado.name(), motivo, edicaoId));
	}

	/** OffsetDateTime: o driver do Postgres não sabe gravar Instant como parâmetro. */
	private OffsetDateTime daquiA(Duration prazo) {
		return agora().plus(prazo);
	}

	/** Cortado em microssegundos, como em {@link Lancamentos}: o que vai para o banco é o que volta dele. */
	private OffsetDateTime agora() {
		return OffsetDateTime.now(relogio).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	private <T> T dados(Envelope evento, Class<T> tipo, int versao) {
		if (evento.versao() != versao) {
			throw new IllegalArgumentException(
					"versão " + evento.versao() + " de " + evento.tipo() + " não suportada (espera " + versao + ")");
		}
		return json.treeToValue(evento.dados(), tipo);
	}

}
