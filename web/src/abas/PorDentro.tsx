import { useEffect, useRef, useState } from 'react';
import { api } from '../api/cliente';
import type {
  Envelope,
  LancamentoMudouDeEstado,
  Pedido,
  PedidoMudouDeEstado,
} from '../api/tipos';
import { hora } from '../comum/formato';
import { Gema } from '../gemas/Gema';
import { ETAPA } from './lancamento/palavras';
import { useAoVivo } from './lancamento/useAoVivo';
import { useEdicoes } from './lancamento/useEdicoes';
import { passosDaSaga, type Marco } from './por-dentro/saga';
import './por-dentro.css';

/** Quantas linhas a lista guarda (as mais novas no topo). */
const LINHAS = 60;
/** Os eventos chegam aos montes num lançamento (dezenas por segundo); a tela muda em lotes, 4 vezes por segundo. */
const LOTE_MS = 250;
/** Quantos pedidos a aba lembra para poder "seguir outro". */
const PEDIDOS_LEMBRADOS = 200;

interface Linha {
  id: string;
  hora: string;
  quem: string;
  tipo: string;
  estado: string;
  rotulo: string;
  tom: 'ok' | 'ruim' | 'meio' | 'lilas' | 'neutro';
}

const DO_PEDIDO: Record<PedidoMudouDeEstado['estado'], [string, Linha['tom']]> =
  {
    RESERVANDO: ['separando', 'neutro'],
    AGUARDANDO_PAGAMENTO: ['pagando', 'meio'],
    LIBERANDO: ['liberando', 'meio'],
    CONFIRMADO: ['confirmado', 'ok'],
    SEM_ESTOQUE: ['sem estoque', 'ruim'],
    CANCELADO: ['cancelado', 'ruim'],
  };

function paraLinha(env: Envelope): Linha {
  const base = { id: env.id, hora: hora(env.ocorridoEm), tipo: env.tipo };
  if (env.tipo === 'LancamentoMudouDeEstado') {
    const d = env.dados as LancamentoMudouDeEstado;
    return {
      ...base,
      quem: 'lançamento',
      estado: d.estado,
      rotulo: ETAPA[d.estado] ?? d.estado,
      tom: d.estado === 'FALHOU' ? 'ruim' : 'lilas',
    };
  }
  const d = env.dados as PedidoMudouDeEstado;
  const [rotulo, tom] = DO_PEDIDO[d.estado] ?? [d.estado, 'neutro'];
  return {
    ...base,
    quem: env.pedidoId.slice(0, 8),
    estado: d.estado,
    rotulo,
    tom,
  };
}

