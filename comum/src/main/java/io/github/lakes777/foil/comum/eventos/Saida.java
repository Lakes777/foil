package io.github.lakes777.foil.comum.eventos;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.lakes777.foil.contratos.Envelope;
import tools.jackson.databind.json.JsonMapper;

/**
 * Grava um evento ou comando na tabela {@code saida} (outbox). Exige uma transação aberta: o evento só existe se
 * a mudança de estado que o gerou também existir, e vice-versa.
 */
public class Saida {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	private final Rastro rastro;

	public Saida(JdbcClient jdbc, JsonMapper json, Rastro rastro) {
		this.jdbc = jdbc;
		this.json = json;
		this.rastro = rastro;
	}

	/** No trace de agora (um consumidor: a mensagem que chegou trouxe o trace da saga). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void registrar(String topico, Envelope envelope) {
		registrar(topico, envelope, null);
	}

	/**
	 * Com o trace guardado do pedido, para quem pode estar fora dele (prazo vencido, aviso do Fiel): ver
	 * {@link Rastro#escolher}. Chave de partição = id do pedido: tudo de um pedido cai na mesma partição, em ordem
	 * (P24).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void registrar(String topico, Envelope envelope, String traceDoPedido) {
		jdbc.sql("""
				insert into saida (id, topico, chave, envelope, traceparent)
				values (:id, :topico, :chave, :envelope, :traceparent)
				""")
			.param("id", envelope.id())
			.param("topico", topico)
			.param("chave", envelope.pedidoId().toString())
			.param("envelope", json.writeValueAsString(envelope))
			.param("traceparent", rastro.escolher(traceDoPedido))
			.update();
	}

}
