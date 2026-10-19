package io.github.lakes777.foil.pagamentos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.lakes777.foil.comum.ClienteDeTeste;
import io.github.lakes777.foil.comum.ConteineresDeTeste;
import tools.jackson.databind.json.JsonMapper;

/** O serviço sobe com Postgres e Redpanda de verdade e responde /saude. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ConteineresDeTeste.class)
class PagamentosSaudeTest {

	@LocalServerPort
	int porta;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void sobeERespondeSaude() throws Exception {
		var resposta = ClienteDeTeste.get(porta, "/saude");

		assertThat(resposta.statusCode()).isEqualTo(200);
		var corpo = JsonMapper.builder().build().readTree(resposta.body());
		assertThat(corpo.get("servico").asString()).isEqualTo("pagamentos");
		assertThat(corpo.get("status").asString()).isEqualTo("ok");
		assertThat(corpo.get("banco").asString()).isEqualTo("ok");
		assertThat(corpo.get("kafka").asString()).isEqualTo("ok");
		assertThat(corpo.get("migracao").asString()).isEqualTo("5");
	}

	@Test
	void flywayCriouATabelaDeEventosProcessados() {
		var tabelas = jdbc.queryForObject(
				"select count(*) from information_schema.tables where table_name = 'evento_processado'", Integer.class);

		assertThat(tabelas).isEqualTo(1);
	}

}
