package io.github.lakes777.foil.estoque;

import org.springframework.stereotype.Component;

import io.github.lakes777.foil.comum.eventos.Limpeza;

/**
 * O que a limpeza das 4h10 apaga no estoque. As reservas recusadas e devolvidas respondem a um comando repetido
 * ({@code JA_DEVOLVIDA}, {@code SEM_ESTOQUE}), então ficam o prazo dos eventos. As edições dos lançamentos saem
 * depois do prazo das compras e só sem reserva nesse prazo (as reservas vão junto, em cascata); as do catálogo
 * (sem lançamento) ficam sempre.
 */
@Component
class LimpezaDoEstoque implements Limpeza.Regras {

	@Override
	public void aplicar(Limpeza limpeza) {
		// Com um morto do pedido, a reserva é a trava contra o reprocessar (Mortos): fica enquanto ele existir
		limpeza.apagar("reserva", """
				situacao in ('sem_estoque', 'devolvida') and coalesce(devolvida_em, reservada_em) < %s
				and not exists (select from morto m where m.chave = reserva.pedido_id::text)
				""".formatted(limpeza.antesDosEventos()));
		// Um dia de folga: o pedido sai pela confirmação, que vem depois da reserva; sem a folga, a gema podia
		// sumir de "Minhas gemas" um dia antes do pedido
		limpeza.apagar("edicao", """
				lancamento_id is not null and criada_em < %1$s
				and not exists (select from reserva r where r.edicao_id = edicao.id and r.reservada_em >= %1$s)
				""".formatted(limpeza.antesDasCompras() + " - interval '1 day'"));
	}

}
