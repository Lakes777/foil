package io.github.lakes777.foil.pedidos;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import tools.jackson.databind.json.JsonMapper;

/**
 * Fase 9 pelo HTTP: o pedido mostra o id do trace (a interface leva ao painel do Grafana) sem o traceparent inteiro, e
 * o Prometheus acha as métricas no actuator.
 */
@TesteHttpDePedidos
class ObservabilidadeTest {

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@LocalServerPort
	int porta;

	@Autowired
	JsonMapper json;

	private HttpResponse<String> get(String caminho) throws Exception {
		return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void oPedidoMostraOIdDoTraceENaoOTraceparent() throws Exception {
		var criar = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/pedidos"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{\"edicaoId\":5,\"forma\":\"SIMULADA\"}"))
			.build();
		var criado = json.readTree(HTTP.send(criar, HttpResponse.BodyHandlers.ofString()).body());
		assertThat(criado.get("traceId").asString()).matches("[0-9a-f]{32}");
		assertThat(criado.has("traceparent")).isFalse();

		var lido = json.readTree(get("/pedidos/" + criado.get("id").asString()).body());
		assertThat(lido.get("traceId").asString()).isEqualTo(criado.get("traceId").asString());
	}

	@Test
	void oPrometheusLeAsMetricasNoActuator() throws Exception {
		var resposta = get("/actuator/prometheus");
		assertThat(resposta.statusCode()).isEqualTo(200);
		// Atraso do consumidor (o cliente Kafka mede) e a JVM
		assertThat(resposta.body()).contains("jvm_memory_used_bytes").contains("kafka_consumer_");
		// Os contadores do painel existem com zero antes da primeira venda (senão o rate() perde o começo)
		assertThat(resposta.body()).contains(
				"foil_pedidos_terminados_total{estado=\"CANCELADO\",motivo=\"PRAZO_DO_PAGAMENTO\",origem=\"robo\"}",
				"foil_pedidos_terminados_total{estado=\"SEM_ESTOQUE\",motivo=\"SEM_ESTOQUE\",origem=\"robo\"}");
		// O resto do actuator fica fechado
		assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
		assertThat(get("/actuator/health").statusCode()).isEqualTo(404);
	}

}
