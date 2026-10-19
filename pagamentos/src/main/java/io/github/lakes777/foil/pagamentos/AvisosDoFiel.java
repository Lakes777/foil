package io.github.lakes777.foil.pagamentos;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.lakes777.foil.comum.eventos.EventosProcessados;
import tools.jackson.databind.json.JsonMapper;

/**
 * Os avisos (webhooks) do Fiel: o cadastro do endereço e o que fazer com cada aviso que chega.
 *
 * <p>
 * O Fiel assina cada aviso com {@code Fiel-Assinatura: t=<segundos>,v1=<HMAC-SHA256 de "t.corpo">}, com o segredo
 * que ele deu no cadastro. Conferir a assinatura prova que o aviso veio do Fiel e que o corpo não mudou; conferir o
 * {@code t} recusa um aviso velho reenviado por quem o capturou. O mesmo aviso pode chegar de novo (o Fiel reenvia
 * quando não recebe resposta): o {@code Fiel-Evento-Id} vai para {@code evento_processado}, como os eventos do
 * Redpanda.
 */
@Service
class AvisosDoFiel {

	/** Quanto o {@code t} do aviso pode estar longe da nossa hora. */
	static final Duration TOLERANCIA = Duration.ofMinutes(5);

	private static final Logger log = LoggerFactory.getLogger(AvisosDoFiel.class);

	enum Recebido {

		/** Conferido e aplicado (ou repetido, ou de um tipo que não interessa): responder 2xx. */
		ACEITO,

		/** Assinatura errada ou velha: 401, e o Fiel tenta de novo (talvez o segredo tenha acabado de mudar). */
		RECUSADO,

		/** Ainda sem segredo (o cadastro não aconteceu): 503, o Fiel tenta de novo. */
		SEM_SEGREDO

	}

	private final JdbcClient jdbc;

	private final JsonMapper json;

	private final ClienteDoFiel fiel;

	private final CobrancasNoFiel cobrancas;

	private final EventosProcessados processados;

	private final String endereco;

	private final Clock relogio = Clock.systemUTC();

	AvisosDoFiel(JdbcClient jdbc, JsonMapper json, ClienteDoFiel fiel, CobrancasNoFiel cobrancas,
			EventosProcessados processados, @Value("${foil.fiel.webhook:}") String endereco) {
		this.jdbc = jdbc;
		this.json = json;
		this.fiel = fiel;
		this.cobrancas = cobrancas;
		this.processados = processados;
		this.endereco = endereco.strip();
	}

	/**
	 * Cadastra (ou recadastra) o endereço no Fiel e guarda o segredo novo. Na partida e às 4h05: a demonstração do
	 * Fiel é recriada às 4h, e o cadastro some junto. Devolve se deu certo. Sem transação: não segura uma conexão
	 * do banco enquanto espera o Fiel.
	 */
	public boolean cadastrar() {
		if (!fiel.ligado() || endereco.isEmpty()) {
			return false;
		}
		ClienteDoFiel.Resposta r;
		try {
			r = fiel.cadastrarWebhook(endereco);
		}
		catch (ClienteDoFiel.FalhaPassageira erro) {
			// Fiel fora do ar (subiu depois de nós, ou ainda se recriando às 4h): a agenda tenta de novo a cada minuto
			log.warn("pagamentos: cadastro do endereço dos avisos no Fiel falhou: {}", erro.getMessage());
			return false;
		}
		if (!r.deu()) {
			log.error("pagamentos: o Fiel recusou o endereço dos avisos {}: HTTP {} {}", endereco, r.status(),
					r.corpo().path("detail").asString(""));
			return false;
		}
		jdbc.sql("""
				insert into webhook_do_fiel (url, segredo) values (:url, :segredo)
				on conflict (id) do update set url = excluded.url, segredo = excluded.segredo, registrado_em = now()
				""").param("url", endereco).param("segredo", r.corpo().path("segredo").asString()).update();
		log.info("pagamentos: endereço dos avisos cadastrado no Fiel ({})", endereco);
		return true;
	}

	@Transactional
	public Recebido receber(byte[] corpo, String assinatura, UUID eventoId, String tipo) {
		var segredo = segredo();
		if (segredo.isEmpty()) {
			return Recebido.SEM_SEGREDO;
		}
		var texto = new String(corpo, StandardCharsets.UTF_8);
		if (!confere(segredo.get(), assinatura, texto)) {
			log.warn("pagamentos: aviso {} do Fiel com assinatura que não confere; recusado", eventoId);
			return Recebido.RECUSADO;
		}
		if (!processados.primeiraVez(eventoId)) {
			log.info("pagamentos: aviso {} do Fiel repetido, ignorado", eventoId);
			return Recebido.ACEITO;
		}
		if ("cobranca.paga".equals(tipo)) {
			var cobranca = json.readTree(texto).path("dados").path("cobranca");
			var referencia = cobranca.path("referencia").asString("");
			try {
				cobrancas.paga(UUID.fromString(referencia), cobranca.path("id").asLong());
			}
			catch (IllegalArgumentException erro) {
				// Cobrança da Loja Foil sem referência de pedido (criada à mão pela tela do Fiel): não é do Foil
				log.warn("pagamentos: aviso {} de pagamento sem pedido do Foil (referência '{}')", eventoId, referencia);
			}
		}
		return Recebido.ACEITO;
	}

	private Optional<String> segredo() {
		return jdbc.sql("select segredo from webhook_do_fiel").query(String.class).optional();
	}

	/** O mesmo cálculo do Fiel, comparado em tempo constante; e o {@code t} perto da nossa hora. */
	boolean confere(String segredo, String cabecalho, String corpo) {
		var campos = new HashMap<String, String>();
		for (var parte : cabecalho.split(",")) {
			var chaveEValor = parte.split("=", 2);
			if (chaveEValor.length == 2) {
				campos.put(chaveEValor[0].strip(), chaveEValor[1].strip());
			}
		}
		long segundos;
		try {
			segundos = Long.parseLong(campos.getOrDefault("t", ""));
		}
		catch (NumberFormatException erro) {
			return false;
		}
		var recebido = campos.get("v1");
		if (recebido == null || Math.abs(relogio.instant().getEpochSecond() - segundos) > TOLERANCIA.toSeconds()) {
			return false;
		}
		return MessageDigest.isEqual(hmac(segredo, segundos + "." + corpo).getBytes(StandardCharsets.UTF_8),
				recebido.getBytes(StandardCharsets.UTF_8));
	}

	static String hmac(String segredo, String texto) {
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(texto.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException | InvalidKeyException erro) {
			throw new IllegalStateException(erro);
		}
	}

}
