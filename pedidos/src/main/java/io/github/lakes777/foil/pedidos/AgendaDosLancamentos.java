package io.github.lakes777.foil.pedidos;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Anda com os lançamentos a cada ~100 ms (o ritmo dos robôs é contado pelo relógio, não por rodada); desligável com
 * {@code foil.lancamento.automatico=false} (testes).
 */
@Component
@ConditionalOnProperty(name = "foil.lancamento.automatico", havingValue = "true", matchIfMissing = true)
class AgendaDosLancamentos {

	private final Lancamentos lancamentos;

	AgendaDosLancamentos(Lancamentos lancamentos) {
		this.lancamentos = lancamentos;
	}

	@Scheduled(fixedDelayString = "${foil.lancamento.intervalo-ms:100}")
	void avancar() {
		lancamentos.avancar();
	}

}
