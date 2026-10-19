package io.github.lakes777.foil.estoque;

public enum ResultadoDaDevolucao {

	/** A unidade voltou para o estoque. */
	DEVOLVIDA,

	/** Já tinha sido devolvida antes; nada mudou. */
	JA_DEVOLVIDA,

	/** O pedido não tem unidade reservada (nunca reservou ou ficou sem estoque); nada mudou. */
	SEM_RESERVA

}
