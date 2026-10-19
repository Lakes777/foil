package io.github.lakes777.foil.estoque;

public enum ResultadoDaReserva {

	/** Uma unidade saiu do estoque para o pedido. */
	RESERVADA,

	/** A edição acabou; nada mudou. É definitivo: o mesmo pedido repetido continua sem estoque. */
	SEM_ESTOQUE,

	/** Pedido repetido: a unidade já está reservada para ele; nada mudou. */
	JA_RESERVADA,

	/** Pedido repetido: a unidade dele já foi devolvida (a saga acabou); nada mudou. */
	JA_DEVOLVIDA,

	/** A edição não existe; nada mudou. */
	EDICAO_INEXISTENTE

}
