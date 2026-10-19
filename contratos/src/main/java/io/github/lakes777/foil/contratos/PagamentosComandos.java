package io.github.lakes777.foil.contratos;

/** Comandos que o orquestrador (pedidos) manda para pagamentos, no tópico {@link Topicos#PAGAMENTOS_COMANDOS}. */
public final class PagamentosComandos {

	/**
	 * Cobra o pedido do envelope. Repetir não cobra duas vezes: a resposta é a mesma da primeira.
	 *
	 * <p>
	 * v2 (fase 5) trouxe a {@link Forma}. A v1 ({@code {valorCentavos}}) ainda é aceita e vale como
	 * {@link Forma#SIMULADA}: um comando v1 gravado na saída antes da atualização não trava a partição.
	 */
	public record CobrarPedido(int valorCentavos, Forma forma) {

		public static final String TIPO = "CobrarPedido";

		public static final int VERSAO = 2;

		public static final int VERSAO_ANTERIOR = 1;

		/** A v1 não tem forma: é a simulada. */
		public Forma formaOuSimulada() {
			return forma == null ? Forma.SIMULADA : forma;
		}

		public enum Forma {

			/** Sorteio dentro de pagamentos (os robôs do lançamento, P10). */
			SIMULADA,

			/** Cobrança de verdade no Fiel, paga pelo QR (a compra manual). */
			FIEL

		}

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
