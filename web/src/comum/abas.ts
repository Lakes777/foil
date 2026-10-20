import { useCallback, useEffect, useState } from 'react';

/** Uma função por aba (pedido do André); o endereço guarda a aba (#vitrine, #lancamento, #colecao, #por-dentro). */
export const ABAS = [
  { id: 'vitrine', nome: 'Vitrine' },
  { id: 'lancamento', nome: 'Lançamento' },
  { id: 'colecao', nome: 'Minhas gemas' },
  { id: 'por-dentro', nome: 'Por dentro' },
] as const;

export type Aba = (typeof ABAS)[number]['id'];

function daUrl(): Aba {
  const hash = window.location.hash.slice(1);
  return ABAS.some((a) => a.id === hash) ? (hash as Aba) : 'vitrine';
}

export function useAba(): [Aba, (aba: Aba) => void] {
  const [aba, setAba] = useState<Aba>(daUrl);

  useEffect(() => {
    const voltar = () => setAba(daUrl());
    window.addEventListener('hashchange', voltar);
    return () => window.removeEventListener('hashchange', voltar);
  }, []);

  const abrir = useCallback((nova: Aba) => {
    if (window.location.hash.slice(1) !== nova)
      window.history.pushState(null, '', `#${nova}`);
    setAba(nova);
    window.scrollTo({ top: 0 });
  }, []);

  return [aba, abrir];
}
