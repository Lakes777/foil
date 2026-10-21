package io.github.lakes777.foil.comum;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;

/**
 * O que fica fora dos traces (fase 9): com 100% amostrado e o Jaeger guardando só os últimos 2.000, uma leitura a cada
 * 1,5 s ou um agendamento a cada 100 ms empurrava o trace de quem comprou para fora em minutos.
 */
class SemRuidoTest {

	/** O mesmo bean que os serviços usam. */
	private final ObservationPredicate predicado = new ComumAutoConfiguration().semRuido();

	private static ServerRequestObservationContext pedido(String metodo, String caminho) {
		return new ServerRequestObservationContext(new MockHttpServletRequest(metodo, caminho),
				new MockHttpServletResponse());
	}

	@Test
	void leiturasActuatorEAgendamentosFicamDeFora() {
		assertThat(predicado.test("http.server.requests", pedido("GET", "/pedidos/1"))).isFalse();
		assertThat(predicado.test("http.server.requests", pedido("GET", "/saude"))).isFalse();
		assertThat(predicado.test("http.server.requests", pedido("HEAD", "/"))).isFalse();
		assertThat(predicado.test("http.server.requests", pedido("POST", "/actuator/qualquer"))).isFalse();
		assertThat(predicado.test("tasks.scheduled.execution", new Observation.Context())).isFalse();
	}

	@Test
	void aCompraEOKafkaEntram() {
		assertThat(predicado.test("http.server.requests", pedido("POST", "/pedidos"))).isTrue();
		assertThat(predicado.test("http.server.requests", pedido("POST", "/fiel/avisos"))).isTrue();
		assertThat(predicado.test("spring.kafka.listener", new Observation.Context())).isTrue();
		assertThat(predicado.test("spring.kafka.template", new Observation.Context())).isTrue();
	}

}
