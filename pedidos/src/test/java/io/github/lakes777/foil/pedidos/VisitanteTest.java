package io.github.lakes777.foil.pedidos;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.EstoqueEventos.UnidadeReservada;
import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido.Forma;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoAprovado;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** "Minhas gemas": o cookie do visitante nasce na primeira compra e liga os pedidos dele, e só os dele. */
@TesteHttpDePedidos
class VisitanteTest {

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int porta;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Saga saga;

	/** Cada teste compra de um IP próprio: o limite de 10 compras por minuto é por IP. */
	private HttpResponse<String> comprar(long edicao, String cookie, String ip) throws Exception {
		var pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/pedidos"))
			.header("Content-Type", "application/json")
			.header("X-Forwarded-For", ip)
			.POST(HttpRequest.BodyPublishers.ofString("{\"edicaoId\":" + edicao + ",\"forma\":\"SIMULADA\"}"));
		if (cookie != null) {
			pedido.header("Cookie", cookie);
		}
		return HTTP.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode minhas(String cookie) throws Exception {
		var pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/minhas")).GET();
		if (cookie != null) {
			pedido.header("Cookie", cookie);
		}
		var resposta = HTTP.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(resposta.statusCode()).isEqualTo(200);
		return json.readTree(resposta.body());
	}

	/** "foil_visitante=<uuid>", do Set-Cookie. */
	private static String cookieDe(HttpResponse<String> resposta) {
		return resposta.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
	}

	/** Pelo caminho de verdade da saga: o estoque reservou, o pagamento aprovou. */
	private void confirmar(String pedidoId, long edicao) {
		var id = UUID.fromString(pedidoId);
		chega(id, UnidadeReservada.TIPO, new UnidadeReservada(edicao, 1000));
		chega(id, PagamentoAprovado.TIPO, new PagamentoAprovado(1000));
	}

	private void chega(UUID pedido, String tipo, Object dados) {
		saga.aoReceber(Envelope.novo(tipo, 1, pedido, json.valueToTree(dados), Clock.systemUTC()));
	}

	@Test
	void aPrimeiraCompraCriaOCookieEAsSeguintesUsamOMesmo() throws Exception {
		var primeira = comprar(901, null, "198.51.100.10");
		assertThat(primeira.statusCode()).isEqualTo(202);
		var setCookie = primeira.headers().firstValue("Set-Cookie").orElseThrow();
		assertThat(setCookie).startsWith("foil_visitante=").contains("HttpOnly").contains("SameSite=Lax").contains("Secure")
			.contains("Path=/");
		var cookie = cookieDe(primeira);

		var segunda = comprar(902, cookie, "198.51.100.10");

		assertThat(segunda.headers().firstValue("Set-Cookie")).as("o cookie já existe: não troca").isEmpty();
		var compras = minhas(cookie);
		assertThat(compras.size()).isEqualTo(2);
		assertThat(compras.get(0).get("pedido").get("edicaoId").asLong()).as("mais nova primeiro").isEqualTo(902);
		assertThat(compras.get(0).get("unidade").isNull()).as("ainda não confirmada").isTrue();
	}

	@Test
	void cadaVisitanteSoVeAsProprias() throws Exception {
		var ana = cookieDe(comprar(903, null, "198.51.100.20"));
		var bia = cookieDe(comprar(903, null, "198.51.100.21"));

		assertThat(minhas(ana).size()).isEqualTo(1);
		assertThat(minhas(bia).size()).isEqualTo(1);
		assertThat(minhas(ana).get(0).get("pedido").get("id").asString())
			.isNotEqualTo(minhas(bia).get(0).get("pedido").get("id").asString());
		assertThat(minhas(null).size()).as("sem cookie, nada").isZero();
		assertThat(minhas("foil_visitante=" + UUID.randomUUID()).size()).as("cookie de ninguém, nada").isZero();
	}

	@Test
	void cookieMexidoValeComoNenhum() throws Exception {
		assertThat(minhas("foil_visitante=nao-e-uuid").size()).isZero();

		var resposta = comprar(904, "foil_visitante=nao-e-uuid", "198.51.100.30");

		assertThat(resposta.statusCode()).isEqualTo(202);
		assertThat(cookieDe(resposta)).isNotEqualTo("foil_visitante=nao-e-uuid");
		assertThat(minhas(cookieDe(resposta)).size()).isEqualTo(1);
	}

	@Test
	void aUnidadeEAOrdemEntreOsConfirmadosDaEdicaoContandoOsRobos() throws Exception {
		var edicao = 905;
		var robo = saga.criar(edicao, Forma.SIMULADA, null);
		confirmar(robo.id().toString(), edicao);
		var resposta = comprar(edicao, null, "198.51.100.40");
		var cookie = cookieDe(resposta);
		var meu = json.readTree(resposta.body()).get("id").asString();
		confirmar(meu, edicao);
		var depois = saga.criar(edicao, Forma.SIMULADA, null);
		confirmar(depois.id().toString(), edicao);

		var compra = minhas(cookie).get(0);

		assertThat(compra.get("pedido").get("estado").asString()).isEqualTo("CONFIRMADO");
		assertThat(compra.get("unidade").asInt()).as("o robô confirmou antes; o de depois não muda a minha").isEqualTo(2);
	}

	/** 20 confirmações da mesma edição ao mesmo tempo: unidades de 1 a 20, sem repetir nem pular. */
	@Test
	void confirmacoesSimultaneasRecebemUnidadesDiferentes() throws Exception {
		var edicao = 906;
		var pedidos = new ArrayList<String>();
		for (int i = 0; i < 20; i++) {
			var id = saga.criar(edicao, Forma.SIMULADA, null).id();
			chega(id, UnidadeReservada.TIPO, new UnidadeReservada(edicao, 1000));
			pedidos.add(id.toString());
		}
		var largada = new CountDownLatch(1);
		try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
			var feitos = new ArrayList<Future<?>>();
			for (var id : pedidos) {
				feitos.add(threads.submit(() -> {
					largada.await();
					chega(UUID.fromString(id), PagamentoAprovado.TIPO, new PagamentoAprovado(1000));
					return null;
				}));
			}
			largada.countDown();
			for (var f : feitos) {
				f.get();
			}
		}

		var unidades = jdbc.sql("select unidade from pedido where edicao_id = :e order by unidade")
			.param("e", edicao)
			.query(Integer.class)
			.list();
		assertThat(unidades).containsExactlyElementsOf(IntStream.rangeClosed(1, 20).boxed().toList());
	}

}
