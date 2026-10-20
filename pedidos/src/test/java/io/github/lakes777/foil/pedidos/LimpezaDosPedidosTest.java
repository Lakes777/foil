package io.github.lakes777.foil.pedidos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.lakes777.foil.comum.eventos.Limpeza;

/** O que a limpeza das 4h10 apaga em pedidos, com as linhas gravadas direto no banco e datas no passado. */
@TesteHttpDePedidos
class LimpezaDosPedidosTest {

	@Autowired
	Limpeza limpeza;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transacao;

	/** Uma edição só deste teste: a unidade dos confirmados é única por edição. */
	private final long edicao = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);

	private int unidades;

	private UUID pedido(String estado, UUID visitante, UUID lancamento, String ha) {
		var id = UUID.randomUUID();
		var confirmado = estado.equals("CONFIRMADO");
		var emAndamento = estado.equals("AGUARDANDO_PAGAMENTO");
		jdbc.sql("""
				insert into pedido (id, edicao_id, estado, prazo, visitante, lancamento_id, unidade, criado_em, atualizado_em)
				values (:id, :edicao, :estado, case when :andamento then now() end, :visitante, :lancamento, :unidade,
				        now() - cast(:ha as interval), now() - cast(:ha as interval))
				""")
			.param("id", id)
			.param("edicao", edicao)
			.param("estado", estado)
			.param("andamento", emAndamento)
			.param("visitante", visitante)
			.param("lancamento", lancamento)
			.param("unidade", confirmado ? ++unidades : null)
			.param("ha", ha)
			.update();
		return id;
	}

	private UUID lancamento(String terminou) {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into lancamento (id, estado, robos, pedido_em, terminou_em)
				values (:id, 'ENCERRADO', 200, now() - interval '40 days', """ + terminou + ")")
			.param("id", id)
			.update();
		return id;
	}

	private boolean existe(String tabela, UUID id) {
		return jdbc.sql("select count(*) from " + tabela + " where id = :id").param("id", id).query(Long.class).single() == 1;
	}

	@Test
	void robosRecusadosSaemCedoEVendasEVisitantesFicamOPrazoDasCompras() {
		// Numa transação desfeita no fim: nada fica no banco para a agenda dos prazos dos outros testes
		transacao.executeWithoutResult(t -> {
			var visitante = UUID.randomUUID();
			var lancamento = lancamento("now() - interval '5 days'");
			var roboCancelado = pedido("CANCELADO", null, lancamento, "5 days");
			var roboSemEstoque = pedido("SEM_ESTOQUE", null, lancamento, "5 days");
			var roboRecente = pedido("CANCELADO", null, lancamento, "2 days");
			// Venda dos robôs: fica, senão a próxima venda da edição repetiria o número da unidade
			var roboConfirmado = pedido("CONFIRMADO", null, lancamento, "5 days");
			var doVisitante = pedido("CANCELADO", visitante, null, "5 days");
			var compraVelha = pedido("CONFIRMADO", visitante, null, "31 days");
			var roboConfirmadoVelho = pedido("CONFIRMADO", null, lancamento, "31 days");
			var doVisitanteVelho = pedido("CANCELADO", visitante, null, "31 days");
			var emAndamento = pedido("AGUARDANDO_PAGAMENTO", null, null, "60 days");

			limpeza.limpar();

			assertThat(existe("pedido", roboCancelado)).isFalse();
			assertThat(existe("pedido", roboSemEstoque)).isFalse();
			assertThat(existe("pedido", roboRecente)).isTrue();
			assertThat(existe("pedido", roboConfirmado)).isTrue();
			assertThat(existe("pedido", doVisitante)).isTrue();
			assertThat(existe("pedido", compraVelha)).isFalse();
			assertThat(existe("pedido", roboConfirmadoVelho)).isFalse();
			assertThat(existe("pedido", doVisitanteVelho)).isFalse();
			assertThat(existe("pedido", emAndamento)).isTrue();
			// Ainda tem a venda dos robôs
			assertThat(existe("lancamento", lancamento)).isTrue();
			t.setRollbackOnly();
		});
	}

	@Test
	void lancamentoTerminadoSaiQuandoNaoTemPedidosMasOsTresUltimosFicam() {
		// Numa transação desfeita no fim: para os 3 últimos do banco serem velhos, os dos outros testes envelhecem junto
		transacao.executeWithoutResult(t -> {
			var velho = lancamento("now() - interval '15 days'");
			var comVenda = lancamento("now() - interval '15 days'");
			pedido("CONFIRMADO", null, comVenda, "1 day");
			jdbc.sql("update lancamento set terminou_em = now() - interval '20 days' where estado in ('ENCERRADO', 'FALHOU')"
					+ " and id not in (:ids)").param("ids", List.of(velho, comVenda)).update();
			var ultimos = new ArrayList<UUID>();
			for (var i = 1; i <= 3; i++) {
				ultimos.add(lancamento("now() - interval '10 days' - interval '" + i + " hours'"));
			}

			new LimpezaDosPedidos().aplicar(limpeza);

			assertThat(existe("lancamento", velho)).isFalse();
			assertThat(existe("lancamento", comVenda)).isTrue();
			assertThat(ultimos).allMatch(id -> existe("lancamento", id));
			t.setRollbackOnly();
		});
	}

}
