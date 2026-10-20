package io.github.lakes777.foil.pedidos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.github.lakes777.foil.comum.Carregadores;

@SpringBootApplication
public class PedidosApplication {

	public static void main(String[] args) {
		Carregadores.garantir();
		SpringApplication.run(PedidosApplication.class, args);
	}

}
