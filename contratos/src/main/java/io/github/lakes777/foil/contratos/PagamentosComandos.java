package io.github.lakes777.foil.contratos;

/** Comandos que o orquestrador (pedidos) manda para pagamentos, no tópico {@link Topicos#PAGAMENTOS_COMANDOS}. */
public final class PagamentosComandos {

	/** Cobra o pedido do envelope. Repetir não cobra duas vezes: a resposta é a mesma da primeira. */
	public record CobrarPedido(int valorCentavos) {

		public static final String TIPO = "CobrarPedido";

		public static final int VERSAO = 1;

	}

	/**
	 * Compensação: a saga desistiu (prazo vencido). Cobrança pendente é cancelada; cobrança já aprovada é estornada;
	 * cobrança que ainda não existe fica proibida (um CobrarPedido atrasado é recusado).
	 */
	public record CancelarCobranca() {

		public static final String TIPO = "CancelarCobranca";

		public static final int VERSAO = 1;

	}

	private PagamentosComandos() {
	}

}
