import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { EstadoDoPedido, Pedido } from '../api/tipos';
import { EventSourceFalso } from '../teste/eventSourceFalso';
import { responder } from '../teste/respostas';
import { linkDoTrace, PorDentro } from './PorDentro';

let n = 0;

function doPedido(
  pedidoId: string,
  estado: EstadoDoPedido,
  motivo: string | null = null,
  ocorridoEm = '2026-10-20T14:02:07Z',
) {
  EventSourceFalso.ultima()!.mandar('PedidoMudouDeEstado', {
    id: `ev-${++n}`,
    tipo: 'PedidoMudouDeEstado',
    versao: 1,
    ocorridoEm,
    pedidoId,
    dados: { estado, motivo, edicaoId: 7 },
  });
}

function doLancamento() {
  EventSourceFalso.ultima()!.mandar('LancamentoMudouDeEstado', {
    id: `ev-${++n}`,
    tipo: 'LancamentoMudouDeEstado',
    versao: 1,
    ocorridoEm: '2026-10-20T14:02:00Z',
    pedidoId: 'lanc-1',
    dados: {
      estado: 'SOLTANDO',
      motivo: null,
      edicaoId: 7,
      robos: 200,
      soltos: 0,
    },
  });
}

function pedido(id: string, campos: Partial<Pedido> = {}): Pedido {
  return {
    id,
    edicaoId: 7,
    estado: 'RESERVANDO',
    motivo: null,
    precoCentavos: 4800,
    prazo: null,
    criadoEm: '2026-10-20T14:02:06Z',
    atualizadoEm: '2026-10-20T14:02:06Z',
    forma: 'SIMULADA',
    qr: null,
    linkParaPagar: null,
    lancamentoId: 'lanc-1',
    traceId: '0a8f5d9cce7ada68dcf116be428fbc5a',
    ...campos,
  };
}

/** Pedido, edição e vitrine: o que a saga busca para completar o que o ao vivo mostrou. */
function combinar() {
  return responder({
    '/api/pedidos/pedidos/': () =>
      new Response(JSON.stringify(pedido('8f3a2c19-aaaa')), { status: 200 }),
    '/api/estoque/edicoes/7': {
      id: 7,
      cartaId: 2,
      numero: 2,
      total: 10,
      disponivel: 4,
      precoCentavos: 1800,
      criadaEm: '2026-10-20T13:58:00Z',
    },
    '/api/estoque/vitrine': [
      {
        carta: {
          id: 2,
          nome: 'Vidro-de-Eco',
          raridade: 'rara',
          descricao: '',
          mohs: 7,
          cor: 'mar',
          fluorescencia: null,
        },
        edicao: {},
      },
    ],
  });
}

const linhasDoLog = () =>
  screen.getByRole('log').querySelectorAll('.linha').length;

