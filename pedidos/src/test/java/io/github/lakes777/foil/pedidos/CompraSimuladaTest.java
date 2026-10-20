package io.github.lakes777.foil.pedidos;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import io.github.lakes777.foil.comum.ConteineresDeTeste;

/**
 * Com a configuração de produção, a compra pelo HTTP não escolhe o pagamento simulado: sem isso, um curl levava
 * gemas sem pagar (o simulado aprova por sorteio). Os robôs dos lançamentos não passam pelo HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "foil.lancamento.automatico=false", "foil.pedidos.prazos-automaticos=false" })
@Import(ConteineresDeTeste.class)
class CompraSimuladaTest {

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@LocalServerPort
	int porta;

	private int comprar(String corpo) throws Exception {
		var pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/pedidos"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(corpo))
			.build();
		return HTTP.send(pedido, HttpResponse.BodyHandlers.ofString()).statusCode();
	}

	@Test
	void simuladaPeloHttpERecusadaEPeloFielPassa() throws Exception {
		assertThat(comprar("{\"edicaoId\":5,\"forma\":\"SIMULADA\"}")).isEqualTo(400);
		assertThat(comprar("{\"edicaoId\":5,\"forma\":\"FIEL\"}")).isEqualTo(202);
		assertThat(comprar("{\"edicaoId\":5}")).isEqualTo(202);
	}

}
