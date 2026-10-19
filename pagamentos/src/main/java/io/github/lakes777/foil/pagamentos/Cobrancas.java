package io.github.lakes777.foil.pagamentos;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cobranças simuladas (P10: os robôs do lançamento não passam pelo Fiel; a compra manual passa, na fase 5).
 * O resultado é sorteado, mas sempre o mesmo para o mesmo pedido: depende só do id dele.
 */
@Service
public class Cobrancas {

	public enum Resultado {

		APROVADA, RECUSADA, JA_CANCELADA

	}

	public enum Cancelamento {

		/** Não havia nada aprovado: a cobrança fica cancelada (e proibida, se ainda não existia). */
		CANCELADA,

		/** Já estava aprovada: o dinheiro de mentira volta. */
		ESTORNADA

	}

	private final JdbcClient jdbc;

	private final double taxaDeAprovacao;

	Cobrancas(JdbcClient jdbc, @Value("${foil.pagamentos.simulado.taxa-de-aprovacao:0.8}") double taxaDeAprovacao) {
		this.jdbc = jdbc;
		this.taxaDeAprovacao = taxaDeAprovacao;
	}

	/** O sorteio do pedido: a mesma resposta toda vez, sem guardar semente. */
	boolean aprovaria(UUID pedidoId) {
		return Math.floorMod(pedidoId.hashCode(), 100) < Math.round(taxaDeAprovacao * 100);
	}

	@Transactional
	public Resultado cobrar(UUID pedidoId, int valorCentavos) {
		var situacao = aprovaria(pedidoId) ? "aprovada" : "recusada";
		var gravou = jdbc.sql("""
				insert into cobranca (pedido_id, valor_centavos, situacao) values (:pedido, :valor, :situacao)
				on conflict (pedido_id) do nothing
				""").param("pedido", pedidoId).param("valor", valorCentavos).param("situacao", situacao).update();
		if (gravou == 0) {
			// Repetido: responde pelo que está gravado
			situacao = situacao(pedidoId).orElseThrow();
		}
		return switch (situacao) {
			case "aprovada" -> Resultado.APROVADA;
			case "recusada" -> Resultado.RECUSADA;
			default -> Resultado.JA_CANCELADA;
		};
	}

	@Transactional
	public Cancelamento cancelar(UUID pedidoId) {
		// Ainda não cobrado: grava a trava, e um CobrarPedido atrasado encontra "cancelada"
		var travou = jdbc.sql("""
				insert into cobranca (pedido_id, situacao) values (:pedido, 'cancelada')
				on conflict (pedido_id) do nothing
				""").param("pedido", pedidoId).update();
		if (travou == 1) {
			return Cancelamento.CANCELADA;
		}
		var estornou = jdbc.sql("""
				update cobranca set situacao = 'estornada', atualizada_em = now()
				where pedido_id = :pedido and situacao = 'aprovada'
				""").param("pedido", pedidoId).update();
		if (estornou == 1) {
			return Cancelamento.ESTORNADA;
		}
		jdbc.sql("""
				update cobranca set situacao = 'cancelada', atualizada_em = now()
				where pedido_id = :pedido and situacao = 'recusada'
				""").param("pedido", pedidoId).update();
		return situacao(pedidoId).filter("estornada"::equals).isPresent() ? Cancelamento.ESTORNADA
				: Cancelamento.CANCELADA;
	}

	@Transactional(readOnly = true)
	public Optional<String> situacao(UUID pedidoId) {
		return jdbc.sql("select situacao from cobranca where pedido_id = :pedido")
			.param("pedido", pedidoId)
			.query(String.class)
			.optional();
	}

	@Transactional(readOnly = true)
	public Optional<Integer> valor(UUID pedidoId) {
		return jdbc.sql("select valor_centavos from cobranca where pedido_id = :pedido")
			.param("pedido", pedidoId)
			.query(Integer.class)
			.optional();
	}

}
