package io.github.lakes777.foil.pedidos;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** O relógio dos prazos; os testes põem um @Primary que eles mesmos avançam. */
@Configuration(proxyBeanMethods = false)
class ConfiguracaoDoRelogio {

	@Bean
	Clock relogio() {
		return Clock.systemUTC();
	}

}
