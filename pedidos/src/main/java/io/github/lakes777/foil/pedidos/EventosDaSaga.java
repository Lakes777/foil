package io.github.lakes777.foil.pedidos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.lakes777.foil.comum.eventos.EventosProcessados;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.EstoqueEventos.EdicaoAberta;
import io.github.lakes777.foil.contratos.EstoqueEventos.EdicaoRecusada;
import io.github.lakes777.foil.contratos.Topicos;
import tools.jackson.databind.json.JsonMapper;

/**
 * As respostas do estoque e de pagamentos entram aqui; repetidas são ignoradas, as outras vão para a saga (ou, as da
 * edição de um lançamento, para {@link Lancamentos}).
 */
@Component
class EventosDaSaga {

	private static final Logger log = LoggerFactory.getLogger(EventosDaSaga.class);

	private final Saga saga;

	private final Lancamentos lancamentos;

	private final EventosProcessados processados;

	private final JsonMapper json;

	EventosDaSaga(Saga saga, Lancamentos lancamentos, EventosProcessados processados, JsonMapper json) {
		this.saga = saga;
		this.lancamentos = lancamentos;
		this.processados = processados;
		this.json = json;
	}

	@KafkaListener(topics = { Topicos.ESTOQUE_EVENTOS, Topicos.PAGAMENTOS_EVENTOS })
	@Transactional
	public void receber(String mensagem) {
		var evento = json.readValue(mensagem, Envelope.class);
		if (!processados.primeiraVez(evento.id())) {
			log.info("saga: evento {} repetido, ignorado", evento.id());
			return;
		}
		switch (evento.tipo()) {
			case EdicaoAberta.TIPO, EdicaoRecusada.TIPO -> lancamentos.aoReceber(evento);
			default -> saga.aoReceber(evento);
		}
	}

}
