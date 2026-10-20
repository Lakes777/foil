package io.github.lakes777.foil.comum.eventos;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * A limpeza das 4h10 (§5 e §8 do SPEC): a demonstração não pode encher o banco da VM com os robôs de todo dia.
 * Aqui fica o que os três serviços têm igual (saída já publicada, ids de eventos processados, mortos velhos);
 * cada serviço acrescenta as próprias tabelas com uma {@link Regras}.
 *
 * <p>
 * Prazos: {@code diasDosEventos} precisa passar do tempo que uma mensagem pode ficar no tópico
 * ({@link #DIAS_NO_TOPICO}). Um evento ainda no tópico pode ser lido de novo (consumidor que perdeu a posição recomeça
 * do início), e só não tem efeito duas vezes se o id dele ainda estiver em {@code evento_processado}. O mesmo vale
 * para as linhas que respondem a um comando repetido (reserva, cobrança): apagadas antes, um comando antigo relido
 * seria tratado como novo. Essas linhas ficam também enquanto houver um morto do pedido (ver {@link Mortos}).
 */
public class Limpeza {

	private static final Logger log = LoggerFactory.getLogger(Limpeza.class);

	/** {@code retention.ms} dos tópicos (ver ComumAutoConfiguration). */
	public static final int DIAS_DE_RETENCAO = 2;

	/**
	 * {@code segment.ms} dos tópicos. A retenção só apaga segmentos fechados, e o padrão do Redpanda fecha o segmento
	 * em 14 dias: com pouco movimento, uma mensagem ficaria até ~17 dias (achado do revisor, 20/10/2026).
	 */
	public static final int DIAS_DO_SEGMENTO = 1;

	/** O máximo que uma mensagem fica no tópico: retenção + o segmento que ainda não fechou. */
	public static final int DIAS_NO_TOPICO = DIAS_DE_RETENCAO + DIAS_DO_SEGMENTO;

	/** Apaga em pedaços: um delete enorme seguraria as linhas e o único núcleo da VM de uma vez. */
	static final int LOTE = 5000;

	/** As tabelas de um serviço. Cada {@link Limpeza#apagar} é um passo à parte, na ordem em que vierem. */
	public interface Regras {

		void aplicar(Limpeza limpeza);

	}

	private final JdbcClient jdbc;

	private final List<Regras> regras;

	private final int diasDosEventos;

	private final int diasDasCompras;

	public Limpeza(JdbcClient jdbc, List<Regras> regras, int diasDosEventos, int diasDasCompras) {
		if (diasDosEventos <= DIAS_NO_TOPICO) {
			throw new IllegalArgumentException("foil.limpeza.dias-dos-eventos precisa passar de " + DIAS_NO_TOPICO
					+ " (o máximo que uma mensagem fica no tópico); veio " + diasDosEventos);
		}
		if (diasDasCompras <= diasDosEventos) {
			throw new IllegalArgumentException("foil.limpeza.dias-das-compras precisa passar dos dias dos eventos");
		}
		this.jdbc = jdbc;
		this.regras = regras;
		this.diasDosEventos = diasDosEventos;
		this.diasDasCompras = diasDasCompras;
	}

	/** 4h10 de Brasília: depois do recadastro no Fiel (4h05), num horário sem ninguém. */
	@Scheduled(cron = "${foil.limpeza.quando:0 10 4 * * *}", zone = "America/Sao_Paulo")
	public void limpar() {
		var inicio = System.nanoTime();
		apagar("saida", "publicado_em < now() - interval '1 day'");
		apagar("evento_processado", "processado_em < " + haDias(diasDosEventos));
		apagar("morto", "morto_em < " + haDias(diasDasCompras));
		regras.forEach(r -> r.aplicar(this));
		log.info("limpeza: pronta em {} ms", (System.nanoTime() - inicio) / 1_000_000);
	}

	/**
	 * Apaga de {@code tabela} as linhas que cumprem {@code condicao}, {@link #LOTE} por vez, cada lote na sua
	 * transação. A condição é SQL escrito no código (nunca texto de fora).
	 */
	public int apagar(String tabela, String condicao) {
		var sql = "delete from " + tabela + " where ctid = any(array(select ctid from " + tabela + " where "
				+ condicao + " limit " + LOTE + "))";
		var total = 0;
		int apagadas;
		do {
			apagadas = jdbc.sql(sql).update();
			total += apagadas;
		}
		while (apagadas == LOTE);
		if (total > 0) {
			log.info("limpeza: {} linhas de {}", total, tabela);
		}
		return total;
	}

	/** Fim dos ids de eventos e das respostas a comandos repetidos (passa da retenção do tópico). */
	public String antesDosEventos() {
		return haDias(diasDosEventos);
	}

	/** Fim do que o visitante vê em "Minhas gemas" (e das vendas, que dão o número da unidade). */
	public String antesDasCompras() {
		return haDias(diasDasCompras);
	}

	/**
	 * Até quando um morto pode ser reprocessado: a mensagem devolvida ao tópico pode ser lida até
	 * {@link #DIAS_NO_TOPICO} depois, e as travas do pedido (reserva devolvida, cobrança cancelada) só ficam enquanto o
	 * morto existe ({@code diasDasCompras}). Com folga de um dia.
	 */
	public static int diasParaReprocessar(int diasDasCompras) {
		return diasDasCompras - DIAS_NO_TOPICO - 1;
	}

	private static String haDias(int dias) {
		return "now() - interval '" + dias + " days'";
	}

}
