package io.github.lakes777.foil.comum.eventos;

import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.common.errors.RetriableException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publica o que está pendente na {@code saida}, em ordem, e só marca como publicado depois da confirmação do
 * Redpanda. Se o serviço cair entre o envio e a marcação, o evento sai de novo na próxima vez: entrega "pelo
 * menos uma vez", e quem consome ignora o repetido ({@link EventosProcessados}).
 */
public class PublicadorDaSaida {

	static final int LOTE = 100;

	/** Maior que o delivery.timeout do produtor (5 s): o get() nunca desiste antes dele. */
	static final Duration PRAZO = Duration.ofSeconds(6);

	private static final Logger log = LoggerFactory.getLogger(PublicadorDaSaida.class);

	private final JdbcClient jdbc;

	private final KafkaTemplate<String, String> kafka;

	public PublicadorDaSaida(JdbcClient jdbc, KafkaTemplate<String, String> kafka) {
		this.jdbc = jdbc;
		this.kafka = kafka;
	}

	private record Pendente(long seq, String topico, String chave, String envelope) {
	}

	/**
	 * Publica um lote; devolve quantos saíram. Se um evento falha, os seguintes do mesmo pedido esperam (a ordem
	 * vale por pedido, P24), mas os dos outros pedidos seguem: um evento que nunca sai não congela a loja.
	 * Pior caso por evento ~11 s (max.block 5 s + get 6 s), com a transação aberta.
	 */
	@Transactional
	public int publicarPendentes() {
		// Um publicador por vez no banco: se um dia houver duas instâncias, a segunda não publica nada nesta
		// rodada (com "skip locked", ela passaria um evento de um pedido na frente de outro ainda travado)
		var vez = jdbc.sql("select pg_try_advisory_xact_lock(hashtext('foil.saida'))").query(Boolean.class).single();
		if (!vez) {
			return 0;
		}
		var pendentes = jdbc.sql("""
				select seq, topico, chave, envelope from saida
				where publicado_em is null order by seq limit :lote
				""").param("lote", LOTE).query(Pendente.class).list();
		var travados = new HashSet<String>();
		var publicados = 0;
		for (var p : pendentes) {
			if (travados.contains(p.chave())) {
				continue;
			}
			try {
				kafka.send(p.topico(), p.chave(), p.envelope()).get(PRAZO.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			catch (Exception e) {
				if (redpandaIndisponivel(e)) {
					// Vale para todos: tentar o próximo pedido só somaria mais uma espera com a transação aberta
					log.warn("saida: Redpanda indisponível, lote interrompido na seq {}: {}", p.seq(), e.toString());
					break;
				}
				// Problema deste evento (grande demais, tópico inválido...): só o pedido dele espera
				log.error("saida: não publicou seq {} (pedido {}) em {}: {}", p.seq(), p.chave(), p.topico(),
						e.toString());
				travados.add(p.chave());
				continue;
			}
			jdbc.sql("update saida set publicado_em = now() where seq = :seq").param("seq", p.seq()).update();
			publicados++;
		}
		return publicados;
	}

	/** Prazo esgotado ou erro "tente de novo" do Kafka = o Redpanda (não o evento) é o problema. */
	static boolean redpandaIndisponivel(Throwable erro) {
		for (var e = erro; e != null; e = e.getCause()) {
			if (e instanceof TimeoutException || e instanceof RetriableException) {
				return true;
			}
		}
		return false;
	}

}
