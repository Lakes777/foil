package io.github.lakes777.foil.pedidos;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.github.lakes777.foil.contratos.Topicos;
import tools.jackson.databind.json.JsonMapper;

/**
 * O ao vivo do painel (P15): lê o {@code pedidos.eventos} e repassa cada envelope por SSE a quem estiver olhando.
 *
 * <ul>
 * <li>Lê com {@code assign} nas partições, a partir do fim, sem confirmar posição: cada instância vê tudo, não guarda
 * posição e não disputa partição com ninguém (o {@code group.id} da configuração vem junto, mas sem {@code subscribe}
 * nem commit ele não faz nada). Quem conecta vê dali em diante; o retrato do momento vem do
 * {@code GET /lancamentos}.</li>
 * <li>Cada visitante tem uma fila própria e uma thread virtual que escreve nela. Um navegador lento não segura os
 * outros: se a fila dele enche, ele é desconectado (o EventSource reconecta sozinho).</li>
 * <li>Teto de conexões no total e por IP: cada conexão é uma thread e uma fila na memória.</li>
 * </ul>
 */
@Component
class PainelAoVivo {

	private static final Logger log = LoggerFactory.getLogger(PainelAoVivo.class);

	/** Mensagens esperando por visitante; num lançamento saem ~40 por segundo. */
	static final int FILA_POR_VISITANTE = 1000;

	private record Visitante(String ip, SseEmitter emissor, BlockingQueue<SseEmitter.SseEventBuilder> fila,
			Thread escritor) {
	}

	private final ConsumerFactory<?, ?> fabrica;

	private final JsonMapper json;

	private final int conexoesMaximas;

	private final int conexoesPorIp;

	private final Duration duracaoMaxima;

	private final List<Visitante> visitantes = new CopyOnWriteArrayList<>();

	private volatile boolean ligado = true;

	private volatile Consumer<?, ?> consumidor;

	private Thread leitor;

	PainelAoVivo(ConsumerFactory<?, ?> fabrica, JsonMapper json,
			@Value("${foil.painel.conexoes-maximas:100}") int conexoesMaximas,
			@Value("${foil.painel.conexoes-por-ip:5}") int conexoesPorIp,
			@Value("${foil.painel.duracao-maxima:30m}") Duration duracaoMaxima) {
		this.fabrica = fabrica;
		this.json = json;
		this.conexoesMaximas = conexoesMaximas;
		this.conexoesPorIp = conexoesPorIp;
		this.duracaoMaxima = duracaoMaxima;
	}

	/** Um visitante novo. Passou do teto por IP = 429; do teto geral = 503. */
	synchronized SseEmitter conectar(String ip) throws IOException {
		if (visitantes.stream().filter(v -> v.ip().equals(ip)).count() >= conexoesPorIp) {
			throw new MuitasTentativas(30);
		}
		if (visitantes.size() >= conexoesMaximas) {
			throw new Recusas.Lotado("o painel ao vivo está lotado; tente daqui a pouco");
		}
		// Vence sozinho depois de um tempo: o EventSource reconecta, e conexão esquecida aberta não fica para sempre
		var emissor = new SseEmitter(duracaoMaxima.toMillis());
		var fila = new ArrayBlockingQueue<SseEmitter.SseEventBuilder>(FILA_POR_VISITANTE);
		// Já manda algo: assim a resposta sai na hora (e o navegador sabe que conectou)
		emissor.send(SseEmitter.event().reconnectTime(3000).comment("conectado"));
		var visitante = new Visitante(ip, emissor, fila, Thread.ofVirtual().unstarted(() -> escrever(emissor, fila)));
		visitantes.add(visitante);
		emissor.onCompletion(() -> sair(visitante));
		emissor.onTimeout(() -> sair(visitante));
		emissor.onError(e -> sair(visitante));
		visitante.escritor().start();
		return emissor;
	}

