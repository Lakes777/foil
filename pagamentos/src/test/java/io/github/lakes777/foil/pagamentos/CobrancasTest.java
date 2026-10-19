package io.github.lakes777.foil.pagamentos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.lakes777.foil.comum.ConteineresDeTeste;
import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido.Forma;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ConteineresDeTeste.class)
class CobrancasTest {

	@Autowired
	Cobrancas cobrancas;

	private UUID pedidoQue(boolean aprova) {
		while (true) {
			var id = UUID.randomUUID();
			if (cobrancas.aprovaria(id) == aprova) {
				return id;
			}
		}
	}

	@Test
	void cobrarDeNovoRespondeOMesmo() {
		var aprovado = pedidoQue(true);
		var recusado = pedidoQue(false);

		assertThat(cobrancas.cobrar(aprovado, 1000, Forma.SIMULADA)).isEqualTo(Cobrancas.Resultado.APROVADA);
		assertThat(cobrancas.cobrar(aprovado, 1000, Forma.SIMULADA)).isEqualTo(Cobrancas.Resultado.APROVADA);
		assertThat(cobrancas.cobrar(recusado, 1000, Forma.SIMULADA)).isEqualTo(Cobrancas.Resultado.RECUSADA);
		assertThat(cobrancas.cobrar(recusado, 1000, Forma.SIMULADA)).isEqualTo(Cobrancas.Resultado.RECUSADA);
	}

	@Test
	void cancelarAprovadaEstornaUmaVez() {
		var pedido = pedidoQue(true);
		cobrancas.cobrar(pedido, 1500, Forma.SIMULADA);

		assertThat(cobrancas.cancelar(pedido)).isEqualTo(Cobrancas.Cancelamento.ESTORNADA);
		assertThat(cobrancas.cancelar(pedido)).isEqualTo(Cobrancas.Cancelamento.ESTORNADA);
		assertThat(cobrancas.situacao(pedido)).contains("estornada");
		// Depois do estorno, cobrar de novo não aprova outra vez
		assertThat(cobrancas.cobrar(pedido, 1500, Forma.SIMULADA)).isEqualTo(Cobrancas.Resultado.JA_CANCELADA);
	}

	@Test
	void cancelarAntesDeCobrarProibeACobrancaAtrasada() {
		var pedido = pedidoQue(true);

		assertThat(cobrancas.cancelar(pedido)).isEqualTo(Cobrancas.Cancelamento.CANCELADA);

		assertThat(cobrancas.cobrar(pedido, 1000, Forma.SIMULADA)).isEqualTo(Cobrancas.Resultado.JA_CANCELADA);
		assertThat(cobrancas.situacao(pedido)).contains("cancelada");
	}

	@Test
	void cancelarRecusadaSoCancela() {
		var pedido = pedidoQue(false);
		cobrancas.cobrar(pedido, 1000, Forma.SIMULADA);

		assertThat(cobrancas.cancelar(pedido)).isEqualTo(Cobrancas.Cancelamento.CANCELADA);
		assertThat(cobrancas.situacao(pedido)).contains("cancelada");
	}

	@Test
	void sorteioRespeitaATaxaPadraoDeOitentaPorCento() {
		var aprovados = 0;
		for (int i = 0; i < 10_000; i++) {
			aprovados += cobrancas.aprovaria(UUID.randomUUID()) ? 1 : 0;
		}

		assertThat(aprovados).isBetween(7_700, 8_300);
	}

}
