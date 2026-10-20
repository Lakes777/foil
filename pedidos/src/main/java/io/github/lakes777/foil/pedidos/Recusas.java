package io.github.lakes777.foil.pedidos;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** As recusas que dizem quando tentar de novo (RFC 9457, com {@code Retry-After}). */
@RestControllerAdvice
class Recusas {

	/** A fila de lançamentos está cheia, ou o painel ao vivo está lotado: não é culpa de quem pediu. */
	static class Lotado extends RuntimeException {

		Lotado(String mensagem) {
			super(mensagem);
		}

	}

	@ExceptionHandler(MuitasTentativas.class)
	ResponseEntity<ProblemDetail> muitasTentativas(MuitasTentativas e) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.segundos()))
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
	}

	@ExceptionHandler(Lotado.class)
	ResponseEntity<ProblemDetail> lotado(Lotado e) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.header(HttpHeaders.RETRY_AFTER, "60")
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage()));
	}

}
