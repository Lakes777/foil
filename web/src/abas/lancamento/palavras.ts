import type { EstadoDoLancamento } from '../../api/tipos';

/** As etapas na ordem em que aparecem nas pílulas (FALHOU não é etapa: marca a de lapidar como falha). */
export const ETAPAS: readonly { estado: EstadoDoLancamento; nome: string }[] = [
  { estado: 'NA_FILA', nome: 'Na fila' },
  { estado: 'ABRINDO', nome: 'Lapidando' },
  { estado: 'SOLTANDO', nome: 'Vendendo' },
  { estado: 'ESPERANDO', nome: 'Conferindo' },
  { estado: 'ENCERRADO', nome: 'Encerrado' },
];

/** A etapa em uma palavra minúscula (painel de eventos e fila). */
export const ETAPA: Record<EstadoDoLancamento, string> = {
  NA_FILA: 'na fila',
  ABRINDO: 'lapidando',
  SOLTANDO: 'vendendo',
  ESPERANDO: 'conferindo',
  ENCERRADO: 'encerrado',
  FALHOU: 'falhou',
};

/** Os motivos do servidor em palavras de quem assiste. */
export const MOTIVO_DO_LANCAMENTO: Record<string, string> = {
  ESTOQUE_SEM_RESPOSTA:
    'O estoque não respondeu a tempo, nem depois de 5 tentativas.',
  SEM_CARTA_RARA: 'Não havia nenhuma gema rara ou lendária para lapidar.',
  UNIDADES_INVALIDAS: 'O estoque recusou o tamanho da edição.',
  PRAZO:
    'Encerrado pelo prazo de 10 minutos; as compras que faltavam terminam sozinhas.',
};

export function motivoEmPalavras(motivo: string | null): string | null {
  if (!motivo) return null;
  return MOTIVO_DO_LANCAMENTO[motivo] ?? `Motivo: ${motivo}.`;
}

export const ATIVOS: readonly EstadoDoLancamento[] = [
  'ABRINDO',
  'SOLTANDO',
  'ESPERANDO',
];
