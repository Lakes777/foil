/** Dados de exemplo para os testes da vitrine e de Minhas gemas. */
import type {
  Carta,
  CompraDoVisitante,
  Edicao,
  ItemDaVitrine,
  Pedido,
} from '../../api/tipos';

export function carta(dados: Partial<Carta> = {}): Carta {
  return {
    id: 9,
    nome: 'Auroíta',
    raridade: 'lendaria',
    descricao: 'Só brilha com luz de madrugada.',
    mohs: 9,
    cor: 'aurora',
    fluorescencia: 'aurora',
    ...dados,
  };
}

export function edicao(dados: Partial<Edicao> = {}): Edicao {
  return {
    id: 31,
    cartaId: 9,
    numero: 3,
    total: 10,
    disponivel: 3,
    precoCentavos: 4800,
    criadaEm: '2026-10-20T14:01:00Z',
    ...dados,
  };
}

export const VITRINE: ItemDaVitrine[] = [
  { carta: carta(), edicao: edicao() },
  {
    carta: carta({
      id: 2,
      nome: 'Vidro-de-Eco',
      raridade: 'rara',
      mohs: 7,
      cor: 'mar',
      fluorescencia: null,
    }),
    edicao: edicao({
      id: 12,
      cartaId: 2,
      numero: 2,
      disponivel: 6,
      precoCentavos: 1800,
    }),
  },
  {
    carta: carta({
      id: 3,
      nome: 'Granada Fria',
      raridade: 'rara',
      mohs: 8,
      cor: 'brasa',
      fluorescencia: null,
    }),
    edicao: edicao({
      id: 13,
      cartaId: 3,
      numero: 1,
      disponivel: 0,
      precoCentavos: 1800,
    }),
  },
];

export function pedido(dados: Partial<Pedido> = {}): Pedido {
  return {
    id: '8f3a2c19-0000-4000-8000-000000000001',
    edicaoId: 31,
    estado: 'RESERVANDO',
    motivo: null,
    precoCentavos: 4800,
    prazo: null,
    criadoEm: '2026-10-20T14:02:00Z',
    atualizadoEm: '2026-10-20T14:02:07Z',
    forma: 'FIEL',
    qr: null,
    linkParaPagar: null,
    lancamentoId: null,
    ...dados,
  };
}

export function compra(
  dados: Partial<Pedido>,
  unidade: number | null = null,
): CompraDoVisitante {
  return { pedido: pedido(dados), unidade };
}

/** Uma resposta JSON com status (para os erros). */
export function resposta(
  status: number,
  corpo: unknown,
  cabecalhos: Record<string, string> = {},
) {
  return () =>
    new Response(JSON.stringify(corpo), {
      status,
      headers: { 'Content-Type': 'application/json', ...cabecalhos },
    });
}

/** Uma rota que devolve um item da lista por chamada e repete o último. */
export function emSequencia(...corpos: unknown[]) {
  let i = 0;
  return () => {
    const corpo = corpos[Math.min(i++, corpos.length - 1)];
    return new Response(JSON.stringify(corpo), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  };
}
