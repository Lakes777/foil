import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { responder } from '../teste/respostas';
import { MinhasGemas } from './MinhasGemas';
import { compra, edicao, resposta, VITRINE } from './vitrine/exemplos';

const CONFIRMADA = compra(
  {
    id: '91c0d7aa-1111-4000-8000-000000000002',
    estado: 'CONFIRMADO',
    atualizadoEm: '2026-10-20T14:02:11Z',
  },
  2,
);
const OUTRA_CONFIRMADA = compra(
  {
    id: '4be1902f-2222-4000-8000-000000000003',
    edicaoId: 12,
    estado: 'CONFIRMADO',
    precoCentavos: 1800,
  },
  4,
);
const PAGANDO = compra({
  id: 'c07e5b31-3333-4000-8000-000000000004',
  edicaoId: 12,
  estado: 'AGUARDANDO_PAGAMENTO',
  linkParaPagar: 'https://fiel.andrelagos.dev/pagar/xyz',
});
const SEM_ESTOQUE = compra({
  id: '7d22aaaa-4444-4000-8000-000000000005',
  edicaoId: 13,
  estado: 'SEM_ESTOQUE',
  motivo: 'SEM_ESTOQUE',
});

function combinar(minhas: unknown) {
  return responder({
    '/api/pedidos/minhas': minhas,
    '/api/estoque/vitrine': VITRINE,
    '/api/estoque/edicoes/31': edicao(),
    '/api/estoque/edicoes/12': edicao({ id: 12, cartaId: 2, numero: 2 }),
    '/api/estoque/edicoes/13': edicao({ id: 13, cartaId: 3, numero: 1 }),
  });
}

function mesa() {
  return screen.getByRole('group', { name: /setas giram, V vira/i });
}

function certificado() {
  return screen.queryByRole('region', { name: 'Certificado de lapidação' });
}

