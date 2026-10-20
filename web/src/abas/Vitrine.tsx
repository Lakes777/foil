import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ErroDaApi } from '../api/cliente';
import type { ItemDaVitrine, Pedido } from '../api/tipos';
import type { Aba } from '../comum/abas';
import { dinheiro, RARIDADE } from '../comum/formato';
import { Gema } from '../gemas/Gema';
import { NOME_DA_FLUORESCENCIA } from '../gemas/desenho';
import { CartaEmDestaque, CartaEsperando } from './vitrine/CartaEmDestaque';
import { Compra } from './vitrine/Compra';
import './vitrine.css';

type Carga =
  | { tipo: 'carregando' }
  | { tipo: 'erro'; mensagem: string }
  | { tipo: 'pronta'; itens: ItemDaVitrine[] };

function mensagemDe(e: unknown): string {
  return e instanceof ErroDaApi ? e.message : 'Não deu para abrir a vitrine.';
}

/** A abertura (a gema mais rara em destaque) e as gemas à venda, com a compra pelo Fiel. */
export function Vitrine({ abrir }: { abrir: (aba: Aba) => void }) {
  const [carga, setCarga] = useState<Carga>({ tipo: 'carregando' });
  const [comprando, setComprando] = useState<{
    item: ItemDaVitrine;
    pedido: Promise<Pedido>;
  } | null>(null);
  const ultimaCarga = useRef(0);

  /**
   * Recarrega sem esconder o que já está na tela (depois de uma compra, só os números mudam). Uma resposta mais
   * velha que a última pedida é descartada: senão uma recarga lenta mostraria o estoque de antes da venda.
   */
  const recarregar = useCallback(async () => {
    const esta = ++ultimaCarga.current;
    try {
      const itens = await api.vitrine();
      if (esta !== ultimaCarga.current) return;
      setCarga({ tipo: 'pronta', itens });
    } catch (e) {
      if (esta !== ultimaCarga.current) return;
      setCarga((atual) =>
        atual.tipo === 'pronta'
          ? atual
          : { tipo: 'erro', mensagem: mensagemDe(e) },
      );
    }
  }, []);

  useEffect(() => {
    void recarregar();
  }, [recarregar]);

  function tentarDeNovo() {
    setCarga({ tipo: 'carregando' });
    void recarregar();
  }

  const destaque = carga.tipo === 'pronta' ? carga.itens[0] : undefined;

  return (
    <div className="vitrine-aba">
      <section className="abertura">
        <div className="miolo abertura-miolo">
          <div>
            <h1 className="abertura-titulo">
              Gemas que não existem, vendidas <em>uma de cada vez</em>.
            </h1>
            <p className="abertura-lead">
              Cada lançamento lapida uma gema nova em dez unidades. Duzentos
              compradores chegam no mesmo segundo, e nenhuma é vendida a mais.
            </p>
            <div className="abertura-acoes">
              <button
                className="btn btn-forte"
                type="button"
                onClick={() => abrir('lancamento')}
              >
                Simular lançamento
              </button>
              <button
                className="btn btn-leve"
                type="button"
                onClick={() => abrir('por-dentro')}
              >
                Ver os eventos ao vivo
              </button>
            </div>
            <dl className="abertura-fatos">
              <div>
                <dt>unidades por edição</dt>
                <dd className="num">10</dd>
              </div>
              <div>
                <dt>compradores-robô</dt>
                <dd className="num">200</dd>
              </div>
              <div>
                <dt>vendas a mais</dt>
                <dd className="num">0</dd>
              </div>
            </dl>
          </div>
          <div className="abertura-palco">
            {destaque ? (
              <CartaEmDestaque item={destaque} />
            ) : carga.tipo === 'carregando' ? (
              <CartaEsperando />
            ) : null}
          </div>
        </div>
      </section>

      <section className="secao" aria-labelledby="titulo-vitrine">
        <div className="miolo">
          <div className="vitrine-cab">
            <h2 id="titulo-vitrine" className="titulo">
              A <em>vitrine</em>
            </h2>
            <p className="desc">
              Compra de verdade com dinheiro de mentira: o pagamento passa pelo
              Fiel, por QR.
            </p>
            <p className="aviso-uv">
              Algumas gemas reagem à luz negra. Ligue no topo da página e veja
              quais acendem.
            </p>
          </div>
          {carga.tipo === 'carregando' && <Esqueleto />}
          {carga.tipo === 'erro' && (
            <div className="vitrine-vazia" role="alert">
              <p>{carga.mensagem}</p>
              <button
                className="btn btn-leve"
                type="button"
                onClick={tentarDeNovo}
              >
                Tentar de novo
              </button>
            </div>
          )}
          {carga.tipo === 'pronta' && carga.itens.length === 0 && (
            <div className="vitrine-vazia">
              <p>
                A vitrine está vazia agora. As gemas voltam quando o próximo
                lançamento lapidar uma edição nova.
              </p>
            </div>
          )}
          {carga.tipo === 'pronta' && carga.itens.length > 0 && (
            <ul className="pecas">
              {carga.itens.map((item) => (
                <Peca
                  key={item.edicao.id}
                  item={item}
                  comprar={() => {
                    const pedido = api.comprar(item.edicao.id);
                    // a recusa é mostrada pelo diálogo; isto só evita o aviso de promessa sem tratamento
                    pedido.catch(() => {});
                    setComprando({ item, pedido });
                  }}
                />
              ))}
            </ul>
          )}
        </div>
      </section>

      {comprando && (
        <Compra
          item={comprando.item}
          pedido={comprando.pedido}
          aoFechar={() => setComprando(null)}
          aoMudar={recarregar}
          verColecao={() => abrir('colecao')}
        />
      )}
    </div>
  );
}

function Peca({ item, comprar }: { item: ItemDaVitrine; comprar: () => void }) {
  const { carta, edicao } = item;
  const esgotada = edicao.disponivel === 0;
  const fluoresce = carta.fluorescencia
    ? NOME_DA_FLUORESCENCIA[carta.fluorescencia]
    : undefined;
  return (
    <li className={esgotada ? 'peca esgotada' : 'peca'}>
      <div className="peca-vidro">
        <Gema cor={carta.cor} fluorescencia={carta.fluorescencia} />
      </div>
      <h3 className="peca-nome">{carta.nome}</h3>
      <div className="peca-ficha">
        <span>
          Mohs {carta.mohs} · {RARIDADE[carta.raridade]}
          {fluoresce && <span className="so-uv"> · fluoresce {fluoresce}</span>}
        </span>
        <span className="num">
          {esgotada ? 'esgotada' : `${edicao.disponivel} de ${edicao.total}`}
        </span>
      </div>
      <div className="peca-comprar">
        <span className="peca-preco num">{dinheiro(edicao.precoCentavos)}</span>
        {esgotada ? (
          <button className="btn btn-leve" type="button" disabled>
            Esgotada
          </button>
        ) : (
          <button
            className="btn btn-forte"
            type="button"
            aria-label={`Comprar ${carta.nome}`}
            onClick={comprar}
          >
            Comprar
          </button>
        )}
      </div>
    </li>
  );
}

function Esqueleto() {
  return (
    <div className="pecas" aria-busy="true">
      <p className="sr">Carregando a vitrine...</p>
      {Array.from({ length: 6 }, (_, i) => (
        <div key={i} className="peca esperando" aria-hidden="true">
          <div className="peca-vidro" />
          <span className="osso osso-titulo" />
          <span className="osso osso-curto" />
        </div>
      ))}
    </div>
  );
}
