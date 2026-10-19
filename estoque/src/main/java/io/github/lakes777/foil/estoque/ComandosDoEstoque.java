package io.github.lakes777.foil.estoque;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.lakes777.foil.comum.eventos.EventosProcessados;
import io.github.lakes777.foil.comum.eventos.Saida;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.EstoqueComandos.DevolverUnidade;
import io.github.lakes777.foil.contratos.EstoqueComandos.ReservarUnidade;
import io.github.lakes777.foil.contratos.EstoqueEventos.ReservaRecusada;
import io.github.lakes777.foil.contratos.EstoqueEventos.UnidadeDevolvida;
import io.github.lakes777.foil.contratos.EstoqueEventos.UnidadeReservada;
import io.github.lakes777.foil.contratos.Topicos;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recebe os comandos do orquestrador. Numa transação só: anota o id do comando, aplica no estoque e grava a
 * resposta na saída. Comando repetido (mesmo id) não faz nada; ele só é confirmado no Redpanda depois disso.
 */
@Component
class ComandosDoEstoque {

	private static final Logger log = LoggerFactory.getLogger(ComandosDoEstoque.class);

	private final EstoqueService estoque;

	private final EventosProcessados processados;

	private final Saida saida;

	private final JsonMapper json;

	private final Clock relogio = Clock.systemUTC();

	ComandosDoEstoque(EstoqueService estoque, EventosProcessados processados, Saida saida, JsonMapper json) {
		this.estoque = estoque;
		this.processados = processados;
		this.saida = saida;
		this.json = json;
	}

	// A transação fica aqui, no método que o Spring Kafka chama pelo proxy (chamada interna não abriria transação)
	@KafkaListener(topics = Topicos.ESTOQUE_COMANDOS)
	@Transactional
	public void receber(String mensagem) {
		tratar(json.readValue(mensagem, Envelope.class));
	}

	private void tratar(Envelope comando) {
		if (!processados.primeiraVez(comando.id())) {
			log.info("estoque: comando {} repetido, ignorado", comando.id());
			return;
		}
		switch (comando.tipo()) {
			case ReservarUnidade.TIPO -> reservar(comando, dados(comando, ReservarUnidade.class, ReservarUnidade.VERSAO));
			case DevolverUnidade.TIPO -> devolver(comando, dados(comando, DevolverUnidade.class, DevolverUnidade.VERSAO));
			// A fila de mortos (fase 4) recebe o que cair aqui
			default -> throw new IllegalArgumentException("comando desconhecido: " + comando.tipo());
		}
	}

	private void reservar(Envelope comando, ReservarUnidade dados) {
		var resultado = estoque.reservar(comando.pedidoId(), dados.edicaoId());
		// Pedido repetido com outra edição: a resposta fala da edição que está de fato na reserva
		var edicao = estoque.edicaoDaReserva(comando.pedidoId()).orElse(dados.edicaoId());
		switch (resultado) {
			case RESERVADA, JA_RESERVADA -> responder(comando, UnidadeReservada.TIPO, UnidadeReservada.VERSAO,
					new UnidadeReservada(edicao, estoque.buscarEdicao(edicao).orElseThrow().precoCentavos()));
			case SEM_ESTOQUE -> recusar(comando, edicao, ReservaRecusada.Motivo.SEM_ESTOQUE);
			case EDICAO_INEXISTENTE -> recusar(comando, edicao, ReservaRecusada.Motivo.EDICAO_INEXISTENTE);
			case JA_DEVOLVIDA -> recusar(comando, edicao, ReservaRecusada.Motivo.JA_DEVOLVIDA);
		}
	}

	private void devolver(Envelope comando, DevolverUnidade dados) {
		var agora = estoque.devolver(comando.pedidoId(), dados.edicaoId()) == ResultadoDaDevolucao.DEVOLVIDA;
		responder(comando, UnidadeDevolvida.TIPO, UnidadeDevolvida.VERSAO, new UnidadeDevolvida(agora));
	}

	private void recusar(Envelope comando, long edicao, ReservaRecusada.Motivo motivo) {
		responder(comando, ReservaRecusada.TIPO, ReservaRecusada.VERSAO, new ReservaRecusada(edicao, motivo));
	}

	private void responder(Envelope comando, String tipo, int versao, Object dados) {
		saida.registrar(Topicos.ESTOQUE_EVENTOS,
				Envelope.novo(tipo, versao, comando.pedidoId(), json.valueToTree(dados), relogio));
	}

	private <T> T dados(Envelope comando, Class<T> tipo, int versao) {
		if (comando.versao() != versao) {
			throw new IllegalArgumentException(
					"versão " + comando.versao() + " de " + comando.tipo() + " não suportada (espera " + versao + ")");
		}
		return json.treeToValue(comando.dados(), tipo);
	}

}
