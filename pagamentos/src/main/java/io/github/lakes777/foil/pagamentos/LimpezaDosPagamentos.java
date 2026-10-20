package io.github.lakes777.foil.pagamentos;

import org.springframework.stereotype.Component;

import io.github.lakes777.foil.comum.eventos.Limpeza;

/**
 * O que a limpeza das 4h10 apaga em pagamentos: cobranças que terminaram. Elas respondem a um comando repetido
 * (e a cancelada é a trava contra o {@code CobrarPedido} atrasado), então ficam o prazo dos eventos. As que ainda
 * esperam o Fiel (pendente, cancelando, estornando) nunca saem.
 */
@Component
class LimpezaDosPagamentos implements Limpeza.Regras {

	@Override
	public void aplicar(Limpeza limpeza) {
		// Com um morto do pedido, a cobrança é a trava contra o reprocessar (Mortos): fica enquanto ele existir
		limpeza.apagar("cobranca", """
				situacao in ('aprovada', 'recusada', 'cancelada', 'estornada') and atualizada_em < %s
				and not exists (select from morto m where m.chave = cobranca.pedido_id::text)
				""".formatted(limpeza.antesDosEventos()));
	}

}
