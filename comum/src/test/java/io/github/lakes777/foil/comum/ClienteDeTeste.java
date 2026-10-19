package io.github.lakes777.foil.comum;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** GET simples na porta sorteada do teste (vai no test-jar do comum). */
public final class ClienteDeTeste {

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private ClienteDeTeste() {
	}

	public static HttpResponse<String> get(int porta, String caminho) throws IOException, InterruptedException {
		var pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho)).GET().build();
		return HTTP.send(pedido, HttpResponse.BodyHandlers.ofString());
	}

}
