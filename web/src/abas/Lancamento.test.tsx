import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type {
  Edicao,
  ItemDaVitrine,
  Lancamento as Lanc,
  SituacaoDosLancamentos,
} from '../api/tipos';
import { EventSourceFalso } from '../teste/eventSourceFalso';
import { responder } from '../teste/respostas';
import { Lancamento } from './Lancamento';

function lancamento(campos: Partial<Lanc> = {}): Lanc {
  return {
    id: 'lanc-1',
    estado: 'SOLTANDO',
    motivo: null,
    edicaoId: 7,
    robos: 200,
    soltos: 141,
    posicaoNaFila: null,
    pedidoEm: '2026-10-20T14:01:00Z',
    comecouEm: '2026-10-20T14:01:10Z',
    terminouEm: null,
    vendidos: 7,
    semEstoque: 118,
    desfeitos: 3,
    emAndamento: 2,
    ...campos,
  };
}

const EDICAO: Edicao = {
  id: 7,
  cartaId: 9,
  numero: 3,
  total: 10,
  disponivel: 3,
  precoCentavos: 4800,
  criadaEm: '2026-10-20T14:01:05Z',
};

const VITRINE: ItemDaVitrine[] = [
  {
    carta: {
      id: 9,
      nome: 'Auroíta',
      raridade: 'lendaria',
      descricao: 'Só brilha com luz de madrugada.',
      mohs: 9,
      cor: 'aurora',
      fluorescencia: 'aurora',
    },
    edicao: EDICAO,
  },
];

function situacao(
  campos: Partial<SituacaoDosLancamentos> = {},
): SituacaoDosLancamentos {
  return { atual: lancamento(), fila: [], recentes: [], ...campos };
}

function combinar(
  s: SituacaoDosLancamentos | (() => SituacaoDosLancamentos),
  extra: Record<string, unknown> = {},
) {
  return responder({
    '/api/pedidos/lancamentos': () =>
      new Response(JSON.stringify(typeof s === 'function' ? s() : s), {
        status: 200,
      }),
    '/api/estoque/edicoes/7': EDICAO,
    '/api/estoque/vitrine': VITRINE,
    ...extra,
  });
}

function vendidas() {
  const estojo = screen.getByRole('list', { name: /unidades vendidas/ });
  return within(estojo)
    .getAllByRole('listitem')
    .filter((li) => li.textContent?.includes('vendida')).length;
}

function chamadasDoRetrato(espiao: ReturnType<typeof responder>) {
  return espiao.mock.calls.filter(
    ([url, opcoes]) =>
      url === '/api/pedidos/lancamentos' && !(opcoes as RequestInit)?.method,
  ).length;
}

