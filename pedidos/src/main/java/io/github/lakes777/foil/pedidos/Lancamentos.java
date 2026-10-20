package io.github.lakes777.foil.pedidos;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.lakes777.foil.comum.eventos.Saida;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.EstoqueComandos.AbrirEdicao;
import io.github.lakes777.foil.contratos.EstoqueEventos.EdicaoAberta;
import io.github.lakes777.foil.contratos.EstoqueEventos.EdicaoRecusada;
import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido.Forma;
import io.github.lakes777.foil.contratos.PedidosEventos.LancamentoMudouDeEstado;
import io.github.lakes777.foil.contratos.Topicos;
import tools.jackson.databind.json.JsonMapper;

/**
 * O "Simular lançamento" (P7, P27): uma edição nova de carta rara e ~200 compradores-robôs contra ela, com pagamento
 * simulado (P10). Um lançamento por vez no site inteiro; os outros esperam na fila, em ordem de chegada.
 *
 * <pre>
 * NA_FILA ──vez dele──> ABRINDO (AbrirEdicao) ──EdicaoAberta──> SOLTANDO ──todos soltos──> ESPERANDO ──sagas no fim──> ENCERRADO
 *                         │  └──prazo: reenvia AbrirEdicao                                    └──prazo──> ENCERRADO (motivo PRAZO)
 *                         └──EdicaoRecusada, ou {@value #ABERTURAS_MAXIMAS} AbrirEdicao sem resposta──> FALHOU
 * </pre>
 *
 * Os robôs são pedidos comuns da {@link Saga} (forma SIMULADA), soltos no ritmo configurado pela
 * {@link AgendaDosLancamentos}. Quantos já saíram fica gravado na mesma transação que os cria.
 */
@Service
public class Lancamentos {

	private static final Logger log = LoggerFactory.getLogger(Lancamentos.class);

	/** Teto de robôs criados numa rodada: depois de uma parada longa, o atraso volta aos poucos, sem rajada. */
	static final int ROBOS_POR_RODADA = 50;

	/**
	 * Quantos AbrirEdicao sem resposta antes de desistir ({@code motivo = ESTOQUE_SEM_RESPOSTA}): sem teto, um
	 * EdicaoAberta que sempre cai na fila de mortos deixaria o lançamento em ABRINDO e a fila parada para sempre.
	 */
	static final int ABERTURAS_MAXIMAS = 5;

	public enum EstadoDoLancamento {

		NA_FILA, ABRINDO, SOLTANDO, ESPERANDO, ENCERRADO, FALHOU

	}

	/** Como o lançamento vai: os pedidos dos robôs contados pelo estado da saga. */
	public record Resumo(UUID id, EstadoDoLancamento estado, String motivo, Long edicaoId, int robos, int soltos,
			Integer posicaoNaFila, Instant pedidoEm, Instant comecouEm, Instant terminouEm, int vendidos,
			int semEstoque, int desfeitos, int emAndamento) {

	}

	/** O que o painel mostra: o lançamento da vez, a fila e os últimos que terminaram. */
	public record Situacao(Resumo atual, List<Resumo> fila, List<Resumo> recentes) {

	}

	private record Linha(UUID id, EstadoDoLancamento estado, String motivo, Long edicaoId, int robos, int soltos,
			Instant prazo, Instant pedidoEm, Instant comecouEm, Instant terminouEm, long ordem, int aberturas) {

	}

	private record Contagem(Estado estado, int quantos) {

	}

	private static final String COLUNAS = """
			select id, estado, motivo, edicao_id, robos, soltos, prazo, pedido_em, comecou_em, terminou_em, ordem,
			aberturas from lancamento
			""";

	private final JdbcClient jdbc;

	private final Saida saida;

	private final JsonMapper json;

	private final Clock relogio;

	private final Saga saga;

	private final int robos;

	private final int unidades;

	private final double robosPorSegundo;

	private final int filaMaxima;

	private final Duration prazoDaAbertura;

	private final Duration prazoDaEspera;

