package io.github.lakes777.foil.comum.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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

/** O tratador de erros e a fila de mortos de verdade, num listener de teste ligado ao Redpanda. */
@SpringBootTest(properties = { "spring.application.name=teste", "spring.flyway.locations=classpath:db/teste-comum",
		"foil.saida.automatica=false", "foil.consumo.espera-inicial-ms=10", "foil.consumo.espera-maxima-ms=50",
		"foil.mortos.topicos=teste.erros.mortos", "spring.kafka.consumer.group-id=teste",
		"spring.kafka.consumer.auto-offset-reset=earliest" })
@Import(ConteineresDeTeste.class)
class ErrosDoConsumidorTest {

	static final String TOPICO = "teste.erros";

	/** Falhas passageiras antes de aceitar: mais que as 10 tentativas do padrão do Spring Kafka. */
	static final int FALHAS = 15;

	@TestConfiguration(proxyBeanMethods = false)
	static class Ouvinte {

		final List<String> aceitas = new CopyOnWriteArrayList<>();

		final AtomicInteger tentativasDoPassageiro = new AtomicInteger();

		/** Simula o conserto do código: depois disso, a mensagem que tinha morrido passa. */
		final AtomicBoolean consertado = new AtomicBoolean();

		@Bean
		NewTopic topicoDeTeste() {
			return TopicBuilder.name(TOPICO).partitions(1).replicas(1).build();
		}

		@Bean
		NewTopic mortosDeTeste() {
			return TopicBuilder.name(TOPICO + ".mortos").partitions(1).replicas(1).build();
		}

		@KafkaListener(topics = TOPICO, groupId = "teste-erros")
		void receber(String mensagem) {
			switch (mensagem) {
				case "contrato-quebrado" -> {
					if (!consertado.get()) {
						throw new IllegalArgumentException("tipo desconhecido");
					}
					aceitas.add(mensagem);
				}
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

	@Autowired
	Mortos mortos;

	@Test
	void passageiroEReaplicadoContratoQuebradoVaiParaOsMortosEOBotaoReprocessa() throws Exception {
		kafka.send(TOPICO, "k", "contrato-quebrado").get();
		kafka.send(TOPICO, "k", "passageiro").get();
		kafka.send(TOPICO, "k", "depois").get();

		await().atMost(Duration.ofSeconds(30)).until(() -> ouvinte.aceitas.size() == 2);

		// O de contrato não travou os seguintes; o passageiro passou depois de 15 falhas; a ordem ficou
		assertThat(ouvinte.aceitas).containsExactly("passageiro", "depois");
		assertThat(ouvinte.tentativasDoPassageiro.get()).isEqualTo(FALHAS + 1);

		// Ficou guardado na fila de mortos, com o tópico de origem e o erro
		await().atMost(Duration.ofSeconds(30)).until(() -> mortos.listar().size() == 1);
		var morto = mortos.listar().getFirst();
		assertThat(morto.topico()).isEqualTo(TOPICO);
		assertThat(morto.mensagem()).isEqualTo("contrato-quebrado");
		assertThat(morto.erro()).contains("tipo desconhecido");

		// Código consertado: o botão devolve a mensagem ao tópico e agora ela passa
		ouvinte.consertado.set(true);
		assertThat(mortos.reprocessar(morto.id())).isEqualTo(Mortos.Reprocesso.FEITO);
		assertThat(mortos.reprocessar(morto.id())).isEqualTo(Mortos.Reprocesso.JA_REPROCESSADO);

		await().atMost(Duration.ofSeconds(30)).until(() -> ouvinte.aceitas.size() == 3);
		assertThat(ouvinte.aceitas).containsExactly("passageiro", "depois", "contrato-quebrado");
		assertThat(mortos.listar().getFirst().reprocessadoEm()).isNotNull();
	}

}
