package io.github.lakes777.foil.pedidos;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Confere os prazos a cada segundo; desligável com {@code foil.pedidos.prazos-automaticos=false} (testes). */
@Component
@ConditionalOnProperty(name = "foil.pedidos.prazos-automaticos", havingValue = "true", matchIfMissing = true)
class AgendaDosPrazos {

	private final Saga saga;

	AgendaDosPrazos(Saga saga) {
		this.saga = saga;
	}

	@Scheduled(fixedDelayString = "${foil.pedidos.intervalo-dos-prazos-ms:1000}")
	void vencer() {
		while (saga.vencerPrazos() == 100) {
			// lote cheio: provavelmente tem mais
		}
	}

}
