package io.github.lakes777.foil.pagamentos;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * O que roda sozinho para o Fiel; desligável com {@code foil.fiel.automatico=false} (testes que chamam na mão).
 */
@Component
@ConditionalOnProperty(name = "foil.fiel.automatico", havingValue = "true", matchIfMissing = true)
class AgendaDoFiel {

	private final TrabalhoNoFiel trabalho;

	private final AvisosDoFiel avisos;

	private volatile boolean cadastrado;

	AgendaDoFiel(TrabalhoNoFiel trabalho, AvisosDoFiel avisos) {
		this.trabalho = trabalho;
		this.avisos = avisos;
	}

	@Scheduled(fixedDelayString = "${foil.fiel.intervalo-ms:1000}")
	void trabalhar() {
		while (trabalho.rodar() == TrabalhoNoFiel.LOTE) {
			// lote cheio: provavelmente tem mais
		}
	}

	/** Na partida e, enquanto não der certo (Fiel fora do ar), a cada minuto. */
	@EventListener(ApplicationReadyEvent.class)
	@Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
	void cadastrarSeFaltar() {
		if (!cadastrado) {
			cadastrado = avisos.cadastrar();
		}
	}

	/** 4h05 de Brasília: o Fiel recria a demonstração às 4h e o cadastro do endereço some junto (§7 do SPEC). */
	@Scheduled(cron = "${foil.fiel.recadastro:0 5 4 * * *}", zone = "America/Sao_Paulo")
	void recadastrar() {
		cadastrado = false;
		cadastrarSeFaltar();
	}

}
