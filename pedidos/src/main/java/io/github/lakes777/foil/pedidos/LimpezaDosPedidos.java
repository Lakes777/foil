package io.github.lakes777.foil.pedidos;

import org.springframework.stereotype.Component;

import io.github.lakes777.foil.comum.eventos.Limpeza;

/**
 * O que a limpeza das 4h10 apaga em pedidos. Só pedidos que já terminaram: os recusados e cancelados dos robôs
 * saem cedo (são ~190 de cada lançamento de 200); os confirmados e os do visitante ficam o prazo das compras.
 * Confirmados ficam juntos por um motivo: a unidade de uma venda nova é a maior da edição + 1, então apagar a
 * maior de uma edição ainda à venda repetiria o número de um certificado.
 */
@Component
class LimpezaDosPedidos implements Limpeza.Regras {

	@Override
	public void aplicar(Limpeza limpeza) {
		limpeza.apagar("pedido", "estado in ('SEM_ESTOQUE', 'CANCELADO') and visitante is null and atualizado_em < "
				+ limpeza.antesDosEventos());
		limpeza.apagar("pedido",
				"estado in ('CONFIRMADO', 'SEM_ESTOQUE', 'CANCELADO') and atualizado_em < " + limpeza.antesDasCompras());
		// Lançamento terminado e sem pedidos; os 3 últimos ficam sempre (o GET /lancamentos mostra)
		limpeza.apagar("lancamento", """
				estado in ('ENCERRADO', 'FALHOU') and coalesce(terminou_em, atualizado_em) < %s
				and not exists (select from pedido p where p.lancamento_id = lancamento.id)
				and id not in (select id from lancamento where estado in ('ENCERRADO', 'FALHOU')
				               order by terminou_em desc limit 3)
				""".formatted(limpeza.antesDosEventos()));
	}

}
