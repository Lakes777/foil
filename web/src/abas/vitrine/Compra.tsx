import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { createPortal } from 'react-dom';
import { api, ErroDaApi } from '../../api/cliente';
import type { ItemDaVitrine, Pedido } from '../../api/tipos';
import { dinheiro } from '../../comum/formato';
import { Gema } from '../../gemas/Gema';
import { final, motivo } from './motivo';
import { Qr } from './Qr';
import './compra.css';
import { linkSeguro } from '../../comum/linkSeguro';

/** De quanto em quanto tempo o diálogo pergunta pelo pedido. */
export const INTERVALO_DA_CONSULTA = 1500;

const FOCAVEIS =
  'a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])';

interface Props {
  item: ItemDaVitrine;
  /**
   * O POST do pedido, feito no clique de quem abriu o diálogo (não num efeito daqui: o StrictMode monta o diálogo
   * duas vezes em dev, e um efeito faria duas compras).
   */
  pedido: Promise<Pedido>;
  aoFechar: () => void;
  /** O estado do pedido mudou: a vitrine recarrega o "X de total". */
  aoMudar: () => void;
  verColecao: () => void;
}

type Situacao =
  | { tipo: 'pedindo' }
  | { tipo: 'recusada'; mensagem: string }
  | { tipo: 'acompanhando'; pedido: Pedido; falhou: string | null };

/**
 * A compra de uma gema: faz o pedido e acompanha até o QR do Fiel e, depois, até a confirmação (ou o fim sem
 * gema). Consulta o pedido a cada 1,5 s enquanto ele não chega a um estado final e para quando o diálogo fecha.
 */
export function Compra({
  item,
  pedido: pedindo,
  aoFechar,
  aoMudar,
  verColecao,
}: Props) {
  const [situacao, setSituacao] = useState<Situacao>({ tipo: 'pedindo' });
  const caixa = useRef<HTMLDivElement>(null);
  const titulo = useId();
  const pedidoId = situacao.tipo === 'acompanhando' ? situacao.pedido.id : null;
  const estado =
    situacao.tipo === 'acompanhando' ? situacao.pedido.estado : null;
  const terminou = estado !== null && final(estado);

  // a resposta do pedido (a mesma promessa, quantas vezes o efeito rodar)
  useEffect(() => {
    let vivo = true;
    pedindo.then(
      (pedido) => {
        if (vivo) setSituacao({ tipo: 'acompanhando', pedido, falhou: null });
      },
      (e: unknown) => {
        if (vivo) setSituacao({ tipo: 'recusada', mensagem: recusa(e) });
      },
    );
    return () => {
      vivo = false;
    };
  }, [pedindo]);

  // a consulta: para no estado final ou quando o diálogo fecha (o retorno do efeito cancela o próximo passo)
  useEffect(() => {
    if (!pedidoId || terminou) return;
    let vivo = true;
    let relogio: ReturnType<typeof setTimeout>;
    const consultar = async () => {
      try {
        const pedido = await api.pedido(pedidoId);
        if (!vivo) return;
        setSituacao({ tipo: 'acompanhando', pedido, falhou: null });
        if (final(pedido.estado)) return;
      } catch (e) {
        if (!vivo) return;
        if (e instanceof ErroDaApi && e.status === 404) {
          setSituacao({
            tipo: 'recusada',
            mensagem: 'A loja não encontrou mais este pedido.',
          });
          return;
        }
        setSituacao((s) =>
          s.tipo === 'acompanhando'
            ? {
                ...s,
                falhou: 'Perdi o contato com a loja. Tentando de novo...',
              }
            : s,
        );
      }
      relogio = setTimeout(consultar, INTERVALO_DA_CONSULTA);
    };
    relogio = setTimeout(consultar, INTERVALO_DA_CONSULTA);
    return () => {
      vivo = false;
      clearTimeout(relogio);
    };
  }, [pedidoId, terminou]);

  // a vitrine muda quando a gema é separada, devolvida ou vendida
  useEffect(() => {
    if (estado && estado !== 'RESERVANDO') aoMudar();
  }, [estado, aoMudar]);

  // foco: entra no diálogo, volta para quem abriu
  useEffect(() => {
    const antes = document.activeElement as HTMLElement | null;
    caixa.current?.focus();
    return () => antes?.focus?.();
  }, []);

  function teclas(e: KeyboardEvent<HTMLDivElement>) {
    if (e.key === 'Escape') {
      e.stopPropagation();
      aoFechar();
      return;
    }
    if (e.key !== 'Tab' || !caixa.current) return;
    const lista = [...caixa.current.querySelectorAll<HTMLElement>(FOCAVEIS)];
    if (!lista.length) {
      e.preventDefault();
      return;
    }
    const primeiro = lista[0];
    const ultimo = lista[lista.length - 1];
    const agora = document.activeElement;
    if (e.shiftKey && (agora === primeiro || agora === caixa.current)) {
      e.preventDefault();
      ultimo.focus();
    } else if (!e.shiftKey && agora === ultimo) {
      e.preventDefault();
      primeiro.focus();
    }
  }

  return createPortal(
    <div
      className="compra-fundo"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) aoFechar();
      }}
    >
      <div
        ref={caixa}
        className="compra-caixa"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titulo}
        tabIndex={-1}
        onKeyDown={teclas}
      >
        <div className="compra-cab">
          <Gema cor={item.carta.cor} fluorescencia={item.carta.fluorescencia} />
          <div>
            <h2 id={titulo} className="compra-titulo">
              {item.carta.nome}
            </h2>
            <p className="compra-sub num">
              edição {item.edicao.numero} ·{' '}
              {dinheiro(item.edicao.precoCentavos)}
            </p>
          </div>
          <button
            type="button"
            className="compra-fechar"
            aria-label="Fechar"
            onClick={aoFechar}
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M6 6l12 12M18 6 6 18" />
            </svg>
          </button>
        </div>
        <div className="compra-corpo" aria-live="polite">
          {conteudo(situacao, aoFechar, verColecao)}
        </div>
      </div>
    </div>,
    document.body,
  );
}

