package io.github.lakes777.foil.pedidos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** O limite sozinho, sem Spring: a janela, a devolução e a chave de cada IP. */
class LimitePorJanelaTest {

	private final RelogioDeTeste relogio = new RelogioDeTeste();

	private final LimitePorJanela limite = new LimitePorJanela(2, Duration.ofMinutes(1), relogio);

	@Test
	void passouDoLimiteDizQuantoFaltaEAJanelaZera() {
		limite.registrar("a");
		relogio.avancar(Duration.ofSeconds(20));
		limite.registrar("a");

		assertThatThrownBy(() -> limite.registrar("a")).isInstanceOfSatisfying(MuitasTentativas.class,
				e -> assertThat(e.segundos()).isEqualTo(40));
		limite.registrar("b");

		relogio.avancar(Duration.ofSeconds(40));
		limite.registrar("a");
	}

	@Test
	void devolverDevolveUmSo() {
		limite.registrar("a");
		limite.registrar("a");
		limite.devolver("a");

		limite.registrar("a");
		assertThatThrownBy(() -> limite.registrar("a")).isInstanceOf(MuitasTentativas.class);
	}

	@Test
	void chaveDoIp() {
		assertThat(LimitePorJanela.chaveDoIp("203.0.113.9")).isEqualTo("203.0.113.9");
		assertThat(LimitePorJanela.chaveDoIp("2001:db8:1:2:aaaa::1")).isEqualTo("2001:db8:1:2::/64");
		assertThat(LimitePorJanela.chaveDoIp("2001:0db8:0001:0002::ffff")).isEqualTo("2001:db8:1:2::/64");
		// Nome não vira consulta de DNS: fica como está
		assertThat(LimitePorJanela.chaveDoIp("exemplo.invalid")).isEqualTo("exemplo.invalid");
	}

}
