import { useCallback, useEffect, useState } from 'react';
import { api, ErroDaApi } from '../api/cliente';
import type { Carta, CompraDoVisitante, Edicao } from '../api/tipos';
import {
  dataEHora,
  dinheiro,
  ESTADO_DO_PEDIDO,
  RARIDADE,
} from '../comum/formato';
import { Gema } from '../gemas/Gema';
import { NOME_DA_FLUORESCENCIA } from '../gemas/desenho';
import { idCurto, Inspetor, type GemaComprada } from './colecao/Inspetor';
import { motivo } from './vitrine/motivo';
import './minhas-gemas.css';
import { linkSeguro } from '../comum/linkSeguro';

/** Uma compra deste navegador com a edição e a carta (quando a loja ainda as conhece). */
interface Compra extends CompraDoVisitante {
  edicao: Edicao | null;
  carta: Carta | null;
}

type Carga =
  | { tipo: 'carregando' }
  | { tipo: 'erro'; mensagem: string }
  | { tipo: 'pronta'; compras: Compra[] };

/**
 * Junta as compras com as edições (uma consulta por edição) e as cartas (a vitrine, uma vez, cruzada por `cartaId`).
 * Só o 404 (a edição foi apagada) deixa a compra sem nome; qualquer outra falha vira erro com "Tentar de novo",
 * senão uma gema paga apareceria como "não deu certo" só porque a rede oscilou.
 */
async function carregar(): Promise<Compra[]> {
  const [minhas, vitrine] = await Promise.all([api.minhas(), api.vitrine()]);
  const cartas = new Map(vitrine.map((i) => [i.carta.id, i.carta]));
  const ids = [...new Set(minhas.map((m) => m.pedido.edicaoId))];
  const edicoes = new Map(
    await Promise.all(
      ids.map(
        async (id) =>
          [
            id,
            await api.edicao(id).catch((e: unknown) => {
              if (e instanceof ErroDaApi && e.status === 404) return null;
              throw e;
            }),
          ] as const,
      ),
    ),
  );
  return minhas.map((m) => {
    const edicao = edicoes.get(m.pedido.edicaoId) ?? null;
    return {
      ...m,
      edicao,
      carta: edicao ? (cartas.get(edicao.cartaId) ?? null) : null,
    };
  });
}

function comprada(c: Compra): c is Compra & GemaComprada {
  return (
    c.pedido.estado === 'CONFIRMADO' &&
    c.unidade !== null &&
    c.edicao !== null &&
    c.carta !== null
  );
}

/** As compras deste navegador: as confirmadas no inspetor 3D; as outras, discretas, embaixo. */
export function MinhasGemas() {
  const [carga, setCarga] = useState<Carga>({ tipo: 'carregando' });
  const [escolhida, setEscolhida] = useState<string | null>(null);

  const buscar = useCallback(async () => {
    try {
      setCarga({ tipo: 'pronta', compras: await carregar() });
    } catch (e) {
      setCarga({
        tipo: 'erro',
        mensagem:
          e instanceof ErroDaApi
            ? e.message
            : 'Não deu para abrir as suas gemas.',
      });
    }
  }, []);

  useEffect(() => {
    void buscar();
  }, [buscar]);

  return (
    <section className="secao minhas-gemas" aria-labelledby="titulo-colecao">
      <div className="miolo">
        <div className="colecao-cab">
          <h1 id="titulo-colecao" className="titulo">
            Minhas <em>gemas</em>
          </h1>
          <p className="desc">
            As que você comprou neste navegador. Arraste a carta para girar,
            vire para ler o certificado da unidade. Com a luz negra ligada, o
            certificado mostra a marca de autenticidade escondida.
          </p>
        </div>
        {carga.tipo === 'carregando' && (
          <div className="colecao-esperando" aria-busy="true">
            <p className="sr">Carregando as suas gemas...</p>
            <div className="colecao-osso" aria-hidden="true" />
          </div>
        )}
        {carga.tipo === 'erro' && (
          <div className="colecao-vazia" role="alert">
            <p>{carga.mensagem}</p>
            <button
              className="btn btn-leve"
              type="button"
              onClick={() => {
                setCarga({ tipo: 'carregando' });
                void buscar();
              }}
            >
              Tentar de novo
            </button>
          </div>
        )}
        {carga.tipo === 'pronta' && (
          <Colecao
            compras={carga.compras}
            escolhida={escolhida}
            escolher={setEscolhida}
          />
        )}
      </div>
    </section>
  );
}