function recusa(e: unknown): string {
  if (e instanceof ErroDaApi) {
    if (e.status === 429)
      return e.tenteEmSegundos
        ? `Muitas compras seguidas deste endereço. Tente de novo em ${e.tenteEmSegundos} segundos.`
        : 'Muitas compras seguidas deste endereço. Espere um minuto e tente de novo.';
    return e.message;
  }
  return 'Algo deu errado ao fazer o pedido.';
}

function conteudo(
  situacao: Situacao,
  aoFechar: () => void,
  verColecao: () => void,
) {
  if (situacao.tipo === 'pedindo')
    return <Passo titulo="Fazendo o pedido..." texto="Falando com a loja." />;
  if (situacao.tipo === 'recusada')
    return (
      <>
        <p className="compra-recusa">{situacao.mensagem}</p>
        <div className="compra-acoes">
          <button type="button" className="btn btn-leve" onClick={aoFechar}>
            Fechar
          </button>
        </div>
      </>
    );

  const { pedido, falhou } = situacao;
  const aviso = falhou && <p className="compra-falhou">{falhou}</p>;
  switch (pedido.estado) {
    case 'CONFIRMADO':
      return (
        <>
          <p className="compra-final ok">Confirmada!</p>
          <p className="compra-texto">
            O Fiel avisou que foi pago. A gema é sua, com certificado de
            lapidação.
          </p>
          <div className="compra-acoes">
            <button
              type="button"
              className="btn btn-forte"
              onClick={() => {
                aoFechar();
                verColecao();
              }}
            >
              Veja em Minhas gemas
            </button>
          </div>
        </>
      );
    case 'SEM_ESTOQUE':
      return (
        <Fim
          titulo="Sem estoque: alguém levou a última."
          texto="Nenhum dinheiro saiu da sua carteira."
          aoFechar={aoFechar}
        />
      );
    case 'CANCELADO':
      return (
        <Fim
          titulo="Cancelada"
          texto={
            motivo(pedido.motivo)
              ? `Motivo: ${motivo(pedido.motivo)}. A gema voltou para a vitrine.`
              : 'A gema voltou para a vitrine.'
          }
          aoFechar={aoFechar}
        />
      );
    case 'LIBERANDO':
      return (
        <>
          <Passo
            titulo="Devolvendo a gema..."
            texto={
              motivo(pedido.motivo)
                ? `${motivo(pedido.motivo)}; a gema está voltando para a vitrine.`
                : 'A gema está voltando para a vitrine.'
            }
          />
          {aviso}
        </>
      );
    default:
      if (pedido.qr)
        return (
          <>
            <div className="compra-qr">
              <Qr texto={pedido.qr} />
            </div>
            <p className="compra-texto">
              Aponte a câmera para o QR ou pague direto no Fiel. A gema fica
              separada para você até o prazo acabar.
            </p>
            {pedido.prazo && <Prazo ate={pedido.prazo} />}
            <div className="compra-acoes">
              {linkSeguro(pedido.linkParaPagar) && (
                <a
                  className="btn btn-forte"
                  href={linkSeguro(pedido.linkParaPagar)!}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  Pagar no Fiel
                  <span className="sr"> (abre em outra aba)</span>
                </a>
              )}
            </div>
            {aviso}
          </>
        );
      return (
        <>
          <Passo
            titulo={
              pedido.estado === 'RESERVANDO'
                ? 'Separando a gema...'
                : 'Gerando a cobrança no Fiel...'
            }
            texto={
              pedido.estado === 'RESERVANDO'
                ? 'O estoque está reservando uma unidade para você.'
                : 'Gema separada. O QR aparece em instantes.'
            }
          />
          {aviso}
        </>
      );
  }
}

function Passo({ titulo, texto }: { titulo: string; texto: string }) {
  return (
    <div className="compra-passo">
      <span className="compra-lapidando" aria-hidden="true" />
      <div>
        <p className="compra-passo-titulo">{titulo}</p>
        <p className="compra-texto">{texto}</p>
      </div>
    </div>
  );
}

function Fim({
  titulo,
  texto,
  aoFechar,
}: {
  titulo: string;
  texto: string;
  aoFechar: () => void;
}) {
  return (
    <>
      <p className="compra-final ruim">{titulo}</p>
      <p className="compra-texto">{texto}</p>
      <div className="compra-acoes">
        <button type="button" className="btn btn-leve" onClick={aoFechar}>
          Voltar à vitrine
        </button>
      </div>
    </>
  );
}

/** O prazo para pagar, contando (o servidor manda quando acaba em `prazo`). */
function Prazo({ ate }: { ate: string }) {
  const fim = new Date(ate).getTime();
  const [agora, setAgora] = useState(() => Date.now());
  useEffect(() => {
    const relogio = setInterval(() => setAgora(Date.now()), 1000);
    return () => clearInterval(relogio);
  }, []);
  const resta = Math.max(0, Math.round((fim - agora) / 1000));
  const minutos = Math.floor(resta / 60);
  const segundos = String(resta % 60).padStart(2, '0');
  return (
    <p className="compra-prazo" aria-live="off">
      {resta > 0 ? (
        <>
          Prazo para pagar:{' '}
          <b className="num">
            {minutos}:{segundos}
          </b>
        </>
      ) : (
        'O prazo acabou. A loja vai devolver a gema à vitrine.'
      )}
    </p>
  );
}
