package io.github.lakes777.foil.pagamentos;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.lakes777.foil.comum.eventos.EventosProcessados;
import io.github.lakes777.foil.comum.eventos.Saida;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.PagamentosComandos.CancelarCobranca;
import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido;
import io.github.lakes777.foil.contratos.PagamentosEventos.CobrancaCancelada;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoAprovado;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoEstornado;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoRecusado;
import io.github.lakes777.foil.contratos.Topicos;
import tools.jackson.databind.json.JsonMapper;

/** Recebe os comandos do orquestrador; mesmo desenho do estoque (anota, aplica e responde numa transação). */
@Component
class ComandosDePagamentos {

	private static final Logger log = LoggerFactory.getLogger(ComandosDePagamentos.class);

	private final Cobrancas cobrancas;

	private final EventosProcessados processados;

	private final Saida saida;

	private final JsonMapper json;

	private final Clock relogio = Clock.systemUTC();

	ComandosDePagamentos(Cobrancas cobrancas, EventosProcessados processados, Saida saida, JsonMapper json) {
		this.cobrancas = cobrancas;
		this.processados = processados;
		this.saida = saida;
		this.json = json;
	}

	// A transação fica aqui, no método que o Spring Kafka chama pelo proxy (chamada interna não abriria transação)
	@KafkaListener(topics = Topicos.PAGAMENTOS_COMANDOS)
	@Transactional
	public void receber(String mensagem) {
		var comando = json.readValue(mensagem, Envelope.class);
		if (!processados.primeiraVez(comando.id())) {
			log.info("pagamentos: comando {} repetido, ignorado", comando.id());
			return;
		}
		switch (comando.tipo()) {
			case CobrarPedido.TIPO -> cobrar(comando, dados(comando, CobrarPedido.class, CobrarPedido.VERSAO));
			case CancelarCobranca.TIPO -> {
				dados(comando, CancelarCobranca.class, CancelarCobranca.VERSAO);
				cancelar(comando);
			}
			default -> throw new IllegalArgumentException("comando desconhecido: " + comando.tipo());
		}
	}

	private void cobrar(Envelope comando, CobrarPedido dados) {
		switch (cobrancas.cobrar(comando.pedidoId(), dados.valorCentavos())) {
			case APROVADA -> responder(comando, PagamentoAprovado.TIPO, PagamentoAprovado.VERSAO,
					new PagamentoAprovado(dados.valorCentavos()));
			case RECUSADA -> responder(comando, PagamentoRecusado.TIPO, PagamentoRecusado.VERSAO,
					new PagamentoRecusado(PagamentoRecusado.Motivo.RECUSADO));
			case JA_CANCELADA -> responder(comando, PagamentoRecusado.TIPO, PagamentoRecusado.VERSAO,
					new PagamentoRecusado(PagamentoRecusado.Motivo.CANCELADA));
		}
	}

	private void cancelar(Envelope comando) {
		switch (cobrancas.cancelar(comando.pedidoId())) {
			case CANCELADA -> responder(comando, CobrancaCancelada.TIPO, CobrancaCancelada.VERSAO,
					new CobrancaCancelada());
			case ESTORNADA -> responder(comando, PagamentoEstornado.TIPO, PagamentoEstornado.VERSAO,
					new PagamentoEstornado(cobrancas.valor(comando.pedidoId()).orElse(0)));
		}
	}

	private void responder(Envelope comando, String tipo, int versao, Object dados) {
		saida.registrar(Topicos.PAGAMENTOS_EVENTOS,
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
