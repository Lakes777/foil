package io.github.lakes777.foil.pagamentos;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A API do Fiel, pela chave de integração da Loja Foil (P17). Só o que a chave pode: criar, ler, cancelar e estornar
 * as cobranças da loja e cadastrar o endereço dos avisos.
 *
 * <p>
 * Cada chamada devolve a resposta definitiva do Fiel ou lança {@link FalhaPassageira} (rede, Fiel fora do ar, 5xx,
 * limite de pedidos, chave recusada): quem chamou tenta de novo mais tarde. Tudo o que muda algo no Fiel é seguro
 * de repetir: a cobrança tem a referência (o id do pedido), o estorno tem a Idempotency-Key (também o id do pedido),
 * e cancelar a cancelada só responde 409.
 */
@Component
class ClienteDoFiel {

	/** Erro que passa: tente de novo depois. */
	static class FalhaPassageira extends RuntimeException {

		FalhaPassageira(String mensagem) {
			super(mensagem);
		}

	}

	/** Uma resposta definitiva do Fiel: o status e o corpo (vazio quando não é JSON). */
	record Resposta(int status, JsonNode corpo) {

		boolean deu() {
			return status / 100 == 2;
		}

		String estado() {
			return corpo.path("estado").asString("");
		}

	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	private final JsonMapper json;

	private final String api;

	private final String chave;

	private final String site;

	private final int validadeMinutos;

	ClienteDoFiel(JsonMapper json, @Value("${foil.fiel.api:}") String api, @Value("${foil.fiel.chave:}") String chave,
			@Value("${foil.fiel.site:https://fiel.andrelagos.dev}") String site,
			@Value("${foil.fiel.validade-minutos:5}") int validadeMinutos) {
		this.json = json;
		this.api = api.strip().replaceAll("/+$", "");
		this.chave = chave.strip();
		this.site = site.strip().replaceAll("/+$", "");
		this.validadeMinutos = validadeMinutos;
	}

	/** Sem endereço ou sem chave, a compra pelo Fiel fica desligada (é recusada na hora). */
	boolean ligado() {
		return !api.isEmpty() && !chave.isEmpty();
	}

	/**
	 * Cria (ou acha, pela referência) a cobrança do pedido. A validade é a do prazo do pagamento da saga (5 min):
	 * passado o prazo, o QR também deixa de valer.
	 */
	Resposta criar(UUID pedidoId, int valorCentavos) {
		return enviar("POST", "/cobrancas", Map.of("valorCentavos", valorCentavos, "descricao",
				"Foil, pedido " + pedidoId.toString().substring(0, 8), "validadeMinutos", validadeMinutos,
				"referencia", pedidoId.toString()), Map.of());
	}

	Resposta ler(long fielId) {
		return enviar("GET", "/cobrancas/" + fielId, null, Map.of());
	}

	Resposta cancelar(long fielId) {
		return enviar("POST", "/cobrancas/" + fielId + "/cancelamento", null, Map.of());
	}

	/** A Idempotency-Key é o pedido: repetir o estorno devolve o mesmo estorno. */
	Resposta estornar(long fielId, int valorCentavos, UUID pedidoId) {
		return enviar("POST", "/cobrancas/" + fielId + "/estornos",
				Map.of("valorCentavos", valorCentavos, "motivo", "pedido cancelado no Foil"),
				Map.of("Idempotency-Key", pedidoId.toString()));
	}

	/** Cadastra o endereço dos avisos; a resposta traz o segredo novo, que não aparece de novo. */
	Resposta cadastrarWebhook(String url) {
		return enviar("PUT", "/webhook", Map.of("url", url), Map.of());
	}

	/** A tela do Fiel que abre o QR (sem login, ela leva a entrar como visitante e volta). */
	String linkParaPagar(String qr) {
		return site + "/pagar?qr=" + URLEncoder.encode(qr, StandardCharsets.UTF_8);
	}

	private Resposta enviar(String metodo, String caminho, Object corpo, Map<String, String> cabecalhos) {
		var pedido = HttpRequest.newBuilder(URI.create(api + caminho))
			.timeout(Duration.ofSeconds(5))
			.header("Authorization", "Bearer " + chave)
			.header("Content-Type", "application/json")
			.method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)));
		cabecalhos.forEach(pedido::header);
		HttpResponse<String> resposta;
		try {
			resposta = http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException erro) {
			throw new FalhaPassageira(metodo + " " + caminho + ": " + erro.getClass().getSimpleName());
		}
		catch (InterruptedException erro) {
			Thread.currentThread().interrupt();
			throw new FalhaPassageira(metodo + " " + caminho + ": interrompido");
		}
		var status = resposta.statusCode();
		// 401/403: chave errada ou trocada só de um lado; passa quando alguém arrumar o .env (e o log mostra)
		if (status >= 500 || status == 429 || status == 401 || status == 403) {
			throw new FalhaPassageira(metodo + " " + caminho + ": HTTP " + status);
		}
		return new Resposta(status, lerJson(resposta.body()).orElse(json.createObjectNode()));
	}

	private Optional<JsonNode> lerJson(String corpo) {
		try {
			return corpo.isBlank() ? Optional.empty() : Optional.of(json.readTree(corpo));
		}
		catch (RuntimeException erro) {
			return Optional.empty();
		}
	}

}
