package io.github.lakes777.foil.contratos;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Envelope comum a todo evento e comando que passa pelo Redpanda.
 *
 * <p>O {@code id} identifica o evento (é o que os consumidores guardam para ignorar repetidos) e o
 * {@code pedidoId} é a chave de partição, para que tudo de um mesmo pedido chegue em ordem. Nos comandos e eventos
 * de um lançamento (fase 6), ele leva o id do lançamento. Mudar o formato de {@code dados} exige uma {@code versao}
 * nova.
 */
public record Envelope(UUID id, String tipo, int versao, Instant ocorridoEm, UUID pedidoId, JsonNode dados) {

	public Envelope {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(tipo, "tipo");
		Objects.requireNonNull(ocorridoEm, "ocorridoEm");
		Objects.requireNonNull(pedidoId, "pedidoId");
		Objects.requireNonNull(dados, "dados");
		if (tipo.isBlank()) {
			throw new IllegalArgumentException("tipo vazio");
		}
		if (versao < 1) {
			throw new IllegalArgumentException("versao precisa ser 1 ou mais: " + versao);
		}
	}

	/** Envelope de um evento novo, com id sorteado e a hora do relógio. */
	public static Envelope novo(String tipo, int versao, UUID pedidoId, JsonNode dados, Clock relogio) {
		return new Envelope(UUID.randomUUID(), tipo, versao, Instant.now(relogio), pedidoId, dados);
	}

}
