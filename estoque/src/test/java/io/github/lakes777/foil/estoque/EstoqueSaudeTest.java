package io.github.lakes777.foil.estoque;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.lakes777.foil.comum.ClienteDeTeste;
import tools.jackson.databind.json.JsonMapper;

/** O serviço sobe com Postgres e Redpanda de verdade e responde /saude. */
@TesteDoEstoque
class EstoqueSaudeTest {

	@LocalServerPort
	int porta;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void sobeERespondeSaude() throws Exception {
		var resposta = ClienteDeTeste.get(porta, "/saude");

		assertThat(resposta.statusCode()).isEqualTo(200);
		var corpo = JsonMapper.builder().build().readTree(resposta.body());
		assertThat(corpo.get("servico").asString()).isEqualTo("estoque");
		assertThat(corpo.get("status").asString()).isEqualTo("ok");
		assertThat(corpo.get("banco").asString()).isEqualTo("ok");
		assertThat(corpo.get("kafka").asString()).isEqualTo("ok");
		assertThat(corpo.get("migracao").asString()).isEqualTo("3");
	}

	@Test
	void flywayCriouATabelaDeEventosProcessados() {
		var tabelas = jdbc.queryForObject(
				"select count(*) from information_schema.tables where table_name = 'evento_processado'", Integer.class);

		assertThat(tabelas).isEqualTo(1);
	}

}
