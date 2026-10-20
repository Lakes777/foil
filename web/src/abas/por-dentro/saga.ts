import type { EstadoDoPedido } from '../../api/tipos';
import { hora } from '../../comum/formato';

/** Um estado que o pedido atingiu (do SSE ou do GET do pedido) e quando. */
export interface Marco {
  estado: EstadoDoPedido;
  motivo: string | null;
  ocorridoEm: string;
}

export interface Passo {
  titulo: string;
  /** Qual serviço fez o quê, em uma frase. */
  detalhe: string;
  hora: string | null;
  feito: boolean;
  tom: 'normal' | 'ok' | 'ruim';
}

/** Como a compra desfeita aparece, pelo motivo do cancelamento. */
const FALHA: Record<string, { titulo: string; detalhe: string }> = {
  PAGAMENTO_RECUSADO: {
    titulo: 'Pagamento recusado',
    detalhe: 'pagamentos recusou; pedidos mandou o estoque devolver a gema',
  },
  PAGAMENTO_CANCELADA: {
    titulo: 'Cobrança cancelada',
    detalhe: 'a cobrança foi cancelada; pedidos mandou devolver a gema',
  },
  PRAZO_DO_PAGAMENTO: {
    titulo: 'O prazo do pagamento venceu',
    detalhe: 'ninguém pagou em 5 min; pedidos cancelou a cobrança e a reserva',
  },
  PRAZO_DA_RESERVA: {
    titulo: 'O estoque não respondeu',
    detalhe: 'a reserva passou de 2 min; pedidos desfez por garantia',
  },
};

const PAGAMENTO = {
  FIEL: 'pagamentos criou a cobrança no Fiel: o QR vale 5 min',
  SIMULADA: 'pagamentos cobrou o robô com dinheiro simulado',
} as const;

/**
 * Monta a linha do tempo da saga a partir do que já se viu do pedido. O caminho feliz tem quatro passos; se o pedido
 * foi desfeito, o ramo de falha substitui o fim (recusa → devolução → cancelada); sem estoque, a saga para logo.
 */
export function passosDaSaga(
  marcos: readonly Marco[],
  forma?: 'FIEL' | 'SIMULADA' | null,
): Passo[] {
  const quando = (...estados: EstadoDoPedido[]) => {
    const m = marcos.find((x) => estados.includes(x.estado));
    return m ? hora(m.ocorridoEm) : null;
  };
  const teve = (...estados: EstadoDoPedido[]) =>
    marcos.some((m) => estados.includes(m.estado));
  const motivo = marcos.findLast((m) => m.motivo)?.motivo ?? null;

  const recebido: Passo = {
    titulo: 'Pedido recebido',
    detalhe: 'pedidos registrou a compra e pediu a gema ao estoque pelo Kafka',
    hora: quando('RESERVANDO'),
    feito: true,
    tom: 'normal',
  };

  if (teve('SEM_ESTOQUE'))
    return [
      recebido,
      {
        titulo: 'Sem estoque',
        detalhe:
          'o estoque não tinha mais unidade; a saga parou aqui, sem cobrar',
        hora: quando('SEM_ESTOQUE'),
        feito: true,
        tom: 'ruim',
      },
    ];

  const desfeito = teve('LIBERANDO', 'CANCELADO');
  const separou =
    teve('AGUARDANDO_PAGAMENTO', 'CONFIRMADO') ||
    (desfeito && motivo !== 'PRAZO_DA_RESERVA');

  const meio: Passo[] = [
    {
      titulo: 'Gema separada',
      detalhe: 'o estoque reservou uma unidade e respondeu',
      hora: quando('AGUARDANDO_PAGAMENTO'),
      feito: separou,
      tom: 'normal',
    },
    {
      titulo: 'Esperando o pagamento',
      detalhe: forma ? PAGAMENTO[forma] : 'pagamentos recebeu a cobrança',
      hora: quando('AGUARDANDO_PAGAMENTO'),
      feito: separou,
      tom: 'normal',
    },
  ];

  if (!desfeito)
    return [
      recebido,
      ...meio,
      {
        titulo: 'Confirmada',
        detalhe: teve('CONFIRMADO')
          ? 'pagamentos aprovou e pedidos fechou a saga'
          : 'quando pagamentos avisar que foi pago',
        hora: quando('CONFIRMADO'),
        feito: teve('CONFIRMADO'),
        tom: teve('CONFIRMADO') ? 'ok' : 'normal',
      },
    ];

  const falha = (motivo && FALHA[motivo]) || {
    titulo: 'Compra desfeita',
    detalhe: 'pedidos mandou o estoque devolver a gema',
  };
  const cancelado = teve('CANCELADO');
  return [
    recebido,
    ...(motivo === 'PRAZO_DA_RESERVA' ? [] : meio),
    {
      ...falha,
      hora: quando('LIBERANDO', 'CANCELADO'),
      feito: true,
      tom: 'ruim',
    },
    {
      titulo: 'Gema devolvida',
      detalhe: 'o estoque devolveu a unidade; ela volta para a vitrine',
      hora: quando('CANCELADO'),
      feito: cancelado,
      tom: 'normal',
    },
    {
      titulo: 'Cancelada',
      detalhe: 'pedidos encerrou a saga sem ficar com o dinheiro',
      hora: quando('CANCELADO'),
      feito: cancelado,
      tom: cancelado ? 'ruim' : 'normal',
    },
  ];
}
