package io.github.lakes777.foil.pedidos;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.lakes777.foil.pedidos.Lancamentos.Resumo;
import io.github.lakes777.foil.pedidos.Lancamentos.Situacao;
import jakarta.servlet.http.HttpServletRequest;

/**
 * O botão "Simular lançamento". O POST não leva nada: a carta e o tamanho da edição são do servidor, então quem chama
 * só escolhe a hora (e no máximo uma vez por minuto).
 */
@RestController
class LancamentosController {

	private final Lancamentos lancamentos;

	private final LimitesPorIp limites;

	LancamentosController(Lancamentos lancamentos, LimitesPorIp limites) {
		this.lancamentos = lancamentos;
		this.limites = limites;
	}

	@PostMapping("/lancamentos")
	ResponseEntity<Resumo> pedir(HttpServletRequest pedido) {
		var ip = LimitePorJanela.chaveDoIp(pedido.getRemoteAddr());
		limites.lancamentos.registrar(ip);
		try {
			return ResponseEntity.status(HttpStatus.ACCEPTED).body(lancamentos.pedir());
		}
		catch (Recusas.Lotado e) {
			// Fila cheia não conta como o lançamento do minuto
			limites.lancamentos.devolver(ip);
			throw e;
		}
	}

	@GetMapping("/lancamentos")
	Situacao situacao() {
		return lancamentos.situacao();
	}

	@GetMapping("/lancamentos/{id}")
	ResponseEntity<Resumo> buscar(@PathVariable UUID id) {
		return ResponseEntity.of(lancamentos.buscar(id));
	}

}
