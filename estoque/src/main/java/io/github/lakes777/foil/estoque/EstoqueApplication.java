package io.github.lakes777.foil.estoque;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.github.lakes777.foil.comum.Carregadores;

@SpringBootApplication
public class EstoqueApplication {

	public static void main(String[] args) {
		Carregadores.garantir();
		SpringApplication.run(EstoqueApplication.class, args);
	}

}
