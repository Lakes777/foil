package io.github.lakes777.foil.estoque;

import java.time.Instant;

public record Edicao(long id, long cartaId, int numero, int total, int disponivel, int precoCentavos,
		Instant criadaEm) {

}
