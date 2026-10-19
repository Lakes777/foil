package io.github.lakes777.foil.estoque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@TesteDoEstoque
class ReservaTest {

	@Autowired
	EstoqueService estoque;

	@Autowired
	JdbcClient jdbc;

	private Edicao edicaoNova(int total) {
		var carta = estoque.criarCarta("Carta " + UUID.randomUUID(), "rara", "");
		return estoque.novaEdicao(carta.id(), total);
	}

	private int disponivel(Edicao edicao) {
		return estoque.buscarEdicao(edicao.id()).orElseThrow().disponivel();
	}

	private int reservasAtivas(Edicao edicao) {
		return jdbc.sql("select count(*) from reserva where edicao_id = :e and situacao = 'reservada'")
			.param("e", edicao.id())
			.query(Integer.class)
			.single();
	}

	/** Roda as tarefas todas ao mesmo tempo (soltas juntas por um portão) e conta os resultados. */
	private static <T extends Enum<T>> Map<T, Integer> todasJuntas(Class<T> tipo, List<Callable<T>> tarefas)
			throws Exception {
		var portao = new CountDownLatch(1);
		var futuros = new ArrayList<Future<T>>();
		try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
			for (var tarefa : tarefas) {
				futuros.add(threads.submit(() -> {
					portao.await();
					return tarefa.call();
				}));
			}
			portao.countDown();
		}
		var contagem = new EnumMap<T, Integer>(tipo);
		for (var futuro : futuros) {
			// get() relança qualquer exceção: um erro de banco reprova o teste, não vira "sem estoque"
			contagem.merge(futuro.get(), 1, Integer::sum);
		}
		return contagem;
	}

	@Test
	void duzentosPedidosAoMesmoTempoNumaEdicaoDeDezVendemExatamenteDez() throws Exception {
		var edicao = edicaoNova(10);
		var tarefas = new ArrayList<Callable<ResultadoDaReserva>>();
		for (int i = 0; i < 200; i++) {
			var pedido = UUID.randomUUID();
			tarefas.add(() -> estoque.reservar(pedido, edicao.id()));
		}

		var resultados = todasJuntas(ResultadoDaReserva.class, tarefas);

		assertThat(resultados).containsOnly(Map.entry(ResultadoDaReserva.RESERVADA, 10),
				Map.entry(ResultadoDaReserva.SEM_ESTOQUE, 190));
		assertThat(disponivel(edicao)).isZero();
		assertThat(reservasAtivas(edicao)).isEqualTo(10);
	}

	@Test
	void oMesmoPedidoVinteVezesAoMesmoTempoTiraUmaUnidadeSo() throws Exception {
		var edicao = edicaoNova(5);
		var pedido = UUID.randomUUID();
		var tarefas = new ArrayList<Callable<ResultadoDaReserva>>();
		for (int i = 0; i < 20; i++) {
			tarefas.add(() -> estoque.reservar(pedido, edicao.id()));
		}

		var resultados = todasJuntas(ResultadoDaReserva.class, tarefas);

		assertThat(resultados).containsOnly(Map.entry(ResultadoDaReserva.RESERVADA, 1),
				Map.entry(ResultadoDaReserva.JA_RESERVADA, 19));
		assertThat(disponivel(edicao)).isEqualTo(4);
	}

	@Test
	void edicaoQueNaoExisteNaoReservaNada() {
		var pedido = UUID.randomUUID();

		assertThat(estoque.reservar(pedido, -1)).isEqualTo(ResultadoDaReserva.EDICAO_INEXISTENTE);
		assertThat(estoque.devolver(pedido, -1)).isEqualTo(ResultadoDaDevolucao.SEM_RESERVA);
	}

	@Test
	void semEstoqueEDefinitivoMesmoQueUmaUnidadeVolteDepois() {
		var edicao = edicaoNova(1);
		var primeiro = UUID.randomUUID();
		var segundo = UUID.randomUUID();
		assertThat(estoque.reservar(primeiro, edicao.id())).isEqualTo(ResultadoDaReserva.RESERVADA);
		assertThat(estoque.reservar(segundo, edicao.id())).isEqualTo(ResultadoDaReserva.SEM_ESTOQUE);

		assertThat(estoque.devolver(segundo, edicao.id())).isEqualTo(ResultadoDaDevolucao.SEM_RESERVA);
		assertThat(estoque.devolver(primeiro, edicao.id())).isEqualTo(ResultadoDaDevolucao.DEVOLVIDA);

		// A saga do segundo já acabou em SEM_ESTOQUE: se ele levasse a unidade agora, ninguém a devolveria
		assertThat(estoque.reservar(segundo, edicao.id())).isEqualTo(ResultadoDaReserva.SEM_ESTOQUE);
		assertThat(disponivel(edicao)).isEqualTo(1);
		// Um pedido novo leva a unidade devolvida
		assertThat(estoque.reservar(UUID.randomUUID(), edicao.id())).isEqualTo(ResultadoDaReserva.RESERVADA);
		assertThat(disponivel(edicao)).isZero();
	}

	@Test
	void oMesmoPedidoVinteVezesAoMesmoTempoNumaEdicaoEsgotadaFicaSemEstoqueSemErro() throws Exception {
		var edicao = edicaoNova(1);
		estoque.reservar(UUID.randomUUID(), edicao.id());
		var pedido = UUID.randomUUID();
		var tarefas = new ArrayList<Callable<ResultadoDaReserva>>();
		for (int i = 0; i < 20; i++) {
			tarefas.add(() -> estoque.reservar(pedido, edicao.id()));
		}

		var resultados = todasJuntas(ResultadoDaReserva.class, tarefas);

		assertThat(resultados).containsOnly(Map.entry(ResultadoDaReserva.SEM_ESTOQUE, 20));
		assertThat(disponivel(edicao)).isZero();
		assertThat(reservasAtivas(edicao)).isEqualTo(1);
	}

	@Test
	void devolverVoltaUmaUnidadeUmaVezSo() {
		var edicao = edicaoNova(3);
		var pedido = UUID.randomUUID();
		estoque.reservar(pedido, edicao.id());

		assertThat(estoque.devolver(pedido, edicao.id())).isEqualTo(ResultadoDaDevolucao.DEVOLVIDA);
		assertThat(estoque.devolver(pedido, edicao.id())).isEqualTo(ResultadoDaDevolucao.JA_DEVOLVIDA);
		assertThat(disponivel(edicao)).isEqualTo(3);
		// Pedido devolvido não reserva de novo (a saga dele acabou)
		assertThat(estoque.reservar(pedido, edicao.id())).isEqualTo(ResultadoDaReserva.JA_DEVOLVIDA);
		assertThat(disponivel(edicao)).isEqualTo(3);
	}

	@Test
	void cinquentaDevolucoesAoMesmoTempoDoMesmoPedidoDevolvemUmaUnidade() throws Exception {
		var edicao = edicaoNova(2);
		var pedido = UUID.randomUUID();
		estoque.reservar(pedido, edicao.id());
		estoque.reservar(UUID.randomUUID(), edicao.id());
		var tarefas = new ArrayList<Callable<ResultadoDaDevolucao>>();
		for (int i = 0; i < 50; i++) {
			tarefas.add(() -> estoque.devolver(pedido, edicao.id()));
		}

		var resultados = todasJuntas(ResultadoDaDevolucao.class, tarefas);

		assertThat(resultados).containsOnly(Map.entry(ResultadoDaDevolucao.DEVOLVIDA, 1),
				Map.entry(ResultadoDaDevolucao.JA_DEVOLVIDA, 49));
		assertThat(disponivel(edicao)).isEqualTo(1);
	}

	@Test
	void devolverAntesDeReservarTravaAReservaAtrasada() {
		var edicao = edicaoNova(3);
		var pedido = UUID.randomUUID();

		// A devolução chega primeiro (a reserva estava na fila de mortos e foi reprocessada depois)
		assertThat(estoque.devolver(pedido, edicao.id())).isEqualTo(ResultadoDaDevolucao.SEM_RESERVA);

		assertThat(estoque.reservar(pedido, edicao.id())).isEqualTo(ResultadoDaReserva.JA_DEVOLVIDA);
		assertThat(disponivel(edicao)).isEqualTo(3);
		assertThat(estoque.devolver(pedido, edicao.id())).isEqualTo(ResultadoDaDevolucao.JA_DEVOLVIDA);
		assertThat(disponivel(edicao)).isEqualTo(3);
	}

	@Test
	void cadaEdicaoTemEstoqueProprioENumeroSeguinte() {
		var carta = estoque.criarCarta("Carta " + UUID.randomUUID(), "lendaria", "");
		var primeira = estoque.novaEdicao(carta.id(), 1);
		var segunda = estoque.novaEdicao(carta.id(), 4);

		estoque.reservar(UUID.randomUUID(), primeira.id());

		assertThat(primeira.numero()).isEqualTo(1);
		assertThat(segunda.numero()).isEqualTo(2);
		assertThat(disponivel(primeira)).isZero();
		assertThat(disponivel(segunda)).isEqualTo(4);
		assertThat(estoque.edicoes(carta.id())).extracting(Edicao::numero).containsExactly(1, 2);
	}

	@Test
	void oBancoRecusaDisponivelNegativoOuAcimaDoTotal() {
		var edicao = edicaoNova(2);

		assertThatThrownBy(() -> jdbc.sql("update edicao set disponivel = -1 where id = :e")
			.param("e", edicao.id())
			.update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("update edicao set disponivel = 3 where id = :e")
			.param("e", edicao.id())
			.update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void apagarUmaEdicaoLevaAsReservasJunto() {
		var edicao = edicaoNova(2);
		estoque.reservar(UUID.randomUUID(), edicao.id());

		jdbc.sql("delete from edicao where id = :e").param("e", edicao.id()).update();

		assertThat(reservasAtivas(edicao)).isZero();
	}

	@Test
	void novaEdicaoDeCartaInexistenteERecusada() {
		assertThatThrownBy(() -> estoque.novaEdicao(-1, 10)).isInstanceOf(IllegalArgumentException.class);
	}

}
