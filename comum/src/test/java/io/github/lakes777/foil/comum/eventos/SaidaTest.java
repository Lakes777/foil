package io.github.lakes777.foil.comum.eventos;

import static io.github.lakes777.foil.comum.LeitorDeTopico.doPedido;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.redpanda.RedpandaContainer;

import io.github.lakes777.foil.comum.ConteineresDeTeste;
import io.github.lakes777.foil.comum.LeitorDeTopico;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.Topicos;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbox e consumidor idempotente com Postgres e Redpanda de verdade. O publicador automático fica desligado:
 * cada teste decide quando "o serviço" publica, para simular as quedas.
 */
@SpringBootTest(properties = { "spring.application.name=teste", "spring.flyway.locations=classpath:db/teste-comum",
		"foil.saida.automatica=false" })
@Import(ConteineresDeTeste.class)
class SaidaTest {

	private static final String TOPICO = Topicos.PEDIDOS_EVENTOS;

	@Autowired
	Saida saida;

	@Autowired
	PublicadorDaSaida publicador;

	@Autowired
	EventosProcessados processados;

	@Autowired
	TransactionTemplate transacao;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	@Autowired
	RedpandaContainer redpanda;

	private Envelope evento(UUID pedido, String tipo) {
		return Envelope.novo(tipo, 1, pedido, json.readTree("{}"), Clock.systemUTC());
	}

	private void registrar(Envelope... envelopes) {
		transacao.executeWithoutResult(t -> {
			for (var e : envelopes) {
				saida.registrar(TOPICO, e);
			}
		});
	}

	private int pendentes(UUID pedido) {
		return jdbc.sql("select count(*) from saida where chave = :c and publicado_em is null")
			.param("c", pedido.toString())
			.query(Integer.class)
			.single();
	}

	private LeitorDeTopico leitor() {
		return new LeitorDeTopico(redpanda.getBootstrapServers(), TOPICO);
	}