describe('Lançamento', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date('2026-10-20T14:01:24Z'));
    sessionStorage.clear();
  });

  it('mostra a gema da edição, há quanto tempo começou e apaga no estojo as vendidas', async () => {
    combinar(situacao());
    render(<Lancamento />);

    expect(
      await screen.findByRole('heading', { name: 'Auroíta, edição 3' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/10 robôs por segundo · começou há 14 s/),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('list', { name: '7 de 10 unidades vendidas' }),
    ).toBeInTheDocument();
    expect(vendidas()).toBe(7);
    expect(screen.getByText('141 de 200 robôs soltos')).toBeInTheDocument();
    expect(screen.getByText('chegaram tarde').nextSibling).toHaveTextContent(
      '118',
    );
    expect(screen.getByText('Vendendo').closest('li')).toHaveAttribute(
      'aria-current',
      'step',
    );
  });

  it('o evento do lançamento muda a etapa na hora, sem esperar o GET', async () => {
    combinar(situacao());
    render(<Lancamento />);
    await screen.findByRole('heading', { name: 'Auroíta, edição 3' });

    act(() => {
      EventSourceFalso.ultima()!.mandar('LancamentoMudouDeEstado', {
        id: 'ev-1',
        tipo: 'LancamentoMudouDeEstado',
        versao: 1,
        ocorridoEm: '2026-10-20T14:01:31Z',
        pedidoId: 'lanc-1',
        dados: {
          estado: 'ESPERANDO',
          motivo: null,
          edicaoId: 7,
          robos: 200,
          soltos: 200,
        },
      });
    });

    expect(screen.getByText('Conferindo').closest('li')).toHaveAttribute(
      'aria-current',
      'step',
    );
    expect(screen.getByText('200 de 200 robôs soltos')).toBeInTheDocument();
    expect(screen.getByText('ao vivo', { selector: 'p' })).toBeInTheDocument();
  });

  it('pedidos da edição viram no máximo um GET por segundo; e há um GET de garantia a cada 5 s', async () => {
    let vendidos = 7;
    const espiao = combinar(() =>
      situacao({ atual: lancamento({ vendidos }) }),
    );
    render(<Lancamento />);
    await screen.findByRole('heading', { name: 'Auroíta, edição 3' });
    const antes = chamadasDoRetrato(espiao);

    vendidos = 9;
    act(() => {
      for (let i = 0; i < 30; i++)
        EventSourceFalso.ultima()!.mandar('PedidoMudouDeEstado', {
          id: `p-${i}`,
          tipo: 'PedidoMudouDeEstado',
          versao: 1,
          ocorridoEm: '2026-10-20T14:01:25Z',
          pedidoId: `pedido-${i}`,
          dados: { estado: 'CONFIRMADO', motivo: null, edicaoId: 7 },
        });
      // de outra edição: não conta
      EventSourceFalso.ultima()!.mandar('PedidoMudouDeEstado', {
        id: 'p-x',
        tipo: 'PedidoMudouDeEstado',
        versao: 1,
        ocorridoEm: '2026-10-20T14:01:25Z',
        pedidoId: 'pedido-x',
        dados: { estado: 'CONFIRMADO', motivo: null, edicaoId: 99 },
      });
    });
    await act(() => vi.advanceTimersByTimeAsync(1000));

    expect(chamadasDoRetrato(espiao)).toBe(antes + 1);
    expect(vendidas()).toBe(9);

    await act(() => vi.advanceTimersByTimeAsync(4000));
    expect(chamadasDoRetrato(espiao)).toBe(antes + 2);
  });

  it('marca o seu na fila e nos últimos', async () => {
    sessionStorage.setItem('foil-meu-lancamento', 'lanc-3');
    combinar(
      situacao({
        fila: [
          lancamento({
            id: 'lanc-2',
            estado: 'NA_FILA',
            edicaoId: null,
            posicaoNaFila: 1,
          }),
          lancamento({
            id: 'lanc-3',
            estado: 'NA_FILA',
            edicaoId: null,
            posicaoNaFila: 2,
          }),
        ],
        recentes: [
          lancamento({
            id: 'lanc-0',
            estado: 'ENCERRADO',
            vendidos: 10,
            semEstoque: 180,
            desfeitos: 10,
          }),
        ],
      }),
    );
    render(<Lancamento />);

    const seu = await screen.findByText('O seu');
    expect(seu.closest('li')).toHaveClass('voce');
    expect(seu.closest('li')).toHaveTextContent('2º da fila');
    expect(screen.getByText('Outro visitante').closest('li')).toHaveTextContent(
      '1º da fila',
    );
    expect(await screen.findByText('Auroíta · agora')).toBeInTheDocument();
    expect(
      screen.getByText('10 vendidas · 180 tarde · 10 desfeitas'),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        'Até 5 esperando e 1 pedido por minuto para cada visitante.',
      ),
    ).toBeInTheDocument();
  });

  it('pedir guarda o seu na sessão e o marca na fila', async () => {
    let s = situacao();
    combinar(() => s, {
      'POST /api/pedidos/lancamentos': () => {
        s = situacao({
          fila: [
            lancamento({
              id: 'lanc-9',
              estado: 'NA_FILA',
              edicaoId: null,
              posicaoNaFila: 1,
            }),
          ],
        });
        return new Response(JSON.stringify(s.fila[0]), { status: 202 });
      },
    });
    const usuario = userEvent.setup({
      advanceTimers: vi.advanceTimersByTime,
    });
    render(<Lancamento />);
    await screen.findByRole('heading', { name: 'Auroíta, edição 3' });

    await usuario.click(
      screen.getByRole('button', { name: 'Simular lançamento' }),
    );

    expect(
      await screen.findByText('Pedido feito: o seu é o 1º da fila.'),
    ).toBeInTheDocument();
    expect(await screen.findByText('O seu')).toBeInTheDocument();
    expect(sessionStorage.getItem('foil-meu-lancamento')).toBe('lanc-9');
  });

  it('429 mostra quanto falta e conta para baixo; o botão volta quando zera', async () => {
    combinar(situacao(), {
      'POST /api/pedidos/lancamentos': () =>
        new Response(JSON.stringify({ detail: 'devagar' }), {
          status: 429,
          headers: { 'Retry-After': '42' },
        }),
    });
    const usuario = userEvent.setup({
      advanceTimers: vi.advanceTimersByTime,
    });
    render(<Lancamento />);
    await screen.findByRole('heading', { name: 'Auroíta, edição 3' });
    const botao = screen.getByRole('button', { name: 'Simular lançamento' });

    await usuario.click(botao);

    expect(
      await screen.findByText('Você pediu um há pouco. Tente em 42 s.'),
    ).toBeInTheDocument();
    expect(botao).toBeDisabled();

    await act(() => vi.advanceTimersByTimeAsync(10_000));
    expect(
      // o relógio da tela anda de segundo em segundo: 32 ou 33, conforme o tique
      screen.getByText(/^Você pediu um há pouco\. Tente em 3[23] s\.$/),
    ).toBeInTheDocument();

    await act(() => vi.advanceTimersByTimeAsync(33_000));
    expect(screen.queryByText(/Tente em/)).not.toBeInTheDocument();
    expect(botao).toBeEnabled();
  });

  it('503 avisa que a fila está cheia', async () => {
    combinar(situacao(), {
      'POST /api/pedidos/lancamentos': () =>
        new Response(null, { status: 503, headers: { 'Retry-After': '60' } }),
    });
    const usuario = userEvent.setup({
      advanceTimers: vi.advanceTimersByTime,
    });
    render(<Lancamento />);
    await screen.findByRole('heading', { name: 'Auroíta, edição 3' });

    await usuario.click(
      screen.getByRole('button', { name: 'Simular lançamento' }),
    );

    expect(
      await screen.findByText(
        'A fila está cheia (5 esperando). Tente em 60 s.',
      ),
    ).toBeInTheDocument();
  });

  it('sem lançamento nenhum convida a simular', async () => {
    combinar({ atual: null, fila: [], recentes: [] });
    render(<Lancamento />);

    expect(
      await screen.findByRole('heading', { name: 'Nenhum lançamento ainda' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Simular lançamento' }),
    ).toBeEnabled();
  });

  it('o que falhou diz o motivo em palavras', async () => {
    combinar({
      atual: null,
      fila: [],
      recentes: [
        lancamento({
          estado: 'FALHOU',
          motivo: 'SEM_CARTA_RARA',
          edicaoId: null,
          terminouEm: '2026-10-20T14:01:20Z',
        }),
      ],
    });
    render(<Lancamento />);

    expect(
      await screen.findByText(
        'Não havia nenhuma gema rara ou lendária para lapidar.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('Lapidando').closest('li')).toHaveTextContent(
      'falhou',
    );
  });

  it('fecha o ao vivo ao sair da aba', async () => {
    combinar(situacao());
    const { unmount } = render(<Lancamento />);
    await screen.findByRole('heading', { name: 'Auroíta, edição 3' });
    const fonte = EventSourceFalso.ultima()!;

    unmount();

    expect(fonte.fechada).toBe(true);
  });
});
