package io.github.lakes777.foil.contratos;

import java.util.List;

/** Nomes dos tópicos (rascunho da SPEC; a fase 3 fecha os nomes). */
public final class Topicos {

	public static final String ESTOQUE_COMANDOS = "estoque.comandos";
	public static final String ESTOQUE_EVENTOS = "estoque.eventos";
	public static final String PAGAMENTOS_COMANDOS = "pagamentos.comandos";
	public static final String PAGAMENTOS_EVENTOS = "pagamentos.eventos";
	public static final String PEDIDOS_EVENTOS = "pedidos.eventos";

	public static final List<String> TODOS = List.of(ESTOQUE_COMANDOS, ESTOQUE_EVENTOS, PAGAMENTOS_COMANDOS,
			PAGAMENTOS_EVENTOS, PEDIDOS_EVENTOS);

	/** Partições por tópico; a chave é o id do pedido (P24). */
	public static final int PARTICOES = 3;

	private Topicos() {
	}

}
