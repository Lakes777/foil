import { useCallback, useEffect, useState } from 'react';

const CHAVE = 'foil-luz-negra';

function lembrada(): boolean {
  try {
    return localStorage.getItem(CHAVE) === '1';
  } catch {
    return false;
  }
}

/**
 * A luz negra vale para o site inteiro: o atributo `data-uv` na raiz troca as cores (tema.css) e as gemas. Liga e
 * desliga pelo botão ou pela tecla L (fora de campos de texto); o navegador lembra.
 */
export function useLuzNegra(): [boolean, () => void] {
  const [ligada, setLigada] = useState(lembrada);

  useEffect(() => {
    const raiz = document.documentElement;
    if (ligada) raiz.setAttribute('data-uv', '');
    else raiz.removeAttribute('data-uv');
    try {
      localStorage.setItem(CHAVE, ligada ? '1' : '0');
    } catch {
      // aba anônima ou armazenamento bloqueado: só não lembra
    }
  }, [ligada]);

  const alternar = useCallback(() => setLigada((l) => !l), []);

  useEffect(() => {
    const tecla = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() !== 'l' || e.ctrlKey || e.metaKey || e.altKey)
        return;
      const alvo = e.target as HTMLElement | null;
      if (alvo?.closest('input, textarea, select, [contenteditable="true"]'))
        return;
      alternar();
    };
    document.addEventListener('keydown', tecla);
    return () => document.removeEventListener('keydown', tecla);
  }, [alternar]);

  return [ligada, alternar];
}
