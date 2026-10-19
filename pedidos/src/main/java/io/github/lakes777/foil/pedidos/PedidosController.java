package io.github.lakes777.foil.pedidos;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Criar e acompanhar pedidos. A resposta do POST é 202: o resto da saga acontece por eventos. */
@RestController
class PedidosController {

	private final Saga saga;

	PedidosController(Saga saga) {
		this.saga = saga;
	}

	record NovoPedido(long edicaoId) {
	}

	@PostMapping("/pedidos")
	ResponseEntity<Pedido> criar(@RequestBody NovoPedido novo) {
		if (novo.edicaoId() <= 0) {
			return ResponseEntity.badRequest().build();
		}
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(saga.criar(novo.edicaoId()));
	}

	@GetMapping("/pedidos/{id}")
	ResponseEntity<Pedido> buscar(@PathVariable UUID id) {
		return ResponseEntity.of(saga.buscar(id));
	}

}
