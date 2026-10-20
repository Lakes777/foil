package io.github.lakes777.foil.pedidos;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Até {@code maximo} eventos por {@code janela} para cada chave (um IP), em janela fixa: a contagem começa no primeiro
 * evento e zera quando a janela passa. O mesmo desenho do Fiel.
 *
 * <p>
 * Fica na memória, o que basta para uma instância só (a VM tem um núcleo e nada escala na demo, §2 do SPEC). Reiniciar
 * o serviço zera as contagens: aceito.
 */
class LimitePorJanela {

	private record Contagem(Instant inicio, int quantos) {
	}

	private final int maximo;

	private final Duration janela;

	private final Clock relogio;

	private final ConcurrentHashMap<String, Contagem> contagens = new ConcurrentHashMap<>();

	private volatile Instant ultimaLimpeza = Instant.EPOCH;

	LimitePorJanela(int maximo, Duration janela, Clock relogio) {
		this.maximo = maximo;
		this.janela = janela;
		this.relogio = relogio;
	}

	/** Conta um evento; lança {@link MuitasTentativas} se ele passou do limite. */
	void registrar(String chave) {
		var agora = relogio.instant();
		limparVencidas(agora);
		var atual = contagens.compute(chave, (k, antes) -> antes == null || venceu(antes, agora) ? new Contagem(agora, 1)
				: new Contagem(antes.inicio(), antes.quantos() + 1));
		if (atual.quantos() > maximo) {
			throw new MuitasTentativas(segundosAteOFim(atual, agora));
		}
	}

	/** Devolve o evento contado (o pedido foi recusado por outro motivo e não deve gastar o limite). */
	void devolver(String chave) {
		contagens.computeIfPresent(chave,
				(k, c) -> c.quantos() <= 1 ? null : new Contagem(c.inicio(), c.quantos() - 1));
	}

	/** No máximo uma varredura por janela, para o mapa não crescer com IPs que já passaram. */
	private void limparVencidas(Instant agora) {
		if (Duration.between(ultimaLimpeza, agora).compareTo(janela) < 0) {
			return;
		}
		ultimaLimpeza = agora;
		contagens.entrySet().removeIf(e -> venceu(e.getValue(), agora));
	}

	private boolean venceu(Contagem contagem, Instant agora) {
		return !agora.isBefore(contagem.inicio().plus(janela));
	}

	/** Arredonda para cima: "tente em 0 s" faria o cliente tentar cedo demais. */
	private long segundosAteOFim(Contagem contagem, Instant agora) {
		var falta = Duration.between(agora, contagem.inicio().plus(janela));
		return falta.toSeconds() + (falta.toNanosPart() > 0 ? 1 : 0);
	}

	// IPv4 com quatro números, ou IPv6 (tem ":", que o Java nunca trata como nome a resolver)
	private static final Pattern IP_LITERAL = Pattern
		.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|[0-9a-fA-F.]*:[0-9a-fA-F:.]*)$");

	/**
	 * A chave de um IP. IPv6 conta pelo prefixo /64: uma casa recebe um /64 inteiro, e contar cada endereço à parte
	 * deixaria qualquer um escapar do limite.
	 */
	static String chaveDoIp(String ip) {
		if (ip == null || !IP_LITERAL.matcher(ip).matches()) {
			return String.valueOf(ip);
		}
		try {
			// Só texto com cara de IP literal: assim o getByName nunca vira uma consulta de DNS
			var endereco = InetAddress.getByName(ip);
			if (endereco instanceof Inet6Address) {
				var b = endereco.getAddress();
				return String.format("%x:%x:%x:%x::/64", (b[0] & 0xff) << 8 | b[1] & 0xff, (b[2] & 0xff) << 8 | b[3] & 0xff,
						(b[4] & 0xff) << 8 | b[5] & 0xff, (b[6] & 0xff) << 8 | b[7] & 0xff);
			}
			return endereco.getHostAddress();
		}
		catch (UnknownHostException e) {
			return ip;
		}
	}

}