describe('Minhas gemas', () => {
  it('uma edição que falha por rede vira erro, e não "não deu certo" para uma gema paga', async () => {
    responder({
      '/api/pedidos/minhas': [CONFIRMADA],
      '/api/estoque/vitrine': VITRINE,
      '/api/estoque/edicoes/31': () => new Response('', { status: 503 }),
    });
    render(<MinhasGemas />);

    expect(
      await screen.findByRole('button', { name: 'Tentar de novo' }),
    ).toBeInTheDocument();
    expect(screen.queryByText(/não deram certo/i)).not.toBeInTheDocument();
  });

  it('não põe link para pagar que não seja https', async () => {
    combinar([
      {
        ...PAGANDO,
        pedido: { ...PAGANDO.pedido, linkParaPagar: 'javascript:alert(1)' },
      },
    ]);
    render(<MinhasGemas />);

    expect(await screen.findByText(/em andamento/i)).toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: /pagar/i }),
    ).not.toBeInTheDocument();
  });

  it('põe só as confirmadas no inspetor; as outras ficam embaixo', async () => {
    combinar([PAGANDO, CONFIRMADA, SEM_ESTOQUE, OUTRA_CONFIRMADA]);
    render(<MinhasGemas />);

    const lista = await screen.findByRole('group', { name: 'Suas gemas' });
    const botoes = within(lista).getAllByRole('button');
    expect(botoes.map((b) => b.textContent)).toEqual([
      'Auroítaunidade 2 de 10',
      'Vidro-de-Ecounidade 4 de 10',
    ]);
    expect(botoes[0]).toHaveAttribute('aria-pressed', 'true');

    // a ficha da escolhida
    const ficha = screen.getByRole('complementary', { name: 'Ficha da gema' });
    expect(ficha).toHaveTextContent('lendária');
    expect(ficha).toHaveTextContent('9 de 10');
    expect(ficha).toHaveTextContent('rosa');
    expect(ficha).toHaveTextContent('F$ 48,00 pelo Fiel');

    // em andamento, com o link para pagar; a sem estoque, discreta
    const andando = screen
      .getByRole('heading', { name: 'Em andamento' })
      .closest('div')!;
    expect(
      within(andando).getByRole('link', { name: /pagar no fiel/i }),
    ).toHaveAttribute('href', 'https://fiel.andrelagos.dev/pagar/xyz');
    expect(screen.getByText(/Não deram certo/)).toBeInTheDocument();
    expect(
      screen.getByText('alguém levou a última unidade antes'),
    ).toBeInTheDocument();

    // trocar de gema troca a ficha
    await userEvent.click(botoes[1]);
    expect(botoes[1]).toHaveAttribute('aria-pressed', 'true');
    expect(ficha).toHaveTextContent('Vidro-de-Eco');
    expect(ficha).toHaveTextContent('nenhuma');
  });

  it('"Virar a carta" mostra o certificado da unidade', async () => {
    combinar([CONFIRMADA]);
    render(<MinhasGemas />);
    await screen.findByRole('group', { name: 'Suas gemas' });
    expect(certificado()).not.toBeInTheDocument();

    await userEvent.click(
      screen.getByRole('button', { name: 'Virar a carta' }),
    );

    const verso = certificado()!;
    expect(verso).toBeInTheDocument();
    expect(verso).toHaveTextContent('2 de 10');
    expect(verso).toHaveTextContent('91c0d7aa');
    expect(verso).toHaveTextContent('pelo Fiel');
    // a tinta invisível está lá (o CSS só mostra com a luz negra)
    expect(verso).toHaveTextContent('AUTÊNTICA');
    expect(verso).toHaveTextContent('2/10 · 91c0d7aa');

    await userEvent.click(screen.getByRole('button', { name: 'Endireitar' }));
    expect(certificado()).not.toBeInTheDocument();
  });

  it('pelo teclado: setas giram, V vira, + e - mudam o zoom', async () => {
    combinar([CONFIRMADA]);
    render(<MinhasGemas />);
    await screen.findByRole('group', { name: 'Suas gemas' });
    const giro = screen.getByTestId('giro');
    const zoom = screen.getByRole('slider', { name: 'Zoom' });
    const angulo = () => giro.style.getPropertyValue('--ry');

    const antes = angulo();
    fireEvent.keyDown(mesa(), { key: 'ArrowRight' });
    expect(angulo()).not.toBe(antes);
    expect(angulo()).toBe('30deg');

    fireEvent.keyDown(mesa(), { key: 'v' });
    expect(certificado()).toBeInTheDocument();
    fireEvent.keyDown(mesa(), { key: 'V' });
    expect(certificado()).not.toBeInTheDocument();

    fireEvent.keyDown(mesa(), { key: '+' });
    expect(zoom).toHaveValue('110');
    fireEvent.keyDown(mesa(), { key: '-' });
    fireEvent.keyDown(mesa(), { key: '-' });
    expect(zoom).toHaveValue('90');
  });

  it('arrastar gira a carta', async () => {
    combinar([CONFIRMADA]);
    render(<MinhasGemas />);
    await screen.findByRole('group', { name: 'Suas gemas' });
    const giro = screen.getByTestId('giro');

    fireEvent.pointerDown(mesa(), { clientX: 100, clientY: 100, pointerId: 1 });
    fireEvent.pointerMove(mesa(), { clientX: 200, clientY: 100, pointerId: 1 });
    fireEvent.pointerUp(mesa(), { pointerId: 1 });

    // 18 graus de começo + 100 px * 0,45
    expect(giro.style.getPropertyValue('--ry')).toBe('63deg');
  });

  it('sem compras, mostra o estado vazio com o caminho para a vitrine', async () => {
    combinar([]);
    render(<MinhasGemas />);

    expect(
      await screen.findByRole('heading', { name: 'Você ainda não tem gemas' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'Ir para a vitrine' }),
    ).toHaveAttribute('href', '#vitrine');
    expect(screen.queryByRole('group', { name: 'Suas gemas' })).toBeNull();
  });

  it('só com compra em andamento, avisa que ainda não há gema confirmada', async () => {
    combinar([PAGANDO]);
    render(<MinhasGemas />);

    expect(
      await screen.findByRole('heading', {
        name: 'Nenhuma gema confirmada ainda',
      }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /pagar no fiel/i })).toBeVisible();
  });

  it('o erro mostra a mensagem e "Tentar de novo" busca outra vez', async () => {
    let vez = 0;
    responder({
      '/api/pedidos/minhas': () =>
        vez++ === 0
          ? resposta(503, { detail: 'Pedidos fora do ar.' })()
          : resposta(200, [CONFIRMADA])(),
      '/api/estoque/vitrine': VITRINE,
      '/api/estoque/edicoes/31': edicao(),
    });
    render(<MinhasGemas />);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Pedidos fora do ar.',
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Tentar de novo' }),
    );
    expect(
      await screen.findByRole('group', { name: 'Suas gemas' }),
    ).toBeInTheDocument();
  });
});
