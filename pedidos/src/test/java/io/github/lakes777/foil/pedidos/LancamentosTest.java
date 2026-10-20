package io.github.lakes777.foil.pedidos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.lakes777.foil.comum.ConteineresDeTeste;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.EstoqueComandos.AbrirEdicao;
import io.github.lakes777.foil.contratos.EstoqueEventos.EdicaoAberta;
import io.github.lakes777.foil.contratos.EstoqueEventos.EdicaoRecusada;
import io.github.lakes777.foil.contratos.PedidosEventos.LancamentoMudouDeEstado;
import io.github.lakes777.foil.pedidos.Lancamentos.EstadoDoLancamento;
import tools.jackson.databind.json.JsonMapper;

/**
 * O lançamento sozinho, como o {@link SagaTest}: as respostas do estoque entram direto, o relógio é do teste e cada
 * rodada da agenda é um {@code avancar()} na mão. Mesma configuração do SagaTest (o Spring reaproveita o contexto).
 */
@SpringBootTest(properties = { "foil.saida.automatica=false", "foil.pedidos.prazos-automaticos=false" })
@Import({ ConteineresDeTeste.class, SagaTest.RelogioManual.class })
class LancamentosTest {

	@Autowired
	Lancamentos lancamentos;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	@Autowired
	RelogioDeTeste relogio;

	/**
	 * Cada teste começa sem lançamentos. Os robôs saem também: os 200 pedidos com prazo atrapalhariam o
	 * {@code vencerPrazos} do SagaTest, que trata 100 por vez (o mesmo banco).
	 */
	@BeforeEach
	@AfterEach
	void limpar() {
		jdbc.sql("delete from saida where chave in (select id::text from pedido where lancamento_id is not null)").update();
		jdbc.sql("delete from pedido where lancamento_id is not null").update();
		jdbc.sql("delete from saida where chave in (select id::text from lancamento)").update();
		jdbc.sql("delete from lancamento").update();
	}

	private void chega(UUID lancamento, String tipo, Object dados) {
		lancamentos.aoReceber(Envelope.novo(tipo, 1, lancamento, json.valueToTree(dados), Clock.systemUTC()));
	}

	private Lancamentos.Resumo lancamento(UUID id) {
		return lancamentos.buscar(id).orElseThrow();
	}

	/** O que foi para a saída com a chave do lançamento, na ordem: "tópico:tipo". */
	private List<String> mandados(UUID lancamento) {
		return jdbc.sql("select topico, envelope from saida where chave = :c order by seq")
			.param("c", lancamento.toString())
			.query((rs, i) -> rs.getString("topico") + ":"
					+ json.readValue(rs.getString("envelope"), Envelope.class).tipo())
			.list();
	}

	private List<LancamentoMudouDeEstado> passos(UUID lancamento) {
		return jdbc.sql("select envelope from saida where chave = :c and topico = 'pedidos.eventos' order by seq")
			.param("c", lancamento.toString())
			.query(String.class)
			.list()
			.stream()
			.map(e -> json.treeToValue(json.readValue(e, Envelope.class).dados(), LancamentoMudouDeEstado.class))
			.toList();
	}

	private int robos(UUID lancamento) {
		return jdbc.sql("select count(*) from pedido where lancamento_id = :l")
			.param("l", lancamento)
			.query(Integer.class)
			.single();
	}

	/** Pede, chama a vez e o estoque abre a edição 77: o lançamento fica SOLTANDO. */
	private UUID soltando() {
		var id = lancamentos.pedir().id();
		lancamentos.avancar();
		chega(id, EdicaoAberta.TIPO, new EdicaoAberta(77, 3, 10, 1000));
		return id;
	}

	private void soltarTodos(UUID id) {
		relogio.avancar(Duration.ofMinutes(1));
		while (lancamento(id).estado() == EstadoDoLancamento.SOLTANDO) {
			lancamentos.avancar();
		}
	}