	@Test
	void registrarForaDeTransacaoEProibido() {
		assertThatThrownBy(() -> saida.registrar(TOPICO, evento(UUID.randomUUID(), "X")))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void transacaoDesfeitaNaoDeixaEvento() {
		var pedido = UUID.randomUUID();

		transacao.executeWithoutResult(t -> {
			saida.registrar(TOPICO, evento(pedido, "X"));
			t.setRollbackOnly();
		});

		assertThat(jdbc.sql("select count(*) from saida where chave = :c")
			.param("c", pedido.toString())
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	void publicaEmOrdemNaMesmaParticaoComAChaveDoPedido() {
		var pedido = UUID.randomUUID();
		registrar(evento(pedido, "Um"), evento(pedido, "Dois"), evento(pedido, "Tres"));
		registrar(evento(pedido, "Quatro"));

		publicador.publicarPendentes();

		try (var leitor = leitor()) {
			var lidos = leitor.ler(doPedido(pedido), 4, Duration.ofSeconds(15));
			assertThat(lidos).extracting(r -> json.readValue(r.value(), Envelope.class).tipo())
				.containsExactly("Um", "Dois", "Tres", "Quatro");
			assertThat(lidos).extracting(r -> r.partition()).containsOnly(lidos.getFirst().partition());
		}
		assertThat(pendentes(pedido)).isZero();
	}

	@Test
	void quedaEntreGravarEPublicarNaoPerdeOEvento() {
		var pedido = UUID.randomUUID();
		var envelope = evento(pedido, "Gravado");
		// O serviço grava e cai antes de publicar: o evento só existe no banco
		registrar(envelope);

		try (var leitor = leitor()) {
			assertThat(leitor.lerDurante(doPedido(pedido), Duration.ofSeconds(2))).isEmpty();
			assertThat(pendentes(pedido)).isEqualTo(1);

			// O serviço volta e o publicador encontra o pendente
			publicador.publicarPendentes();

			var lidos = leitor.ler(doPedido(pedido), 1, Duration.ofSeconds(15));
			assertThat(lidos).hasSize(1);
			assertThat(json.readValue(lidos.getFirst().value(), Envelope.class)).isEqualTo(envelope);
		}
		assertThat(pendentes(pedido)).isZero();
	}

	@Test
	void quedaEntrePublicarEMarcarRepublicaOMesmoId() {
		var pedido = UUID.randomUUID();
		var envelope = evento(pedido, "Duplicado");
		registrar(envelope);
		publicador.publicarPendentes();
		// O serviço publicou mas caiu antes de gravar "publicado": a marcação se perdeu
		jdbc.sql("update saida set publicado_em = null where id = :id").param("id", envelope.id()).update();

		publicador.publicarPendentes();

		try (var leitor = leitor()) {
			var lidos = leitor.ler(doPedido(pedido), 2, Duration.ofSeconds(15));
			// Pelo menos uma vez: chega duas vezes com o mesmo id, e é por isso que o consumidor anota os ids
			assertThat(lidos).hasSize(2)
				.extracting(r -> json.readValue(r.value(), Envelope.class).id())
				.containsOnly(envelope.id());
		}
	}

	@Test
	void eventoQueNaoSaiTravaSoOMesmoPedido() {
		var travado = UUID.randomUUID();
		var outro = UUID.randomUUID();
		// Mais de 1 MB: o produtor recusa (RecordTooLargeException) todas as vezes
		var enorme = Envelope.novo("Enorme", 1, travado, json.valueToTree(Map.of("x", "a".repeat(1_200_000))),
				Clock.systemUTC());
		var depoisDoEnorme = evento(travado, "DepoisDoEnorme");
		registrar(enorme, depoisDoEnorme, evento(outro, "DoOutro"));

		publicador.publicarPendentes();

		try (var leitor = leitor()) {
			assertThat(leitor.ler(doPedido(outro), 1, Duration.ofSeconds(15))).hasSize(1);
			// O seguinte do pedido travado não passa na frente do que falhou
			assertThat(leitor.lerDurante(doPedido(travado), Duration.ofSeconds(2))).isEmpty();
		}
		assertThat(pendentes(travado)).isEqualTo(2);
		assertThat(pendentes(outro)).isZero();
	}

	@Test
	void consumidorReconheceOEventoRepetido() {
		var id = UUID.randomUUID();

		var primeira = transacao.execute(t -> processados.primeiraVez(id));
		var segunda = transacao.execute(t -> processados.primeiraVez(id));

		assertThat(primeira).isTrue();
		assertThat(segunda).isFalse();
	}

	@Test
	void anotacaoDesfeitaJuntoComOEfeitoDeixaProcessarDeNovo() {
		var id = UUID.randomUUID();

		transacao.executeWithoutResult(t -> {
			processados.primeiraVez(id);
			t.setRollbackOnly(); // o efeito falhou: a anotação também não pode ficar
		});

		Boolean deNovo = transacao.execute(t -> processados.primeiraVez(id));
		assertThat(deNovo).isTrue();
	}

	@Test
	void vinteEntregasAoMesmoTempoDoMesmoEventoSoUmaPassa() throws Exception {
		var id = UUID.randomUUID();
		var portao = new CountDownLatch(1);
		var futuros = new ArrayList<Future<Boolean>>();
		try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < 20; i++) {
				Callable<Boolean> tarefa = () -> {
					portao.await();
					return transacao.execute(t -> processados.primeiraVez(id));
				};
				futuros.add(threads.submit(tarefa));
			}
			portao.countDown();
		}
		var passaram = 0;
		for (var f : futuros) {
			passaram += f.get() ? 1 : 0;
		}

		assertThat(passaram).isEqualTo(1);
	}

	@Test
	void anotarForaDeTransacaoEProibido() {
		assertThatThrownBy(() -> processados.primeiraVez(UUID.randomUUID()))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

}
