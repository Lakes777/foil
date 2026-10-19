package io.github.lakes777.foil.comum.eventos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import tools.jackson.core.JacksonException;

/**
 * O que o consumidor faz quando uma mensagem dá erro. O padrão do Spring Kafka (10 tentativas sem espera e depois
 * descartar) perderia um comando válido se o banco ficasse fora por uns 30 s.
 *
 * <ul>
 * <li>Erro passageiro (banco fora, Redpanda instável): tenta de novo para sempre, com espera crescente até
 * {@code esperaMaxima}. A partição para enquanto isso, o que preserva a ordem; nada se perde.</li>
 * <li>Erro de contrato (tipo ou versão desconhecidos, JSON inválido, campo faltando): tentar de novo não adianta.
 * Hoje vai para um log de erro; a fila de mortos da fase 4 vai guardá-lo para reprocessar.</li>
 * </ul>
 */
public final class ErrosDoConsumidor {

	private static final Logger log = LoggerFactory.getLogger(ErrosDoConsumidor.class);

	private ErrosDoConsumidor() {
	}

	public static DefaultErrorHandler criar(long esperaInicialMs, long esperaMaximaMs) {
		var espera = new ExponentialBackOff(esperaInicialMs, 2.0);
		espera.setMaxInterval(esperaMaximaMs);
		// Sem limite de tempo nem de tentativas: erro passageiro nunca vira mensagem perdida
		var tratador = new DefaultErrorHandler((registro, erro) -> log.error(
				"consumidor: mensagem descartada por erro de contrato ({} partição {} offset {}): {}", registro.topic(),
				registro.partition(), registro.offset(), erro.getCause() == null ? erro : erro.getCause()), espera);
		tratador.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class,
				NullPointerException.class);
		return tratador;
	}

}