	private void escrever(SseEmitter emissor, BlockingQueue<SseEmitter.SseEventBuilder> fila) {
		try {
			while (true) {
				emissor.send(fila.take());
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		catch (IOException | IllegalStateException e) {
			// Navegador fechou, ou o emissor já terminou: o onError/onCompletion tira o visitante
			try {
				emissor.completeWithError(e);
			}
			catch (IllegalStateException jaTerminou) {
				// O Tomcat já tratou o erro da conexão (onError) e não deixa mais mexer nela: nada a fazer
			}
		}
	}

	private void sair(Visitante visitante) {
		if (visitantes.remove(visitante)) {
			visitante.escritor().interrupt();
		}
	}

	int conectados() {
		return visitantes.size();
	}

	/** Repassa um envelope a todos; quem está com a fila cheia é desconectado em vez de segurar os outros. */
	void espalhar(String envelope) {
		String tipo;
		try {
			tipo = json.readTree(envelope).path("tipo").asString("evento");
		}
		catch (RuntimeException e) {
			log.warn("painel: mensagem que não é JSON no {}, ignorada", Topicos.PEDIDOS_EVENTOS);
			return;
		}
		for (var v : visitantes) {
			if (!v.fila().offer(SseEmitter.event().name(tipo).data(envelope, MediaType.APPLICATION_JSON))) {
				log.info("painel: visitante lento ({} mensagens esperando), desconectado", FILA_POR_VISITANTE);
				sair(v);
				fechar(v);
			}
		}
	}

	/**
	 * Um comentário a cada 15 s: mantém a conexão viva em proxies que fecham conexão parada e acha quem já saiu. Quem
	 * saiu só aparece na segunda escrita depois de fechar (a primeira cabe no buffer do sistema), então a vaga volta em
	 * até duas voltas disto.
	 */
	@Scheduled(fixedDelayString = "${foil.painel.manter-vivo:15s}")
	void manterVivo() {
		for (var v : visitantes) {
			v.fila().offer(SseEmitter.event().comment("ainda aqui"));
		}
	}

	@EventListener(ApplicationReadyEvent.class)
	void ligar() {
		leitor = Thread.ofVirtual().name("painel-ao-vivo").start(this::ler);
	}

	/**
	 * O ContextClosedEvent vem antes de o Tomcat parar: fechar as conexões aqui deixa o desligamento elegante sem
	 * esperar o prazo inteiro por SSE que nunca acaba.
	 */
	@EventListener(ContextClosedEvent.class)
	void desligar() {
		ligado = false;
		var c = consumidor;
		if (c != null) {
			c.wakeup();
		}
		for (var v : visitantes) {
			sair(v);
			fechar(v);
		}
	}

	/**
	 * Fecha fora da thread que chamou: o {@code complete()} espera a mesma trava do {@code send()}, e o escritor de um
	 * visitante que parou de ler fica preso no {@code send()} até o Tomcat desistir da escrita. Chamado da thread do
	 * Kafka, isso pararia o painel de todo mundo.
	 */
	private void fechar(Visitante v) {
		Thread.ofVirtual().name("painel-fechar").start(v.emissor()::complete);
	}

	private void ler() {
		var ajustes = new Properties();
		ajustes.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		ajustes.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
		while (ligado) {
			try (var c = fabrica.createConsumer(null, "painel", null, ajustes)) {
				consumidor = c;
				var particoes = c.partitionsFor(Topicos.PEDIDOS_EVENTOS)
					.stream()
					.map(p -> new TopicPartition(p.topic(), p.partition()))
					.toList();
				if (particoes.isEmpty()) {
					throw new IllegalStateException("tópico " + Topicos.PEDIDOS_EVENTOS + " ainda não existe");
				}
				c.assign(particoes);
				c.seekToEnd(particoes);
				while (ligado) {
					for (var registro : c.poll(Duration.ofMillis(500))) {
						if (registro.value() instanceof String envelope) {
							espalhar(envelope);
						}
					}
				}
			}
			catch (WakeupException e) {
				return;
			}
			catch (RuntimeException e) {
				if (!ligado) {
					return;
				}
				log.warn("painel: leitura do {} caiu ({}); tentando de novo em 2 s", Topicos.PEDIDOS_EVENTOS,
						e.toString());
				try {
					Thread.sleep(2000);
				}
				catch (InterruptedException i) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			finally {
				consumidor = null;
			}
		}
	}

}
