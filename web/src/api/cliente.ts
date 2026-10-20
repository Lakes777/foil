import type {
  CompraDoVisitante,
  Edicao,
  Envelope,
  ItemDaVitrine,
  Lancamento,
  Pedido,
  SituacaoDosLancamentos,
} from './tipos';

/** Na VM o Caddy (e em dev o Vite) tira o prefixo e manda para o serviço certo. */
const ESTOQUE = '/api/estoque';
const PEDIDOS = '/api/pedidos';

/** Uma recusa do servidor, com a mensagem dele (RFC 9457) e, no 429/503, quantos segundos esperar. */
export class ErroDaApi extends Error {
  constructor(
    readonly status: number,
    mensagem: string,
    readonly tenteEmSegundos: number | null = null,
  ) {
    super(mensagem);
    this.name = 'ErroDaApi';
  }
}

const MENSAGEM_PADRAO: Record<number, string> = {
  404: 'Não encontrado.',
  429: 'Muitas tentativas seguidas. Espere um pouco e tente de novo.',
  503: 'Está lotado agora. Tente daqui a pouco.',
};

async function pedir<T>(caminho: string, opcoes?: RequestInit): Promise<T> {
  let resposta: Response;
  try {
    resposta = await fetch(caminho, { credentials: 'same-origin', ...opcoes });
  } catch {
    throw new ErroDaApi(
      0,
      'Sem conexão com a loja. Confira a internet e tente de novo.',
    );
  }
  if (!resposta.ok) {
    let mensagem =
      MENSAGEM_PADRAO[resposta.status] ??
      `A loja recusou (${resposta.status}).`;
    try {
      const corpo = (await resposta.json()) as { detail?: string };
      if (corpo.detail) mensagem = corpo.detail;
    } catch {
      // corpo vazio ou não JSON: fica a mensagem padrão
    }
    const espera = Number(resposta.headers.get('Retry-After'));
    throw new ErroDaApi(
      resposta.status,
      mensagem,
      Number.isFinite(espera) && espera > 0 ? espera : null,
    );
  }
  return (await resposta.json()) as T;
}

export const api = {
  vitrine: () => pedir<ItemDaVitrine[]>(`${ESTOQUE}/vitrine`),
  edicao: (id: number) => pedir<Edicao>(`${ESTOQUE}/edicoes/${id}`),

  /** Compra pela loja: sempre pelo Fiel (o QR chega depois, no pedido). O cookie do visitante nasce aqui. */
  comprar: (edicaoId: number) =>
    pedir<Pedido>(`${PEDIDOS}/pedidos`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ edicaoId }),
    }),
  pedido: (id: string) => pedir<Pedido>(`${PEDIDOS}/pedidos/${id}`),
  minhas: () => pedir<CompraDoVisitante[]>(`${PEDIDOS}/minhas`),

  pedirLancamento: () =>
    pedir<Lancamento>(`${PEDIDOS}/lancamentos`, { method: 'POST' }),
  lancamentos: () => pedir<SituacaoDosLancamentos>(`${PEDIDOS}/lancamentos`),
  lancamento: (id: string) => pedir<Lancamento>(`${PEDIDOS}/lancamentos/${id}`),
};

/** Os tipos de evento que pedidos.eventos manda pelo SSE. */
export const TIPOS_DE_EVENTO = [
  'PedidoMudouDeEstado',
  'LancamentoMudouDeEstado',
] as const;

/**
 * Ouve o ao vivo (GET /eventos). O EventSource reconecta sozinho; quem chama recebe cada envelope e devolve uma
 * função para parar.
 */
export function ouvirEventos(
  aoChegar: (envelope: Envelope) => void,
  aoMudarConexao?: (aberta: boolean) => void,
) {
  const fonte = new EventSource(`${PEDIDOS}/eventos`);
  const ler = (e: MessageEvent<string>) => {
    try {
      aoChegar(JSON.parse(e.data) as Envelope);
    } catch {
      // mensagem que não é JSON: ignora
    }
  };
  for (const tipo of TIPOS_DE_EVENTO)
    fonte.addEventListener(tipo, ler as EventListener);
  fonte.onopen = () => aoMudarConexao?.(true);
  fonte.onerror = () => aoMudarConexao?.(false);
  return () => fonte.close();
}
