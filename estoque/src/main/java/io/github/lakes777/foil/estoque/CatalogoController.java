package io.github.lakes777.foil.estoque;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Só leitura. Reservar e devolver chegam por eventos (fases 3 e 4), nunca por HTTP. */
@RestController
class CatalogoController {

	private final EstoqueService estoque;

	CatalogoController(EstoqueService estoque) {
		this.estoque = estoque;
	}

	@GetMapping("/cartas")
	List<Carta> cartas() {
		return estoque.cartas();
	}

	@GetMapping("/cartas/{id}/edicoes")
	ResponseEntity<List<Edicao>> edicoes(@PathVariable long id) {
		if (!estoque.existeCarta(id)) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(estoque.edicoes(id));
	}

	@GetMapping("/edicoes/{id}")
	ResponseEntity<Edicao> edicao(@PathVariable long id) {
		return ResponseEntity.of(estoque.buscarEdicao(id));
	}

}
