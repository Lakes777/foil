package io.github.lakes777.foil.pagamentos;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Onde o Fiel entrega os avisos (o endereço de {@code foil.fiel.webhook}). O corpo é lido em bytes, com limite: a
 * assinatura é do texto exato que o Fiel mandou, e nenhuma conversão pode mexer nele antes da conferência.
 */
@RestController
class AvisosDoFielController {

	private final AvisosDoFiel avisos;

	AvisosDoFielController(AvisosDoFiel avisos) {
		this.avisos = avisos;
	}

	/** Um aviso do Fiel tem poucas centenas de bytes; o endereço é público, e o corpo é lido antes da assinatura. */
	static final int MAIOR_CORPO = 64 * 1024;

	@PostMapping("/fiel/avisos")
	ResponseEntity<Void> receber(HttpServletRequest pedido, @RequestHeader("Fiel-Assinatura") String assinatura,
			@RequestHeader("Fiel-Evento-Id") UUID eventoId, @RequestHeader("Fiel-Evento") String tipo)
			throws IOException {
		if (pedido.getContentLengthLong() > MAIOR_CORPO) {
			return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
		}
		// Lê no máximo um byte além do limite: sem Content-Length (chunked), o tamanho só aparece lendo
		var corpo = pedido.getInputStream().readNBytes(MAIOR_CORPO + 1);
		if (corpo.length > MAIOR_CORPO) {
			return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
		}
		return switch (avisos.receber(corpo, assinatura, eventoId, tipo)) {
			case ACEITO -> ResponseEntity.noContent().build();
			case RECUSADO -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
			case SEM_SEGREDO -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
		};
	}

}
