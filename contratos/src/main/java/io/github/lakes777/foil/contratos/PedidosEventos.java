package io.github.lakes777.foil.contratos;

/** O que o orquestrador conta para o painel e a observabilidade, no tópico {@link Topicos#PEDIDOS_EVENTOS}. */
public final class PedidosEventos {

	/** Cada passo da saga. {@code motivo} explica estados como SEM_ESTOQUE e CANCELADO (pode ser nulo). */
	public record PedidoMudouDeEstado(String estado, String motivo, long edicaoId) {

		public static final String TIPO = "PedidoMudouDeEstado";

		public static final int VERSAO = 1;

	}

	/**
	 * Fase 6: cada passo de um lançamento (o envelope leva o id do lançamento). {@code edicaoId} só existe depois que o
	 * estoque abriu a edição; os pedidos dos robôs saem como {@link PedidoMudouDeEstado} dessa edição.
	 */
	public record LancamentoMudouDeEstado(String estado, String motivo, Long edicaoId, int robos, int soltos) {

		public static final String TIPO = "LancamentoMudouDeEstado";

		public static final int VERSAO = 1;

	}

	private PedidosEventos() {
	}

}
