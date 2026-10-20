package io.github.lakes777.foil.pedidos;

/** Passou do limite por IP: 429 com {@code Retry-After}. */
class MuitasTentativas extends RuntimeException {

	private final long segundos;

	MuitasTentativas(long segundos) {
		super("muitas tentativas; tente de novo em " + segundos + " s");
		this.segundos = segundos;
	}

	long segundos() {
		return segundos;
	}

}
