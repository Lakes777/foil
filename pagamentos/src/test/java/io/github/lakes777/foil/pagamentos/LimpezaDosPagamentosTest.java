package io.github.lakes777.foil.pagamentos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.lakes777.foil.comum.ConteineresDeTeste;
import io.github.lakes777.foil.comum.eventos.Limpeza;

/** O que a limpeza das 4h10 apaga em pagamentos: só cobrança terminada e velha. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ConteineresDeTeste.class)
class LimpezaDosPagamentosTest {

	@Autowired
	Limpeza limpeza;

	@Autowired
	JdbcClient jdbc;

	private UUID cobranca(String situacao, String forma, String ha) {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into cobranca (pedido_id, valor_centavos, situacao, forma, fiel_id, criada_em, atualizada_em)
				values (:id, 1000, :situacao, :forma, case when :forma = 'fiel' then 1 end,
				        now() - cast(:ha as interval), now() - cast(:ha as interval))
				""").param("id", id).param("situacao", situacao).param("forma", forma).param("ha", ha).update();
		return id;
	}

	private boolean existe(UUID id) {
		return jdbc.sql("select count(*) from cobranca where pedido_id = :id").param("id", id).query(Long.class).single() == 1;
	}

	@Test
	void apagaSoCobrancaTerminadaDepoisDoPrazoDosEventos() {
		var aprovada = cobranca("aprovada", "simulada", "5 days");
		var recusada = cobranca("recusada", "simulada", "5 days");
		var cancelada = cobranca("cancelada", "simulada", "5 days");
		var estornada = cobranca("estornada", "fiel", "5 days");
		// A trava contra um CobrarPedido atrasado: ainda dentro do tópico, fica
		var canceladaRecente = cobranca("cancelada", "simulada", "3 days");
		// Um CobrarPedido do pedido na fila de mortos: a cancelada é a trava contra o reprocessar, e fica
		var canceladaComMorto = cobranca("cancelada", "simulada", "5 days");
		jdbc.sql("insert into morto (id, topico, chave, erro, origem) values (:id, 'pagamentos.comandos', :chave, 'e', :id::text)")
			.param("id", UUID.randomUUID())
			.param("chave", canceladaComMorto.toString())
			.update();
		// Esperando o Fiel: nunca sai
		var pendente = cobranca("pendente", "fiel", "60 days");
		var estornando = cobranca("estornando", "fiel", "60 days");

		limpeza.limpar();

		assertThat(existe(aprovada)).isFalse();
		assertThat(existe(recusada)).isFalse();
		assertThat(existe(cancelada)).isFalse();
		assertThat(existe(estornada)).isFalse();
		assertThat(existe(canceladaRecente)).isTrue();
		assertThat(existe(canceladaComMorto)).isTrue();
		assertThat(existe(pendente)).isTrue();
		assertThat(existe(estornando)).isTrue();
	}

}
