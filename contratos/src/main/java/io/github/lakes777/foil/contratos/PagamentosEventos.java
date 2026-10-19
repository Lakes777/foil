package io.github.lakes777.foil.contratos;

/** Respostas de pagamentos, no tópico {@link Topicos#PAGAMENTOS_EVENTOS}. */
public final class PagamentosEventos {

	public record PagamentoAprovado(int valorCentavos) {

		public static final String TIPO = "PagamentoAprovado";

		public static final int VERSAO = 1;

	}

	public record PagamentoRecusado(Motivo motivo) {

		public static final String TIPO = "PagamentoRecusado";

		public static final int VERSAO = 1;

		public enum Motivo {

			/** O pagamento não foi aprovado. */
			RECUSADO,

			/** A cobrança tinha sido cancelada antes (CobrarPedido atrasado). */
			CANCELADA

		}

	}

	/** Resposta ao CancelarCobranca quando não havia nada aprovado. */
	public record CobrancaCancelada() {

		public static final String TIPO = "CobrancaCancelada";

		public static final int VERSAO = 1;

	}

	/** Resposta ao CancelarCobranca quando o pagamento já tinha sido aprovado: o dinheiro voltou. */
	public record PagamentoEstornado(int valorCentavos) {

		public static final String TIPO = "PagamentoEstornado";

		public static final int VERSAO = 1;

	}

	private PagamentosEventos() {
	}

}
