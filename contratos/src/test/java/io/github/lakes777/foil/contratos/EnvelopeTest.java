package io.github.lakes777.foil.contratos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EnvelopeTest {

	private final JsonMapper json = JsonMapper.builder().build();

	private final UUID id = UUID.fromString("0b6f3a52-6a39-4d55-9d6e-1f0a3c1e2b77");

	private final UUID pedidoId = UUID.fromString("5e1d2c3b-4a59-4687-8899-aabbccddeeff");

	private final Instant quando = Instant.parse("2026-10-18T21:00:00Z");

	@Test
	void escreveOFormatoDaSpec() {
		var envelope = new Envelope(id, "EstoqueReservado", 1, quando, pedidoId, json.readTree("{\"edicaoId\":7}"));

		JsonNode escrito = json.readTree(json.writeValueAsString(envelope));

		assertThat(escrito.propertyNames()).containsExactlyInAnyOrder("id", "tipo", "versao", "ocorridoEm",
				"pedidoId", "dados");
		assertThat(escrito.get("id").asString()).isEqualTo(id.toString());
		assertThat(escrito.get("tipo").asString()).isEqualTo("EstoqueReservado");
		assertThat(escrito.get("versao").asInt()).isEqualTo(1);
		assertThat(escrito.get("ocorridoEm").asString()).isEqualTo("2026-10-18T21:00:00Z");
		assertThat(escrito.get("pedidoId").asString()).isEqualTo(pedidoId.toString());
		assertThat(escrito.get("dados").get("edicaoId").asInt()).isEqualTo(7);
	}

	@Test
	void leDeVoltaIgual() {
		var envelope = new Envelope(id, "EstoqueReservado", 2, quando, pedidoId, json.readTree("{\"a\":[1,2]}"));

		var lido = json.readValue(json.writeValueAsString(envelope), Envelope.class);

		assertThat(lido).isEqualTo(envelope);
	}

	@Test
	void novoUsaORelogioESorteiaOId() {
		var relogio = Clock.fixed(quando, ZoneOffset.UTC);
		var dados = json.readTree("{}");

		var um = Envelope.novo("PedidoCriado", 1, pedidoId, dados, relogio);
		var outro = Envelope.novo("PedidoCriado", 1, pedidoId, dados, relogio);

		assertThat(um.ocorridoEm()).isEqualTo(quando);
		assertThat(um.id()).isNotEqualTo(outro.id());
	}

	@Test
	void recusaCampoFaltandoOuVersaoInvalida() {
		var dados = json.readTree("{}");

		assertThatThrownBy(() -> new Envelope(null, "X", 1, quando, pedidoId, dados))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new Envelope(id, "X", 1, quando, null, dados))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new Envelope(id, " ", 1, quando, pedidoId, dados))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Envelope(id, "X", 0, quando, pedidoId, dados))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void jsonSemCampoObrigatorioNaoViraEnvelope() {
		var semPedido = "{\"id\":\"" + id + "\",\"tipo\":\"X\",\"versao\":1,\"ocorridoEm\":\"2026-10-18T21:00:00Z\",\"dados\":{}}";

		assertThatThrownBy(() -> json.readValue(semPedido, Envelope.class)).hasRootCauseInstanceOf(NullPointerException.class);
	}

}