	@Test
	void umPorVezNaOrdemDeChegada() {
		var primeiro = lancamentos.pedir();
		var segundo = lancamentos.pedir();
		assertThat(primeiro.estado()).isEqualTo(EstadoDoLancamento.NA_FILA);
		assertThat(primeiro.posicaoNaFila()).isEqualTo(1);
		assertThat(segundo.posicaoNaFila()).isEqualTo(2);

		lancamentos.avancar();
		lancamentos.avancar();

		assertThat(lancamento(primeiro.id()).estado()).isEqualTo(EstadoDoLancamento.ABRINDO);
		assertThat(lancamento(segundo.id()).estado()).isEqualTo(EstadoDoLancamento.NA_FILA);
		assertThat(lancamento(segundo.id()).posicaoNaFila()).isEqualTo(1);
		assertThat(mandados(primeiro.id())).containsExactly("pedidos.eventos:LancamentoMudouDeEstado",
				"estoque.comandos:AbrirEdicao", "pedidos.eventos:LancamentoMudouDeEstado");
		var abrir = jdbc.sql("select envelope from saida where chave = :c and topico = 'estoque.comandos'")
			.param("c", primeiro.id().toString())
			.query(String.class)
			.single();
		assertThat(json.treeToValue(json.readValue(abrir, Envelope.class).dados(), AbrirEdicao.class))
			.isEqualTo(new AbrirEdicao(10));
		assertThat(mandados(segundo.id())).containsExactly("pedidos.eventos:LancamentoMudouDeEstado");
	}

	@Test
	void robosSaemNoRitmoComoPedidosSimuladosDaEdicao() {
		var id = soltando();
		assertThat(lancamento(id).estado()).isEqualTo(EstadoDoLancamento.SOLTANDO);
		assertThat(lancamento(id).edicaoId()).isEqualTo(77);

		lancamentos.avancar();
		assertThat(robos(id)).isEqualTo(1);
		lancamentos.avancar();
		assertThat(robos(id)).as("mesma hora, nenhum robô a mais").isEqualTo(1);

		relogio.avancar(Duration.ofSeconds(1));
		lancamentos.avancar();
		assertThat(robos(id)).as("10 por segundo").isEqualTo(11);
		assertThat(lancamento(id).soltos()).isEqualTo(11);

		// Uma parada longa: o atraso volta em lotes de 50, não numa rajada só
		relogio.avancar(Duration.ofMinutes(1));
		lancamentos.avancar();
		assertThat(robos(id)).isEqualTo(61);

		soltarTodos(id);
		assertThat(robos(id)).isEqualTo(200);
		var l = lancamento(id);
		assertThat(l.estado()).isEqualTo(EstadoDoLancamento.ESPERANDO);
		assertThat(l.soltos()).isEqualTo(200);
		assertThat(l.emAndamento()).isEqualTo(200);
		assertThat(jdbc.sql("""
				select count(*) from pedido where lancamento_id = :l and edicao_id = 77 and forma = 'SIMULADA'
				and estado = 'RESERVANDO'
				""").param("l", id).query(Integer.class).single()).isEqualTo(200);
	}

