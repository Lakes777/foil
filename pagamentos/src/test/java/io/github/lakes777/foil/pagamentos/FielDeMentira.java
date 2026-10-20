package io.github.lakes777.foil.pagamentos;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O contrato do Fiel que o Foil usa (o Fiel de verdade tem o IntegracaoTest dele): a chave no Bearer, a cobrança
 * idempotente pela referência, 409 ao cancelar a que não está pendente, estorno com Idempotency-Key e o aviso
 * assinado com o mesmo desenho ({@code t=..,v1=HMAC("t.corpo")}). O HMAC é calculado aqui à parte, sem usar o
 * código do Foil, para o teste não conferir o código com ele mesmo.
 *
 * <p>
 * O teste também manda o Fiel falhar: fora do ar, perder a resposta depois de criar, recusar a criação.
 */
final class FielDeMentira implements AutoCloseable {

	static final String CHAVE = "chave-de-teste-do-foil-com-mais-de-32-caracteres";

	private static final Pattern COBRANCA = Pattern.compile("/cobrancas/(\\d+)(/cancelamento|/estornos)?");

	static final class Cobranca {

		final long id;

		final String referencia;

		final int valor;

		volatile String estado = "pendente";

		final List<String> estornos = new ArrayList<>();

		Cobranca(long id, String referencia, int valor) {
			this.id = id;
			this.referencia = referencia;
			this.valor = valor;
		}

		String qr() {
			return "FIEL1|" + id + "|" + valor;
		}

	}

	private final HttpServer servidor;

	private final JsonMapper json = JsonMapper.builder().build();

	private final HttpClient http = HttpClient.newHttpClient();

	private final AtomicLong ids = new AtomicLong();

	final Map<Long, Cobranca> cobrancas = new ConcurrentHashMap<>();

	/** Quantos POST /cobrancas chegaram, por referência (para provar que repetir não duplica). */
	final Map<String, AtomicInteger> pedidosDeCriacao = new ConcurrentHashMap<>();

	volatile String webhook;

	volatile String segredo;

	volatile boolean foraDoAr;

	/** O cadastro do endereço responde sem o campo "segredo". */
	volatile boolean semSegredo;

	/** Os próximos N POST /cobrancas criam a cobrança, mas respondem 500 (a resposta "se perde"). */
	final AtomicInteger perderRespostas = new AtomicInteger();

	/** Valor a partir do qual a criação é recusada com 422 (o limite diário do Fiel). */
	volatile int valorMaximo = Integer.MAX_VALUE;

	private volatile String ultimoAviso;

	private volatile String ultimoAvisoId;

	FielDeMentira() throws IOException {
		servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		servidor.createContext("/", this::atender);
		servidor.start();
	}

	String endereco() {
		return "http://127.0.0.1:" + servidor.getAddress().getPort();
	}

	/**
	 * A recriação diária do Fiel: tudo some e os ids recomeçam. {@code proximoId} escolhe o próximo id, para o teste
	 * fazer outro pedido ganhar o mesmo id de uma cobrança antiga.
	 */
	synchronized void recriar(long proximoId) {
		cobrancas.clear();
		ids.set(proximoId - 1);
	}

	Cobranca daReferencia(UUID pedido) {
		return cobrancas.values()
			.stream()
			.filter(c -> c.referencia.equals(pedido.toString()))
			.findFirst()
			.orElseThrow();
	}

	/** O visitante paga pelo QR. Com {@code avisar}, o Fiel entrega o aviso e devolve o status da resposta do Foil. */
	int pagar(UUID pedido, boolean avisar) throws Exception {
		var c = daReferencia(pedido);
		c.estado = "paga";
		if (!avisar) {
			return 0;
		}
		var corpo = json.writeValueAsString(Map.of("id", UUID.randomUUID().toString(), "tipo", "cobranca.paga",
				"criadoEm", Instant.now().toString(), "dados", Map.of("cobranca",
						Map.of("id", c.id, "valorCentavos", c.valor, "referencia", c.referencia), "pagador", "Ana S.")));
		return avisar(corpo, UUID.randomUUID().toString(), segredo, Instant.now().getEpochSecond());
	}

	/** O Fiel reenvia o último aviso, com o mesmo id (o lojista não respondeu a tempo, por exemplo). */
	int reenviarUltimo() throws Exception {
		return avisar(ultimoAviso, ultimoAvisoId, segredo, Instant.now().getEpochSecond());
	}

