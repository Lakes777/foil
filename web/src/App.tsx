import { useRef, type KeyboardEvent } from 'react';
import { Lancamento } from './abas/Lancamento';
import { MinhasGemas } from './abas/MinhasGemas';
import { PorDentro } from './abas/PorDentro';
import { Vitrine } from './abas/Vitrine';
import { ABAS, useAba, type Aba } from './comum/abas';
import { useLuzNegra } from './comum/luzNegra';
import { Gema } from './gemas/Gema';

export function App() {
  const [aba, abrir] = useAba();
  const [luzNegra, alternarLuz] = useLuzNegra();
  const botoes = useRef<(HTMLButtonElement | null)[]>([]);

  /** Setas andam entre as abas (padrão de tablist). */
  function teclas(e: KeyboardEvent, i: number) {
    const passo = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
    if (!passo) return;
    e.preventDefault();
    const j = (i + passo + ABAS.length) % ABAS.length;
    abrir(ABAS[j].id);
    botoes.current[j]?.focus();
  }

  return (
    <>
      <header className="barra">
        <div className="miolo barra-miolo">
          <a
            className="marca"
            href="#vitrine"
            onClick={(e) => (e.preventDefault(), abrir('vitrine'))}
          >
            <Gema cor="lilas" fluorescencia="tempestade" />
            Foil
          </a>
          <nav className="abas" role="tablist" aria-label="Funções da loja">
            {ABAS.map((a, i) => (
              <button
                key={a.id}
                ref={(b) => {
                  botoes.current[i] = b;
                }}
                id={`aba-${a.id}`}
                role="tab"
                type="button"
                aria-selected={aba === a.id}
                aria-controls="conteudo"
                tabIndex={aba === a.id ? 0 : -1}
                onClick={() => abrir(a.id)}
                onKeyDown={(e) => teclas(e, i)}
              >
                {a.nome}
              </button>
            ))}
          </nav>
          <button
            className="luz-negra"
            type="button"
            aria-pressed={luzNegra}
            title="Luz negra (atalho: L)"
            onClick={alternarLuz}
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              aria-hidden="true"
            >
              <path d="M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2V16h5v-.1c0-.8.4-1.5 1-2A6 6 0 0 0 12 3Z" />
            </svg>
            Luz negra
          </button>
        </div>
      </header>
      <main
        id="conteudo"
        role="tabpanel"
        aria-labelledby={`aba-${aba}`}
        key={aba}
        className="aba"
      >
        {conteudo(aba, abrir)}
      </main>
      <footer className="rodape">
        <div className="miolo rodape-miolo">
          <span>
            Foil · gemas fictícias, dinheiro de mentira, eventos de verdade
          </span>
          <a href="https://github.com/Lakes777/foil">código no GitHub</a>
        </div>
      </footer>
    </>
  );
}

function conteudo(aba: Aba, abrir: (aba: Aba) => void) {
  switch (aba) {
    case 'vitrine':
      return <Vitrine abrir={abrir} />;
    case 'lancamento':
      return <Lancamento />;
    case 'colecao':
      return <MinhasGemas />;
    case 'por-dentro':
      return <PorDentro />;
  }
}