/** A saga da compra passo a passo e todos os eventos ao vivo. */
export function PorDentro() {
  const [linhas, setLinhas] = useState<Linha[]>([]);
  const [pausado, setPausado] = useState(false);
  const [guardadas, setGuardadas] = useState(0);
  const [seguido, setSeguido] = useState<string | null>(null);
  const [, setVersao] = useState(0);

  const chegando = useRef<Envelope[]>([]);
  const paradas = useRef<Linha[]>([]);
  const pausadoRef = useRef(false);
  const seguidoRef = useRef<string | null>(null);
  /** O que se viu de cada pedido (Map em ordem de atividade: o mais recente por último). */
  const historico = useRef(new Map<string, Marco[]>());
  const edicaoDe = useRef(new Map<string, number>());

  const conectado = useAoVivo((env) => {
    chegando.current.push(env);
  });

  useEffect(() => {
    const vez = setInterval(() => {
      const lote = chegando.current;
      if (!lote.length) return;
      chegando.current = [];

      let ultimoPedido: string | null = null;
      for (const env of lote) {
        if (env.tipo !== 'PedidoMudouDeEstado') continue;
        const d = env.dados as PedidoMudouDeEstado;
        const marcos = historico.current.get(env.pedidoId) ?? [];
        historico.current.delete(env.pedidoId);
        historico.current.set(env.pedidoId, [
          ...marcos,
          { estado: d.estado, motivo: d.motivo, ocorridoEm: env.ocorridoEm },
        ]);
        edicaoDe.current.set(env.pedidoId, d.edicaoId);
        ultimoPedido = env.pedidoId;
      }
      for (const id of historico.current.keys()) {
        if (historico.current.size <= PEDIDOS_LEMBRADOS) break;
        if (id === seguidoRef.current) continue;
        historico.current.delete(id);
        edicaoDe.current.delete(id);
      }
      if (!seguidoRef.current && ultimoPedido) {
        seguidoRef.current = ultimoPedido;
        setSeguido(ultimoPedido);
      }

      const novas = lote.map(paraLinha).reverse();
      if (pausadoRef.current) {
        paradas.current = [...novas, ...paradas.current].slice(0, LINHAS);
        setGuardadas((n) => n + novas.length);
      } else {
        setLinhas((l) => [...novas, ...l].slice(0, LINHAS));
      }
      setVersao((v) => v + 1);
    }, LOTE_MS);
    return () => clearInterval(vez);
  }, []);

  function alternarPausa() {
    const pausar = !pausadoRef.current;
    pausadoRef.current = pausar;
    setPausado(pausar);
    if (!pausar) {
      const guardadas = paradas.current;
      paradas.current = [];
      setLinhas((l) => [...guardadas, ...l].slice(0, LINHAS));
      setGuardadas(0);
    }
  }

  function seguirOutro() {
    const ids = [...historico.current.keys()];
    const outro = ids.reverse().find((id) => id !== seguidoRef.current);
    seguidoRef.current = outro ?? null;
    setSeguido(outro ?? null);
  }

  return (
    <section className="secao por-dentro">
      <div className="miolo">
        <h1 className="titulo">
          O que acontece <em>por dentro</em>
        </h1>
        <p className="desc">
          Cada compra é uma saga entre três serviços. Se o pagamento falha, a
          gema volta para a vitrine.
        </p>
        <p className="arquitetura">
          <b>pedidos</b>, <b>estoque</b> e <b>pagamentos</b> não se chamam
          direto: cada um grava o que fez e, na mesma transação, deixa o aviso
          numa caixa de saída (outbox), que segue pelo Kafka. Pedidos conduz a
          saga e, se um passo falha, manda desfazer os anteriores.
        </p>

        <div className="painel">
          <Saga
            pedidoId={seguido}
            marcos={seguido ? (historico.current.get(seguido) ?? []) : []}
            edicaoId={seguido ? edicaoDe.current.get(seguido) : undefined}
            podeTrocar={historico.current.size > (seguido ? 1 : 0)}
            seguirOutro={seguirOutro}
          />

          <div className="cartao eventos">
            <div className="eventos-cab">
              <div>
                <h2>Todos os eventos</h2>
                <p className="sub" data-conectado={conectado}>
                  pedidos.eventos ·{' '}
                  {conectado ? 'ao vivo' : 'conectando ao vivo…'}
                </p>
              </div>
              <button
                className="btn btn-leve"
                type="button"
                aria-pressed={pausado}
                onClick={alternarPausa}
              >
                {pausado
                  ? guardadas
                    ? `Continuar (${guardadas} novos)`
                    : 'Continuar'
                  : 'Pausar'}
              </button>
            </div>
            <div
              className="fluxo"
              role="log"
              aria-live="off"
              aria-label="Eventos ao vivo"
            >
              {linhas.length === 0 && (
                <p className="fluxo-vazio">
                  Nada ainda. Os eventos aparecem aqui quando alguém compra ou
                  quando um lançamento começa.
                </p>
              )}
              {linhas.map((l) => (
                <div key={l.id} className="linha nova">
                  <time>{l.hora}</time>
                  <span className="ev">
                    {l.quem} · <code>{l.tipo}</code>{' '}
                    <code className="ev-estado">{l.estado}</code>
                  </span>
                  <span className={`estado ${l.tom}`}>{l.rotulo}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

function Saga({
  pedidoId,
  marcos,
  edicaoId,
  podeTrocar,
  seguirOutro,
}: {
  pedidoId: string | null;
  marcos: Marco[];
  edicaoId: number | undefined;
  podeTrocar: boolean;
  seguirOutro: () => void;
}) {
  const [pedido, setPedido] = useState<Pedido | null>(null);
  const gemas = useEdicoes([edicaoId ?? pedido?.edicaoId]);

  // o GET do pedido completa o que o ao vivo não viu (ele pode ter começado antes de a aba abrir)
  useEffect(() => {
    setPedido(null);
    if (!pedidoId) return;
    let vale = true;
    api.pedido(pedidoId).then(
      (p) => vale && setPedido(p),
      () => {},
    );
    return () => {
      vale = false;
    };
  }, [pedidoId]);

  const todos: Marco[] = pedido
    ? [
        { estado: 'RESERVANDO', motivo: null, ocorridoEm: pedido.criadoEm },
        ...marcos,
        ...(marcos.some((m) => m.estado === pedido.estado)
          ? []
          : [
              {
                estado: pedido.estado,
                motivo: pedido.motivo,
                ocorridoEm: pedido.atualizadoEm,
              },
            ]),
      ]
    : marcos;
  const gema = gemas.get(edicaoId ?? pedido?.edicaoId ?? -1);
  const passos = passosDaSaga(todos, pedido?.forma);

  return (
    <div className="cartao saga-cartao">
      <div className="eventos-cab">
        <div>
          <h2>Uma compra por dentro</h2>
          <p className="sub">
            {pedidoId
              ? `pedido ${pedidoId.slice(0, 8)}${gema ? ` · ${gema.nome}` : ''}${
                  pedido?.lancamentoId ? ' · robô do lançamento' : ''
                }`
              : 'esperando a próxima compra'}
          </p>
        </div>
        <button
          className="btn btn-leve"
          type="button"
          onClick={seguirOutro}
          disabled={!podeTrocar}
        >
          Seguir outro
        </button>
      </div>
      {pedidoId ? (
        <ol className="saga" aria-label="Passos da compra">
          {passos.map((p) => (
            <li
              key={p.titulo}
              className={[p.feito ? 'feito' : 'falta', p.tom].join(' ')}
            >
              <span className="ponto">
                <Gema
                  cor={
                    !p.feito
                      ? 'cinza'
                      : p.tom === 'ruim'
                        ? 'brasa'
                        : (gema?.cor ?? 'lilas')
                  }
                  fluorescencia={p.feito ? gema?.fluorescencia : null}
                />
              </span>
              <div>
                <b>{p.titulo}</b>
                <small>{p.detalhe}</small>
              </div>
              <time>{p.hora ?? '—'}</time>
            </li>
          ))}
        </ol>
      ) : (
        <p className="saga-vazia">
          Assim que uma compra acontecer (a sua, na vitrine, ou a de um robô num
          lançamento), ela aparece aqui passo a passo.
        </p>
      )}
    </div>
  );
}
