import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/cliente';
import type { ItemDaVitrine } from '../../api/tipos';

/** O que a tela precisa saber da gema de uma edição: o nome, a cor para desenhar e o número da edição. */
export interface GemaDaEdicao {
  nome: string;
  cor: string;
  fluorescencia: string | null;
  numero: number;
  total: number;
}

/**
 * Traduz ids de edição em gemas: edição (estoque) → carta → a carta na vitrine. Cada edição é pedida uma vez só; a
 * vitrine também, a não ser que a carta ainda não esteja nela (aí pede de novo). Se falhar, tenta na próxima mudança.
 */
export function useEdicoes(
  ids: readonly (number | null | undefined)[],
): ReadonlyMap<number, GemaDaEdicao> {
  const [gemas, setGemas] = useState<ReadonlyMap<number, GemaDaEdicao>>(
    () => new Map(),
  );
  const pedidas = useRef(new Set<number>());
  const vitrine = useRef<Promise<ItemDaVitrine[]> | null>(null);

  const chave = [...new Set(ids.filter((id): id is number => id != null))].join(
    ',',
  );

  useEffect(() => {
    if (!chave) return;

    async function daVitrine(cartaId: number, deNovo: boolean) {
      if (!vitrine.current || deNovo) vitrine.current = api.vitrine();
      try {
        const itens = await vitrine.current;
        return itens.find((i) => i.carta.id === cartaId);
      } catch (e) {
        vitrine.current = null;
        throw e;
      }
    }

    async function buscar(id: number): Promise<GemaDaEdicao> {
      const edicao = await api.edicao(id);
      const item =
        (await daVitrine(edicao.cartaId, false)) ??
        (await daVitrine(edicao.cartaId, true));
      return {
        nome: item?.carta.nome ?? `Gema ${edicao.cartaId}`,
        cor: item?.carta.cor ?? 'lilas',
        fluorescencia: item?.carta.fluorescencia ?? null,
        numero: edicao.numero,
        total: edicao.total,
      };
    }

    for (const id of chave.split(',').map(Number)) {
      if (pedidas.current.has(id)) continue;
      pedidas.current.add(id);
      buscar(id).then(
        (gema) => setGemas((m) => new Map(m).set(id, gema)),
        () => pedidas.current.delete(id),
      );
    }
  }, [chave]);

  return gemas;
}
