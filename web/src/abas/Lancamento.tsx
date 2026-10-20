import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ErroDaApi } from '../api/cliente';
import type {
  Envelope,
  Lancamento as Lanc,
  LancamentoMudouDeEstado,
  PedidoMudouDeEstado,
  SituacaoDosLancamentos,
} from '../api/tipos';
import { hora } from '../comum/formato';
import { Gema } from '../gemas/Gema';
import { ATIVOS, ETAPA, ETAPAS, motivoEmPalavras } from './lancamento/palavras';
import { aplicarEvento } from './lancamento/retrato';
import { useAoVivo } from './lancamento/useAoVivo';
import { useEdicoes, type GemaDaEdicao } from './lancamento/useEdicoes';
import './lancamento.css';

/** O id do lançamento que este navegador pediu (para marcar "O seu" na fila). Some ao fechar a aba do navegador. */
const CHAVE_DO_MEU = 'foil-meu-lancamento';
/** O retrato de garantia: o SSE pode cair (ou bater no teto) sem a tela parar. */
const GARANTIA_MS = 5000;
/** Vários pedidos dos robôs no mesmo segundo viram um GET só. */
const NO_MAXIMO_A_CADA_MS = 1000;

function lerMeu(): string | null {
  try {
    return sessionStorage.getItem(CHAVE_DO_MEU);
  } catch {
    return null;
  }
}

function guardarMeu(id: string) {
  try {
    sessionStorage.setItem(CHAVE_DO_MEU, id);
  } catch {
    // aba anônima ou armazenamento bloqueado: só não marca "O seu"
  }
}

function duracao(segundos: number): string {
  const s = Math.max(0, segundos);
  return s < 60 ? `${s} s` : `${Math.floor(s / 60)} min ${s % 60} s`;
}

interface Recusa {
  status: number;
  ate: number;
}

