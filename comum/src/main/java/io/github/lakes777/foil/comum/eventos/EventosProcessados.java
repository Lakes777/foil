package io.github.lakes777.foil.comum.eventos;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumidor idempotente (P12): anota o id do evento na mesma transação do efeito. Se o evento chegar de novo,
 * {@link #primeiraVez} devolve false e o consumidor não faz nada.
 */
public class EventosProcessados {

	private final JdbcClient jdbc;

	public EventosProcessados(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Exige transação: anotar e aplicar o efeito são uma coisa só (ou as duas acontecem, ou nenhuma). */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean primeiraVez(UUID eventoId) {
		// Dois iguais ao mesmo tempo: o segundo espera o primeiro terminar e então não insere nada
		return jdbc.sql("insert into evento_processado (evento_id) values (:id) on conflict do nothing")
			.param("id", eventoId)
			.update() == 1;
	}

}
