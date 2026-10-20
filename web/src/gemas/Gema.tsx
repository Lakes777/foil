import { facetas, luzNegra, paleta } from './desenho';

interface Props {
  cor: string;
  fluorescencia?: string | null;
  className?: string;
}

/**
 * A gema desenhada. Duas camadas: a de dia e, se a gema fluoresce, a da luz negra; o CSS troca uma pela outra
 * (`[data-uv]` na raiz) e apaga as que não fluorescem.
 */
export function Gema({ cor, fluorescencia, className }: Props) {
  const uv = luzNegra(fluorescencia);
  return (
    <svg
      viewBox="0 0 100 100"
      aria-hidden="true"
      className={['gema', uv ? 'fluorescente' : 'opaca', className]
        .filter(Boolean)
        .join(' ')}
    >
      <g className="dia">
        {facetas(paleta(cor)).map((f, i) => (
          <polygon
            key={i}
            points={f.pontos}
            fill={f.cor}
            opacity={f.opacidade}
          />
        ))}
        <circle cx="38" cy="34" r="3.2" fill="#fff" opacity="0.85" />
      </g>
      {uv && (
        <g className="uv">
          {facetas(uv).map((f, i) => (
            <polygon
              key={i}
              points={f.pontos}
              fill={f.cor}
              opacity={f.opacidade}
            />
          ))}
        </g>
      )}
    </svg>
  );
}
