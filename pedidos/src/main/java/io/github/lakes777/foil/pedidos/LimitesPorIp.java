package io.github.lakes777.foil.pedidos;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Os limites por IP das portas públicas. O IP vem do {@code getRemoteAddr()}: atrás do Caddy, o Tomcat troca pelo do
 * {@code X-Forwarded-For} só quando o pedido vem de um proxy confiável ({@code FOIL_PROXIES_CONFIAVEIS}), senão
 * qualquer um escolheria o próprio IP.
 */
@Component
class LimitesPorIp {

	/** P7: no máximo um lançamento por minuto por IP. */
	final LimitePorJanela lancamentos;

	/**
	 * Compras da loja. Cada uma prende uma unidade por até 5 min (e, no Fiel, cria uma cobrança): sem limite, um laço
	 * de curl esvaziaria uma edição inteira sem pagar (achado da fase 4).
	 */
	final LimitePorJanela pedidos;

	LimitesPorIp(Clock relogio, @Value("${foil.limite.lancamentos-por-minuto:1}") int lancamentosPorMinuto,
			@Value("${foil.limite.pedidos-por-minuto:10}") int pedidosPorMinuto) {
		this.lancamentos = new LimitePorJanela(lancamentosPorMinuto, Duration.ofMinutes(1), relogio);
		this.pedidos = new LimitePorJanela(pedidosPorMinuto, Duration.ofMinutes(1), relogio);
	}

}