	Lancamentos(JdbcClient jdbc, Saida saida, JsonMapper json, Clock relogio, Saga saga,
			@Value("${foil.lancamento.robos:200}") int robos, @Value("${foil.lancamento.unidades:10}") int unidades,
			@Value("${foil.lancamento.robos-por-segundo:10}") double robosPorSegundo,
			@Value("${foil.lancamento.fila-maxima:5}") int filaMaxima,
			@Value("${foil.lancamento.prazo-da-abertura:1m}") Duration prazoDaAbertura,
			@Value("${foil.lancamento.prazo-da-espera:10m}") Duration prazoDaEspera) {
		this.jdbc = jdbc;
		this.saida = saida;
		this.json = json;
		this.relogio = relogio;
		this.saga = saga;
		this.robos = robos;
		this.unidades = unidades;
		this.robosPorSegundo = robosPorSegundo;
		this.filaMaxima = filaMaxima;
		this.prazoDaAbertura = prazoDaAbertura;
		this.prazoDaEspera = prazoDaEspera;
	}

	/**
	 * Põe um lançamento na fila. Mais que {@code filaMaxima} esperando = {@link Recusas.Lotado}: com um lançamento a
	 * cada ~1 min, uma fila maior seria espera de vários minutos para quem está na ponta.
	 */
	@Transactional
	public Resumo pedir() {
		// Um pedido de lançamento por vez: a contagem da fila e a inserção não se cruzam com outro pedido
		travarAFila();
		var naFila = jdbc.sql("select count(*) from lancamento where estado = 'NA_FILA'").query(Integer.class).single();
		if (naFila >= filaMaxima) {
			throw new Recusas.Lotado("a fila de lançamentos está cheia (" + filaMaxima + "); tente daqui a pouco");
		}
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into lancamento (id, estado, robos, pedido_em) values (:id, 'NA_FILA', :robos, :agora)
				""").param("id", id).param("robos", robos).param("agora", agora()).update();
		contar(travar(id).orElseThrow());
		return buscar(id).orElseThrow();
	}

	/**
	 * Uma rodada: anda com o lançamento da vez ou, se não há nenhum, chama o próximo da fila. A agenda chama a cada
	 * ~100 ms; tudo o que muda fica numa transação, então uma queda no meio não deixa robô pela metade.
	 */
	@Transactional
	public void avancar() {
		// Com duas instâncias, as duas veriam "nenhum ativo" e chamariam o próximo juntas (uma leva erro do índice
		// único); com a trava, uma rodada espera a outra
		travarAFila();
		var ativo = jdbc.sql(COLUNAS + "where estado in ('ABRINDO', 'SOLTANDO', 'ESPERANDO') for update")
			.query(Linha.class)
			.optional();
		if (ativo.isEmpty()) {
			chamarOProximo();
			return;
		}
		var l = ativo.get();
		switch (l.estado()) {
			case ABRINDO -> {
				if (vencido(l) && l.aberturas() >= ABERTURAS_MAXIMAS) {
					log.warn("lançamento {}: estoque não respondeu a {} AbrirEdicao; desistindo", l.id(), l.aberturas());
					encerrar(l, EstadoDoLancamento.FALHOU, "ESTOQUE_SEM_RESPOSTA");
				}
				else if (vencido(l)) {
					log.warn("lançamento {}: estoque não respondeu; reenviando AbrirEdicao", l.id());
					jdbc.sql("update lancamento set prazo = :prazo, atualizado_em = now() where id = :id")
						.param("prazo", daquiA(prazoDaAbertura))
						.param("id", l.id())
						.update();
					pedirEdicao(l.id());
				}
			}
			case SOLTANDO -> soltar(l);
			case ESPERANDO -> {
				if (emAndamento(l.id()) == 0) {
					encerrar(l, EstadoDoLancamento.ENCERRADO, null);
				}
				else if (vencido(l)) {
					// As sagas que faltam terminam sozinhas pelos próprios prazos; a fila não espera por elas
					log.warn("lançamento {}: encerrado pelo prazo com {} pedidos ainda andando", l.id(),
							emAndamento(l.id()));
					encerrar(l, EstadoDoLancamento.ENCERRADO, "PRAZO");
				}
			}
			default -> throw new IllegalStateException("lançamento final como ativo: " + l.id());
		}
	}

	private void chamarOProximo() {
		var proximo = jdbc.sql(COLUNAS + "where estado = 'NA_FILA' order by ordem limit 1 for update")
			.query(Linha.class)
			.optional();
		if (proximo.isEmpty()) {
			return;
		}
		var l = proximo.get();
		jdbc.sql("""
				update lancamento set estado = 'ABRINDO', prazo = :prazo, atualizado_em = now() where id = :id
				""").param("prazo", daquiA(prazoDaAbertura)).param("id", l.id()).update();
		pedirEdicao(l.id());
		contar(travar(l.id()).orElseThrow());
	}

	private void travarAFila() {
		jdbc.sql("select pg_advisory_xact_lock(hashtext('foil.lancamento.fila'))").query((rs, i) -> 1).single();
	}

	private void pedirEdicao(UUID lancamento) {
		jdbc.sql("update lancamento set aberturas = aberturas + 1 where id = :id").param("id", lancamento).update();
		mandar(Topicos.ESTOQUE_COMANDOS, lancamento, AbrirEdicao.TIPO, AbrirEdicao.VERSAO, new AbrirEdicao(unidades));
	}

	/**
	 * Solta os robôs que já deviam ter saído pelo ritmo (contado desde o começo, e não por rodada: uma rodada atrasada
	 * não muda o ritmo do lançamento), no máximo {@link #ROBOS_POR_RODADA} por vez.
	 */
	private void soltar(Linha l) {
		var passados = Duration.between(l.comecouEm(), relogio.instant()).toMillis() / 1000.0;
		var devidos = (int) Math.min(l.robos(), Math.floor(passados * robosPorSegundo) + 1);
		var agora = Math.min(devidos - l.soltos(), ROBOS_POR_RODADA);
		for (int i = 0; i < agora; i++) {
			saga.criar(l.edicaoId(), Forma.SIMULADA, l.id());
		}
		var soltos = l.soltos() + Math.max(agora, 0);
		if (soltos == l.robos()) {
			jdbc.sql("""
					update lancamento set soltos = :soltos, estado = 'ESPERANDO', prazo = :prazo, atualizado_em = now()
					where id = :id
					""").param("soltos", soltos).param("prazo", daquiA(prazoDaEspera)).param("id", l.id()).update();
			contar(travar(l.id()).orElseThrow());
		}
		else if (agora > 0) {
			jdbc.sql("update lancamento set soltos = :soltos, atualizado_em = now() where id = :id")
				.param("soltos", soltos)
				.param("id", l.id())
				.update();
		}
	}

	/** Resposta do estoque ao AbrirEdicao (o {@code pedidoId} do envelope é o id do lançamento). */
	@Transactional
	public void aoReceber(Envelope evento) {
		var lancamento = travar(evento.pedidoId());
		if (lancamento.isEmpty()) {
			log.warn("lançamento: {} de lançamento desconhecido {}", evento.tipo(), evento.pedidoId());
			return;
		}
		var l = lancamento.get();
		switch (evento.tipo()) {
			case EdicaoAberta.TIPO -> {
				var dados = dados(evento, EdicaoAberta.class, EdicaoAberta.VERSAO);
				if (l.estado() == EstadoDoLancamento.ABRINDO) {
					jdbc.sql("""
							update lancamento set estado = 'SOLTANDO', edicao_id = :edicao, comecou_em = :agora,
							prazo = null, atualizado_em = now() where id = :id
							""").param("edicao", dados.edicaoId()).param("agora", agora()).param("id", l.id()).update();
					contar(travar(l.id()).orElseThrow());
				}
				else {
					log.info("lançamento {} em {} ignorou {} (repetido)", l.id(), l.estado(), evento.tipo());
				}
			}
			case EdicaoRecusada.TIPO -> {
				var dados = dados(evento, EdicaoRecusada.class, EdicaoRecusada.VERSAO);
				if (l.estado() == EstadoDoLancamento.ABRINDO) {
					encerrar(l, EstadoDoLancamento.FALHOU, dados.motivo().name());
				}
				else {
					log.info("lançamento {} em {} ignorou {} (repetido)", l.id(), l.estado(), evento.tipo());
				}
			}
			default -> throw new IllegalArgumentException("evento desconhecido: " + evento.tipo());
		}
	}

	private void encerrar(Linha l, EstadoDoLancamento fim, String motivo) {
		jdbc.sql("""
				update lancamento set estado = :estado, motivo = :motivo, prazo = null, terminou_em = :agora,
				atualizado_em = now() where id = :id
				""").param("estado", fim.name()).param("motivo", motivo).param("agora", agora()).param("id", l.id())
			.update();
		contar(travar(l.id()).orElseThrow());
	}

	@Transactional(readOnly = true)
	public Optional<Resumo> buscar(UUID id) {
		return jdbc.sql(COLUNAS + "where id = :id").param("id", id).query(Linha.class).optional().map(this::resumir);
	}

	@Transactional(readOnly = true)
	public Situacao situacao() {
		var atual = jdbc.sql(COLUNAS + "where estado in ('ABRINDO', 'SOLTANDO', 'ESPERANDO')")
			.query(Linha.class)
			.optional()
			.map(this::resumir)
			.orElse(null);
		var fila = jdbc.sql(COLUNAS + "where estado = 'NA_FILA' order by ordem").query(Linha.class).list();
		var recentes = jdbc.sql(COLUNAS + "where estado in ('ENCERRADO', 'FALHOU') order by terminou_em desc limit 3")
			.query(Linha.class)
			.list();
		return new Situacao(atual, fila.stream().map(this::resumir).toList(),
				recentes.stream().map(this::resumir).toList());
	}

	private Resumo resumir(Linha l) {
		var contagens = jdbc.sql("select estado, count(*) as quantos from pedido where lancamento_id = :id group by estado")
			.param("id", l.id())
			.query(Contagem.class)
			.list();
		int vendidos = 0, semEstoque = 0, desfeitos = 0, emAndamento = 0;
		for (var c : contagens) {
			switch (c.estado()) {
				case CONFIRMADO -> vendidos = c.quantos();
				case SEM_ESTOQUE -> semEstoque = c.quantos();
				case CANCELADO -> desfeitos = c.quantos();
				default -> emAndamento += c.quantos();
			}
		}
		Integer posicao = null;
		if (l.estado() == EstadoDoLancamento.NA_FILA) {
			posicao = jdbc.sql("select count(*) from lancamento where estado = 'NA_FILA' and ordem <= :ordem")
				.param("ordem", l.ordem())
				.query(Integer.class)
				.single();
		}
		return new Resumo(l.id(), l.estado(), l.motivo(), l.edicaoId(), l.robos(), l.soltos(), posicao, l.pedidoEm(),
				l.comecouEm(), l.terminouEm(), vendidos, semEstoque, desfeitos, emAndamento);
	}

	private int emAndamento(UUID lancamento) {
		return jdbc.sql("""
				select count(*) from pedido where lancamento_id = :id
				and estado in ('RESERVANDO', 'AGUARDANDO_PAGAMENTO', 'LIBERANDO')
				""").param("id", lancamento).query(Integer.class).single();
	}

	private Optional<Linha> travar(UUID id) {
		return jdbc.sql(COLUNAS + "where id = :id for update").param("id", id).query(Linha.class).optional();
	}

	private boolean vencido(Linha l) {
		return l.prazo() != null && l.prazo().isBefore(relogio.instant());
	}

	/** Cada passo vai para pedidos.eventos (o painel ao vivo). */
	private void contar(Linha l) {
		mandar(Topicos.PEDIDOS_EVENTOS, l.id(), LancamentoMudouDeEstado.TIPO, LancamentoMudouDeEstado.VERSAO,
				new LancamentoMudouDeEstado(l.estado().name(), l.motivo(), l.edicaoId(), l.robos(), l.soltos()));
	}

	private void mandar(String topico, UUID lancamento, String tipo, int versao, Object dados) {
		saida.registrar(topico, Envelope.novo(tipo, versao, lancamento, json.valueToTree(dados), relogio));
	}

	/** OffsetDateTime: o driver do Postgres não sabe gravar Instant como parâmetro. */
	private OffsetDateTime daquiA(Duration prazo) {
		return agora().plus(prazo);
	}

	/**
	 * Cortado em microssegundos, a precisão do Postgres: o driver arredonda os nanossegundos e o começo gravado podia
	 * ficar depois do relógio, e "1 s depois" virava 0,999 s (um robô a menos no ritmo).
	 */
	private OffsetDateTime agora() {
		return OffsetDateTime.now(relogio).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	private <T> T dados(Envelope evento, Class<T> tipo, int versao) {
		if (evento.versao() != versao) {
			throw new IllegalArgumentException(
					"versão " + evento.versao() + " de " + evento.tipo() + " não suportada (espera " + versao + ")");
		}
		return json.treeToValue(evento.dados(), tipo);
	}

}
