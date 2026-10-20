import type { EstadoDoPedido, Raridade } from '../api/tipos';

const REAIS = new Intl.NumberFormat('pt-BR', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** Dinheiro de mentira: "F$ 18,00". */
export function dinheiro(centavos: number): string {
  return `F$ ${REAIS.format(centavos / 100)}`;
}

export const RARIDADE: Record<Raridade, string> = {
  comum: 'comum',
  incomum: 'incomum',
  rara: 'rara',
  lendaria: 'lendária',
};

/** O estado do pedido em palavras de quem compra. */
export const ESTADO_DO_PEDIDO: Record<EstadoDoPedido, string> = {
  RESERVANDO: 'separando a gema',
  AGUARDANDO_PAGAMENTO: 'esperando o pagamento',
  LIBERANDO: 'devolvendo a gema',
  CONFIRMADO: 'confirmada',
  SEM_ESTOQUE: 'sem estoque',
  CANCELADO: 'cancelada',
};

const HORA = new Intl.DateTimeFormat('pt-BR', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
});
const DATA_E_HORA = new Intl.DateTimeFormat('pt-BR', {
  dateStyle: 'short',
  timeStyle: 'short',
});

export function hora(iso: string): string {
  return HORA.format(new Date(iso));
}

export function dataEHora(iso: string): string {
  return DATA_E_HORA.format(new Date(iso));
}
