package io.github.lakes777.foil.comum.eventos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.redpanda.RedpandaContainer;

import io.github.lakes777.foil.comum.ConteineresDeTeste;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.Topicos;
import tools.jackson.databind.json.JsonMapper;

/**
 * Com o Redpanda fora, nada é marcado como publicado e o publicador não fica pendurado. Tem a mesma configuração
 * do SaidaTest e derruba o Redpanda do contexto em cache: o @DirtiesContext descarta esse contexto no fim.
 */
@SpringBootTest(properties = { "spring.application.name=teste", "spring.flyway.locations=classpath:db/teste-comum",
		"foil.saida.automatica=false" })
@Import(ConteineresDeTeste.class)
@DirtiesContext
class SaidaSemRedpandaTest {

	@Autowired
	Saida saida;

	@Autowired
	PublicadorDaSaida publicador;

	@Autowired
	TransactionTemplate transacao;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	@Autowired
	RedpandaContainer redpanda;

	@Test
	void redpandaForaDeixaOEventoPendente() {
		var pedido = UUID.randomUUID();
		transacao.executeWithoutResult(t -> saida.registrar(Topicos.PEDIDOS_EVENTOS,
				Envelope.novo("X", 1, pedido, json.readTree("{}"), Clock.systemUTC())));
		redpanda.stop();

		var inicio = System.nanoTime();
		var publicados = publicador.publicarPendentes();

		assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(10));
		assertThat(publicados).isZero();
		assertThat(jdbc.sql("select count(*) from saida where chave = :c and publicado_em is null")
			.param("c", pedido.toString())
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

}
