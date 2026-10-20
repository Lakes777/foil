package io.github.lakes777.foil.contratos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.lakes777.foil.contratos.EstoqueComandos.AbrirEdicao;
import io.github.lakes777.foil.contratos.EstoqueComandos.ReservarUnidade;
import io.github.lakes777.foil.contratos.EstoqueEventos.EdicaoAberta;
import io.github.lakes777.foil.contratos.EstoqueEventos.EdicaoRecusada;
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

	@Test
	void abrirEdicaoEAsRespostasNoFio() {
		assertThat(json.valueToTree(new AbrirEdicao(10)).toString()).isEqualTo("{\"unidades\":10}");
		var aberta = json.valueToTree(new EdicaoAberta(3, 2, 10, 1000));
		assertThat(aberta.toString())
			.isEqualTo("{\"edicaoId\":3,\"cartaId\":2,\"unidades\":10,\"precoCentavos\":1000}");
		assertThat(json.treeToValue(aberta, EdicaoAberta.class)).isEqualTo(new EdicaoAberta(3, 2, 10, 1000));
		assertThat(json.valueToTree(new EdicaoRecusada(EdicaoRecusada.Motivo.SEM_CARTA_RARA)).toString())
			.isEqualTo("{\"motivo\":\"SEM_CARTA_RARA\"}");
	}

}