	@Test
	void encerraQuandoTodasAsSagasTerminamEChamaOProximo() {
		var id = soltando();
		var proximo = lancamentos.pedir().id();
		soltarTodos(id);
		lancamentos.avancar();
		assertThat(lancamento(id).estado()).as("ainda há sagas andando").isEqualTo(EstadoDoLancamento.ESPERANDO);

		jdbc.sql("""
				update pedido set estado = case when id in (select id from pedido where lancamento_id = :l order by id limit 9)
				then 'CONFIRMADO' else 'SEM_ESTOQUE' end, prazo = null
				where lancamento_id = :l and id <> (select max(id::text)::uuid from pedido where lancamento_id = :l)
				""").param("l", id).update();
		lancamentos.avancar();
		assertThat(lancamento(id).estado()).as("falta uma").isEqualTo(EstadoDoLancamento.ESPERANDO);

		jdbc.sql("update pedido set estado = 'CANCELADO', prazo = null where lancamento_id = :l and prazo is not null")
			.param("l", id)
			.update();
		lancamentos.avancar();

		var fim = lancamento(id);
		assertThat(fim.estado()).isEqualTo(EstadoDoLancamento.ENCERRADO);
		assertThat(fim.motivo()).isNull();
		assertThat(fim.terminouEm()).isNotNull();
		assertThat(fim.vendidos() + fim.semEstoque() + fim.desfeitos()).isEqualTo(200);
		assertThat(fim.vendidos()).isEqualTo(9);
		assertThat(fim.desfeitos()).isEqualTo(1);
		assertThat(passos(id)).extracting(LancamentoMudouDeEstado::estado)
			.containsExactly("NA_FILA", "ABRINDO", "SOLTANDO", "ESPERANDO", "ENCERRADO");

		lancamentos.avancar();
		assertThat(lancamento(proximo).estado()).isEqualTo(EstadoDoLancamento.ABRINDO);
	}

	@Test
	void esperaComPrazoNaoTravaAFila() {
		var id = soltando();
		soltarTodos(id);

		relogio.avancar(Duration.ofMinutes(10).plusSeconds(1));
		lancamentos.avancar();

		assertThat(lancamento(id).estado()).isEqualTo(EstadoDoLancamento.ENCERRADO);
		assertThat(lancamento(id).motivo()).isEqualTo("PRAZO");
	}

	@Test
	void estoqueSemRespostaRecebeOAbrirEdicaoDeNovo() {
		var id = lancamentos.pedir().id();
		lancamentos.avancar();
		relogio.avancar(Duration.ofSeconds(59));
		lancamentos.avancar();
		assertThat(mandados(id)).filteredOn(m -> m.startsWith("estoque.")).hasSize(1);

		relogio.avancar(Duration.ofSeconds(2));
		lancamentos.avancar();

		assertThat(mandados(id)).filteredOn(m -> m.startsWith("estoque.")).hasSize(2);
		assertThat(lancamento(id).estado()).isEqualTo(EstadoDoLancamento.ABRINDO);
	}

	@Test
	void estoqueQueNuncaRespondeFalhaEPassaAVez() {
		var id = lancamentos.pedir().id();
		var proximo = lancamentos.pedir().id();
		lancamentos.avancar();
		for (int i = 1; i < Lancamentos.ABERTURAS_MAXIMAS; i++) {
			relogio.avancar(Duration.ofMinutes(1).plusSeconds(1));
			lancamentos.avancar();
			assertThat(lancamento(id).estado()).isEqualTo(EstadoDoLancamento.ABRINDO);
		}
		assertThat(mandados(id)).filteredOn(m -> m.startsWith("estoque.")).hasSize(Lancamentos.ABERTURAS_MAXIMAS);

		relogio.avancar(Duration.ofMinutes(1).plusSeconds(1));
		lancamentos.avancar();

		assertThat(lancamento(id).estado()).isEqualTo(EstadoDoLancamento.FALHOU);
		assertThat(lancamento(id).motivo()).isEqualTo("ESTOQUE_SEM_RESPOSTA");
		assertThat(mandados(id)).filteredOn(m -> m.startsWith("estoque.")).hasSize(Lancamentos.ABERTURAS_MAXIMAS);
		lancamentos.avancar();
		assertThat(lancamento(proximo).estado()).isEqualTo(EstadoDoLancamento.ABRINDO);

		// A resposta que chega atrasada não ressuscita o lançamento
		chega(id, EdicaoAberta.TIPO, new EdicaoAberta(77, 3, 10, 1000));
		assertThat(lancamento(id).estado()).isEqualTo(EstadoDoLancamento.FALHOU);
	}

