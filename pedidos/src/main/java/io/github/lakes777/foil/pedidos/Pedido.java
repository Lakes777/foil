package io.github.lakes777.foil.pedidos;

import java.time.Instant;
import java.util.UUID;

import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido.Forma;

/** {@code qr} e {@code linkParaPagar} só na forma FIEL, depois que a cobrança existe no Fiel. */
public record Pedido(UUID id, long edicaoId, Estado estado, String motivo, Integer precoCentavos, Instant prazo,
		Instant criadoEm, Instant atualizadoEm, Forma forma, String qr, String linkParaPagar) {

}
