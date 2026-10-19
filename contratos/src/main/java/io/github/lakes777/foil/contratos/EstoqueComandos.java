package io.github.lakes777.foil.contratos;

/** Comandos que o orquestrador (pedidos) manda para o estoque, no tópico {@link Topicos#ESTOQUE_COMANDOS}. */
public final class EstoqueComandos {

	/** Tira uma unidade da edição para o pedido do envelope. */
	public record ReservarUnidade(long edicaoId) {

		public static final String TIPO = "ReservarUnidade";

		public static final int VERSAO = 1;

	}

	/**
	 * Compensação: devolve a unidade do pedido do envelope (repetir não devolve duas vezes). Se ainda não houver
	 * reserva, o estoque grava a devolução assim mesmo: um ReservarUnidade que chegue depois (reprocessado da fila
	 * de mortos) é recusado, em vez de prender uma unidade que nenhuma saga vai devolver.
	 */
	public record DevolverUnidade(long edicaoId) {

		public static final String TIPO = "DevolverUnidade";

		public static final int VERSAO = 1;

	}

	private EstoqueComandos() {
	}

}
