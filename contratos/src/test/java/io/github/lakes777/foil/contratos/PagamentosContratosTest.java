package io.github.lakes777.foil.contratos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.lakes777.foil.contratos.EstoqueEventos.UnidadeReservada;
import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoRecusado;
import io.github.lakes777.foil.contratos.PedidosEventos.PedidoMudouDeEstado;
import tools.jackson.databind.json.JsonMapper;

class PagamentosContratosTest {

	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	void formatosNoFio() {
		assertThat(json.writeValueAsString(new CobrarPedido(1500))).isEqualTo("{\"valorCentavos\":1500}");
		assertThat(json.writeValueAsString(new PagamentoRecusado(PagamentoRecusado.Motivo.CANCELADA)))
			.isEqualTo("{\"motivo\":\"CANCELADA\"}");
		assertThat(json.writeValueAsString(new UnidadeReservada(7, 1500)))
			.isEqualTo("{\"edicaoId\":7,\"precoCentavos\":1500}");
		assertThat(json.writeValueAsString(new PedidoMudouDeEstado("CANCELADO", "PRAZO_PAGAMENTO", 7)))
			.isEqualTo("{\"estado\":\"CANCELADO\",\"motivo\":\"PRAZO_PAGAMENTO\",\"edicaoId\":7}");
	}

	@Test
	void topicosDeMortos() {
		assertThat(Topicos.ESTOQUE_COMANDOS + Topicos.MORTOS).isEqualTo("estoque.comandos.mortos");
	}

}