function Colecao({
  compras,
  escolhida,
  escolher,
}: {
  compras: Compra[];
  escolhida: string | null;
  escolher: (id: string) => void;
}) {
  const gemas = compras.filter(comprada);
  const andando = compras.filter(
    (c) =>
      c.pedido.estado === 'RESERVANDO' ||
      c.pedido.estado === 'AGUARDANDO_PAGAMENTO',
  );
  const perdidas = compras.filter(
    (c) => c.pedido.estado === 'SEM_ESTOQUE' || c.pedido.estado === 'CANCELADO',
  );
  const atual = gemas.find((g) => g.pedido.id === escolhida) ?? gemas[0];

  return (
    <>
      {atual ? (
        <div className="colecao">
          <div className="colecao-lista" role="group" aria-label="Suas gemas">
            {gemas.map((g) => (
              <button
                key={g.pedido.id}
                type="button"
                aria-pressed={g === atual}
                onClick={() => escolher(g.pedido.id)}
              >
                <Gema cor={g.carta.cor} fluorescencia={g.carta.fluorescencia} />
                <span>
                  <b>{g.carta.nome}</b>
                  <small className="num">
                    unidade {g.unidade} de {g.edicao.total}
                  </small>
                </span>
              </button>
            ))}
          </div>
          <Inspetor gema={atual} />
          <Ficha gema={atual} />
        </div>
      ) : (
        <Vazia
          titulo={
            andando.length
              ? 'Nenhuma gema confirmada ainda'
              : 'Você ainda não tem gemas'
          }
          texto={
            andando.length
              ? 'Assim que o Fiel confirmar o pagamento, a gema aparece aqui com o certificado dela.'
              : 'Quando comprar uma na vitrine, ela aparece aqui: dá para girar a carta, ler o certificado e procurar a marca escondida com a luz negra.'
          }
        />
      )}
      {andando.length > 0 && (
        <div className="colecao-outras">
          <h2 className="colecao-outras-titulo">Em andamento</h2>
          <ul>
            {andando.map((c) => (
              <li key={c.pedido.id}>
                <Resumo compra={c} />
                <span className="estado meio">
                  {ESTADO_DO_PEDIDO[c.pedido.estado]}
                </span>
                {linkSeguro(c.pedido.linkParaPagar) && (
                  <a
                    href={linkSeguro(c.pedido.linkParaPagar)!}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    Pagar no Fiel
                    <span className="sr"> (abre em outra aba)</span>
                  </a>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
      {perdidas.length > 0 && (
        <details className="colecao-outras discretas">
          <summary>
            Não deram certo <span className="num">({perdidas.length})</span>
          </summary>
          <ul>
            {perdidas.map((c) => (
              <li key={c.pedido.id}>
                <Resumo compra={c} />
                <span className="estado ruim">
                  {ESTADO_DO_PEDIDO[c.pedido.estado]}
                </span>
                {motivo(c.pedido.motivo) && (
                  <small className="colecao-outras-motivo">
                    {motivo(c.pedido.motivo)}
                  </small>
                )}
              </li>
            ))}
          </ul>
        </details>
      )}
    </>
  );
}

function Resumo({ compra }: { compra: Compra }) {
  const { pedido, carta, edicao } = compra;
  return (
    <span className="colecao-outras-resumo">
      <Gema cor={carta?.cor ?? 'cinza'} fluorescencia={carta?.fluorescencia} />
      <span>
        <b>{carta?.nome ?? 'Gema'}</b>
        <small className="num">
          {edicao ? `edição ${edicao.numero} · ` : ''}
          pedido {idCurto(pedido.id)} · {dataEHora(pedido.criadoEm)}
        </small>
      </span>
    </span>
  );
}

function Ficha({ gema }: { gema: GemaComprada }) {
  const { pedido, unidade, edicao, carta } = gema;
  const fluorescencia = carta.fluorescencia
    ? (NOME_DA_FLUORESCENCIA[carta.fluorescencia] ?? 'nenhuma')
    : 'nenhuma';
  const preco = pedido.precoCentavos ?? edicao.precoCentavos;
  return (
    <aside className="ficha-gema" aria-label="Ficha da gema">
      <h2 className="ficha-gema-nome">{carta.nome}</h2>
      <div className="ficha-gema-unidade num">
        {unidade}
        <small>de {edicao.total}</small>
      </div>
      <dl>
        <dt>Raridade</dt>
        <dd>{RARIDADE[carta.raridade]}</dd>
        <dt>Dureza Mohs</dt>
        <dd className="num">{carta.mohs} de 10</dd>
        <dt>Fluorescência</dt>
        <dd>{fluorescencia}</dd>
        <dt>Edição</dt>
        <dd className="num">{edicao.numero}</dd>
        <dt>Pago</dt>
        <dd className="num">
          {dinheiro(preco)}
          {pedido.forma === 'FIEL' ? ' pelo Fiel' : ''}
        </dd>
        <dt>Comprada em</dt>
        <dd className="num">{dataEHora(pedido.atualizadoEm)}</dd>
      </dl>
      <p className="ficha-gema-raro">
        {unidade === 1
          ? `A primeira unidade das ${edicao.total} desta edição.`
          : `Das ${edicao.total} desta edição, a sua foi a ${unidade}ª a ser vendida.`}
      </p>
    </aside>
  );
}

function Vazia({ titulo, texto }: { titulo: string; texto: string }) {
  return (
    <div className="colecao-vazia">
      <div className="vazia-estojo" aria-hidden="true">
        {Array.from({ length: 3 }, (_, i) => (
          <span key={i}>
            <Gema cor="cinza" />
          </span>
        ))}
      </div>
      <h2 className="vazia-titulo">{titulo}</h2>
      <p>{texto}</p>
      <a className="btn btn-forte" href="#vitrine">
        Ir para a vitrine
      </a>
    </div>
  );
}
