package io.github.lakes777.foil.contratos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.lakes777.foil.contratos.PedidosEventos.LancamentoMudouDeEstado;
import tools.jackson.databind.json.JsonMapper;

/** O que o painel (fase 7) vai ler do pedidos.eventos. */
class PedidosContratosTest {

	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	void lancamentoNaFilaAindaNaoTemEdicao() {
		var dados = json.valueToTree(new LancamentoMudouDeEstado("NA_FILA", null, null, 200, 0));

		assertThat(dados.toString())
			.isEqualTo("{\"estado\":\"NA_FILA\",\"motivo\":null,\"edicaoId\":null,\"robos\":200,\"soltos\":0}");
		assertThat(json.treeToValue(dados, LancamentoMudouDeEstado.class))
			.isEqualTo(new LancamentoMudouDeEstado("NA_FILA", null, null, 200, 0));
	}

}
