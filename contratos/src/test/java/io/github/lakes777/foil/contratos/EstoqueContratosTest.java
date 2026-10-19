package io.github.lakes777.foil.contratos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.lakes777.foil.contratos.EstoqueComandos.ReservarUnidade;
import io.github.lakes777.foil.contratos.EstoqueEventos.ReservaRecusada;
import io.github.lakes777.foil.contratos.EstoqueEventos.UnidadeDevolvida;
import tools.jackson.databind.json.JsonMapper;

/** Quem escreve e quem lê usam estas mesmas classes: o formato no fio fica preso aqui. */
class EstoqueContratosTest {

	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	void reservarUnidadeNoFio() {
		var dados = json.valueToTree(new ReservarUnidade(7));

		assertThat(dados.toString()).isEqualTo("{\"edicaoId\":7}");
		assertThat(json.treeToValue(dados, ReservarUnidade.class)).isEqualTo(new ReservarUnidade(7));
	}

	@Test
	void reservaRecusadaLevaOMotivoPeloNome() {
		var dados = json.valueToTree(new ReservaRecusada(7, ReservaRecusada.Motivo.SEM_ESTOQUE));

		assertThat(dados.toString()).isEqualTo("{\"edicaoId\":7,\"motivo\":\"SEM_ESTOQUE\"}");
	}

	@Test
	void envelopeCompletoVaiEVoltaComOsDados() {
		var envelope = Envelope.novo(UnidadeDevolvida.TIPO, UnidadeDevolvida.VERSAO, UUID.randomUUID(),
				json.valueToTree(new UnidadeDevolvida(true)), Clock.systemUTC());

		var lido = json.readValue(json.writeValueAsString(envelope), Envelope.class);

		assertThat(json.treeToValue(lido.dados(), UnidadeDevolvida.class)).isEqualTo(new UnidadeDevolvida(true));
	}

}
