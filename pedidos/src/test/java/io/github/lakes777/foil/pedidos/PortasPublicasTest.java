package io.github.lakes777.foil.pedidos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido.Forma;

/**
 * As portas que vão ao ar na fase 8: limite por IP no lançamento e na compra, fila cheia e o painel ao vivo por SSE.
 * Cada teste usa IPs próprios (os limites ficam na memória do serviço, que é o mesmo para todos).
 */
@TesteHttpDePedidos
class PortasPublicasTest {

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@LocalServerPort
	int porta;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Lancamentos lancamentos;

	@Autowired
	Saga saga;

	@Autowired
	PainelAoVivo painel;

	@BeforeEach
	void semLancamentos() {
		jdbc.sql("delete from pedido where lancamento_id is not null").update();
		jdbc.sql("delete from lancamento").update();
		// As conexões que o teste anterior fechou ainda estão saindo do painel; contar no meio disso dá conta errada
		await().atMost(Duration.ofSeconds(10)).until(() -> painel.conectados() == 0);
	}

	private HttpResponse<String> post(String caminho, String corpo, String ip) throws Exception {
		var pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
			.header("Content-Type", "application/json")
			.header("X-Forwarded-For", ip)
			.POST(HttpRequest.BodyPublishers.ofString(corpo))
			.build();
		return HTTP.send(pedido, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> comprar(String ip) throws Exception {
		return post("/pedidos", "{\"edicaoId\":5,\"forma\":\"SIMULADA\"}", ip);
	}

	@Test
	void umLancamentoPorMinutoPorIp() throws Exception {
		assertThat(post("/lancamentos", "", "203.0.113.1").statusCode()).isEqualTo(202);

		var segundo = post("/lancamentos", "", "203.0.113.1");
		assertThat(segundo.statusCode()).isEqualTo(429);
		assertThat(segundo.headers().firstValue("Retry-After")).hasValueSatisfying(s -> {
			assertThat(Integer.parseInt(s)).isBetween(1, 60);
		});
		assertThat(post("/lancamentos", "", "203.0.113.2").statusCode()).as("outro visitante").isEqualTo(202);
		assertThat(lancamentos.situacao().fila()).hasSize(2);
	}

	@Test
	void filaCheiaRespondeLotadoSemGastarOMinutoDoVisitante() throws Exception {
		for (int i = 0; i < 5; i++) {
			lancamentos.pedir();
		}

		var lotado = post("/lancamentos", "", "203.0.113.10");
		assertThat(lotado.statusCode()).isEqualTo(503);
		assertThat(lotado.headers().firstValue("Retry-After")).hasValue("60");

		jdbc.sql("delete from lancamento").update();
		assertThat(post("/lancamentos", "", "203.0.113.10").statusCode()).isEqualTo(202);
	}

	@Test
	void compraTemLimitePorIpEIpv6ContaPeloPrefixo() throws Exception {
		for (int i = 0; i < 10; i++) {
			assertThat(comprar("198.51.100.7").statusCode()).isEqualTo(202);
		}
		assertThat(comprar("198.51.100.7").statusCode()).isEqualTo(429);
		assertThat(comprar("198.51.100.8").statusCode()).isEqualTo(202);

		// Endereços diferentes do mesmo /64 são a mesma casa
		for (int i = 0; i < 10; i++) {
			assertThat(comprar("2001:db8:1:2::" + Integer.toHexString(i + 1)).statusCode()).isEqualTo(202);
		}
		assertThat(comprar("2001:db8:1:2:ffff::9").statusCode()).isEqualTo(429);
		assertThat(comprar("2001:db8:1:3::1").statusCode()).isEqualTo(202);
	}

	/** Uma conexão SSE aberta: as linhas chegam numa lista; fechar = cancelar o corpo. */
	private record Conexao(HttpResponse<Stream<String>> resposta, List<String> linhas) implements AutoCloseable {

		@Override
		public void close() {
			resposta.body().close();
		}

	}

	private Conexao conectar(String ip) throws Exception {
		var pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/eventos"))
			.header("X-Forwarded-For", ip)
			.GET()
			.build();
		var resposta = HTTP.send(pedido, HttpResponse.BodyHandlers.ofLines());
		var linhas = new CopyOnWriteArrayList<String>();
		if (resposta.statusCode() == 200) {
			CompletableFuture.runAsync(() -> {
				try {
					resposta.body().forEach(linhas::add);
				}
				catch (RuntimeException fechada) {
					// o teste fechou a conexão
				}
			});
		}
		return new Conexao(resposta, linhas);
	}

	@Test
	void painelRecebeOsPassosDaSagaAoVivo() throws Exception {
		try (var conexao = conectar("192.0.2.50")) {
			assertThat(conexao.resposta().statusCode()).isEqualTo(200);
			assertThat(conexao.resposta().headers().firstValue("Content-Type")).hasValueSatisfying(
					t -> assertThat(t).startsWith("text/event-stream"));
			await().atMost(Duration.ofSeconds(10)).until(() -> conexao.linhas().contains(":conectado"));

			var pedido = saga.criar(5, Forma.SIMULADA);

			await().atMost(Duration.ofSeconds(20))
				.until(() -> conexao.linhas().stream().anyMatch(l -> l.startsWith("data:") && l.contains(pedido.id().toString())));
			var i = indiceDoDado(conexao.linhas(), pedido.id().toString());
			assertThat(conexao.linhas().get(i - 1)).isEqualTo("event:PedidoMudouDeEstado");
			assertThat(conexao.linhas().get(i)).contains("\"estado\":\"RESERVANDO\"");
		}
	}

	private static int indiceDoDado(List<String> linhas, String texto) {
		for (int i = 0; i < linhas.size(); i++) {
			if (linhas.get(i).startsWith("data:") && linhas.get(i).contains(texto)) {
				return i;
			}
		}
		return -1;
	}

	@Test
	void painelTemTetoDeConexoesPorIp() throws Exception {
		var abertas = new ArrayList<Conexao>();
		try {
			for (int i = 0; i < 5; i++) {
				var c = conectar("192.0.2.60");
				assertThat(c.resposta().statusCode()).isEqualTo(200);
				abertas.add(c);
			}
			var sexta = conectar("192.0.2.60");
			assertThat(sexta.resposta().statusCode()).isEqualTo(429);
			try (var outroIp = conectar("192.0.2.61")) {
				assertThat(outroIp.resposta().statusCode()).isEqualTo(200);
			}
		}
		finally {
			abertas.forEach(Conexao::close);
		}
	}

	/** Só as mensagens deste teste: as sagas dos outros testes também passam pelo painel. */
	private static long recebidos(Conexao conexao) {
		return conexao.linhas().stream().filter(l -> l.startsWith("data:") && l.contains("\"recheio\"")).count();
	}

	/**
	 * Quem conecta e para de ler enche o TCP: o escritor dele fica preso no send() segurando a trava do emissor. A
	 * fila dele enche e ele é desconectado, mas sem que a thread do Kafka espere por isso: os outros continuam
	 * recebendo tudo.
	 */
	@Test
	void visitanteQueNaoLeNaoSeguraOsOutros() throws Exception {
		try (var parado = new Socket()) {
			parado.setReceiveBufferSize(4096);
			parado.connect(new InetSocketAddress("localhost", porta));
			parado.getOutputStream()
				.write(("GET /eventos HTTP/1.1\r\nHost: localhost\r\nX-Forwarded-For: 192.0.2.80\r\n\r\n")
					.getBytes(StandardCharsets.US_ASCII));
			parado.getOutputStream().flush();
			await().atMost(Duration.ofSeconds(5)).until(() -> painel.conectados() == 1);

			try (var atento = conectar("192.0.2.81")) {
				await().atMost(Duration.ofSeconds(5)).until(() -> atento.linhas().contains(":conectado"));
				var envelope = "{\"tipo\":\"Teste\",\"recheio\":\"" + "a".repeat(8000) + "\"}";
				var mensagens = 3000;
				var pior = assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
					long maior = 0;
					for (int i = 1; i <= mensagens; i++) {
						var comeco = System.nanoTime();
						painel.espalhar(envelope);
						maior = Math.max(maior, System.nanoTime() - comeco);
						// No ritmo de quem lê: o atento não pode ficar 1000 para trás, ou é desconectado também
						if (i % 100 == 0) {
							var enviadas = i;
							await().atMost(Duration.ofSeconds(10)).until(() -> recebidos(atento) >= enviadas - 200);
						}
					}
					return Duration.ofNanos(maior);
				});

				assertThat(pior).as("espalhar nunca espera pelo visitante parado").isLessThan(Duration.ofSeconds(1));
				assertThat(painel.conectados()).as("o parado foi desconectado; o atento continua").isEqualTo(1);
				await().atMost(Duration.ofSeconds(10)).until(() -> recebidos(atento) == mensagens);
			}
		}
	}

	@Test
	void visitanteQueSaiLiberaAVaga() throws Exception {
		var c = conectar("192.0.2.70");
		await().atMost(Duration.ofSeconds(5)).until(() -> painel.conectados() == 1);

		c.close();
		// O servidor só percebe na segunda escrita depois de fechar; o "ainda aqui" (1 s nos testes) cuida disso
		await().atMost(Duration.ofSeconds(10)).until(() -> painel.conectados() == 0);
	}

}
