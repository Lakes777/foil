package io.github.lakes777.foil.pontaaponta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O critério da fase 5 com o Fiel de verdade: o jar dele (montado do repositório do Fiel; no CI, pelo workflow) roda
 * como mais um processo, com a chave de integração e a demonstração ligada. O visitante entra no Fiel como visitante
 * e paga o QR do pedido, como faria pelo navegador.
 *
 * <p>
 * Sem {@code -Dfiel.jar=...} o teste é pulado: no desenvolvimento, monte com {@code ./gradlew bootJar} no Fiel.
 */
class CompraPeloFielTest {

	static final String CHAVE = UUID.randomUUID().toString() + UUID.randomUUID();

	/** Curto para o teste do pagamento atrasado; a compra paga termina bem antes. */
	static final Duration PRAZO = Duration.ofSeconds(20);

	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	static RedpandaContainer redpanda = new RedpandaContainer("redpandadata/redpanda:v26.2.5");

	static Servico fiel;

	static Servico estoque;

	static Servico pedidos;

	static Servico pagamentos;

	static JdbcClient bancoDoEstoque;

	static JdbcClient bancoDosPagamentos;

	static final HttpClient HTTP = HttpClient.newHttpClient();

	static final JsonMapper JSON = JsonMapper.builder().build();

	@BeforeAll
	static void subirTudo() throws Exception {
		var jar = System.getProperty("fiel.jar", "");
		assumeTrue(!jar.isBlank(), "sem -Dfiel.jar: pulando a compra pelo Fiel de verdade");
		postgres.start();
		redpanda.start();
		criarBancos();
		fiel = new Servico("fiel", Path.of(jar), "FIEL_PORTA", Map.of("FIEL_BANCO_URL", url("fiel"),
				"FIEL_BANCO_USUARIO", postgres.getUsername(), "FIEL_BANCO_SENHA", postgres.getPassword(),
				"FIEL_INTEGRACAO_CHAVE", CHAVE, "FIEL_DEMO", "true", "FIEL_CABECALHOS_DO_PROXY", "native"),
				List.of("--fiel.avisos.permitir-rede-local=true", "--server.servlet.session.cookie.secure=false"));
		estoque = servico("estoque", new HashMap<>());
		pedidos = servico("pedidos", new HashMap<>());
		var ambienteDosPagamentos = new HashMap<String, String>();
		pagamentos = servico("pagamentos", ambienteDosPagamentos);
		ambienteDosPagamentos.putAll(Map.of("FOIL_FIEL_API", fiel.url(""), "FOIL_FIEL_CHAVE", CHAVE, "FOIL_FIEL_SITE",
				fiel.url(""), "FOIL_FIEL_WEBHOOK", pagamentos.url("/fiel/avisos")));
		fiel.subir();
		estoque.subir();
		pedidos.subir();
		pagamentos.subir();
		bancoDoEstoque = banco("foil_estoque");
		bancoDosPagamentos = banco("foil_pagamentos");
		// O cadastro do endereço dos avisos acontece na partida de pagamentos
		esperar(() -> bancoDosPagamentos.sql("select count(*) from webhook_do_fiel").query(Long.class).single() == 1,
				Duration.ofSeconds(30), "o cadastro do webhook no Fiel");
	}

