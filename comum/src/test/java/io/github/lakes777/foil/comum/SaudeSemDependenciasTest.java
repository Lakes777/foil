package io.github.lakes777.foil.comum;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.kafka.core.KafkaAdmin;

/** Sem contêiner nenhum: o /saude tem de responder 503 rápido, nunca 500 nem pendurado. */
class SaudeSemDependenciasTest {

	/** Banco que nunca conecta. */
	private static final AbstractDataSource BANCO_FORA = new AbstractDataSource() {

		@Override
		public java.sql.Connection getConnection() throws SQLException {
			throw new SQLException("banco fora");
		}

		@Override
		public java.sql.Connection getConnection(String usuario, String senha) throws SQLException {
			throw new SQLException("banco fora");
		}

	};

	private static SaudeController controller(String bootstrap) {
		var kafka = new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
		// Sem Flyway no contexto (ex.: spring.flyway.enabled=false): tem de subir igual
		var semFlyway = new StaticListableBeanFactory().getBeanProvider(Flyway.class);
		return new SaudeController("teste", BANCO_FORA, semFlyway, kafka);
	}

	@Test
	void nomeDoRedpandaQueNaoResolveDa503EmVezDe500() {
		var resposta = controller("foil-redpanda.invalid:9092").saude();

		assertThat(resposta.getStatusCode().value()).isEqualTo(503);
		assertThat(resposta.getBody().kafka()).isEqualTo("falhou");
		assertThat(resposta.getBody().banco()).isEqualTo("falhou");
		assertThat(resposta.getBody().migracao()).isNull();
	}

	@Test
	void bancoERedpandaForaRespondeEmMenosDe5Segundos() {
		// Porta fechada: o nome resolve, mas ninguém atende (o caso que mais demora)
		var inicio = System.nanoTime();
		var resposta = controller("localhost:1").saude();

		assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(5));
		assertThat(resposta.getStatusCode().value()).isEqualTo(503);
	}

	@Test
	void repetirDentroDaValidadeNaoConfereDeNovo() {
		var saude = controller("foil-redpanda.invalid:9092");

		var primeira = saude.atual();

		assertThat(saude.atual()).isSameAs(primeira);
	}

}
