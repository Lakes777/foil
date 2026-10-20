package io.github.lakes777.foil.pedidos;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido.Forma;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Criar e acompanhar pedidos. A resposta do POST é 202: o resto da saga acontece por eventos. Quem compra é um
 * visitante sem conta (P14): o cookie {@code foil_visitante} nasce na primeira compra e liga os pedidos a "Minhas
 * gemas".
 */
@RestController
class PedidosController {

	static final String COOKIE = "foil_visitante";

	private final Saga saga;

	private final LimitesPorIp limites;

	private final boolean cookieSeguro;

	PedidosController(Saga saga, LimitesPorIp limites, @Value("${foil.cookie.seguro:true}") boolean cookieSeguro) {
		this.saga = saga;
		this.limites = limites;
		this.cookieSeguro = cookieSeguro;
	}

	/** Valor que não é um UUID (velho, mexido) vale como "sem cookie": o visitante ganha um novo. */
	private static Optional<UUID> visitante(String cookie) {
		try {
			return cookie == null ? Optional.empty() : Optional.of(UUID.fromString(cookie));
		}
		catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/** Sem {@code forma}, é FIEL: quem compra pela loja paga pelo QR. SIMULADA é a dos robôs (e dos testes). */
	record NovoPedido(long edicaoId, Forma forma) {
	}

	@PostMapping("/pedidos")
	ResponseEntity<Pedido> criar(@RequestBody NovoPedido novo, HttpServletRequest pedido,
			@CookieValue(name = COOKIE, required = false) String cookie) {
		if (novo.edicaoId() <= 0) {
			return ResponseEntity.badRequest().build();
		}
		limites.pedidos.registrar(LimitePorJanela.chaveDoIp(pedido.getRemoteAddr()));
		var forma = novo.forma() == null ? Forma.FIEL : novo.forma();
		var conhecido = visitante(cookie);
		var quem = conhecido.orElseGet(UUID::randomUUID);
		var resposta = ResponseEntity.status(HttpStatus.ACCEPTED);
		if (conhecido.isEmpty()) {
			// HttpOnly: o JavaScript da página não lê; SameSite=Lax: outro site não compra em nome do visitante
			resposta.header(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, quem.toString())
				.httpOnly(true)
				.secure(cookieSeguro)
				.sameSite("Lax")
				.path("/")
				.maxAge(Duration.ofDays(365))
				.build()
				.toString());
		}
		return resposta.body(saga.criar(novo.edicaoId(), forma, null, quem));
	}

	/** As compras deste navegador (as 50 mais novas). Sem cookie, nenhuma. */
	@GetMapping("/minhas")
	List<Saga.CompraDoVisitante> minhas(@CookieValue(name = COOKIE, required = false) String cookie) {
		return visitante(cookie).map(saga::doVisitante).orElse(List.of());
	}

	@GetMapping("/pedidos/{id}")
	ResponseEntity<Pedido> buscar(@PathVariable UUID id) {
		return ResponseEntity.of(saga.buscar(id));
	}

}
