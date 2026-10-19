package io.github.lakes777.foil.comum.eventos;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.transaction.annotation.Transactional;

import io.github.lakes777.foil.contratos.Topicos;

/**
 * Fila de mortos (P25): guarda no banco o que caiu em {@code <tópico>.mortos} (para o painel listar) e devolve a
 * mensagem ao tópico de origem quando alguém manda reprocessar. Como os consumidores são idempotentes,
 * reprocessar duas vezes não aplica duas vezes.
 */
public class Mortos {

	private static final Logger log = LoggerFactory.getLogger(Mortos.class);

	private final JdbcClient jdbc;

	private final KafkaTemplate<String, String> kafka;

	public Mortos(JdbcClient jdbc, KafkaTemplate<String, String> kafka) {
		this.jdbc = jdbc;
		this.kafka = kafka;
	}

	public record Morto(UUID id, String topico, String chave, String mensagem, String erro, Instant mortoEm,
			Instant reprocessadoEm) {
	}

	@KafkaListener(topics = "#{'${foil.mortos.topicos}'.split(',')}")
	@Transactional
	public void guardar(ConsumerRecord<String, String> registro) {
		var topico = texto(registro, KafkaHeaders.DLT_ORIGINAL_TOPIC)
			.orElse(registro.topic().replace(Topicos.MORTOS, ""));
		var erro = texto(registro, KafkaHeaders.DLT_EXCEPTION_MESSAGE).orElse("");
		log.error("mortos: mensagem de {} (chave {}) foi para a fila de mortos: {}", topico, registro.key(), erro);
		// A posição na fila de mortos identifica a mensagem: entregue de novo, não vira dois registros
		jdbc.sql("""
				insert into morto (id, topico, chave, mensagem, erro, origem)
				values (:id, :topico, :chave, :mensagem, :erro, :origem)
				on conflict (origem) do nothing
				""")
			.param("id", UUID.randomUUID())
			.param("topico", topico)
			.param("chave", registro.key())
			.param("mensagem", registro.value())
			.param("erro", erro)
			.param("origem", registro.topic() + "/" + registro.partition() + "/" + registro.offset())
			.update();
	}

	public List<Morto> listar() {
		return jdbc.sql("""
				select id, topico, chave, mensagem, erro, morto_em, reprocessado_em from morto
				order by morto_em desc limit 200
				""").query(Morto.class).list();
	}

	public enum Reprocesso {

		FEITO, NAO_EXISTE, JA_REPROCESSADO

	}

	/**
	 * Devolve a mensagem ao tópico de origem, com a mesma chave (mesma partição; vai para o fim da fila do pedido).
	 * Uma vez só por registro: se falhar de novo, vira outro registro na fila de mortos.
	 */
	@Transactional
	public Reprocesso reprocessar(UUID id) throws Exception {
		var morto = jdbc.sql("""
				select id, topico, chave, mensagem, erro, morto_em, reprocessado_em from morto where id = :id for update
				""").param("id", id).query(Morto.class).optional();
		if (morto.isEmpty()) {
			return Reprocesso.NAO_EXISTE;
		}
		var m = morto.get();
		if (m.reprocessadoEm() != null) {
			return Reprocesso.JA_REPROCESSADO;
		}
		kafka.send(m.topico(), m.chave(), m.mensagem()).get(PublicadorDaSaida.PRAZO.toMillis(), TimeUnit.MILLISECONDS);
		jdbc.sql("update morto set reprocessado_em = now() where id = :id").param("id", id).update();
		return Reprocesso.FEITO;
	}

	private static Optional<String> texto(ConsumerRecord<String, String> registro, String cabecalho) {
		return Optional.ofNullable(registro.headers().lastHeader(cabecalho))
			.map(Header::value)
			.map(v -> new String(v, StandardCharsets.UTF_8));
	}

}
