package io.github.lakes777.foil.comum;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Fase 9: o que os três serviços ligam igual para métricas e traces. Entra por último (qualquer
 * {@code application.properties}, variável de ambiente ou teste muda um valor daqui).
 *
 * <p>
 * O endereço do Jaeger ({@code management.opentelemetry.tracing.export.otlp.endpoint}) NÃO tem padrão: sem ele, os
 * spans existem (a outbox ainda guarda o trace) mas não saem do serviço. Na VM ele vem do compose.
 */
class PadroesDeObservabilidade implements EnvironmentPostProcessor {

	static final Map<String, Object> PADROES = Map.of(
			// Só o Prometheus lê o actuator; o Caddy nem deixa /actuator sair da VM (lista fechada)
			"management.endpoints.web.exposure.include", "prometheus",
			// Todo trace sai (decidido em 21/10/2026); o Jaeger guarda só os últimos
			"management.tracing.sampling.probability", "1.0",
			// Métricas vão pelo Prometheus, não por OTLP; logs ficam no docker logs
			"management.otlp.metrics.export.enabled", "false",
			"management.logging.export.otlp.enabled", "false",
			// O KafkaTemplate põe o traceparent no cabeçalho da mensagem, e o listener continua o trace dele
			"spring.kafka.template.observation-enabled", "true",
			"spring.kafka.listener.observation-enabled", "true");

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment ambiente, SpringApplication aplicacao) {
		ambiente.getPropertySources().addLast(new MapPropertySource("foil-observabilidade", PADROES));
	}

}