	@Test
	void duasRodadasAoMesmoTempoChamamUmSo() throws Exception {
		var id = lancamentos.pedir().id();
		var segundo = lancamentos.pedir().id();
		var largada = new CountDownLatch(1);
		try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
			var rodadas = new ArrayList<Future<?>>();
			for (int i = 0; i < 8; i++) {
				rodadas.add(threads.submit(() -> {
					largada.await();
					lancamentos.avancar();
					return null;
				}));
			}
			largada.countDown();
			for (var r : rodadas) {
				r.get();
			}
		}

		assertThat(lancamento(id).estado()).isEqualTo(EstadoDoLancamento.ABRINDO);
		assertThat(lancamento(segundo).estado()).isEqualTo(EstadoDoLancamento.NA_FILA);
		assertThat(mandados(id)).filteredOn(m -> m.startsWith("estoque.")).hasSize(1);
	}

	@Test
	void edicaoRecusadaFalhaEPassaAVez() {
		var id = lancamentos.pedir().id();
		var proximo = lancamentos.pedir().id();
		lancamentos.avancar();

		chega(id, EdicaoRecusada.TIPO, new EdicaoRecusada(EdicaoRecusada.Motivo.SEM_CARTA_RARA));
		lancamentos.avancar();

		assertThat(lancamento(id).estado()).isEqualTo(EstadoDoLancamento.FALHOU);
		assertThat(lancamento(id).motivo()).isEqualTo("SEM_CARTA_RARA");
		assertThat(robos(id)).isZero();
		assertThat(lancamento(proximo).estado()).isEqualTo(EstadoDoLancamento.ABRINDO);
	}

	@Test
	void respostaRepetidaDoEstoqueNaoMudaNada() {
		var id = soltando();
		lancamentos.avancar();

		chega(id, EdicaoAberta.TIPO, new EdicaoAberta(88, 3, 10, 1000));
		chega(id, EdicaoRecusada.TIPO, new EdicaoRecusada(EdicaoRecusada.Motivo.SEM_CARTA_RARA));

		var l = lancamento(id);
		assertThat(l.estado()).isEqualTo(EstadoDoLancamento.SOLTANDO);
		assertThat(l.edicaoId()).isEqualTo(77);
		assertThat(l.comecouEm()).isNotNull();
		assertThat(robos(id)).isEqualTo(1);
	}

	@Test
	void filaCheiaRecusaOSexto() {
		lancamentos.pedir();
		lancamentos.avancar();
		// O da vez não conta: são 5 esperando
		for (int i = 0; i < 5; i++) {
			lancamentos.pedir();
		}

		assertThatThrownBy(lancamentos::pedir).isInstanceOf(Recusas.Lotado.class);
		assertThat(lancamentos.situacao().fila()).hasSize(5);
	}

	@Test
	void situacaoMostraODaVezAFilaEOsRecentes() {
		var falhou = lancamentos.pedir().id();
		lancamentos.avancar();
		chega(falhou, EdicaoRecusada.TIPO, new EdicaoRecusada(EdicaoRecusada.Motivo.SEM_CARTA_RARA));
		var daVez = soltando();
		var esperando = lancamentos.pedir().id();

		var s = lancamentos.situacao();

		assertThat(s.atual().id()).isEqualTo(daVez);
		assertThat(s.fila()).extracting(Lancamentos.Resumo::id).containsExactly(esperando);
		assertThat(s.recentes()).extracting(Lancamentos.Resumo::id).containsExactly(falhou);
	}

	/** Contraprova da regra no banco: mesmo com um bug no código, dois lançamentos andando ao mesmo tempo não cabem. */
	@Test
	void oBancoRecusaDoisLancamentosAoMesmoTempo() {
		var sql = """
				insert into lancamento (id, estado, robos, prazo, pedido_em) values (:id, 'ABRINDO', 200, now(), now())
				""";
		jdbc.sql(sql).param("id", UUID.randomUUID()).update();

		assertThatThrownBy(() -> jdbc.sql(sql).param("id", UUID.randomUUID()).update())
			.isInstanceOf(DuplicateKeyException.class);
	}

}
