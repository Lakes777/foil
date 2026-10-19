package io.github.lakes777.foil.comum.eventos;

import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import io.github.lakes777.foil.contratos.Topicos;
import tools.jackson.core.JacksonException;

/**
 * O que o consumidor faz quando uma mensagem dá erro. O padrão do Spring Kafka (10 tentativas sem espera e depois
 * descartar) perderia um comando válido se o banco ficasse fora por uns 30 s.
 *
 * <ul>
 * <li>Erro passageiro (banco fora, Redpanda instável): tenta de novo para sempre, com espera crescente até
 * {@code esperaMaxima}. A partição para enquanto isso, o que preserva a ordem; nada se perde.</li>
 * <li>Erro de contrato (tipo ou versão desconhecidos, JSON inválido, campo faltando): tentar de novo não adianta.
 * Vai para a fila de mortos ({@code <tópico>.mortos}, mesma partição), onde fica guardado e pode ser reprocessado
 * ({@link Mortos}). Se nem a fila de mortos aceitar, conta como passageiro e tenta de novo.</li>
 * </ul>
 */
public final class ErrosDoConsumidor {

	private ErrosDoConsumidor() {
	}

	public static DefaultErrorHandler criar(KafkaTemplate<String, String> kafka, long esperaInicialMs,
			long esperaMaximaMs) {
		var espera = new ExponentialBackOff(esperaInicialMs, 2.0);
		espera.setMaxInterval(esperaMaximaMs);
		var paraOsMortos = new DeadLetterPublishingRecoverer(kafka,
				(registro, erro) -> new TopicPartition(registro.topic() + Topicos.MORTOS, registro.partition()));
		// Sem limite de tempo nem de tentativas: erro passageiro nunca vira mensagem perdida
		var tratador = new DefaultErrorHandler(paraOsMortos, espera);
		tratador.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class,
				NullPointerException.class);
		return tratador;
	}

}
