import './mohs.css';

/** A dureza na escala de Mohs: 10 traços, acesos até o valor da gema. */
export function Mohs({ valor }: { valor: number }) {
  return (
    <div className="mohs">
      <div className="mohs-escala" aria-hidden="true">
        {Array.from({ length: 10 }, (_, i) => (
          <i key={i} className={i < valor ? 'aceso' : undefined} />
        ))}
      </div>
      <div className="mohs-legenda">
        <span>dureza Mohs</span>
        <b className="num">{valor} de 10</b>
      </div>
    </div>
  );
}
