import {
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
  type PointerEvent,
} from 'react';
import type { Carta, Edicao, Pedido } from '../../api/tipos';
import { dataEHora } from '../../comum/formato';
import { Gema } from '../../gemas/Gema';
import { Mohs } from '../vitrine/Mohs';
import '../vitrine/carta.css';
import { selo } from './selo';

/** Uma gema confirmada, com tudo o que o inspetor e a ficha mostram. */
export interface GemaComprada {
  pedido: Pedido;
  unidade: number;
  edicao: Edicao;
  carta: Carta;
}

const INICIO = { rx: -6, ry: 18 };
const ZOOM = { min: 80, max: 150, passo: 10 };
const SETAS: Record<string, [number, number]> = {
  ArrowLeft: [0, -12],
  ArrowRight: [0, 12],
  ArrowUp: [8, 0],
  ArrowDown: [-8, 0],
};

interface Orientacao {
  /** Para qual pedido esta orientação vale: trocar de gema endireita a carta. */
  para: string;
  rx: number;
  ry: number;
  virada: boolean;
}

function limitar(rx: number) {
  return Math.max(-35, Math.min(35, rx));
}

export function idCurto(id: string) {
  return id.replaceAll('-', '').slice(0, 8);
}

/**
 * A carta na mesa do inspetor: frente (a gema) e verso (o certificado de lapidação), em 3D. Arrastar gira; pelo
 * teclado, as setas giram, V vira e + e - aproximam. Luz e zoom têm controles próprios.
 */
