package io.github.lakes777.foil.comum.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.redpanda.RedpandaContainer;

import io.github.lakes777.foil.comum.ConteineresDeTeste;
import io.github.lakes777.foil.contratos.Topicos;

/** A parte da limpeza das 4h10 que os três serviços têm igual, com Postgres de verdade. */
@SpringBootTest(properties = { "spring.application.name=teste", "spring.flyway.locations=classpath:db/teste-comum",
		"foil.saida.automatica=false", "spring.kafka.admin.modify-topic-configs=true" })
@Import(ConteineresDeTeste.class)
class LimpezaTest {

	@Autowired
	Limpeza limpeza;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	KafkaAdmin kafkaAdmin;

	@Autowired
	RedpandaContainer redpanda;

	private UUID saida(String publicado, String criado) {
		var id = UUID.randomUUID();
		jdbc.sql("insert into saida (id, topico, chave, envelope, criado_em, publicado_em) values (:id, 't', 'c', '{}', "
				+ criado + ", " + publicado + ")").param("id", id).update();
		return id;
	}

	private UUID processado(String quando) {
		var id = UUID.randomUUID();
		jdbc.sql("insert into evento_processado (evento_id, processado_em) values (:id, " + quando + ")")
			.param("id", id)
			.update();
		return id;
	}

	private UUID morto(String quando) {
		var id = UUID.randomUUID();
		jdbc.sql("insert into morto (id, topico, erro, origem, morto_em) values (:id, 't', 'e', :id::text, " + quando
				+ ")").param("id", id).update();
		return id;
	}

	private boolean existe(String tabela, String coluna, UUID id) {
		return jdbc.sql("select count(*) from " + tabela + " where " + coluna + " = :id")
			.param("id", id)
			.query(Long.class)
			.single() == 1;
	}

	@Test
	void apagaSoOQueJaPassouDoPrazo() {
		var publicadaVelha = saida("now() - interval '2 days'", "now() - interval '2 days'");
		var publicadaHoje = saida("now() - interval '1 hour'", "now() - interval '1 hour'");
		// Nunca publicada (Redpanda fora há muito tempo): ainda vai sair, e apagar perderia o evento
		var pendenteVelha = saida("null", "now() - interval '60 days'");
		var processadoVelho = processado("now() - interval '5 days'");
		// Ainda dentro da retenção do tópico + 1 dia: o evento pode ser relido, o id precisa ficar
		var processadoRecente = processado("now() - interval '3 days 23 hours'");
		var mortoVelho = morto("now() - interval '31 days'");
		var mortoRecente = morto("now() - interval '29 days'");

		limpeza.limpar();

		assertThat(existe("saida", "id", publicadaVelha)).isFalse();
		assertThat(existe("saida", "id", publicadaHoje)).isTrue();
		assertThat(existe("saida", "id", pendenteVelha)).isTrue();
		assertThat(existe("evento_processado", "evento_id", processadoVelho)).isFalse();
		assertThat(existe("evento_processado", "evento_id", processadoRecente)).isTrue();
		assertThat(existe("morto", "id", mortoVelho)).isFalse();
		assertThat(existe("morto", "id", mortoRecente)).isTrue();
	}

	@Test
	void apagaEmLotesAteAcabar() {
		var quantos = Limpeza.LOTE * 2 + 7;
		jdbc.sql("""
				insert into evento_processado (evento_id, processado_em)
				select gen_random_uuid(), now() - interval '10 days' from generate_series(1, :n)
				""").param("n", quantos).update();

		var apagadas = limpeza.apagar("evento_processado", "processado_em < " + limpeza.antesDosEventos());

		assertThat(apagadas).isGreaterThanOrEqualTo(quantos);
		assertThat(jdbc.sql("select count(*) from evento_processado where processado_em < now() - interval '9 days'")
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void mortoVelhoDemaisNaoPodeSerReprocessado() throws Exception {
		// As travas do pedido saem com o morto (30 dias); reprocessar perto disso deixaria a mensagem viva sem elas
		var mortos = new Mortos(jdbc, kafka, Limpeza.diasParaReprocessar(30));
		var velho = morto("now() - interval '27 days'");
		var aindaDa = morto("now() - interval '25 days'");
		jdbc.sql("update morto set topico = :t, chave = 'c', mensagem = '{}' where id = :id")
			.param("t", Topicos.PEDIDOS_EVENTOS)
			.param("id", aindaDa)
			.update();

		assertThat(mortos.reprocessar(velho)).isEqualTo(Mortos.Reprocesso.VENCIDO);
		assertThat(mortos.reprocessar(aindaDa)).isEqualTo(Mortos.Reprocesso.FEITO);
	}

	private Map<String, String> configuracao(Admin admin, String topico) throws Exception {
		var recurso = new ConfigResource(ConfigResource.Type.TOPIC, topico);
		var lida = admin.describeConfigs(List.of(recurso)).all().get().get(recurso);
		return Map.of(TopicConfig.RETENTION_MS_CONFIG, lida.get(TopicConfig.RETENTION_MS_CONFIG).value(),
				TopicConfig.SEGMENT_MS_CONFIG, lida.get(TopicConfig.SEGMENT_MS_CONFIG).value());
	}

	@Test
	void topicoGuardaNoMaximoTresDiasMesmoOQueJaExistia() throws Exception {
		var esperada = Map.of(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(2).toMillis()),
				TopicConfig.SEGMENT_MS_CONFIG, String.valueOf(Duration.ofDays(1).toMillis()));
		try (var admin = Admin.create(Map.of("bootstrap.servers", redpanda.getBootstrapServers()))) {
			assertThat(configuracao(admin, Topicos.ESTOQUE_COMANDOS)).isEqualTo(esperada);
			// Um tópico de antes desta regra (padrão do Redpanda: 7 dias e segmento de 14): a partida acerta
			var recurso = new ConfigResource(ConfigResource.Type.TOPIC, Topicos.ESTOQUE_COMANDOS);
			admin.incrementalAlterConfigs(Map.of(recurso, List.of(
					new AlterConfigOp(new ConfigEntry(TopicConfig.RETENTION_MS_CONFIG, "604800000"), AlterConfigOp.OpType.SET),
					new AlterConfigOp(new ConfigEntry(TopicConfig.SEGMENT_MS_CONFIG, "1209600000"), AlterConfigOp.OpType.SET))))
				.all()
				.get();
			assertThat(configuracao(admin, Topicos.ESTOQUE_COMANDOS)).isNotEqualTo(esperada);

			kafkaAdmin.initialize();

			assertThat(configuracao(admin, Topicos.ESTOQUE_COMANDOS)).isEqualTo(esperada);
		}
	}

	@Test
	void naoAceitaGuardarOsIdsMenosQueOTopico() {
		// Com o id apagado antes da mensagem sair do tópico, um consumidor que relê aplicaria o efeito duas vezes
		assertThatThrownBy(() -> new Limpeza(jdbc, List.of(), Limpeza.DIAS_NO_TOPICO, 30))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Limpeza(jdbc, List.of(), 10, 10)).isInstanceOf(IllegalArgumentException.class);
		// O padrão (4 e 30) sobe
		new Limpeza(jdbc, List.of(), 4, 30);
	}

}
