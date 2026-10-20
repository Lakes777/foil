package io.github.lakes777.foil.estoque;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.lakes777.foil.comum.eventos.Limpeza;

/** O que a limpeza das 4h10 apaga no estoque, com as datas empurradas para o passado. */
@TesteDoEstoque
class LimpezaDoEstoqueTest {

	@Autowired
	Limpeza limpeza;

	@Autowired
	EstoqueService estoque;

	@Autowired
	JdbcClient jdbc;

	private long carta() {
		return estoque.criarCarta("Carta " + UUID.randomUUID(), "comum", "teste").id();
	}

	private long edicaoDeLancamento(long carta, String criada) {
		var edicao = estoque.novaEdicao(carta, 10).id();
		jdbc.sql("update edicao set lancamento_id = :l, criada_em = now() - cast(:ha as interval) where id = :id")
			.param("l", UUID.randomUUID())
			.param("ha", criada)
			.param("id", edicao)
			.update();
		return edicao;
	}

	private void envelhecerReserva(UUID pedido, String ha) {
		jdbc.sql("""
				update reserva set reservada_em = now() - cast(:ha as interval),
				devolvida_em = case when devolvida_em is not null then now() - cast(:ha as interval) end
				where pedido_id = :p
				""").param("ha", ha).param("p", pedido).update();
	}

	private boolean temReserva(UUID pedido) {
		return jdbc.sql("select count(*) from reserva where pedido_id = :p").param("p", pedido).query(Long.class).single() == 1;
	}

	private boolean temEdicao(long id) {
		return jdbc.sql("select count(*) from edicao where id = :id").param("id", id).query(Long.class).single() == 1;
	}

	@Test
	void reservasQueResponderamSaemDepoisDosEventosEVendasFicam() {
		var edicao = estoque.novaEdicao(carta(), 1).id();
		var vendida = UUID.randomUUID();
		var semEstoque = UUID.randomUUID();
		var devolvida = UUID.randomUUID();
		var devolvidaRecente = UUID.randomUUID();
		var devolvidaComMorto = UUID.randomUUID();
		estoque.reservar(vendida, edicao);
		estoque.reservar(semEstoque, edicao);
		var outra = estoque.novaEdicao(carta(), 5).id();
		estoque.reservar(devolvida, outra);
		estoque.devolver(devolvida, outra);
		estoque.reservar(devolvidaRecente, outra);
		estoque.devolver(devolvidaRecente, outra);
		estoque.reservar(devolvidaComMorto, outra);
		estoque.devolver(devolvidaComMorto, outra);
		// Um comando do pedido na fila de mortos: a devolução é a trava contra o reprocessar, e fica
		jdbc.sql("insert into morto (id, topico, chave, erro, origem) values (:id, 'estoque.comandos', :chave, 'e', :id::text)")
			.param("id", UUID.randomUUID())
			.param("chave", devolvidaComMorto.toString())
			.update();
		for (var p : new UUID[] { vendida, semEstoque, devolvida, devolvidaComMorto }) {
			envelhecerReserva(p, "5 days");
		}
		envelhecerReserva(devolvidaRecente, "3 days");

		limpeza.limpar();

		assertThat(temReserva(vendida)).isTrue();
		assertThat(temReserva(semEstoque)).isFalse();
		assertThat(temReserva(devolvida)).isFalse();
		assertThat(temReserva(devolvidaRecente)).isTrue();
		assertThat(temReserva(devolvidaComMorto)).isTrue();
	}

	@Test
	void edicaoDeLancamentoVelhaSaiSoSemReservaRecenteEAsDoCatalogoFicam() {
		var carta = carta();
		var velha = edicaoDeLancamento(carta, "31 days");
		var vendidaHaPouco = edicaoDeLancamento(carta, "31 days");
		var comprador = UUID.randomUUID();
		estoque.reservar(comprador, vendidaHaPouco);
		envelhecerReserva(comprador, "29 days");
		var vendidaHaMuito = edicaoDeLancamento(carta, "31 days");
		var antigo = UUID.randomUUID();
		estoque.reservar(antigo, vendidaHaMuito);
		envelhecerReserva(antigo, "31 days");
		var nova = edicaoDeLancamento(carta, "29 days");
		var doCatalogo = estoque.novaEdicao(carta, 10).id();
		jdbc.sql("update edicao set criada_em = now() - interval '400 days' where id = :id").param("id", doCatalogo).update();

		limpeza.limpar();

		assertThat(temEdicao(velha)).isFalse();
		assertThat(temEdicao(vendidaHaPouco)).isTrue();
		assertThat(temEdicao(vendidaHaMuito)).isFalse();
		// A reserva vai junto com a edição (cascata)
		assertThat(temReserva(antigo)).isFalse();
		assertThat(temEdicao(nova)).isTrue();
		assertThat(temEdicao(doCatalogo)).isTrue();
	}

}
