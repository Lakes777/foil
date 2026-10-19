package io.github.lakes777.foil.contratos;

/** O que o orquestrador conta para o painel e a observabilidade, no tópico {@link Topicos#PEDIDOS_EVENTOS}. */
public final class PedidosEventos {

	/** Cada passo da saga. {@code motivo} explica estados como SEM_ESTOQUE e CANCELADO (pode ser nulo). */
	public record PedidoMudouDeEstado(String estado, String motivo, long edicaoId) {

		public static final String TIPO = "PedidoMudouDeEstado";

		public static final int VERSAO = 1;

	}

	private PedidosEventos() {
	}

}
