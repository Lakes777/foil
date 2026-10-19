package io.github.lakes777.foil.pedidos;

import java.time.Instant;
import java.util.UUID;

public record Pedido(UUID id, long edicaoId, Estado estado, String motivo, Integer precoCentavos, Instant prazo,
		Instant criadoEm, Instant atualizadoEm) {

}
