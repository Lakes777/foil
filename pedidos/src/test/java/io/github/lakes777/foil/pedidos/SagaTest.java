package io.github.lakes777.foil.pedidos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.lakes777.foil.comum.ConteineresDeTeste;
import io.github.lakes777.foil.comum.eventos.Rastro;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.EstoqueEventos.ReservaRecusada;
import io.github.lakes777.foil.contratos.EstoqueEventos.UnidadeDevolvida;
import io.github.lakes777.foil.contratos.EstoqueEventos.UnidadeReservada;
import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido;
import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido.Forma;
import io.github.lakes777.foil.contratos.PagamentosEventos.CobrancaCriada;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoAprovado;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoRecusado;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * A máquina de estados sozinha: os eventos entram direto na saga (sem Redpanda no meio) e o teste confere o estado
 * e os comandos gravados na saída. O publicador e os prazos automáticos ficam desligados; o relógio é do teste.
 */
@SpringBootTest(properties = { "foil.saida.automatica=false", "foil.pedidos.prazos-automaticos=false" })
@Import({ ConteineresDeTeste.class, SagaTest.RelogioManual.class })
class SagaTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class RelogioManual {

		@Bean
		@Primary
		RelogioDeTeste relogioDeTeste() {
			return new RelogioDeTeste();
		}

	}

	@Autowired
	Saga saga;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	@Autowired
	RelogioDeTeste relogio;

	@Autowired
	Rastro rastro;

	@Autowired
	MeterRegistry metricas;

	@Autowired
	TransactionTemplate transacao;

	private void chega(UUID pedido, String tipo, Object dados) {
		saga.aoReceber(Envelope.novo(tipo, 1, pedido, json.valueToTree(dados), Clock.systemUTC()));
	}

	private Pedido pedido(UUID id) {
		return saga.buscar(id).orElseThrow();
	}

	/** O que a saga mandou (comandos e passos), na ordem da saída: "tópico:tipo". */
	private List<String> mandados(UUID pedido) {
		return jdbc.sql("select topico, envelope from saida where chave = :c order by seq")
			.param("c", pedido.toString())
			.query((rs, i) -> rs.getString("topico") + ":"
					+ json.readValue(rs.getString("envelope"), Envelope.class).tipo())
			.list();
	}

	private List<String> comandos(UUID pedido) {
		return mandados(pedido).stream().filter(m -> !m.startsWith("pedidos.eventos")).toList();
	}

	@Test
	void criarNasceReservandoComOComandoNaSaida() {
		var p = saga.criar(7, Forma.SIMULADA);

		assertThat(p.estado()).isEqualTo(Estado.RESERVANDO);
		assertThat(p.prazo()).isNotNull();
		assertThat(mandados(p.id())).containsExactly("estoque.comandos:ReservarUnidade",
				"pedidos.eventos:PedidoMudouDeEstado");
	}

	@Test
	void caminhoFelizTerminaConfirmado() {
		var p = saga.criar(7, Forma.SIMULADA);

		chega(p.id(), UnidadeReservada.TIPO, new UnidadeReservada(7, 1500));
		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.AGUARDANDO_PAGAMENTO);
		assertThat(pedido(p.id()).precoCentavos()).isEqualTo(1500);
		var cobrar = jdbc.sql("select envelope from saida where chave = :c and topico = 'pagamentos.comandos'")
			.param("c", p.id().toString())
			.query(String.class)
			.single();
		assertThat(json.treeToValue(json.readValue(cobrar, Envelope.class).dados(), CobrarPedido.class))
			.isEqualTo(new CobrarPedido(1500, Forma.SIMULADA));

		chega(p.id(), PagamentoAprovado.TIPO, new PagamentoAprovado(1500));

		var fim = pedido(p.id());
		assertThat(fim.estado()).isEqualTo(Estado.CONFIRMADO);
		assertThat(fim.prazo()).isNull();
		assertThat(comandos(p.id())).containsExactly("estoque.comandos:ReservarUnidade",
				"pagamentos.comandos:CobrarPedido");
	}

	@Test
	void compraPeloFielGuardaOQrEConfirmaComOPagamento() {
		var p = saga.criar(7, Forma.FIEL);
		chega(p.id(), UnidadeReservada.TIPO, new UnidadeReservada(7, 1990));
		var cobrar = jdbc.sql("select envelope from saida where chave = :c and topico = 'pagamentos.comandos'")
			.param("c", p.id().toString())
			.query(String.class)
			.single();
		var envelope = json.readValue(cobrar, Envelope.class);
		assertThat(envelope.versao()).isEqualTo(CobrarPedido.VERSAO);
		assertThat(json.treeToValue(envelope.dados(), CobrarPedido.class)).isEqualTo(new CobrarPedido(1990, Forma.FIEL));

		chega(p.id(), CobrancaCriada.TIPO, new CobrancaCriada("QR-1", "https://fiel/pagar?qr=QR-1"));
		var esperando = pedido(p.id());
		assertThat(esperando.estado()).isEqualTo(Estado.AGUARDANDO_PAGAMENTO);
		assertThat(esperando.qr()).isEqualTo("QR-1");
		assertThat(esperando.linkParaPagar()).isEqualTo("https://fiel/pagar?qr=QR-1");

		chega(p.id(), PagamentoAprovado.TIPO, new PagamentoAprovado(1990));
		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.CONFIRMADO);
	}

	@Test
	void desistirTiraOQrDoPedido() {
		var p = saga.criar(7, Forma.FIEL);
		chega(p.id(), UnidadeReservada.TIPO, new UnidadeReservada(7, 1990));
		chega(p.id(), CobrancaCriada.TIPO, new CobrancaCriada("QR-3", "https://fiel/pagar?qr=QR-3"));
		relogio.avancar(Duration.ofMinutes(5).plusSeconds(1));

		saga.vencerPrazos();

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.LIBERANDO);
		assertThat(pedido(p.id()).qr()).isNull();
		assertThat(pedido(p.id()).linkParaPagar()).isNull();
	}

	@Test
	void qrQueChegaDepoisDoPrazoNaoApareceNoPedido() {
		var p = saga.criar(7, Forma.FIEL);
		chega(p.id(), UnidadeReservada.TIPO, new UnidadeReservada(7, 1990));
		relogio.avancar(Duration.ofMinutes(5).plusSeconds(1));
		saga.vencerPrazos();

		chega(p.id(), CobrancaCriada.TIPO, new CobrancaCriada("QR-2", "https://fiel/pagar?qr=QR-2"));

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.LIBERANDO);
		assertThat(pedido(p.id()).qr()).isNull();
	}

	@Test
	void reservaRecusadaTerminaSemEstoque() {
		var p = saga.criar(7, Forma.SIMULADA);

		chega(p.id(), ReservaRecusada.TIPO, new ReservaRecusada(7, ReservaRecusada.Motivo.SEM_ESTOQUE));

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.SEM_ESTOQUE);
		assertThat(pedido(p.id()).motivo()).isEqualTo("SEM_ESTOQUE");
		assertThat(comandos(p.id())).containsExactly("estoque.comandos:ReservarUnidade");
	}

	@Test
	void pagamentoRecusadoDevolveAUnidadeETerminaCancelado() {
		var p = saga.criar(7, Forma.SIMULADA);
		chega(p.id(), UnidadeReservada.TIPO, new UnidadeReservada(7, 1000));

		chega(p.id(), PagamentoRecusado.TIPO, new PagamentoRecusado(PagamentoRecusado.Motivo.RECUSADO));
		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.LIBERANDO);
		assertThat(pedido(p.id()).prazo()).isNotNull();

		chega(p.id(), UnidadeDevolvida.TIPO, new UnidadeDevolvida(true));

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.CANCELADO);
		assertThat(pedido(p.id()).motivo()).isEqualTo("PAGAMENTO_RECUSADO");
		assertThat(comandos(p.id())).containsExactly("estoque.comandos:ReservarUnidade",
				"pagamentos.comandos:CobrarPedido", "estoque.comandos:DevolverUnidade");
	}

	@Test
	void prazoDoPagamentoCancelaACobrancaEDevolveEAprovacaoAtrasadaEIgnorada() {
		var p = saga.criar(7, Forma.SIMULADA);
		chega(p.id(), UnidadeReservada.TIPO, new UnidadeReservada(7, 1000));

		relogio.avancar(Duration.ofMinutes(5).plusSeconds(1));
		saga.vencerPrazos();

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.LIBERANDO);
		assertThat(pedido(p.id()).motivo()).isEqualTo("PRAZO_DO_PAGAMENTO");
		// A aprovação que chega depois não confirma: o CancelarCobranca já na saída vai estorná-la
		chega(p.id(), PagamentoAprovado.TIPO, new PagamentoAprovado(1000));
		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.LIBERANDO);

		chega(p.id(), UnidadeDevolvida.TIPO, new UnidadeDevolvida(true));

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.CANCELADO);
		assertThat(pedido(p.id()).motivo()).isEqualTo("PRAZO_DO_PAGAMENTO");
		assertThat(comandos(p.id())).containsExactly("estoque.comandos:ReservarUnidade",
				"pagamentos.comandos:CobrarPedido", "pagamentos.comandos:CancelarCobranca",
				"estoque.comandos:DevolverUnidade");
	}

	@Test
	void prazoDaReservaDevolveSemCobrarEReservaAtrasadaEIgnorada() {
		var p = saga.criar(7, Forma.SIMULADA);

		relogio.avancar(Duration.ofMinutes(2).plusSeconds(1));
		saga.vencerPrazos();
		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.LIBERANDO);

		// O estoque processa a reserva atrasada e depois a devolução (mesma partição, nessa ordem)
		chega(p.id(), UnidadeReservada.TIPO, new UnidadeReservada(7, 1000));
		chega(p.id(), UnidadeDevolvida.TIPO, new UnidadeDevolvida(true));

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.CANCELADO);
		assertThat(pedido(p.id()).motivo()).isEqualTo("PRAZO_DA_RESERVA");
		assertThat(comandos(p.id())).containsExactly("estoque.comandos:ReservarUnidade",
				"estoque.comandos:DevolverUnidade");
	}

	@Test
	void liberandoSemRespostaReenviaADevolucaoAteFechar() {
		var p = saga.criar(7, Forma.SIMULADA);
		chega(p.id(), UnidadeReservada.TIPO, new UnidadeReservada(7, 1000));
		chega(p.id(), PagamentoRecusado.TIPO, new PagamentoRecusado(PagamentoRecusado.Motivo.RECUSADO));

		// A resposta da devolução caiu na fila de mortos: passa 1 min, a saga manda de novo; e de novo
		relogio.avancar(Duration.ofMinutes(1).plusSeconds(1));
		saga.vencerPrazos();
		relogio.avancar(Duration.ofMinutes(1).plusSeconds(1));
		saga.vencerPrazos();

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.LIBERANDO);
		assertThat(comandos(p.id())).containsExactly("estoque.comandos:ReservarUnidade",
				"pagamentos.comandos:CobrarPedido", "estoque.comandos:DevolverUnidade",
				"estoque.comandos:DevolverUnidade", "estoque.comandos:DevolverUnidade");

		chega(p.id(), UnidadeDevolvida.TIPO, new UnidadeDevolvida(false));

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.CANCELADO);
		assertThat(pedido(p.id()).prazo()).isNull();
		relogio.avancar(Duration.ofMinutes(5));
		saga.vencerPrazos();
		assertThat(comandos(p.id())).hasSize(5);
	}

	@Test
	void antesDoPrazoNadaVence() {
		var p = saga.criar(7, Forma.SIMULADA);

		relogio.avancar(Duration.ofMinutes(1));
		saga.vencerPrazos();

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.RESERVANDO);
	}

	@Test
	void estadoFinalIgnoraQualquerEventoAtrasado() {
		var p = saga.criar(7, Forma.SIMULADA);
		chega(p.id(), ReservaRecusada.TIPO, new ReservaRecusada(7, ReservaRecusada.Motivo.SEM_ESTOQUE));

		chega(p.id(), UnidadeReservada.TIPO, new UnidadeReservada(7, 1000));
		chega(p.id(), PagamentoAprovado.TIPO, new PagamentoAprovado(1000));
		chega(p.id(), UnidadeDevolvida.TIPO, new UnidadeDevolvida(false));

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.SEM_ESTOQUE);
		assertThat(comandos(p.id())).containsExactly("estoque.comandos:ReservarUnidade");
	}

	@Test
	void eventoDesconhecidoVaiParaOsMortosEPedidoDesconhecidoNaoFazNada() {
		var p = saga.criar(7, Forma.SIMULADA);

		assertThatThrownBy(() -> chega(p.id(), "Inventado", new Object())).isInstanceOf(IllegalArgumentException.class);
		chega(UUID.randomUUID(), PagamentoAprovado.TIPO, new PagamentoAprovado(1000));

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.RESERVANDO);
	}


	/** Os ids de trace de tudo o que a saga gravou na saída para o pedido, na ordem. */
	private List<String> tracesNaSaida(UUID pedido) {
		return jdbc.sql("select traceparent from saida where chave = :c order by seq")
			.param("c", pedido.toString())
			.query((rs, i) -> Rastro.idDoTrace(rs.getString("traceparent")))
			.list();
	}

	/**
	 * Fase 9: a saga inteira num trace só, inclusive o que sai de um prazo vencido (fora de qualquer trace).
	 * Contraprova: mandar sem o trace do pedido deixa os passos depois do primeiro sem trace.
	 */
	@Test
	void todoPassoDaSagaSaiNoTraceDoPedido() {
		var p = saga.criar(7, Forma.SIMULADA);
		chega(p.id(), UnidadeReservada.TIPO, new UnidadeReservada(7, 1000));
		relogio.avancar(Duration.ofMinutes(5).plusSeconds(1));
		saga.vencerPrazos();
		chega(p.id(), UnidadeDevolvida.TIPO, new UnidadeDevolvida(true));

		var trace = pedido(p.id()).traceId();
		assertThat(trace).hasSize(32);
		// Reservar, Cobrar, Cancelar, Devolver e os 4 passos no painel
		assertThat(tracesNaSaida(p.id())).hasSize(8).containsOnly(trace);
	}

	/** Contraprova: com rastro.filho para os robôs, todos caem no trace de quem os soltou. */
	@Test
	void cadaRoboTemUmTraceSoDele() {
		var lancamento = UUID.randomUUID();
		jdbc.sql("""
				insert into lancamento (id, estado, robos, pedido_em, terminou_em)
				values (:id, 'ENCERRADO', 2, now(), now())
				""").param("id", lancamento).update();
		try (var quemSolta = rastro.novoTrace("rodada")) {
			var deFora = Rastro.idDoTrace(rastro.atual());
			var um = saga.criar(7, Forma.SIMULADA, lancamento);
			var outro = saga.criar(7, Forma.SIMULADA, lancamento);
			assertThat(List.of(um.traceId(), outro.traceId())).doesNotContain(deFora).doesNotHaveDuplicates();
			// O da loja fica no trace de quem chamou (o POST)
			assertThat(saga.criar(7, Forma.SIMULADA).traceId()).isEqualTo(deFora);
		}
	}

	private double terminados(String estado, String motivo) {
		var contador = metricas.find("foil.pedidos.terminados")
			.tags("estado", estado, "motivo", motivo, "origem", "loja")
			.counter();
		return contador == null ? 0 : contador.count();
	}

	/**
	 * A métrica do painel conta depois do commit: uma confirmação desfeita (o evento volta e é processado de novo) não
	 * conta duas vezes. Contraprova: contar direto no terminar() conta a desfeita.
	 */
	@Test
	void terminadoSoContaDepoisDoCommit() {
		var desfeito = saga.criar(7, Forma.SIMULADA);
		chega(desfeito.id(), UnidadeReservada.TIPO, new UnidadeReservada(7, 1000));
		var antes = terminados("CONFIRMADO", "nenhum");

		transacao.executeWithoutResult(t -> {
			chega(desfeito.id(), PagamentoAprovado.TIPO, new PagamentoAprovado(1000));
			t.setRollbackOnly();
		});
		assertThat(terminados("CONFIRMADO", "nenhum")).isEqualTo(antes);

		chega(desfeito.id(), PagamentoAprovado.TIPO, new PagamentoAprovado(1000));
		assertThat(terminados("CONFIRMADO", "nenhum")).isEqualTo(antes + 1);

		var recusado = saga.criar(7, Forma.SIMULADA);
		var recusados = terminados("CANCELADO", "PAGAMENTO_RECUSADO");
		chega(recusado.id(), UnidadeReservada.TIPO, new UnidadeReservada(7, 1000));
		chega(recusado.id(), PagamentoRecusado.TIPO, new PagamentoRecusado(PagamentoRecusado.Motivo.RECUSADO));
		chega(recusado.id(), UnidadeDevolvida.TIPO, new UnidadeDevolvida(true));
		assertThat(terminados("CANCELADO", "PAGAMENTO_RECUSADO")).isEqualTo(recusados + 1);
	}

}
