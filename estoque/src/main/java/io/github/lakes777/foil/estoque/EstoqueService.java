package io.github.lakes777.foil.estoque;

import java.time.OffsetDateTime;
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

	/** A edição gravada na reserva do pedido (a que vale, mesmo que um comando repetido traga outra). */
	@Transactional(readOnly = true)
	public Optional<Long> edicaoDaReserva(UUID pedidoId) {
		return jdbc.sql("select edicao_id from reserva where pedido_id = :pedido")
			.param("pedido", pedidoId)
			.query(Long.class)
			.optional();
	}

	private Optional<String> situacao(UUID pedidoId) {
		return jdbc.sql("select situacao from reserva where pedido_id = :pedido")
			.param("pedido", pedidoId)
			.query(String.class)
			.optional();
	}

	@Transactional
	public ResultadoDaDevolucao devolver(UUID pedidoId, long edicaoId) {
		// Só quem consegue mudar a situação devolve a unidade: duas devoluções juntas = uma só
		var devolvida = jdbc.sql("""
				update reserva set situacao = 'devolvida', devolvida_em = now()
				where pedido_id = :pedido and situacao = 'reservada'
				returning edicao_id
				""").param("pedido", pedidoId).query(Long.class).optional();
		if (devolvida.isEmpty()) {
			if (situacao(pedidoId).filter("devolvida"::equals).isPresent()) {
				return ResultadoDaDevolucao.JA_DEVOLVIDA;
			}
			// Ainda sem reserva (a reserva pode chegar fora de ordem, reprocessada da fila de mortos): grava a
			// devolução como trava, e a reserva atrasada recebe JA_DEVOLVIDA. Edição inexistente não precisa de trava.
			jdbc.sql("""
					insert into reserva (pedido_id, edicao_id, situacao, devolvida_em)
					select :pedido, id, 'devolvida', now() from edicao where id = :edicao
					on conflict (pedido_id) do nothing
					""").param("pedido", pedidoId).param("edicao", edicaoId).update();
			return ResultadoDaDevolucao.SEM_RESERVA;
		}
		jdbc.sql("update edicao set disponivel = disponivel + 1 where id = :edicao")
			.param("edicao", devolvida.get())
			.update();
		return ResultadoDaDevolucao.DEVOLVIDA;
	}

	@Transactional
	public Carta criarCarta(String nome, String raridade, String descricao) {
		return jdbc.sql("""
				insert into carta (nome, raridade, descricao) values (:nome, :raridade, :descricao)
				returning id, nome, raridade, descricao, mohs, cor, fluorescencia
				""")
			.param("nome", nome)
			.param("raridade", raridade)
			.param("descricao", descricao)
			.query(Carta.class)
			.single();
	}

	public static final int PRECO_PADRAO = 1000;

	@Transactional
	public Edicao novaEdicao(long cartaId, int total) {
		return novaEdicao(cartaId, total, PRECO_PADRAO);
	}

	/** Edição nova da carta, com número seguinte ao da última (cada lançamento tem estoque próprio, P28). */
	@Transactional
	public Edicao novaEdicao(long cartaId, int total, int precoCentavos) {
		// Trava a carta: duas edições novas ao mesmo tempo não pegam o mesmo número
		jdbc.sql("select id from carta where id = :carta for update")
			.param("carta", cartaId)
			.query(Long.class)
			.optional()
			.orElseThrow(() -> new IllegalArgumentException("carta inexistente: " + cartaId));
		return jdbc.sql("""
				insert into edicao (carta_id, numero, total, disponivel, preco_centavos)
				values (:carta, (select coalesce(max(numero), 0) + 1 from edicao where carta_id = :carta), :total, :total,
				        :preco)
				returning id, carta_id, numero, total, disponivel, preco_centavos, criada_em
				""").param("carta", cartaId)
			.param("total", total)
			.param("preco", precoCentavos)
			.query(Edicao.class)
			.single();
	}

	/**
	 * A edição do lançamento (fase 6). O estoque escolhe a carta: entre as raras e lendárias, a que teve menos
	 * edições (empate: a mais antiga), para os lançamentos irem passando pelas cartas. Vazio = não há carta rara.
	 * O mesmo lançamento de novo devolve a edição que já abriu.
	 */
	@Transactional
	public Optional<Edicao> abrirEdicaoDoLancamento(UUID lancamentoId, int unidades) {
		var jaAberta = edicaoDoLancamento(lancamentoId);
		if (jaAberta.isPresent()) {
			return jaAberta;
		}
		var carta = jdbc.sql("""
				select c.id from carta c where c.raridade in ('rara', 'lendaria')
				order by (select count(*) from edicao e where e.carta_id = c.id), c.id limit 1
				""").query(Long.class).optional();
		if (carta.isEmpty()) {
			return Optional.empty();
		}
		var edicao = novaEdicao(carta.get(), unidades);
		jdbc.sql("update edicao set lancamento_id = :lancamento where id = :id")
			.param("lancamento", lancamentoId)
			.param("id", edicao.id())
			.update();
		return Optional.of(edicao);
	}

	private Optional<Edicao> edicaoDoLancamento(UUID lancamentoId) {
		return jdbc.sql("""
				select id, carta_id, numero, total, disponivel, preco_centavos, criada_em from edicao
				where lancamento_id = :lancamento
				""").param("lancamento", lancamentoId).query(Edicao.class).optional();
	}

	@Transactional(readOnly = true)
	public List<Carta> cartas() {
		return jdbc.sql("select id, nome, raridade, descricao, mohs, cor, fluorescencia from carta order by id")
			.query(Carta.class)
			.list();
	}

	/**
	 * Uma gema da vitrine com a edição à venda: a mais nova que ainda tem unidades; se todas esgotaram, a mais nova.
	 * Sem isso, um lançamento (que abre uma edição nova e esgota) escondia o que sobrou da edição anterior.
	 */
	public record ItemDaVitrine(Carta carta, Edicao edicao) {
	}

	@Transactional(readOnly = true)
	public List<ItemDaVitrine> vitrine() {
		return jdbc.sql("""
				select c.id, c.nome, c.raridade, c.descricao, c.mohs, c.cor, c.fluorescencia,
				       e.id as e_id, e.carta_id, e.numero, e.total, e.disponivel, e.preco_centavos, e.criada_em
				from carta c
				join lateral (select * from edicao where carta_id = c.id order by disponivel > 0 desc, numero desc limit 1)
				    e on true
				order by case c.raridade when 'lendaria' then 0 when 'rara' then 1 when 'incomum' then 2 else 3 end, c.id
				""")
			.query((rs, i) -> new ItemDaVitrine(
					new Carta(rs.getLong("id"), rs.getString("nome"), rs.getString("raridade"), rs.getString("descricao"),
							rs.getInt("mohs"), rs.getString("cor"), rs.getString("fluorescencia")),
					new Edicao(rs.getLong("e_id"), rs.getLong("carta_id"), rs.getInt("numero"), rs.getInt("total"),
							rs.getInt("disponivel"), rs.getInt("preco_centavos"),
							rs.getObject("criada_em", OffsetDateTime.class).toInstant())))
			.list();
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
				select id, carta_id, numero, total, disponivel, preco_centavos, criada_em
				from edicao where carta_id = :carta order by numero
				""").param("carta", cartaId).query(Edicao.class).list();
	}

	@Transactional(readOnly = true)
	public Optional<Edicao> buscarEdicao(long edicaoId) {
		return jdbc.sql("""
				select id, carta_id, numero, total, disponivel, preco_centavos, criada_em from edicao where id = :id
				""").param("id", edicaoId).query(Edicao.class).optional();
	}

}
