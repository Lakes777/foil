import type { EstadoDoPedido } from '../../api/tipos';

/** Os motivos que a saga grava no pedido, em palavras de quem compra. */
const MOTIVOS: Record<string, string> = {
  SEM_ESTOQUE: 'alguém levou a última unidade antes',
  EDICAO_INEXISTENTE: 'essa edição não existe mais',
  JA_DEVOLVIDA: 'a gema já tinha voltado para a vitrine',
  PRAZO_DA_RESERVA: 'o estoque demorou demais para separar a gema',
  PRAZO_DO_PAGAMENTO: 'o prazo de 5 minutos para pagar acabou',
  PAGAMENTO_RECUSADO: 'o Fiel recusou o pagamento',
  PAGAMENTO_CANCELADA: 'a cobrança foi cancelada no Fiel',
};

/** Código desconhecido não some: aparece em minúsculas, sem os sublinhados. */
export function motivo(codigo: string | null): string | null {
  if (!codigo) return null;
  return MOTIVOS[codigo] ?? codigo.toLowerCase().replaceAll('_', ' ');
}

export const ESTADOS_FINAIS: readonly EstadoDoPedido[] = [
  'CONFIRMADO',
  'SEM_ESTOQUE',
  'CANCELADO',
];

export function final(estado: EstadoDoPedido): boolean {
  return ESTADOS_FINAIS.includes(estado);
}
