package io.github.lakes777.foil.comum;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Lê um tópico do começo, com grupo próprio, para os testes conferirem o que foi publicado (vai no test-jar). */
public final class LeitorDeTopico implements AutoCloseable {

	private final KafkaConsumer<String, String> consumidor;

	private final List<ConsumerRecord<String, String>> lidos = new ArrayList<>();

	public LeitorDeTopico(String bootstrap, String topico) {
		consumidor = new KafkaConsumer<>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
				ConsumerConfig.GROUP_ID_CONFIG, "teste-" + UUID.randomUUID(), ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
				"earliest"), new StringDeserializer(), new StringDeserializer());
		consumidor.subscribe(List.of(topico));
	}

	/** Lê até achar {@code quantos} registros que passam no filtro, ou até o prazo; devolve os que achou. */
	public List<ConsumerRecord<String, String>> ler(Predicate<ConsumerRecord<String, String>> filtro, int quantos,
			Duration prazo) {
		var fim = System.nanoTime() + prazo.toNanos();
		while (lidos.stream().filter(filtro).count() < quantos && System.nanoTime() < fim) {
			consumidor.poll(Duration.ofMillis(200)).forEach(lidos::add);
		}
		return lidos.stream().filter(filtro).toList();
	}

	/** Lê durante todo o prazo (para conferir que não chegou nada a mais). */
	public List<ConsumerRecord<String, String>> lerDurante(Predicate<ConsumerRecord<String, String>> filtro,
			Duration prazo) {
		return ler(filtro, Integer.MAX_VALUE, prazo);
	}

	/** Filtro pela chave (o id do pedido). */
	public static Predicate<ConsumerRecord<String, String>> doPedido(UUID pedidoId) {
		return r -> pedidoId.toString().equals(r.key());
	}

	@Override
	public void close() {
		consumidor.close(Duration.ofSeconds(1));
	}

}