	int avisar(String corpo, String eventoId, String comSegredo, long segundos) throws Exception {
		ultimoAviso = corpo;
		ultimoAvisoId = eventoId;
		var pedido = HttpRequest.newBuilder(URI.create(webhook))
			.header("Content-Type", "application/json")
			.header("Fiel-Evento", "cobranca.paga")
			.header("Fiel-Evento-Id", eventoId)
			.header("Fiel-Assinatura", "t=" + segundos + ",v1=" + hmac(comSegredo, segundos + "." + corpo))
			.POST(HttpRequest.BodyPublishers.ofString(corpo))
			.build();
		return http.send(pedido, HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	static String hmac(String segredo, String texto) throws Exception {
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(texto.getBytes(StandardCharsets.UTF_8)));
	}

	private void atender(HttpExchange troca) throws IOException {
		try (troca) {
			if (foraDoAr) {
				responder(troca, 503, Map.of("detail", "fora do ar"));
				return;
			}
			if (!("Bearer " + CHAVE).equals(troca.getRequestHeaders().getFirst("Authorization"))) {
				responder(troca, 401, Map.of("detail", "chave de integração inválida"));
				return;
			}
			var corpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			var caminho = troca.getRequestURI().getPath();
			var metodo = troca.getRequestMethod();
			if (metodo.equals("PUT") && caminho.equals("/webhook")) {
				webhook = json.readTree(corpo).path("url").asString();
				if (semSegredo) {
					responder(troca, 200, Map.of("url", webhook));
					return;
				}
				segredo = HexFormat.of().formatHex(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
				responder(troca, 200, Map.of("url", webhook, "segredo", segredo));
			}
			else if (metodo.equals("POST") && caminho.equals("/cobrancas")) {
				criar(troca, json.readTree(corpo));
			}
			else {
				var m = COBRANCA.matcher(caminho);
				var c = m.matches() ? cobrancas.get(Long.parseLong(m.group(1))) : null;
				if (c == null) {
					responder(troca, 404, Map.of("detail", "cobrança não encontrada"));
				}
				else if (m.group(2) == null) {
					responder(troca, 200, visao(c));
				}
				else if (m.group(2).equals("/cancelamento")) {
					cancelar(troca, c);
				}
				else {
					estornar(troca, c);
				}
			}
		}
	}

	private synchronized void criar(HttpExchange troca, JsonNode pedido) throws IOException {
		var referencia = pedido.path("referencia").asString();
		pedidosDeCriacao.computeIfAbsent(referencia, r -> new AtomicInteger()).incrementAndGet();
		var valor = pedido.path("valorCentavos").asInt();
		if (valor > valorMaximo) {
			responder(troca, 422, Map.of("detail", "o valor máximo de uma cobrança é R$ 5.000,00"));
			return;
		}
		var existente = cobrancas.values().stream().filter(c -> c.referencia.equals(referencia)).findFirst();
		var c = existente.orElseGet(() -> {
			var nova = new Cobranca(ids.incrementAndGet(), referencia, valor);
			cobrancas.put(nova.id, nova);
			return nova;
		});
		if (perderRespostas.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
			responder(troca, 500, Map.of("detail", "a resposta se perdeu"));
			return;
		}
		responder(troca, 201, visao(c));
	}

	private synchronized void cancelar(HttpExchange troca, Cobranca c) throws IOException {
		if (!c.estado.equals("pendente")) {
			responder(troca, 409, Map.of("detail", "só cobrança pendente pode ser cancelada; esta está " + c.estado));
			return;
		}
		c.estado = "cancelada";
		responder(troca, 200, visao(c));
	}

	private synchronized void estornar(HttpExchange troca, Cobranca c) throws IOException {
		var chave = troca.getRequestHeaders().getFirst("Idempotency-Key");
		if (!c.estornos.contains(chave)) {
			if (!c.estado.equals("paga")) {
				responder(troca, 409, Map.of("detail", "só cobrança paga pode ser estornada; esta está " + c.estado));
				return;
			}
			c.estornos.add(chave);
			c.estado = "estornada";
		}
		responder(troca, 201, Map.of("cobrancaId", c.id, "valorCentavos", c.valor));
	}

	private Map<String, Object> visao(Cobranca c) {
		return Map.of("id", c.id, "valorCentavos", c.valor, "estado", c.estado, "qr", c.qr(), "referencia",
				c.referencia);
	}

	private void responder(HttpExchange troca, int status, Object corpo) throws IOException {
		var bytes = json.writeValueAsBytes(corpo);
		troca.getResponseHeaders().add("Content-Type", "application/json");
		troca.sendResponseHeaders(status, bytes.length);
		troca.getResponseBody().write(bytes);
	}

	@Override
	public void close() {
		servidor.stop(0);
	}

}
