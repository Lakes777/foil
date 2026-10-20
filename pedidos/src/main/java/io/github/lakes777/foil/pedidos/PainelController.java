package io.github.lakes777.foil.pedidos;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code GET /eventos}: o painel ao vivo por SSE. Cada mensagem tem o tipo do evento como {@code event} e o envelope
 * inteiro como {@code data} ({@code PedidoMudouDeEstado} e {@code LancamentoMudouDeEstado}).
 */
@RestController
class PainelController {

	private final PainelAoVivo painel;

	PainelController(PainelAoVivo painel) {
		this.painel = painel;
	}

	@GetMapping(path = "/eventos", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter eventos(HttpServletRequest pedido) throws IOException {
		return painel.conectar(LimitePorJanela.chaveDoIp(pedido.getRemoteAddr()));
	}

}
