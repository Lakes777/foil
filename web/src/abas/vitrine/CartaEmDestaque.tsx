import { useRef, type CSSProperties, type PointerEvent } from 'react';
import type { ItemDaVitrine } from '../../api/tipos';
import { RARIDADE } from '../../comum/formato';
import { Gema } from '../../gemas/Gema';
import { Mohs } from './Mohs';
import './carta.css';

function calmo(): boolean {
  return (
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
  );
}

/**
 * A carta grande da abertura: inclina e o brilho de prisma segue o ponteiro (sem inclinar, se a pessoa pediu menos
 * movimento). Mexe só nas variáveis do CSS, direto no elemento, para não renderizar a cada movimento.
 */
export function CartaEmDestaque({ item }: { item: ItemDaVitrine }) {
  const carta = useRef<HTMLElement>(null);
  const { carta: c, edicao } = item;

  function mover(e: PointerEvent<HTMLElement>) {
    const el = carta.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    if (!r.width || !r.height) return;
    const x = (e.clientX - r.left) / r.width;
    const y = (e.clientY - r.top) / r.height;
    el.style.setProperty('--mx', `${x * 100}%`);
    el.style.setProperty('--my', `${y * 100}%`);
    if (!calmo()) {
      el.style.setProperty('--ry', `${(x - 0.5) * 12}deg`);
      el.style.setProperty('--rx', `${(0.5 - y) * 12}deg`);
    }
  }

  function soltar() {
    const el = carta.current;
    if (!el) return;
    for (const [nome, valor] of [
      ['--rx', '0deg'],
      ['--ry', '0deg'],
      ['--mx', '40%'],
      ['--my', '30%'],
    ])
      el.style.setProperty(nome, valor);
  }

  return (
    <article
      ref={carta}
      className="carta-destaque"
      tabIndex={0}
      aria-label={`Carta em destaque: ${c.nome}, ${RARIDADE[c.raridade]}, dureza ${c.mohs} na escala de Mohs, edição ${edicao.numero}`}
      onPointerMove={mover}
      onPointerLeave={soltar}
      style={{ '--rx': '0deg', '--ry': '0deg' } as CSSProperties}
    >
      <div className="carta-topo">
        <h2 className="carta-nome">{c.nome}</h2>
        <div className="carta-serie num">
          Nº {String(c.id).padStart(3, '0')}
          <br />
          edição {edicao.numero} · {edicao.total} un.
        </div>
      </div>
      <div className="carta-janela">
        <Gema cor={c.cor} fluorescencia={c.fluorescencia} />
      </div>
      <div className="carta-pe">
        <p className="carta-texto">{c.descricao}</p>
        <Mohs valor={c.mohs} />
      </div>
    </article>
  );
}

/** O lugar da carta enquanto a vitrine chega: a mesma moldura, sem conteúdo. */
export function CartaEsperando() {
  return (
    <div className="carta-destaque esperando" aria-hidden="true">
      <div className="carta-topo">
        <span className="osso osso-titulo" />
      </div>
      <div className="carta-janela" />
      <div className="carta-pe">
        <span className="osso" />
        <span className="osso osso-curto" />
      </div>
    </div>
  );
}
