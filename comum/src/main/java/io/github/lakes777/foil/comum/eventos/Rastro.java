package io.github.lakes777.foil.comum.eventos;

import java.util.HashMap;
import java.util.Map;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * O trace de uma saga atravessa a outbox: quem grava na {@code saida} guarda o {@code traceparent} (W3C) de agora, e o
 * publicador reabre o trace por ele na hora de mandar ao Kafka. Sem isso, a mensagem sairia no trace do agendamento
 * do publicador, e a saga se partiria em um trace por mensagem.
 */
public class Rastro {

	private static final String CABECALHO = "traceparent";

	private final Tracer tracer;

	private final Propagator propagator;

	public Rastro(Tracer tracer, Propagator propagator) {
		this.tracer = tracer;
		this.propagator = propagator;
	}

	/** Sem tracing (nenhum Tracer no contexto): tudo vira nada, e a outbox segue igual. */
	public static Rastro desligado() {
		return new Rastro(Tracer.NOOP, Propagator.NOOP);
	}

	/** O {@code traceparent} do span de agora, ou null fora de qualquer trace. */
	public String atual() {
		var span = tracer.currentSpan();
		if (span == null || span.isNoop()) {
			return null;
		}
		var cabecalhos = new HashMap<String, String>();
		propagator.inject(span.context(), cabecalhos, Map::put);
		return cabecalhos.get(CABECALHO);
	}

	/**
	 * Qual trace a mensagem leva, dado o trace guardado do pedido ({@code doPedido}, pode ser null). O de agora, se for
	 * o mesmo trace (um consumidor da saga: o span dele é o pai mais preciso); o do pedido, se agora é outro trace ou
	 * nenhum (o aviso do Fiel chega num trace próprio; os prazos vencem fora de qualquer trace).
	 */
	public String escolher(String doPedido) {
		var atual = atual();
		if (doPedido == null || (atual != null && idDoTrace(atual).equals(idDoTrace(doPedido)))) {
			return atual;
		}
		return doPedido;
	}

	/**
	 * Abre um span filho do {@code traceparent} e o deixa como o de agora até o {@code close()}. Com null, nenhum
	 * trace fica aberto (nada de herdar um de quem chamou).
	 */
	public Escopo reabrir(String traceparent, String nome) {
		if (traceparent == null) {
			var semTrace = tracer.withSpan(null);
			return semTrace::close;
		}
		Span span = propagator.extract(Map.of(CABECALHO, traceparent), Map::get).name(nome).start();
		var escopo = tracer.withSpan(span);
		return () -> {
			escopo.close();
			span.end();
		};
	}

	/**
	 * Um span novo, raiz de um trace só dele (um robô do lançamento não entra no trace de quem o soltou), aberto até o
	 * {@code close()}.
	 */
	public Escopo novoTrace(String nome) {
		Span span;
		try (var semTrace = tracer.withSpan(null)) {
			span = tracer.nextSpan().name(nome).start();
		}
		var escopo = tracer.withSpan(span);
		return () -> {
			escopo.close();
			span.end();
		};
	}

	/** Um span filho do de agora (ou raiz, sem trace aberto), até o {@code close()}. */
	public Escopo filho(String nome) {
		var span = tracer.nextSpan().name(nome).start();
		var escopo = tracer.withSpan(span);
		return () -> {
			escopo.close();
			span.end();
		};
	}

	/** Marca o span de agora (ex.: o id do pedido, para buscar o trace no Jaeger). */
	public void marcar(String chave, String valor) {
		var span = tracer.currentSpan();
		if (span != null) {
			span.tag(chave, valor);
		}
	}

	/** Os 32 caracteres do id do trace dentro do {@code traceparent} ("00-<trace>-<span>-<flags>"). */
	public static String idDoTrace(String traceparent) {
		return traceparent == null || traceparent.length() < 35 ? null : traceparent.substring(3, 35);
	}

	/** Um {@code AutoCloseable} sem exceção verificada, para o try-with-resources. */
	@FunctionalInterface
	public interface Escopo extends AutoCloseable {

		@Override
		void close();

	}

}
