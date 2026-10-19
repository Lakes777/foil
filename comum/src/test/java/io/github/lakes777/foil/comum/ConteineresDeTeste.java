package io.github.lakes777.foil.comum;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/** Postgres e Redpanda de verdade para os testes dos três serviços (vai no test-jar do comum). */
@TestConfiguration(proxyBeanMethods = false)
public class ConteineresDeTeste {

	/** A mesma versão do Postgres do Beacon na VM. */
	public static final String POSTGRES = "postgres:17-alpine";

	public static final String REDPANDA = "redpandadata/redpanda:v26.2.5";

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer(POSTGRES);
	}

	@Bean
	@ServiceConnection
	RedpandaContainer redpanda() {
		return new RedpandaContainer(REDPANDA);
	}

}
