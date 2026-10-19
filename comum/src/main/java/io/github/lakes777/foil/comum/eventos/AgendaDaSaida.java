package io.github.lakes777.foil.comum.eventos;

import org.springframework.scheduling.annotation.Scheduled;

/** Chama o publicador sem parar; desligável com {@code foil.saida.automatica=false} (testes). */
public class AgendaDaSaida {

	private final PublicadorDaSaida publicador;

	public AgendaDaSaida(PublicadorDaSaida publicador) {
		this.publicador = publicador;
	}

	@Scheduled(fixedDelayString = "${foil.saida.intervalo-ms:200}")
	void publicar() {
		// Lote cheio = provavelmente tem mais: segue sem esperar o próximo intervalo
		var publicados = PublicadorDaSaida.LOTE;
		while (publicados == PublicadorDaSaida.LOTE) {
			publicados = publicador.publicarPendentes();
		}
	}

}
