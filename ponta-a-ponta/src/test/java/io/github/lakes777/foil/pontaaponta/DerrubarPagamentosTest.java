package io.github.lakes777.foil.pontaaponta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

import tools.jackson.databind.json.JsonMapper;

/**
 * Critério da fase 4 (SPEC): derrubar pagamentos no meio e, ao voltar, todas as sagas terminam em estado final,
 * sem pedido preso. Os três serviços rodam como processos de verdade; pagamentos cai com kill -9.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DerrubarPagamentosTest {

	/** Curto para o teste; na demo são 2 min (reserva) e 5 min (pagamento). */
	static final Duration PRAZO = Duration.ofSeconds(25);

	static final List<String> INTERMEDIARIOS = List.of("RESERVANDO", "AGUARDANDO_PAGAMENTO", "LIBERANDO");

	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	static RedpandaContainer redpanda = new RedpandaContainer("redpandadata/redpanda:v26.2.5");

	static Servico estoque;

	static Servico pedidos;

	static Servico pagamentos;

	static JdbcClient bancoDoEstoque;

	static JdbcClient bancoDosPedidos;

	static JdbcClient bancoDosPagamentos;

	static final HttpClient HTTP = HttpClient.newHttpClient();

	static final JsonMapper JSON = JsonMapper.builder().build();

	@BeforeAll
	static void subirTudo() throws Exception {
		postgres.start();
		redpanda.start();
		criarBancos();
		estoque = servico("estoque");
		pedidos = servico("pedidos");
		pagamentos = servico("pagamentos");
		estoque.subir();
		pedidos.subir();
		pagamentos.subir();
		bancoDoEstoque = banco("foil_estoque");
		bancoDosPedidos = banco("foil_pedidos");
		bancoDosPagamentos = banco("foil_pagamentos");
	}

	/** O Postgres às vezes recusa a primeira conexão logo depois de subir: tenta por até 30 s. */
	static void criarBancos() throws Exception {
		var fim = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (true) {
			try (var conexao = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword()); var sql = conexao.createStatement()) {
				for (var banco : List.of("foil_estoque", "foil_pedidos", "foil_pagamentos")) {
					sql.execute("create database " + banco);
				}
				return;
			}
			catch (SQLException e) {
				if (System.nanoTime() > fim) {
					throw e;
				}
				Thread.sleep(500);
			}
		}
	}

	@AfterAll
	static void desligarTudo() throws Exception {
		for (var s : new Servico[] { pagamentos, pedidos, estoque }) {
			if (s != null) {
				s.desligar();
			}
		}
		redpanda.stop();
		postgres.stop();
	}

	static Servico servico(String nome) throws Exception {
		var url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/foil_" + nome + "$1");
		return new Servico(nome,
				Map.of("FOIL_BANCO_URL", url, "FOIL_BANCO_USUARIO", postgres.getUsername(), "FOIL_BANCO_SENHA",
						postgres.getPassword(), "FOIL_KAFKA", redpanda.getBootstrapServers()),
				List.of("--foil.pedidos.prazo-da-reserva=" + PRAZO.toSeconds() + "s",
						"--foil.pedidos.prazo-do-pagamento=" + PRAZO.toSeconds() + "s",
						"--foil.consumo.espera-maxima-ms=2000", "--foil.pagamentos.simulado.taxa-de-aprovacao=0.8",
						// Todas as compras saem do mesmo IP (o do teste)
						"--foil.limite.pedidos-por-minuto=1000"));
	}

	static JdbcClient banco(String nome) {
		var url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + nome + "$1");
		return JdbcClient.create(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
	}

	static long edicaoNova(int total) {
		var carta = bancoDoEstoque.sql("insert into carta (nome, raridade) values (:n, 'rara') returning id")
			.param("n", "Carta " + UUID.randomUUID())
			.query(Long.class)
			.single();
		return bancoDoEstoque
			.sql("insert into edicao (carta_id, numero, total, disponivel) values (:c, 1, :t, :t) returning id")
			.param("c", carta)
			.param("t", total)
			.query(Long.class)
			.single();
	}

	static void comprar(long edicao, int quantos) throws Exception {
		for (int i = 0; i < quantos; i++) {
			var pedido = HttpRequest.newBuilder(URI.create(pedidos.url("/pedidos")))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"edicaoId\":" + edicao + ",\"forma\":\"SIMULADA\"}"))
				.build();
			assertThat(HTTP.send(pedido, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(202);
		}
	}

	static int contar(long edicao, String estado) {
		return bancoDosPedidos.sql("select count(*) from pedido where edicao_id = :e and estado = :s")
			.param("e", edicao)
			.param("s", estado)
			.query(Integer.class)
			.single();
	}

	static int presos(long edicao) {
		return bancoDosPedidos.sql("select count(*) from pedido where edicao_id = :e and estado in (:s)")
			.param("e", edicao)
			.param("s", INTERMEDIARIOS)
			.query(Integer.class)
			.single();
	}

	static int disponivel(long edicao) {
		return bancoDoEstoque.sql("select disponivel from edicao where id = :e")
			.param("e", edicao)
			.query(Integer.class)
			.single();
	}

	static int cobrancas(long edicao, String situacao) {
		var ids = bancoDosPedidos.sql("select id from pedido where edicao_id = :e")
			.param("e", edicao)
			.query(UUID.class)
			.list();
		return bancoDosPagamentos.sql("select count(*) from cobranca where pedido_id in (:ids) and situacao = :s")
			.param("ids", ids)
			.param("s", situacao)
			.query(Integer.class)
			.single();
	}

	@Test
	@Order(1)
	void pagamentosCaiEVoltaAntesDoPrazoTodasAsSagasTerminam() throws Exception {
		var edicao = edicaoNova(10);
		pagamentos.derrubar();

		comprar(edicao, 20);

		// Sem pagamentos: 10 reservam e ficam esperando a cobrança, 10 ficam sem estoque
		await().atMost(Duration.ofSeconds(30))
			.until(() -> contar(edicao, "AGUARDANDO_PAGAMENTO") == 10 && contar(edicao, "SEM_ESTOQUE") == 10);

		pagamentos.subir();

		await().atMost(Duration.ofSeconds(60)).until(() -> presos(edicao) == 0);
		var confirmados = contar(edicao, "CONFIRMADO");
		// Voltou antes do prazo: houve vendas e nada foi estornado (se o prazo tivesse vencido, seria o cenário 2)
		assertThat(confirmados).isPositive();
		assertThat(cobrancas(edicao, "estornada")).isZero();
		assertThat(confirmados + contar(edicao, "CANCELADO")).isEqualTo(10);
		assertThat(contar(edicao, "SEM_ESTOQUE")).isEqualTo(10);
		// Os três serviços contam a mesma história: cada venda tem um pagamento e uma unidade a menos
		assertThat(cobrancas(edicao, "aprovada")).isEqualTo(confirmados);
		await().atMost(Duration.ofSeconds(30)).until(() -> disponivel(edicao) == 10 - confirmados);
	}

	@Test
	@Order(2)
	void pagamentosVoltaDepoisDoPrazoNadaFicaCobradoNemVendido() throws Exception {
		var edicao = edicaoNova(5);
		pagamentos.derrubar();

		comprar(edicao, 5);
		await().atMost(Duration.ofSeconds(30)).until(() -> contar(edicao, "AGUARDANDO_PAGAMENTO") == 5);

		// O prazo vence com pagamentos fora: a saga desiste sozinha e a unidade volta
		await().atMost(PRAZO.plusSeconds(30)).until(() -> contar(edicao, "CANCELADO") == 5);
		assertThat(disponivel(edicao)).isEqualTo(5);

		// Pagamentos volta e processa o CobrarPedido atrasado e, logo depois, o CancelarCobranca
		pagamentos.subir();

		await().atMost(Duration.ofSeconds(60))
			.until(() -> cobrancas(edicao, "estornada") + cobrancas(edicao, "cancelada") == 5);
		assertThat(cobrancas(edicao, "aprovada")).isZero();
		assertThat(contar(edicao, "CANCELADO")).isEqualTo(5);
		assertThat(presos(edicao)).isZero();
		assertThat(disponivel(edicao)).isEqualTo(5);
	}

	@Test
	@Order(3)
	void pagamentosCaiNoMeioDoTrabalhoEAoVoltarTudoFechaCoerente() throws Exception {
		var edicao = edicaoNova(60);

		comprar(edicao, 60);
		// Derruba assim que pagamentos começa a cobrar: com cobranças a meio caminho, saída e offsets pendentes
		await().pollInterval(Duration.ofMillis(20))
			.atMost(Duration.ofSeconds(30))
			.until(() -> cobrancas(edicao, "aprovada") + cobrancas(edicao, "recusada") >= 1);
		pagamentos.derrubar();
		var cobradasNaQueda = cobrancas(edicao, "aprovada") + cobrancas(edicao, "recusada");

		pagamentos.subir();

		await().atMost(PRAZO.plusSeconds(90)).until(() -> presos(edicao) == 0);
		var confirmados = contar(edicao, "CONFIRMADO");
		assertThat(confirmados + contar(edicao, "CANCELADO")).isEqualTo(60);
		assertThat(cobrancas(edicao, "aprovada")).isEqualTo(confirmados);
		await().atMost(Duration.ofSeconds(30)).until(() -> disponivel(edicao) == 60 - confirmados);
		System.out.printf("cenário 3: %d de 60 cobranças feitas na queda; %d confirmados%n", cobradasNaQueda,
				confirmados);
	}

}
