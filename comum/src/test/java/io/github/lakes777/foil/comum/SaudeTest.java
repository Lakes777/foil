package io.github.lakes777.foil.comum;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

import tools.jackson.databind.json.JsonMapper;

/** O /saude com tudo no ar e, depois, derrubando cada contêiner de verdade (contraprova). */
// Migração em db/teste-comum (fora do test-jar): no classpath dos serviços, uma V1 a mais quebraria o Flyway
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.application.name=teste", "spring.flyway.locations=classpath:db/teste-comum",
				"spring.datasource.hikari.connection-timeout=3000" })
@Import(ConteineresDeTeste.class)
@DirtiesContext
class SaudeTest {

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int porta;

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	RedpandaContainer redpanda;

	@Test
	void respondeOkComBancoEKafkaEDerruba503QuandoUmCai() throws Exception {
		var tudoNoAr = ClienteDeTeste.get(porta, "/saude");
		assertThat(tudoNoAr.statusCode()).isEqualTo(200);
		var corpo = json.readTree(tudoNoAr.body());
		assertThat(corpo.get("servico").asString()).isEqualTo("teste");
		assertThat(corpo.get("status").asString()).isEqualTo("ok");
		assertThat(corpo.get("banco").asString()).isEqualTo("ok");
		assertThat(corpo.get("kafka").asString()).isEqualTo("ok");
		assertThat(corpo.get("migracao").asString()).isEqualTo("2");

		Thread.sleep(SaudeController.VALIDADE.toMillis() + 100);
		redpanda.stop();
		var inicio = System.nanoTime();
		var semKafka = ClienteDeTeste.get(porta, "/saude");
		// Não pode ficar pendurado: o Vigil e o healthcheck do Docker desistem antes
		assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(5));
		assertThat(semKafka.statusCode()).isEqualTo(503);
		corpo = json.readTree(semKafka.body());
		assertThat(corpo.get("status").asString()).isEqualTo("falhou");
		assertThat(corpo.get("banco").asString()).isEqualTo("ok");
		assertThat(corpo.get("kafka").asString()).isEqualTo("falhou");

		Thread.sleep(SaudeController.VALIDADE.toMillis() + 100);
		postgres.stop();
		inicio = System.nanoTime();
		var semNada = ClienteDeTeste.get(porta, "/saude");
		assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(5));
		assertThat(semNada.statusCode()).isEqualTo(503);
		corpo = json.readTree(semNada.body());
		assertThat(corpo.get("banco").asString()).isEqualTo("falhou");
	}

}
