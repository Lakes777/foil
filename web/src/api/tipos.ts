/** Os formatos que os serviços devolvem (JSON do Spring: Instant vira texto ISO). */

export type Raridade = 'comum' | 'incomum' | 'rara' | 'lendaria';

export interface Carta {
  id: number;
  nome: string;
  raridade: Raridade;
  descricao: string;
  mohs: number;
  cor: string;
  fluorescencia: string | null;
}

export interface Edicao {
  id: number;
  cartaId: number;
  numero: number;
  total: number;
  disponivel: number;
  precoCentavos: number;
  criadaEm: string;
}

export interface ItemDaVitrine {
  carta: Carta;
  edicao: Edicao;
}

export type EstadoDoPedido =
  | 'RESERVANDO'
  | 'AGUARDANDO_PAGAMENTO'
  | 'LIBERANDO'
  | 'CONFIRMADO'
  | 'SEM_ESTOQUE'
  | 'CANCELADO';

export interface Pedido {
  id: string;
  edicaoId: number;
  estado: EstadoDoPedido;
  motivo: string | null;
  precoCentavos: number | null;
  prazo: string | null;
  criadoEm: string;
  atualizadoEm: string;
  forma: 'FIEL' | 'SIMULADA';
  /** Só na forma FIEL, depois que a cobrança existe: o texto do QR (a tela desenha a imagem) e o link para pagar. */
  qr: string | null;
  linkParaPagar: string | null;
  lancamentoId: string | null;
}

/** Uma compra deste navegador; `unidade` só nos confirmados (a ordem entre os confirmados da edição). */
export interface CompraDoVisitante {
  pedido: Pedido;
  unidade: number | null;
}

export type EstadoDoLancamento =
  'NA_FILA' | 'ABRINDO' | 'SOLTANDO' | 'ESPERANDO' | 'ENCERRADO' | 'FALHOU';

export interface Lancamento {
  id: string;
  estado: EstadoDoLancamento;
  motivo: string | null;
  edicaoId: number | null;
  robos: number;
  soltos: number;
  posicaoNaFila: number | null;
  pedidoEm: string;
  comecouEm: string | null;
  terminouEm: string | null;
  vendidos: number;
  semEstoque: number;
  desfeitos: number;
  emAndamento: number;
}

export interface SituacaoDosLancamentos {
  atual: Lancamento | null;
  fila: Lancamento[];
  recentes: Lancamento[];
}

/** O que chega pelo SSE: o envelope inteiro de cada evento de pedidos.eventos. */
export interface Envelope<D = unknown> {
  id: string;
  tipo: string;
  versao: number;
  ocorridoEm: string;
  /** Nos eventos de lançamento, é o id do lançamento (a chave de partição). */
  pedidoId: string;
  dados: D;
}

export interface PedidoMudouDeEstado {
  estado: EstadoDoPedido;
  motivo: string | null;
  edicaoId: number;
}

export interface LancamentoMudouDeEstado {
  estado: EstadoDoLancamento;
  motivo: string | null;
  edicaoId: number | null;
  robos: number;
  soltos: number;
}
