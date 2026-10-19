package io.github.lakes777.foil.pagamentos;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido.Forma;

/**
 * Uma cobrança por pedido, simulada ou no Fiel. A simulada (P10: os robôs do lançamento) é sorteada, mas sempre
 * com o mesmo resultado para o mesmo pedido: depende só do id dele. A do Fiel (a compra manual) só fica anotada
 * aqui: quem fala com o Fiel é o {@link TrabalhoNoFiel}, e as respostas dela chegam depois (V5).
 */
@Service
public class Cobrancas {

	private static final Logger log = LoggerFactory.getLogger(Cobrancas.class);

	public enum Resultado {

		APROVADA, RECUSADA, JA_CANCELADA,

		/** Só no Fiel: a resposta vem depois, quando o visitante pagar (ou o prazo vencer). */
		PENDENTE

	}

	public enum Cancelamento {

		/** Não havia nada aprovado: a cobrança fica cancelada (e proibida, se ainda não existia). */
		CANCELADA,

		/** Já estava aprovada: o dinheiro de mentira volta. */
		ESTORNADA,

		/** Só no Fiel: o cancelamento ou o estorno estão a caminho, e a resposta vem quando terminarem. */
		EM_ANDAMENTO

	}

	private record Linha(String situacao, String forma, Long fielId) {

		boolean noFiel() {
			return "fiel".equals(forma);
		}

	}

	private final JdbcClient jdbc;

	private final double taxaDeAprovacao;

	private final ClienteDoFiel fiel;

	Cobrancas(JdbcClient jdbc, @Value("${foil.pagamentos.simulado.taxa-de-aprovacao:0.8}") double taxaDeAprovacao,
			ClienteDoFiel fiel) {
		this.jdbc = jdbc;
		this.taxaDeAprovacao = taxaDeAprovacao;
		this.fiel = fiel;
	}

	/** O sorteio do pedido: a mesma resposta toda vez, sem guardar semente. */
	boolean aprovaria(UUID pedidoId) {
		return Math.floorMod(pedidoId.hashCode(), 100) < Math.round(taxaDeAprovacao * 100);
	}

	@Transactional
	public Resultado cobrar(UUID pedidoId, int valorCentavos, Forma forma) {
		String situacao;
		if (forma == Forma.SIMULADA) {
			situacao = aprovaria(pedidoId) ? "aprovada" : "recusada";
		}
		else if (fiel.ligado()) {
			situacao = "pendente";
		}
		else {
			// Sem endereço ou chave do Fiel (desenvolvimento): recusar agora é melhor do que esperar o prazo
			log.warn("pagamentos: pedido {} é do Fiel, mas a integração está desligada; recusado", pedidoId);
			situacao = "recusada";
		}
		var gravou = jdbc.sql("""
				insert into cobranca (pedido_id, valor_centavos, situacao, forma, proxima_em)
				values (:pedido, :valor, :situacao, :forma, case when :situacao = 'pendente' then now() end)
				on conflict (pedido_id) do nothing
				""")
			.param("pedido", pedidoId)
			.param("valor", valorCentavos)
			.param("situacao", situacao)
			.param("forma", forma == Forma.FIEL ? "fiel" : "simulada")
			.update();
		if (gravou == 0) {
			// Repetido: responde pelo que está gravado
			situacao = situacao(pedidoId).orElseThrow();
		}
		return switch (situacao) {
			case "aprovada" -> Resultado.APROVADA;
			case "recusada" -> Resultado.RECUSADA;
			case "pendente" -> Resultado.PENDENTE;
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
		var c = jdbc.sql("select situacao, forma, fiel_id from cobranca where pedido_id = :pedido for update")
			.param("pedido", pedidoId)
			.query(Linha.class)
			.single();
		return switch (c.situacao()) {
			case "aprovada" -> {
				if (c.noFiel()) {
					mudar(pedidoId, "estornando");
					yield Cancelamento.EM_ANDAMENTO;
				}
				mudar(pedidoId, "estornada");
				yield Cancelamento.ESTORNADA;
			}
			case "pendente" -> {
				if (c.fielId() != null) {
					mudar(pedidoId, "cancelando");
					yield Cancelamento.EM_ANDAMENTO;
				}
				// Ainda não existe no Fiel: o trabalhador vê "cancelada" e não cria (ou, se já estava criando, cancela)
				mudar(pedidoId, "cancelada");
				yield Cancelamento.CANCELADA;
			}
			case "recusada" -> {
				mudar(pedidoId, "cancelada");
				yield Cancelamento.CANCELADA;
			}
			case "cancelando", "estornando" -> Cancelamento.EM_ANDAMENTO;
			case "estornada" -> Cancelamento.ESTORNADA;
			default -> Cancelamento.CANCELADA;
		};
	}

	/** Cancelando e estornando voltam a ser trabalho para agora, com as tentativas do zero. */
	private void mudar(UUID pedidoId, String situacao) {
		jdbc.sql("""
				update cobranca set situacao = :situacao, tentativas = 0, atualizada_em = now(),
				proxima_em = case when :situacao in ('cancelando', 'estornando') then now() end
				where pedido_id = :pedido
				""").param("situacao", situacao).param("pedido", pedidoId).update();
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