describe('Por dentro', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    combinar();
  });

  it('mostra os eventos que chegam em lotes, os novos no topo', async () => {
    render(<PorDentro />);

    act(() => {
      doLancamento();
      for (let i = 0; i < 40; i++) doPedido(`pedido-${i}-xxxx`, 'RESERVANDO');
    });
    // ainda no mesmo lote: nada na tela
    expect(linhasDoLog()).toBe(0);

    await act(() => vi.advanceTimersByTimeAsync(250));

    const log = screen.getByRole('log');
    // off: num lançamento chegam dezenas por segundo; anunciar tudo deixaria o leitor de tela inutilizável
    expect(log).toHaveAttribute('aria-live', 'off');
    expect(linhasDoLog()).toBe(41);
    const primeira = log.querySelector('.linha')!;
    expect(primeira).toHaveTextContent('pedido-3');
    expect(primeira).toHaveTextContent('PedidoMudouDeEstado');
    expect(primeira).toHaveTextContent('separando');
    expect(within(log).getByText(/^lançamento/)).toBeInTheDocument();
    expect(within(log).getByText('vendendo')).toBeInTheDocument();
  });

  it('guarda no máximo 60 linhas', async () => {
    render(<PorDentro />);
    act(() => {
      for (let i = 0; i < 100; i++) doPedido(`p${i}`, 'RESERVANDO');
    });
    await act(() => vi.advanceTimersByTimeAsync(250));

    expect(linhasDoLog()).toBe(60);
  });

  it('Pausar congela a lista e Continuar traz o que chegou nesse meio-tempo', async () => {
    const usuario = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(<PorDentro />);
    act(() => doPedido('aaaa1111', 'RESERVANDO'));
    await act(() => vi.advanceTimersByTimeAsync(250));
    expect(linhasDoLog()).toBe(1);

    await usuario.click(screen.getByRole('button', { name: 'Pausar' }));
    act(() => {
      doPedido('bbbb2222', 'RESERVANDO');
      doPedido('cccc3333', 'RESERVANDO');
    });
    await act(() => vi.advanceTimersByTimeAsync(1000));

    expect(linhasDoLog()).toBe(1);
    const continuar = screen.getByRole('button', {
      name: 'Continuar (2 novos)',
    });
    expect(continuar).toHaveAttribute('aria-pressed', 'true');

    await usuario.click(continuar);
    expect(linhasDoLog()).toBe(3);
  });

  it('segue a saga do último pedido visto, passo a passo, com quem fez o quê', async () => {
    render(<PorDentro />);
    act(() => doPedido('8f3a2c19-aaaa', 'RESERVANDO'));
    await act(() => vi.advanceTimersByTimeAsync(250));

    expect(
      await screen.findByText(/pedido 8f3a2c19 · Vidro-de-Eco · robô/),
    ).toBeInTheDocument();
    const saga = () => screen.getByRole('list', { name: 'Passos da compra' });
    const passo = (titulo: string) =>
      within(saga()).getByText(titulo).closest('li')!;
    expect(passo('Pedido recebido')).toHaveClass('feito');
    expect(passo('Confirmada')).toHaveClass('falta');
    expect(passo('Confirmada')).toHaveTextContent('—');

    act(() => {
      doPedido(
        '8f3a2c19-aaaa',
        'AGUARDANDO_PAGAMENTO',
        null,
        '2026-10-20T14:02:08Z',
      );
      doPedido('8f3a2c19-aaaa', 'CONFIRMADO', null, '2026-10-20T14:02:11Z');
    });
    await act(() => vi.advanceTimersByTimeAsync(250));

    expect(passo('Gema separada')).toHaveClass('feito');
    expect(passo('Esperando o pagamento')).toHaveTextContent(
      'pagamentos cobrou o robô com dinheiro simulado',
    );
    expect(passo('Confirmada')).toHaveClass('feito');
    expect(passo('Confirmada')).toHaveTextContent(
      'pagamentos aprovou e pedidos fechou a saga',
    );
  });

  it('mostra o ramo de falha quando o pagamento é recusado', async () => {
    render(<PorDentro />);
    act(() => {
      doPedido('8f3a2c19-aaaa', 'AGUARDANDO_PAGAMENTO');
      doPedido('8f3a2c19-aaaa', 'LIBERANDO', 'PAGAMENTO_RECUSADO');
      doPedido('8f3a2c19-aaaa', 'CANCELADO', 'PAGAMENTO_RECUSADO');
    });
    await act(() => vi.advanceTimersByTimeAsync(250));

    const saga = await screen.findByRole('list', { name: 'Passos da compra' });
    const titulos = within(saga)
      .getAllByRole('listitem')
      .map((li) => li.querySelector('b')!.textContent);
    expect(titulos).toEqual([
      'Pedido recebido',
      'Gema separada',
      'Esperando o pagamento',
      'Pagamento recusado',
      'Gema devolvida',
      'Cancelada',
    ]);
    expect(within(saga).queryByText('Confirmada')).not.toBeInTheDocument();
  });

  it('Seguir outro troca para o pedido mais recente que não é o atual', async () => {
    const usuario = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(<PorDentro />);
    act(() => doPedido('primeiro-1111', 'RESERVANDO'));
    await act(() => vi.advanceTimersByTimeAsync(250));
    act(() => doPedido('segundo-2222', 'SEM_ESTOQUE', 'SEM_ESTOQUE'));
    await act(() => vi.advanceTimersByTimeAsync(250));
    expect(screen.getByText(/^pedido primeiro/)).toBeInTheDocument();

    await usuario.click(screen.getByRole('button', { name: 'Seguir outro' }));

    expect(screen.getByText(/^pedido segundo-/)).toBeInTheDocument();
    expect(
      within(screen.getByRole('list', { name: 'Passos da compra' })).getByText(
        'Sem estoque',
      ),
    ).toBeInTheDocument();
  });

  it('leva ao trace da compra no painel, a partir de um minuto antes do pedido', async () => {
    render(<PorDentro />);
    act(() => doPedido('8f3a2c19-aaaa', 'RESERVANDO'));
    await act(() => vi.advanceTimersByTimeAsync(250));

    const link = await screen.findByRole('link', {
      name: 'Ver o trace desta compra',
    });
    const href = new URL(link.getAttribute('href')!, 'http://loja');
    expect(href.pathname).toBe('/painel/d/foil/foil');
    expect(href.searchParams.get('var-trace')).toBe(
      '0a8f5d9cce7ada68dcf116be428fbc5a',
    );
    expect(href.searchParams.get('viewPanel')).toBe('panel-10');
    expect(
      screen.getByRole('link', { name: 'painel de observabilidade' }),
    ).toHaveAttribute('href', '/painel/d/foil/foil');
  });

  it('o intervalo cobre a última hora, ou desde um minuto antes de um pedido mais velho', () => {
    const agora = Date.parse('2026-10-20T15:00:00Z');
    const de = (criadoEm: string) =>
      Number(
        new URL(
          linkDoTrace(pedido('x', { criadoEm }), agora)!,
          'http://loja',
        ).searchParams.get('from'),
      );
    expect(de('2026-10-20T14:50:00Z')).toBe(agora - 3_600_000);
    expect(de('2026-10-20T12:00:00Z')).toBe(Date.parse('2026-10-20T11:59:00Z'));
  });

  it('pedido sem trace (de antes da fase 9) não ganha link', () => {
    expect(linkDoTrace(pedido('x', { traceId: null }))).toBeNull();
  });

  it('sem eventos, explica o que vai aparecer', () => {
    render(<PorDentro />);
    expect(screen.getByText(/esperando a próxima compra/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Seguir outro' })).toBeDisabled();
  });

  it('fecha o ao vivo ao sair da aba', () => {
    const { unmount } = render(<PorDentro />);
    const fonte = EventSourceFalso.ultima()!;
    expect(fonte.fechada).toBe(false);

    unmount();

    expect(fonte.fechada).toBe(true);
  });
});
