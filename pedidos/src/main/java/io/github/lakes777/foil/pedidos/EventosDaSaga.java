package io.github.lakes777.foil.pedidos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.lakes777.foil.comum.eventos.EventosProcessados;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.Topicos;
import tools.jackson.databind.json.JsonMapper;

/** As respostas do estoque e de pagamentos entram aqui; repetidas são ignoradas, as outras vão para a saga. */
@Component
class EventosDaSaga {

	private static final Logger log = LoggerFactory.getLogger(EventosDaSaga.class);

	private final Saga saga;

	private final EventosProcessados processados;

	private final JsonMapper json;

	EventosDaSaga(Saga saga, EventosProcessados processados, JsonMapper json) {
		this.saga = saga;
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
		saga.aoReceber(evento);
	}

}
