package io.github.lakes777.foil.pedidos;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.lakes777.foil.comum.eventos.Rastro;
import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido.Forma;

/**
 * {@code qr} e {@code linkParaPagar} só na forma FIEL, depois que a cobrança existe no Fiel. {@code lancamentoId} só
 * nos pedidos dos robôs. {@code traceparent}: o trace da saga (fase 9); para fora vai só o id dele, o {@code traceId}
 * que a interface leva ao painel do Grafana.
 */
public record Pedido(UUID id, long edicaoId, Estado estado, String motivo, Integer precoCentavos, Instant prazo,
		Instant criadoEm, Instant atualizadoEm, Forma forma, String qr, String linkParaPagar, UUID lancamentoId,
		@JsonIgnore String traceparent) {

	@JsonProperty
	public String traceId() {
		return Rastro.idDoTrace(traceparent);
	}

}
