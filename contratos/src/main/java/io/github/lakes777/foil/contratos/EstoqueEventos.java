package io.github.lakes777.foil.contratos;

/** Respostas do estoque, no tópico {@link Topicos#ESTOQUE_EVENTOS}. */
public final class EstoqueEventos {

	/** A unidade está reservada para o pedido (também na repetição de um pedido já reservado). */
	public record UnidadeReservada(long edicaoId) {

		public static final String TIPO = "UnidadeReservada";

		public static final int VERSAO = 1;

	}

	/** Não houve reserva; {@code motivo} diz por quê. Nada para desfazer. */
	public record ReservaRecusada(long edicaoId, Motivo motivo) {

		public static final String TIPO = "ReservaRecusada";

		public static final int VERSAO = 1;

		public enum Motivo {

			SEM_ESTOQUE,

			EDICAO_INEXISTENTE,

			/** O pedido já reservou e devolveu: a saga dele acabou, não pode voltar a "reservado". */
			JA_DEVOLVIDA

		}

	}

	/** O pedido não tem mais unidade reservada (devolvida agora ou antes, ou nunca reservou). */
	public record UnidadeDevolvida(boolean devolvidaAgora) {

		public static final String TIPO = "UnidadeDevolvida";

		public static final int VERSAO = 1;

	}

	private EstoqueEventos() {
	}

}
