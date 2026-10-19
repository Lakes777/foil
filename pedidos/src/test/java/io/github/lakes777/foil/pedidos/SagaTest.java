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

import io.github.lakes777.foil.comum.ConteineresDeTeste;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.EstoqueEventos.ReservaRecusada;
import io.github.lakes777.foil.contratos.EstoqueEventos.UnidadeDevolvida;
import io.github.lakes777.foil.contratos.EstoqueEventos.UnidadeReservada;
import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoAprovado;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoRecusado;
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
		var p = saga.criar(7);

		assertThat(p.estado()).isEqualTo(Estado.RESERVANDO);
		assertThat(p.prazo()).isNotNull();
		assertThat(mandados(p.id())).containsExactly("estoque.comandos:ReservarUnidade",
				"pedidos.eventos:PedidoMudouDeEstado");
	}

	@Test
	void caminhoFelizTerminaConfirmado() {
		var p = saga.criar(7);

		chega(p.id(), UnidadeReservada.TIPO, new UnidadeReservada(7, 1500));
		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.AGUARDANDO_PAGAMENTO);
		assertThat(pedido(p.id()).precoCentavos()).isEqualTo(1500);
		var cobrar = jdbc.sql("select envelope from saida where chave = :c and topico = 'pagamentos.comandos'")
			.param("c", p.id().toString())
			.query(String.class)
			.single();
		assertThat(json.treeToValue(json.readValue(cobrar, Envelope.class).dados(), CobrarPedido.class))
			.isEqualTo(new CobrarPedido(1500));

		chega(p.id(), PagamentoAprovado.TIPO, new PagamentoAprovado(1500));

		var fim = pedido(p.id());
		assertThat(fim.estado()).isEqualTo(Estado.CONFIRMADO);
		assertThat(fim.prazo()).isNull();
		assertThat(comandos(p.id())).containsExactly("estoque.comandos:ReservarUnidade",
				"pagamentos.comandos:CobrarPedido");
	}

	@Test
	void reservaRecusadaTerminaSemEstoque() {
		var p = saga.criar(7);

		chega(p.id(), ReservaRecusada.TIPO, new ReservaRecusada(7, ReservaRecusada.Motivo.SEM_ESTOQUE));

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.SEM_ESTOQUE);
		assertThat(pedido(p.id()).motivo()).isEqualTo("SEM_ESTOQUE");
		assertThat(comandos(p.id())).containsExactly("estoque.comandos:ReservarUnidade");
	}

	@Test
	void pagamentoRecusadoDevolveAUnidadeETerminaCancelado() {
		var p = saga.criar(7);
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
		var p = saga.criar(7);
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
		var p = saga.criar(7);

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
		var p = saga.criar(7);
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
		var p = saga.criar(7);

		relogio.avancar(Duration.ofMinutes(1));
		saga.vencerPrazos();

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.RESERVANDO);
	}

	@Test
	void estadoFinalIgnoraQualquerEventoAtrasado() {
		var p = saga.criar(7);
		chega(p.id(), ReservaRecusada.TIPO, new ReservaRecusada(7, ReservaRecusada.Motivo.SEM_ESTOQUE));

		chega(p.id(), UnidadeReservada.TIPO, new UnidadeReservada(7, 1000));
		chega(p.id(), PagamentoAprovado.TIPO, new PagamentoAprovado(1000));
		chega(p.id(), UnidadeDevolvida.TIPO, new UnidadeDevolvida(false));

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.SEM_ESTOQUE);
		assertThat(comandos(p.id())).containsExactly("estoque.comandos:ReservarUnidade");
	}

	@Test
	void eventoDesconhecidoVaiParaOsMortosEPedidoDesconhecidoNaoFazNada() {
		var p = saga.criar(7);

		assertThatThrownBy(() -> chega(p.id(), "Inventado", new Object())).isInstanceOf(IllegalArgumentException.class);
		chega(UUID.randomUUID(), PagamentoAprovado.TIPO, new PagamentoAprovado(1000));

		assertThat(pedido(p.id()).estado()).isEqualTo(Estado.RESERVANDO);
	}

}