/** O lançamento da vez, a fila e o estojo de 10 lugares. */
export function Lancamento() {
  const [situacao, setSituacao] = useState<SituacaoDosLancamentos | null>(null);
  const [falha, setFalha] = useState<string | null>(null);
  const [meu, setMeu] = useState(lerMeu);
  const [agora, setAgora] = useState(() => Date.now());
  const [pedindo, setPedindo] = useState(false);
  const [recusa, setRecusa] = useState<Recusa | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);

  // só a resposta do GET mais novo vale (uma lenta não desfaz uma mais nova)
  const ultimoGet = useRef(0);
  const atualizar = useCallback(async () => {
    const este = ++ultimoGet.current;
    try {
      const s = await api.lancamentos();
      if (este !== ultimoGet.current) return;
      setSituacao(s);
      setFalha(null);
    } catch (e) {
      if (este === ultimoGet.current)
        setFalha(e instanceof Error ? e.message : 'A loja não respondeu.');
    }
  }, []);

  const adiado = useRef<ReturnType<typeof setTimeout> | null>(null);
  const atualizarLogo = useCallback(() => {
    if (adiado.current) return;
    adiado.current = setTimeout(() => {
      adiado.current = null;
      void atualizar();
    }, NO_MAXIMO_A_CADA_MS);
  }, [atualizar]);

  useEffect(() => {
    void atualizar();
    const garantia = setInterval(() => void atualizar(), GARANTIA_MS);
    const relogio = setInterval(() => setAgora(Date.now()), 1000);
    return () => {
      clearInterval(garantia);
      clearInterval(relogio);
      if (adiado.current) clearTimeout(adiado.current);
      adiado.current = null;
    };
  }, [atualizar]);

  const exibido =
    situacao &&
    (situacao.atual ?? situacao.fila[0] ?? situacao.recentes[0] ?? null);
  const edicaoExibida = useRef<number | null>(null);
  useEffect(() => {
    edicaoExibida.current = exibido?.edicaoId ?? null;
  });

  const conectado = useAoVivo((envelope) => {
    if (envelope.tipo === 'LancamentoMudouDeEstado') {
      const env = envelope as Envelope<LancamentoMudouDeEstado>;
      setSituacao((s) => s && aplicarEvento(s, env));
      atualizarLogo();
    } else if (envelope.tipo === 'PedidoMudouDeEstado') {
      const { edicaoId } = envelope.dados as PedidoMudouDeEstado;
      if (edicaoId === edicaoExibida.current) atualizarLogo();
    }
  });

  const gemas = useEdicoes([
    situacao?.atual?.edicaoId,
    ...(situacao?.recentes.map((l) => l.edicaoId) ?? []),
  ]);

  async function pedir() {
    setPedindo(true);
    setAviso(null);
    try {
      const novo = await api.pedirLancamento();
      guardarMeu(novo.id);
      setMeu(novo.id);
      setRecusa(null);
      setAviso(
        novo.posicaoNaFila
          ? `Pedido feito: o seu é o ${novo.posicaoNaFila}º da fila.`
          : 'Pedido feito: o seu já vai começar.',
      );
      void atualizar();
    } catch (e) {
      if (e instanceof ErroDaApi && (e.status === 429 || e.status === 503)) {
        const ja = Date.now();
        setAgora(ja);
        setRecusa({
          status: e.status,
          ate: ja + (e.tenteEmSegundos ?? 60) * 1000,
        });
      } else {
        setAviso(e instanceof Error ? e.message : 'Não deu para pedir agora.');
      }
    } finally {
      setPedindo(false);
    }
  }

  const faltam = recusa ? Math.ceil((recusa.ate - agora) / 1000) : 0;
  const esperando = faltam > 0;
  const mensagem = esperando
    ? recusa!.status === 429
      ? `Você pediu um há pouco. Tente em ${faltam} s.`
      : `A fila está cheia (5 esperando). Tente em ${faltam} s.`
    : aviso;

  return (
    <section className="secao lancamento">
      <div className="miolo">
        <div className="lanc-cab">
          <div>
            <h1 className="titulo">
              Lançamento <em>ao vivo</em>
            </h1>
            <p className="desc">
              Um por vez no site inteiro. Quem aperta o botão entra na fila e vê
              a própria vez chegar.
            </p>
          </div>
          <div className="lanc-acao">
            <button
              className="btn btn-forte"
              type="button"
              onClick={pedir}
              disabled={pedindo || esperando}
            >
              Simular lançamento
            </button>
            <p className="lanc-msg" role="status">
              {mensagem}
            </p>
          </div>
        </div>

        <p className="ao-vivo" data-conectado={conectado}>
          <span className="ao-vivo-ponto" aria-hidden="true" />
          {conectado
            ? 'ao vivo'
            : 'sem conexão ao vivo; atualizando a cada 5 s'}
        </p>

        {!situacao ? (
          <p className={falha ? 'aviso' : 'lanc-carregando'}>
            {falha ?? 'Buscando o lançamento da vez…'}
          </p>
        ) : !exibido ? (
          <div className="cartao lanc-vazio">
            <Gema cor="lilas" fluorescencia="tempestade" />
            <div>
              <h2>Nenhum lançamento ainda</h2>
              <p>
                Aperte <b>Simular lançamento</b>: o estoque lapida uma gema nova
                em 10 unidades e 200 compradores-robô chegam em 20 segundos.
                Nenhuma é vendida a mais.
              </p>
            </div>
          </div>
        ) : (
          <div className="lanc">
            <Palco
              lancamento={exibido}
              gema={exibido.edicaoId ? gemas.get(exibido.edicaoId) : undefined}
              agora={agora}
            />
            <Fila
              situacao={situacao}
              meu={meu}
              gemas={gemas}
              exibido={exibido}
            />
          </div>
        )}
      </div>
    </section>
  );
}

function Etapas({ lancamento }: { lancamento: Lanc }) {
  const falhou = lancamento.estado === 'FALHOU';
  // falhar só acontece ao lapidar (a edição foi recusada ou o estoque não respondeu)
  const atual = falhou
    ? 1
    : ETAPAS.findIndex((e) => e.estado === lancamento.estado);
  return (
    <ol className="etapas" aria-label="Etapas do lançamento">
      {ETAPAS.map((e, i) => {
        const classe =
          i === atual
            ? falhou
              ? 'falhou'
              : 'agora'
            : i < atual
              ? 'feito'
              : '';
        return (
          <li
            key={e.estado}
            className={classe}
            aria-current={i === atual ? 'step' : undefined}
          >
            {e.nome}
            {i === atual && falhou && <span className="sr"> (falhou)</span>}
          </li>
        );
      })}
    </ol>
  );
}

