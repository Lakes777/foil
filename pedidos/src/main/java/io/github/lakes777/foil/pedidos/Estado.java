package io.github.lakes777.foil.pedidos;

/**
 * Estados da saga.
 *
 * <pre>
 * RESERVANDO ──UnidadeReservada──> AGUARDANDO_PAGAMENTO ──PagamentoAprovado──> CONFIRMADO
 *     │  └──ReservaRecusada──> SEM_ESTOQUE           │  └──PagamentoRecusado──┐
 *     └──prazo──┐                                     └──prazo (+ CancelarCobranca)──┤
 *               └──────────────> LIBERANDO (DevolverUnidade) ──UnidadeDevolvida──> CANCELADO
 * </pre>
 */
public enum Estado {

	RESERVANDO(false), AGUARDANDO_PAGAMENTO(false), LIBERANDO(false), CONFIRMADO(true), SEM_ESTOQUE(true),
	CANCELADO(true);

	private final boolean fim;

	Estado(boolean fim) {
		this.fim = fim;
	}

	public boolean fim() {
		return fim;
	}

}
