package io.github.lakes777.foil.pagamentos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.github.lakes777.foil.comum.Carregadores;

@SpringBootApplication
public class PagamentosApplication {

	public static void main(String[] args) {
		Carregadores.garantir();
		SpringApplication.run(PagamentosApplication.class, args);
	}

}