function Palco({
  lancamento: l,
  gema,
  agora,
}: {
  lancamento: Lanc;
  gema: GemaDaEdicao | undefined;
  agora: number;
}) {
  const unidades = gema?.total ?? 10;
  const vendidos = Math.min(l.vendidos, unidades);
  const motivo = motivoEmPalavras(l.motivo);

  let titulo: string;
  if (gema) titulo = `${gema.nome}, edição ${gema.numero}`;
  else if (l.estado === 'NA_FILA') titulo = 'A próxima gema';
  else if (l.estado === 'ABRINDO') titulo = 'Uma gema nova';
  else titulo = 'Lançamento';

  let sub: string;
  if (l.estado === 'NA_FILA')
    sub = l.posicaoNaFila
      ? `${l.posicaoNaFila}º da fila · os robôs saem quando chegar a vez`
      : 'Na fila · os robôs saem quando chegar a vez';
  else if (l.estado === 'ABRINDO')
    sub = 'O estoque está lapidando a edição de 10 unidades';
  else if (l.estado === 'SOLTANDO' || l.estado === 'ESPERANDO')
    sub = l.comecouEm
      ? `10 robôs por segundo · começou há ${duracao(Math.floor((agora - Date.parse(l.comecouEm)) / 1000))}`
      : '10 robôs por segundo';
  else sub = l.terminouEm ? `Terminou às ${hora(l.terminouEm)}` : 'Terminou';

  return (
    <div className="lanc-palco">
      <Etapas lancamento={l} />
      <h2 className="lanc-nome">{titulo}</h2>
      <p className="lanc-sub">{sub}</p>
      {motivo && (
        <p className={l.estado === 'FALHOU' ? 'aviso' : 'lanc-sub'}>{motivo}</p>
      )}

      <ul
        className="estojo"
        aria-label={`${vendidos} de ${unidades} unidades vendidas`}
      >
        {Array.from({ length: unidades }, (_, i) => (
          <li key={i} className={i < vendidos ? 'vendida' : undefined}>
            <Gema
              cor={gema?.cor ?? 'lilas'}
              fluorescencia={gema?.fluorescencia}
            />
            <span className="sr">
              Unidade {i + 1}: {i < vendidos ? 'vendida' : 'no estojo'}
            </span>
          </li>
        ))}
      </ul>

      <dl className="contas">
        <div className="v">
          <dt>vendidas</dt>
          <dd className="num">{l.vendidos}</dd>
        </div>
        <div className="p">
          <dt>em andamento</dt>
          <dd className="num">{l.emAndamento}</dd>
        </div>
        <div className="r">
          <dt>desfeitas</dt>
          <dd className="num">{l.desfeitos}</dd>
        </div>
        <div>
          <dt>chegaram tarde</dt>
          <dd className="num">{l.semEstoque}</dd>
        </div>
      </dl>

      <div className="robos">
        <span className="robos-trilho" aria-hidden="true">
          <i
            style={{
              transform: `scaleX(${l.robos ? l.soltos / l.robos : 0})`,
            }}
          />
        </span>
        <span className="num">
          {l.soltos} de {l.robos} robôs soltos
        </span>
      </div>
    </div>
  );
}

function Fila({
  situacao,
  meu,
  gemas,
  exibido,
}: {
  situacao: SituacaoDosLancamentos;
  meu: string | null;
  gemas: ReadonlyMap<number, GemaDaEdicao>;
  exibido: Lanc;
}) {
  const { atual, fila } = situacao;
  const recentes = situacao.recentes.filter((l) => l.id !== exibido.id);
  const nome = (l: Lanc) =>
    (l.edicaoId != null && gemas.get(l.edicaoId)?.nome) || 'Lançamento';

  return (
    <div className="fila">
      <h3>Fila de lançamentos</h3>
      <ol>
        {atual && ATIVOS.includes(atual.estado) && (
          <li className={atual.id === meu ? 'voce' : undefined}>
            <span>{atual.id === meu ? 'O seu' : nome(atual)} · agora</span>
            <span className="num">
              {atual.estado === 'SOLTANDO'
                ? `${atual.soltos} de ${atual.robos} soltos`
                : ETAPA[atual.estado]}
            </span>
          </li>
        )}
        {fila.map((l) => (
          <li key={l.id} className={l.id === meu ? 'voce' : undefined}>
            <span>{l.id === meu ? 'O seu' : 'Outro visitante'}</span>
            <span className="num">
              {l.posicaoNaFila ? `${l.posicaoNaFila}º da fila` : 'na fila'}
            </span>
          </li>
        ))}
        {!(atual && ATIVOS.includes(atual.estado)) && !fila.length && (
          <li className="fila-vazia">
            <span>Ninguém na fila agora.</span>
          </li>
        )}
      </ol>
      <p className="regra">
        Até 5 esperando e 1 pedido por minuto para cada visitante.
      </p>

      {recentes.length > 0 && (
        <>
          <h3>Os últimos</h3>
          <ol className="recentes">
            {recentes.map((l) => (
              <li key={l.id} className={l.id === meu ? 'voce' : undefined}>
                <span>{l.id === meu ? `O seu · ${nome(l)}` : nome(l)}</span>
                <span className="num">
                  {l.estado === 'FALHOU'
                    ? 'falhou'
                    : `${l.vendidos} vendidas · ${l.semEstoque} tarde · ${l.desfeitos} desfeitas`}
                </span>
              </li>
            ))}
          </ol>
        </>
      )}
    </div>
  );
}
