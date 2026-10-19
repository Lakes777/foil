package io.github.lakes777.foil.estoque;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.lakes777.foil.comum.ConteineresDeTeste;

/**
 * Mesma configuração em todos os testes do estoque, para o Spring reaproveitar o contexto (e os contêineres).
 * Pool de 20 conexões: com as 5 de produção, os 200 pedidos quase não disputariam a mesma linha ao mesmo tempo.
 * Espera por conexão de 30 s (produção usa 3 s): num CI lento, a fila de 200 por 20 não pode reprovar a regra.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.datasource.hikari.maximum-pool-size=20",
				"spring.datasource.hikari.connection-timeout=30000" })
@Import(ConteineresDeTeste.class)
@interface TesteDoEstoque {

}
