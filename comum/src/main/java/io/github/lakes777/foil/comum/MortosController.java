package io.github.lakes777.foil.comum;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.lakes777.foil.comum.eventos.Mortos;

/**
 * {@code GET /mortos} lista a fila de mortos do serviço; {@code POST /mortos/{id}/reprocessar} é o botão. Só existe
 * onde há fila de mortos ({@code foil.mortos.topicos}).
 */
@RestController
@ConditionalOnProperty("foil.mortos.topicos")
class MortosController {

	private final Mortos mortos;

	MortosController(Mortos mortos) {
		this.mortos = mortos;
	}

	@GetMapping("/mortos")
	List<Mortos.Morto> listar() {
		return mortos.listar();
	}

	@PostMapping("/mortos/{id}/reprocessar")
	ResponseEntity<Void> reprocessar(@PathVariable UUID id) throws Exception {
		return switch (mortos.reprocessar(id)) {
			case FEITO -> ResponseEntity.accepted().build();
			case NAO_EXISTE -> ResponseEntity.notFound().build();
			case JA_REPROCESSADO -> ResponseEntity.status(HttpStatus.CONFLICT).build();
			case VENCIDO -> ResponseEntity.status(HttpStatus.GONE).build();
		};
	}

}