	static void criarBancos() throws Exception {
		var fim = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (true) {
			try (var conexao = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword()); var sql = conexao.createStatement()) {
				for (var banco : List.of("fiel", "foil_estoque", "foil_pedidos", "foil_pagamentos")) {
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
		for (var s : new Servico[] { pagamentos, pedidos, estoque, fiel }) {
			if (s != null) {
				s.desligar();
			}
		}
		redpanda.stop();
		postgres.stop();
	}

	static String url(String banco) {
		return postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + banco + "$1");
	}

	static Servico servico(String nome, Map<String, String> ambiente) throws Exception {
		ambiente.putAll(Map.of("FOIL_BANCO_URL", url("foil_" + nome), "FOIL_BANCO_USUARIO", postgres.getUsername(),
				"FOIL_BANCO_SENHA", postgres.getPassword(), "FOIL_KAFKA", redpanda.getBootstrapServers()));
		return new Servico(nome, ambiente, List.of("--foil.pedidos.prazo-do-pagamento=" + PRAZO.toSeconds() + "s",
				"--foil.consumo.espera-maxima-ms=2000", "--foil.fiel.conferir-a-cada=5s"));
	}

	static JdbcClient banco(String nome) {
		return JdbcClient.create(new DriverManagerDataSource(url(nome), postgres.getUsername(), postgres.getPassword()));
	}

	static long edicaoNova(int precoCentavos) {
		var carta = bancoDoEstoque.sql("insert into carta (nome, raridade) values (:n, 'rara') returning id")
			.param("n", "Carta " + UUID.randomUUID())
			.query(Long.class)
			.single();
		return bancoDoEstoque.sql("""
				insert into edicao (carta_id, numero, total, disponivel, preco_centavos) values (:c, 1, 1, 1, :p)
				returning id
				""").param("c", carta).param("p", precoCentavos).query(Long.class).single();
	}

	static int disponivel(long edicao) {
		return bancoDoEstoque.sql("select disponivel from edicao where id = :e")
			.param("e", edicao)
			.query(Integer.class)
			.single();
	}

	/** A compra pela loja: sem forma no pedido, é pelo Fiel. */
	static String comprar(long edicao) throws Exception {
		var resposta = HTTP.send(HttpRequest.newBuilder(URI.create(pedidos.url("/pedidos")))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{\"edicaoId\":" + edicao + "}"))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(resposta.statusCode()).isEqualTo(202);
		return JSON.readTree(resposta.body()).path("id").asString();
	}

	static JsonNode pedido(String id) throws Exception {
		var resposta = HTTP.send(HttpRequest.newBuilder(URI.create(pedidos.url("/pedidos/" + id))).build(),
				HttpResponse.BodyHandlers.ofString());
		return JSON.readTree(resposta.body());
	}

	static JsonNode esperarPedido(String id, Predicate<JsonNode> condicao, Duration prazo, String oQue)
			throws Exception {
		var fim = System.nanoTime() + prazo.toNanos();
		JsonNode p;
		do {
			p = pedido(id);
			if (condicao.test(p)) {
				return p;
			}
			Thread.sleep(250);
		}
		while (System.nanoTime() < fim);
		throw new AssertionError("pedido " + id + " não chegou a " + oQue + " em " + prazo + ": " + p);
	}

	static void esperar(Condicao condicao, Duration prazo, String oQue) throws Exception {
		var fim = System.nanoTime() + prazo.toNanos();
		while (!condicao.vale()) {
			if (System.nanoTime() > fim) {
				throw new AssertionError("não aconteceu em " + prazo + ": " + oQue);
			}
			Thread.sleep(250);
		}
	}

	interface Condicao {

		boolean vale() throws Exception;

	}

	/** Quem paga: um navegador no Fiel, que entra pelo botão de visitante da demonstração. */
	static final class Visitante {

		final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

		final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

		Visitante() throws Exception {
			assertThat(enviar("GET", "/csrf", null).statusCode()).isEqualTo(200);
			assertThat(enviar("POST", "/demo/visitante", null).statusCode()).isEqualTo(201);
		}

		HttpResponse<String> enviar(String metodo, String caminho, Object corpo) throws Exception {
			var pedido = HttpRequest.newBuilder(URI.create(fiel.url(caminho)))
				.header("Content-Type", "application/json")
				.method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(corpo)));
			// O token CSRF muda no login: lê o cookie de agora a cada pedido, como a tela do Fiel
			cookies.getCookieStore()
				.getCookies()
				.stream()
				.filter(c -> c.getName().equals("XSRF-TOKEN"))
				.findFirst()
				.ifPresent(c -> pedido.header("X-XSRF-TOKEN", c.getValue()));
			return http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
		}

		HttpResponse<String> pagar(String qr) throws Exception {
			return enviar("POST", "/pagamentos", Map.of("qr", qr));
		}

