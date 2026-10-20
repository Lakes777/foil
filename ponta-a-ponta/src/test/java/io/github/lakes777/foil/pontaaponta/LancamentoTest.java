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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * Critério da fase 6 (SPEC): um lançamento de 200 robôs contra uma edição de 10 termina com 10 vendas, o resto
 * recusado (sem estoque) ou desfeito (pagamento recusado), sem pedido preso. Os três serviços como processos de
 * verdade, com o pagamento simulado recusando ~20%: as unidades devolvidas voltam para os robôs que chegam depois.
 * Dois visitantes pedem lançamentos ao mesmo tempo: o segundo espera na fila.
 */
class LancamentoTest {

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
		// O catálogo: as cartas do jogo são da fase 7; aqui, duas raras para os dois lançamentos
		for (var nome : List.of("Rara A", "Rara B")) {
			bancoDoEstoque.sql("insert into carta (nome, raridade) values (:n, 'rara')").param("n", nome).update();
		}
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

	static JdbcClient banco(String nome) {
		var url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + nome + "$1");
		return JdbcClient.create(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
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

	/** Como na VM: atrás de um proxy confiável (o teste), cada visitante com o próprio IP no X-Forwarded-For. */
	static Servico servico(String nome) throws Exception {
		var url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/foil_" + nome + "$1");
		return new Servico(nome,
				Map.of("FOIL_BANCO_URL", url, "FOIL_BANCO_USUARIO", postgres.getUsername(), "FOIL_BANCO_SENHA",
						postgres.getPassword(), "FOIL_KAFKA", redpanda.getBootstrapServers(),
						"FOIL_CABECALHOS_DO_PROXY", "native"),
				List.of("--foil.consumo.espera-maxima-ms=2000", "--foil.pagamentos.simulado.taxa-de-aprovacao=0.8"));
	}

	static HttpResponse<String> pedirLancamento(String ip) throws Exception {
		var pedido = HttpRequest.newBuilder(URI.create(pedidos.url("/lancamentos")))
			.header("X-Forwarded-For", ip)
			.POST(HttpRequest.BodyPublishers.noBody())
			.build();
		return HTTP.send(pedido, HttpResponse.BodyHandlers.ofString());
	}

	static JsonNode lancamento(String id) throws Exception {
		var resposta = HTTP.send(HttpRequest.newBuilder(URI.create(pedidos.url("/lancamentos/" + id))).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(resposta.statusCode()).isEqualTo(200);
		return JSON.readTree(resposta.body());
	}

	static boolean terminou(String id) throws Exception {
		var estado = lancamento(id).get("estado").asString();
		return estado.equals("ENCERRADO") || estado.equals("FALHOU");
	}

	static int presos(String lancamento) {
		return bancoDosPedidos.sql("select count(*) from pedido where lancamento_id = :l and estado in (:s)")
			.param("l", UUID.fromString(lancamento))
			.param("s", INTERMEDIARIOS)
			.query(Integer.class)
			.single();
	}

	static int aprovadas(String lancamento) {
		var ids = bancoDosPedidos.sql("select id from pedido where lancamento_id = :l")
			.param("l", UUID.fromString(lancamento))
			.query(UUID.class)
			.list();
		return bancoDosPagamentos.sql("select count(*) from cobranca where pedido_id in (:ids) and situacao = 'aprovada'")
			.param("ids", ids)
			.query(Integer.class)
			.single();
	}

	static int disponivel(long edicao) {
		return bancoDoEstoque.sql("select disponivel from edicao where id = :e")
			.param("e", edicao)
			.query(Integer.class)
			.single();
	}

	/** O resultado de um lançamento que terminou, conferido nos três bancos. */
	static void conferir(String id) throws Exception {
		var l = lancamento(id);
		System.out.printf("lançamento %s: %d vendidos, %d sem estoque, %d desfeitos, de %d robôs%n", id,
				l.get("vendidos").asInt(), l.get("semEstoque").asInt(), l.get("desfeitos").asInt(),
				l.get("robos").asInt());
		assertThat(l.get("estado").asString()).isEqualTo("ENCERRADO");
		assertThat(l.get("motivo").isNull()).as("terminou pelas sagas, não pelo prazo").isTrue();
		assertThat(l.get("soltos").asInt()).isEqualTo(200);
		assertThat(l.get("vendidos").asInt()).isEqualTo(10);
		assertThat(l.get("vendidos").asInt() + l.get("semEstoque").asInt() + l.get("desfeitos").asInt())
			.isEqualTo(200);
		assertThat(l.get("emAndamento").asInt()).isZero();
		assertThat(presos(id)).isZero();
		// Os três serviços contam a mesma história: 10 pagamentos aprovados e a edição vazia
		assertThat(aprovadas(id)).isEqualTo(10);
		var edicao = l.get("edicaoId").asLong();
		await().atMost(Duration.ofSeconds(30)).until(() -> disponivel(edicao) == 0);
		assertThat(bancoDoEstoque.sql("select count(*) from reserva where edicao_id = :e and situacao = 'reservada'")
			.param("e", edicao)
			.query(Integer.class)
			.single()).isEqualTo(10);
	}

	@Test
	void duzentosRobosDezUnidadesDezVendasEOSegundoEsperaNaFila() throws Exception {
		var primeiro = pedirLancamento("203.0.113.1");
		var segundo = pedirLancamento("203.0.113.2");
		assertThat(primeiro.statusCode()).isEqualTo(202);
		assertThat(segundo.statusCode()).isEqualTo(202);
		// O mesmo visitante de novo antes de um minuto
		assertThat(pedirLancamento("203.0.113.1").statusCode()).isEqualTo(429);
		var id1 = JSON.readTree(primeiro.body()).get("id").asString();
		var id2 = JSON.readTree(segundo.body()).get("id").asString();

		await().pollInterval(Duration.ofMillis(500)).atMost(Duration.ofSeconds(120)).until(() -> terminou(id1));
		conferir(id1);

		await().pollInterval(Duration.ofMillis(500)).atMost(Duration.ofSeconds(120)).until(() -> terminou(id2));
		conferir(id2);

		var l1 = lancamento(id1);
		var l2 = lancamento(id2);
		assertThat(Instant.parse(l2.get("comecouEm").asString()))
			.as("o segundo só começou depois do fim do primeiro")
			.isAfterOrEqualTo(Instant.parse(l1.get("terminouEm").asString()));
		assertThat(l2.get("edicaoId").asLong()).isNotEqualTo(l1.get("edicaoId").asLong());
	}

}
