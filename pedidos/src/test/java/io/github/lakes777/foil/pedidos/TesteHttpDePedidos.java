package io.github.lakes777.foil.pedidos;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.lakes777.foil.comum.ConteineresDeTeste;

/**
 * O serviço inteiro numa porta sorteada, como atrás do Caddy: o teste é o proxy confiável (127.0.0.1) e escolhe o IP
 * de cada visitante pelo X-Forwarded-For. A agenda dos lançamentos fica parada (o teste chama a vez quando quer) e o
 * "ainda aqui" do painel sai a cada 1 s, para a vaga de quem saiu voltar logo.
 * Mesma configuração em todos os testes HTTP, para o Spring reaproveitar o contexto (e os contêineres).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "server.forward-headers-strategy=native", "foil.lancamento.automatico=false",
				"foil.painel.manter-vivo=1s" })
@Import(ConteineresDeTeste.class)
@interface TesteHttpDePedidos {

}