		long saldo() throws Exception {
			return JSON.readTree(enviar("GET", "/contas", null).body()).get(0).path("saldoCentavos").asLong();
		}

	}

	@Test
	void compraPagaPeloQrDoFielViraConfirmado() throws Exception {
		var edicao = edicaoNova(1990);
		var id = comprar(edicao);

		var esperando = esperarPedido(id, p -> !p.path("qr").isNull() && !p.path("qr").isMissingNode(),
				Duration.ofSeconds(20), "ter o QR");
		assertThat(esperando.path("estado").asString()).isEqualTo("AGUARDANDO_PAGAMENTO");
		assertThat(esperando.path("forma").asString()).isEqualTo("FIEL");
		assertThat(esperando.path("linkParaPagar").asString()).startsWith(fiel.url("/pagar?qr="));

		var visitante = new Visitante();
		var antes = visitante.saldo();
		assertThat(visitante.pagar(esperando.path("qr").asString()).statusCode()).isEqualTo(201);

		esperarPedido(id, p -> p.path("estado").asString().equals("CONFIRMADO"), Duration.ofSeconds(15),
				"CONFIRMADO");
		assertThat(visitante.saldo()).isEqualTo(antes - 1990);
		assertThat(disponivel(edicao)).isZero();
	}

	@Test
	void pagamentoQueChegaComPagamentosForaEDepoisDoPrazoEhEstornado() throws Exception {
		var edicao = edicaoNova(1500);
		var id = comprar(edicao);
		var qr = esperarPedido(id, p -> !p.path("qr").isNull() && !p.path("qr").isMissingNode(),
				Duration.ofSeconds(20), "ter o QR")
			.path("qr")
			.asString();

		// Pagamentos cai; o visitante paga mesmo assim (o Fiel não depende do Foil para receber)
		pagamentos.derrubar();
		var visitante = new Visitante();
		var antes = visitante.saldo();
		assertThat(visitante.pagar(qr).statusCode()).isEqualTo(201);
		assertThat(visitante.saldo()).isEqualTo(antes - 1500);

		// O aviso do Fiel não tem onde chegar, e o prazo do pagamento vence: a saga desiste
		// (com o estoque de pé, a unidade volta e o pedido pode até fechar antes de pagamentos voltar)
		esperarPedido(id, p -> List.of("LIBERANDO", "CANCELADO").contains(p.path("estado").asString()),
				PRAZO.plusSeconds(15), "desistir (prazo do pagamento)");
		pagamentos.subir();

		var fim = esperarPedido(id, p -> p.path("estado").asString().equals("CANCELADO"), Duration.ofSeconds(60),
				"CANCELADO");
		assertThat(fim.path("motivo").asString()).isEqualTo("PRAZO_DO_PAGAMENTO");
		esperar(() -> visitante.saldo() == antes, Duration.ofSeconds(30), "o estorno no Fiel");
		esperar(() -> "estornada".equals(bancoDosPagamentos.sql("select situacao from cobranca where pedido_id = :p")
			.param("p", UUID.fromString(id))
			.query(String.class)
			.single()), Duration.ofSeconds(30), "a cobrança estornada no Foil");
		assertThat(disponivel(edicao)).isEqualTo(1);
	}

	@Test
	void fielQueRecusaACobrancaDesfazASaga() throws Exception {
		// Acima de R$ 5.000,00, o limite diário de quem paga no Fiel: ele recusa criar a cobrança
		var edicao = edicaoNova(500_001);
		var id = comprar(edicao);

		var fim = esperarPedido(id, p -> p.path("estado").asString().equals("CANCELADO"), Duration.ofSeconds(30),
				"CANCELADO");

		assertThat(fim.path("motivo").asString()).isEqualTo("PAGAMENTO_RECUSADO");
		assertThat(fim.path("qr").isNull() || fim.path("qr").isMissingNode()).isTrue();
		assertThat(disponivel(edicao)).isEqualTo(1);
	}

}
