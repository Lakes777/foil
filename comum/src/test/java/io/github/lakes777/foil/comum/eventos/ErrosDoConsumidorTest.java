package io.github.lakes777.foil.comum.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

import io.github.lakes777.foil.comum.ConteineresDeTeste;

/** O tratador de erros de verdade, num listener de teste ligado ao Redpanda. */
@SpringBootTest(properties = { "spring.application.name=teste", "spring.flyway.locations=classpath:db/teste-comum",
		"foil.saida.automatica=false", "foil.consumo.espera-inicial-ms=10", "foil.consumo.espera-maxima-ms=50" })
@Import(ConteineresDeTeste.class)
class ErrosDoConsumidorTest {

	static final String TOPICO = "teste.erros";

	/** Falhas passageiras antes de aceitar: mais que as 10 tentativas do padrão do Spring Kafka. */
	static final int FALHAS = 15;

	@TestConfiguration(proxyBeanMethods = false)
	static class Ouvinte {

		final List<String> aceitas = new CopyOnWriteArrayList<>();

		final AtomicInteger tentativasDoPassageiro = new AtomicInteger();

		@Bean
		NewTopic topicoDeTeste() {
			return TopicBuilder.name(TOPICO).partitions(1).replicas(1).build();
		}

		@KafkaListener(topics = TOPICO, groupId = "teste-erros", properties = "auto.offset.reset=earliest")
		void receber(String mensagem) {
			switch (mensagem) {
				case "contrato-quebrado" -> throw new IllegalArgumentException("tipo desconhecido");
				case "passageiro" -> {
					if (tentativasDoPassageiro.incrementAndGet() <= FALHAS) {
						throw new IllegalStateException("banco fora");
					}
					aceitas.add(mensagem);
				}
				default -> aceitas.add(mensagem);
			}
		}

	}

	@Autowired
	Ouvinte ouvinte;

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Test
	void erroPassageiroEReaplicadoAteDarCertoENadaSePerde() throws Exception {
		kafka.send(TOPICO, "k", "contrato-quebrado").get();
		kafka.send(TOPICO, "k", "passageiro").get();
		kafka.send(TOPICO, "k", "depois").get();

		await().atMost(Duration.ofSeconds(30)).until(() -> ouvinte.aceitas.size() == 2);

		// O de contrato foi descartado sem travar; o passageiro passou depois de 15 falhas; a ordem ficou
		assertThat(ouvinte.aceitas).containsExactly("passageiro", "depois");
		assertThat(ouvinte.tentativasDoPassageiro.get()).isEqualTo(FALHAS + 1);
	}

}
