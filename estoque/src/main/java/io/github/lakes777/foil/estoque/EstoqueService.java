package io.github.lakes777.foil.estoque;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reserva e devolução de unidades. Toda a regra de "não vender a mais" está no SQL: o banco decide, numa
 * linha só, se ainda há unidade (P23). Nada de ler o disponível, conferir em Java e depois gravar.
 */
@Service
public class EstoqueService {

	private final JdbcClient jdbc;

	EstoqueService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public ResultadoDaReserva reservar(UUID pedidoId, long edicaoId) {
		if (buscarEdicao(edicaoId).isEmpty()) {
			return ResultadoDaReserva.EDICAO_INEXISTENTE;
		}
		// Primeiro marca o pedido: dois pedidos iguais ao mesmo tempo esperam um pelo outro aqui,
		// e o segundo encontra a linha do primeiro e recebe a mesma resposta
		var marcou = jdbc.sql("""
				insert into reserva (pedido_id, edicao_id, situacao) values (:pedido, :edicao, 'reservada')
				on conflict (pedido_id) do nothing
				""").param("pedido", pedidoId).param("edicao", edicaoId).update();
		if (marcou == 0) {
			return repetido(pedidoId);
		}
		// A reserva em si: zero linhas = acabou. O "disponivel > 0" é a regra; o check do banco é só a última trava
		var tirou = jdbc.sql("""
				update edicao set disponivel = disponivel - 1
				where id = :edicao and disponivel > 0
				""").param("edicao", edicaoId).update();
		if (tirou == 0) {
			jdbc.sql("update reserva set situacao = 'sem_estoque' where pedido_id = :pedido")
				.param("pedido", pedidoId)
				.update();
			return ResultadoDaReserva.SEM_ESTOQUE;
		}
		return ResultadoDaReserva.RESERVADA;
	}

	private ResultadoDaReserva repetido(UUID pedidoId) {
		var situacao = situacao(pedidoId).orElseThrow();
		return switch (situacao) {
			case "reservada" -> ResultadoDaReserva.JA_RESERVADA;
			case "devolvida" -> ResultadoDaReserva.JA_DEVOLVIDA;
			case "sem_estoque" -> ResultadoDaReserva.SEM_ESTOQUE;
			default -> throw new IllegalStateException("situação desconhecida: " + situacao);
		};
	}

	private Optional<String> situacao(UUID pedidoId) {
		return jdbc.sql("select situacao from reserva where pedido_id = :pedido")
			.param("pedido", pedidoId)
			.query(String.class)
			.optional();
	}

	@Transactional
	public ResultadoDaDevolucao devolver(UUID pedidoId) {
		// Só quem consegue mudar a situação devolve a unidade: duas devoluções juntas = uma só
		var edicaoId = jdbc.sql("""
				update reserva set situacao = 'devolvida', devolvida_em = now()
				where pedido_id = :pedido and situacao = 'reservada'
				returning edicao_id
				""").param("pedido", pedidoId).query(Long.class).optional();
		if (edicaoId.isEmpty()) {
			return situacao(pedidoId).filter("devolvida"::equals).isPresent() ? ResultadoDaDevolucao.JA_DEVOLVIDA
					: ResultadoDaDevolucao.SEM_RESERVA;
		}
		jdbc.sql("update edicao set disponivel = disponivel + 1 where id = :edicao")
			.param("edicao", edicaoId.get())
			.update();
		return ResultadoDaDevolucao.DEVOLVIDA;
	}

	@Transactional
	public Carta criarCarta(String nome, String raridade, String descricao) {
		return jdbc.sql("""
				insert into carta (nome, raridade, descricao) values (:nome, :raridade, :descricao)
				returning id, nome, raridade, descricao
				""")
			.param("nome", nome)
			.param("raridade", raridade)
			.param("descricao", descricao)
			.query(Carta.class)
			.single();
	}

	/** Edição nova da carta, com número seguinte ao da última (cada lançamento tem estoque próprio, P28). */
	@Transactional
	public Edicao novaEdicao(long cartaId, int total) {
		// Trava a carta: duas edições novas ao mesmo tempo não pegam o mesmo número
		jdbc.sql("select id from carta where id = :carta for update")
			.param("carta", cartaId)
			.query(Long.class)
			.optional()
			.orElseThrow(() -> new IllegalArgumentException("carta inexistente: " + cartaId));
		return jdbc.sql("""
				insert into edicao (carta_id, numero, total, disponivel)
				values (:carta, (select coalesce(max(numero), 0) + 1 from edicao where carta_id = :carta), :total, :total)
				returning id, carta_id, numero, total, disponivel, criada_em
				""").param("carta", cartaId).param("total", total).query(Edicao.class).single();
	}

	@Transactional(readOnly = true)
	public List<Carta> cartas() {
		return jdbc.sql("select id, nome, raridade, descricao from carta order by id").query(Carta.class).list();
	}

	@Transactional(readOnly = true)
	public boolean existeCarta(long cartaId) {
		return jdbc.sql("select exists (select 1 from carta where id = :carta)")
			.param("carta", cartaId)
			.query(Boolean.class)
			.single();
	}

	@Transactional(readOnly = true)
	public List<Edicao> edicoes(long cartaId) {
		return jdbc.sql("""
				select id, carta_id, numero, total, disponivel, criada_em
				from edicao where carta_id = :carta order by numero
				""").param("carta", cartaId).query(Edicao.class).list();
	}

	@Transactional(readOnly = true)
	public Optional<Edicao> buscarEdicao(long edicaoId) {
		return jdbc.sql("""
				select id, carta_id, numero, total, disponivel, criada_em from edicao where id = :id
				""").param("id", edicaoId).query(Edicao.class).optional();
	}

}