export function Inspetor({ gema }: { gema: GemaComprada }) {
  const { pedido, unidade, edicao, carta } = gema;
  const [orientacao, setOrientacao] = useState<Orientacao>({
    para: pedido.id,
    ...INICIO,
    virada: false,
  });
  const [zoom, setZoom] = useState(100);
  const [luz, setLuz] = useState(35);
  const [arrastando, setArrastando] = useState(false);
  const arrasto = useRef<{ x: number; y: number; rx: number; ry: number }>(
    null,
  );

  // outra gema escolhida: começa de frente e endireitada (luz e zoom continuam como a pessoa deixou)
  const atual =
    orientacao.para === pedido.id
      ? orientacao
      : { para: pedido.id, ...INICIO, virada: false };
  if (atual !== orientacao) setOrientacao(atual);
  const { rx, ry, virada } = atual;

  function girar(novoRx: number, novoRy: number) {
    setOrientacao({ ...atual, rx: limitar(novoRx), ry: novoRy });
  }

  function virar() {
    setOrientacao({ ...atual, virada: !atual.virada });
  }

  function endireitar() {
    setOrientacao({ para: pedido.id, ...INICIO, virada: false });
    setZoom(100);
  }

  function mudarZoom(delta: number) {
    setZoom((z) => Math.max(ZOOM.min, Math.min(ZOOM.max, z + delta)));
  }

  function pegar(e: PointerEvent<HTMLDivElement>) {
    arrasto.current = { x: e.clientX, y: e.clientY, rx, ry };
    setArrastando(true);
    e.currentTarget.setPointerCapture?.(e.pointerId);
  }

  function arrastar(e: PointerEvent<HTMLDivElement>) {
    const a = arrasto.current;
    if (!a) return;
    girar(a.rx - (e.clientY - a.y) * 0.35, a.ry + (e.clientX - a.x) * 0.45);
  }

  function soltar() {
    arrasto.current = null;
    setArrastando(false);
  }

  function teclas(e: KeyboardEvent<HTMLDivElement>) {
    const passo = SETAS[e.key];
    if (passo) {
      e.preventDefault();
      girar(rx + passo[0], ry + passo[1]);
    } else if (e.key === 'v' || e.key === 'V') {
      e.preventDefault();
      virar();
    } else if (e.key === '+' || e.key === '=') {
      e.preventDefault();
      mudarZoom(ZOOM.passo);
    } else if (e.key === '-') {
      e.preventDefault();
      mudarZoom(-ZOOM.passo);
    }
  }

  const angulo = (luz / 100) * Math.PI;
  const mesa = {
    '--lx': `${50 - Math.cos(angulo) * 40}%`,
    '--ly': `${45 - Math.sin(angulo) * 25}%`,
  } as CSSProperties;
  const giro = {
    '--rx': `${rx}deg`,
    '--ry': `${ry + (virada ? 180 : 0)}deg`,
    '--zoom': zoom / 100,
  } as CSSProperties;
  const total = edicao.total;
  const curto = idCurto(pedido.id);
  const pagamento = pedido.forma === 'FIEL' ? 'pelo Fiel' : 'simulado';

  return (
    <div className="inspetor">
      <div
        className="inspetor-mesa"
        style={mesa}
        tabIndex={0}
        role="group"
        aria-roledescription="carta em inspeção"
        aria-label={`${carta.nome}, unidade ${unidade} de ${total}. Setas giram, V vira, + e - aproximam.`}
        onPointerDown={pegar}
        onPointerMove={arrastar}
        onPointerUp={soltar}
        onPointerCancel={soltar}
        onKeyDown={teclas}
      >
        <div
          className={arrastando ? 'inspetor-giro arrastando' : 'inspetor-giro'}
          style={giro}
          data-testid="giro"
        >
          <div className="face frente" aria-hidden={virada}>
            <div className="carta-topo">
              <h3 className="carta-nome">{carta.nome}</h3>
              <div className="carta-serie num">
                edição {edicao.numero}
                <br />
                unidade {unidade}/{total}
              </div>
            </div>
            <div className="carta-janela">
              <Gema cor={carta.cor} fluorescencia={carta.fluorescencia} />
            </div>
            <div className="carta-pe">
              <p className="carta-texto">{carta.descricao}</p>
              <Mohs valor={carta.mohs} />
            </div>
          </div>
          <section
            className="face verso"
            aria-label="Certificado de lapidação"
            aria-hidden={!virada}
          >
            <div className="verso-cab">
              Certificado de lapidação
              <small>
                {carta.nome} · edição {edicao.numero}
              </small>
            </div>
            <dl>
              <dt>Unidade</dt>
              <dd>
                {unidade} de {total}
              </dd>
              <dt>Lapidada</dt>
              <dd>{dataEHora(edicao.criadaEm)}</dd>
              <dt>Comprada</dt>
              <dd>{dataEHora(pedido.atualizadoEm)}</dd>
              <dt>Pedido</dt>
              <dd>{curto}</dd>
              <dt>Pagamento</dt>
              <dd>{pagamento}</dd>
            </dl>
            <div className="tinta-uv">
              <span>
                AUTÊNTICA
                <br />
                {unidade}/{total} · {curto}
              </span>
            </div>
            <div className="verso-selo">
              <svg
                viewBox="0 0 9 9"
                shapeRendering="crispEdges"
                aria-hidden="true"
              >
                {selo(pedido.id).map(({ x, y }) => (
                  <rect
                    key={`${x}-${y}`}
                    x={x}
                    y={y}
                    width="1"
                    height="1"
                    fill="var(--lilas)"
                  />
                ))}
              </svg>
            </div>
          </section>
        </div>
      </div>
      <p className="sr" aria-live="polite">
        {virada ? 'Mostrando o verso: o certificado.' : 'Mostrando a frente.'}
      </p>
      <div className="inspetor-comandos">
        <button
          className="btn btn-leve"
          type="button"
          aria-pressed={virada}
          onClick={virar}
        >
          Virar a carta
        </button>
        <button className="btn btn-leve" type="button" onClick={endireitar}>
          Endireitar
        </button>
      </div>
      <div className="inspetor-ajustes">
        <label>
          Luz
          <input
            type="range"
            min="0"
            max="100"
            value={luz}
            onChange={(e) => setLuz(Number(e.target.value))}
          />
        </label>
        <label>
          Zoom
          <input
            type="range"
            min={ZOOM.min}
            max={ZOOM.max}
            value={zoom}
            aria-valuetext={`${zoom}%`}
            onChange={(e) => setZoom(Number(e.target.value))}
          />
        </label>
      </div>
      <p className="inspetor-dica">
        Arraste para girar · V vira · setas giram pelo teclado
      </p>
    </div>
  );
}
