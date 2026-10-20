package io.github.lakes777.foil.estoque;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import io.github.lakes777.foil.comum.ClienteDeTeste;
import tools.jackson.databind.json.JsonMapper;

@TesteDoEstoque
class CatalogoControllerTest {

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int porta;

	@Autowired
	EstoqueService estoque;

	@Test
	void mostraCartasEdicoesEDisponivel() throws Exception {
		var nome = "Carta " + UUID.randomUUID();
		var carta = estoque.criarCarta(nome, "incomum", "teste");
		var edicao = estoque.novaEdicao(carta.id(), 10);
		estoque.reservar(UUID.randomUUID(), edicao.id());

		var cartas = json.readTree(ClienteDeTeste.get(porta, "/cartas").body());
		var daEdicao = ClienteDeTeste.get(porta, "/edicoes/" + edicao.id());
		var daCarta = json.readTree(ClienteDeTeste.get(porta, "/cartas/" + carta.id() + "/edicoes").body());

		assertThat(cartas.valueStream().map(c -> c.get("nome").asString())).contains(nome);
		assertThat(daEdicao.statusCode()).isEqualTo(200);
		var lida = json.readTree(daEdicao.body());
		assertThat(lida.get("total").asInt()).isEqualTo(10);
		assertThat(lida.get("disponivel").asInt()).isEqualTo(9);
		assertThat(daCarta.size()).isEqualTo(1);
		var vitrine = json.readTree(ClienteDeTeste.get(porta, "/vitrine").body());
		var item = vitrine.valueStream().filter(i -> i.get("carta").get("nome").asString().equals(nome)).findFirst().orElseThrow();
		assertThat(item.get("edicao").get("id").asLong()).isEqualTo(edicao.id());
		assertThat(item.get("carta").get("mohs").asInt()).isEqualTo(5);
	}

	@Test
	void edicaoOuCartaQueNaoExisteDa404() throws Exception {
		assertThat(ClienteDeTeste.get(porta, "/edicoes/999999").statusCode()).isEqualTo(404);
		assertThat(ClienteDeTeste.get(porta, "/cartas/999999/edicoes").statusCode()).isEqualTo(404);
	}

}
