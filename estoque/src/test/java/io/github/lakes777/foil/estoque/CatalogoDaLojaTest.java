package io.github.lakes777.foil.estoque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * O catálogo da loja (R__catalogo.sql) roda dentro de uma transação desfeita no fim: os outros testes do estoque
 * continuam sem ele (o lançamento escolhe a carta rara com menos edições, e as do catálogo mudariam a escolha).
 */
@TesteDoEstoque
@Transactional
class CatalogoDaLojaTest {

	@Autowired
	DataSource banco;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	EstoqueService estoque;

	private void rodarOCatalogo() {
		ScriptUtils.executeSqlScript(DataSourceUtils.getConnection(banco), new ClassPathResource("db/catalogo/R__catalogo.sql"));
	}

	@Test
	void rodarDuasVezesNaoDuplicaGemaNemEdicao() {
		rodarOCatalogo();
		var cartas = jdbc.sql("select count(*) from carta").query(Integer.class).single();
		var edicoes = jdbc.sql("select count(*) from edicao").query(Integer.class).single();

		rodarOCatalogo();

		assertThat(jdbc.sql("select count(*) from carta").query(Integer.class).single()).isEqualTo(cartas);
		assertThat(jdbc.sql("select count(*) from edicao").query(Integer.class).single()).isEqualTo(edicoes);
	}

	@Test
	void aVitrineMostraCadaGemaComAEdicaoMaisNova() {
		rodarOCatalogo();
		var auroita = jdbc.sql("select id from carta where nome = 'Auroíta'").query(Long.class).single();
		var segunda = estoque.novaEdicao(auroita, 10);

		var vitrine = estoque.vitrine();

		var item = vitrine.stream().filter(i -> i.carta().nome().equals("Auroíta")).findFirst().orElseThrow();
		assertThat(item.edicao().id()).isEqualTo(segunda.id());
		assertThat(item.edicao().numero()).isEqualTo(2);
		assertThat(item.carta().mohs()).isEqualTo(9);
		assertThat(item.carta().fluorescencia()).isEqualTo("aurora");
		var vidro = vitrine.stream().filter(i -> i.carta().nome().equals("Vidro-de-Eco")).findFirst().orElseThrow();
		assertThat(vidro.carta().fluorescencia()).isNull();
		assertThat(vidro.edicao().disponivel()).isEqualTo(10);
		// Lendárias primeiro, comuns por último
		assertThat(vitrine.getFirst().carta().raridade()).isEqualTo("lendaria");
		assertThat(vitrine.getLast().carta().raridade()).isEqualTo("comum");
	}

	@Test
	void umLancamentoEsgotadoNaoEscondeOQueSobrouDaEdicaoAnterior() {
		rodarOCatalogo();
		var lumenita = jdbc.sql("select id from carta where nome = 'Lumenita Oculta'").query(Long.class).single();
		var lancamento = estoque.novaEdicao(lumenita, 10);
		jdbc.sql("update edicao set disponivel = 0 where id = :id").param("id", lancamento.id()).update();

		var item = estoque.vitrine().stream().filter(i -> i.carta().id() == lumenita).findFirst().orElseThrow();
		assertThat(item.edicao().numero()).as("a 1ª ainda tem 10").isEqualTo(1);

		jdbc.sql("update edicao set disponivel = 0 where carta_id = :c").param("c", lumenita).update();
		item = estoque.vitrine().stream().filter(i -> i.carta().id() == lumenita).findFirst().orElseThrow();
		assertThat(item.edicao().numero()).as("tudo esgotado: mostra a mais nova").isEqualTo(2);
	}

	@Test
	void corForaDoPadraoERecusadaPeloBanco() {
		var erro = catchThrowable(() -> jdbc
			.sql("insert into carta (nome, raridade, cor) values ('X', 'comum', '<script>')")
			.update());

		assertThat(erro).isNotNull();
	}

}
