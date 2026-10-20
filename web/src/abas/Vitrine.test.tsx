import { StrictMode } from 'react';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { vi } from 'vitest';
import { responder } from '../teste/respostas';
import { Vitrine } from './Vitrine';
import { INTERVALO_DA_CONSULTA } from './vitrine/Compra';
import { emSequencia, pedido, resposta, VITRINE } from './vitrine/exemplos';

const ID = pedido().id;

/** Deixa as respostas do fetch (promessas) e os relógios falsos andarem. */
async function passar(ms = 0) {
  await act(() => vi.advanceTimersByTimeAsync(ms));
}

function consultasDoPedido(espiao: ReturnType<typeof responder>) {
  return espiao.mock.calls.filter(([url]) =>
    String(url).startsWith(`/api/pedidos/pedidos/${ID}`),
  ).length;
}

describe('Vitrine', () => {
  it('mostra as gemas, o que resta de cada edição e a esgotada', async () => {
    responder({ '/api/estoque/vitrine': VITRINE });
    render(<Vitrine abrir={() => {}} />);

    const eco = (
      await screen.findByRole('heading', { name: 'Vidro-de-Eco' })
    ).closest('li')!;
    expect(within(eco).getByText('6 de 10')).toBeInTheDocument();
    expect(within(eco).getByText('F$ 18,00')).toBeInTheDocument();
    expect(within(eco).getByText(/Mohs 7 · rara/)).toBeInTheDocument();

    const granada = screen
      .getByRole('heading', { name: 'Granada Fria' })
      .closest('li')!;
    expect(within(granada).getByText('esgotada')).toBeInTheDocument();
    expect(
      within(granada).getByRole('button', { name: 'Esgotada' }),
    ).toBeDisabled();

    // a mais rara (a primeira) vira a carta grande da abertura
    expect(
      screen.getByRole('article', { name: /carta em destaque: auroíta/i }),
    ).toBeInTheDocument();
    // a nota da luz negra só nas que fluorescem
    expect(screen.getAllByText(/fluoresce rosa/)).toHaveLength(1);
  });

  it('os botões da abertura levam às outras abas', async () => {
    responder({ '/api/estoque/vitrine': VITRINE });
    const abrir = vi.fn();
    render(<Vitrine abrir={abrir} />);

    await userEvent.click(
      screen.getByRole('button', { name: 'Simular lançamento' }),
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Ver os eventos ao vivo' }),
    );

    expect(abrir.mock.calls).toEqual([['lancamento'], ['por-dentro']]);
    await screen.findByRole('heading', { name: 'Vidro-de-Eco' });
  });

  it('o erro mostra a mensagem da loja e "Tentar de novo" busca outra vez', async () => {
    let vez = 0;
    responder({
      '/api/estoque/vitrine': () =>
        vez++ === 0
          ? resposta(503, { detail: 'O estoque está fora do ar.' })()
          : resposta(200, VITRINE)(),
    });
    render(<Vitrine abrir={() => {}} />);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'O estoque está fora do ar.',
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Tentar de novo' }),
    );

    expect(
      await screen.findByRole('heading', { name: 'Vidro-de-Eco' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('vitrine vazia avisa em vez de mostrar uma grade sem nada', async () => {
    responder({ '/api/estoque/vitrine': [] });
    render(<Vitrine abrir={() => {}} />);
    expect(
      await screen.findByText(/a vitrine está vazia agora/i),
    ).toBeInTheDocument();
  });

  describe('compra', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    it('acompanha o pedido até o QR do Fiel, com o link e o prazo', async () => {
      const prazo = new Date(Date.now() + 5 * 60_000).toISOString();
      const espiao = responder({
        '/api/estoque/vitrine': VITRINE,
        'POST /api/pedidos/pedidos': pedido(),
        [`/api/pedidos/pedidos/${ID}`]: emSequencia(
          pedido(),
          pedido({
            estado: 'AGUARDANDO_PAGAMENTO',
            qr: '00020101021226-cobranca-do-fiel',
            linkParaPagar: 'https://fiel.andrelagos.dev/pagar/abc',
            prazo,
          }),
        ),
      });
      // StrictMode como no main.tsx: em dev ele monta o diálogo duas vezes, e a compra tem que sair uma vez só
      render(
        <StrictMode>
          <Vitrine abrir={() => {}} />
        </StrictMode>,
      );
      await passar();

      fireEvent.click(screen.getByRole('button', { name: 'Comprar Auroíta' }));
      await passar();
      const dialogo = screen.getByRole('dialog', { name: 'Auroíta' });
      expect(dialogo).toHaveTextContent('Separando a gema...');
      const posts = espiao.mock.calls.filter(
        ([, opcoes]) => opcoes?.method === 'POST',
      );
      expect(posts).toHaveLength(1);
      expect(posts[0]).toEqual([
        '/api/pedidos/pedidos',
        expect.objectContaining({ body: JSON.stringify({ edicaoId: 31 }) }),
      ]);

      await passar(INTERVALO_DA_CONSULTA);
      expect(dialogo).toHaveTextContent('Separando a gema...');

      await passar(INTERVALO_DA_CONSULTA);
      expect(
        within(dialogo).getByRole('img', { name: /QR da cobrança/ }),
      ).toBeInTheDocument();
      const pagar = within(dialogo).getByRole('link', {
        name: /pagar no fiel/i,
      });
      expect(pagar).toHaveAttribute(
        'href',
        'https://fiel.andrelagos.dev/pagar/abc',
      );
      expect(pagar).toHaveAttribute('target', '_blank');
      expect(dialogo).toHaveTextContent(/Prazo para pagar: 4:5\d/);

      await passar(60_000);
      expect(dialogo).toHaveTextContent(/Prazo para pagar: 3:5\d/);
    });

    it('termina em "sem estoque", para de consultar e recarrega a vitrine', async () => {
      const espiao = responder({
        '/api/estoque/vitrine': VITRINE,
        'POST /api/pedidos/pedidos': pedido(),
        [`/api/pedidos/pedidos/${ID}`]: emSequencia(
          pedido({ estado: 'SEM_ESTOQUE', motivo: 'SEM_ESTOQUE' }),
        ),
      });
      render(<Vitrine abrir={() => {}} />);
      await passar();
      fireEvent.click(screen.getByRole('button', { name: 'Comprar Auroíta' }));
      await passar();
      await passar(INTERVALO_DA_CONSULTA);

      expect(screen.getByRole('dialog')).toHaveTextContent(
        'Sem estoque: alguém levou a última.',
      );
      const consultas = consultasDoPedido(espiao);
      const vitrines = () =>
        espiao.mock.calls.filter(([url]) => url === '/api/estoque/vitrine')
          .length;
      expect(vitrines()).toBe(2);

      await passar(INTERVALO_DA_CONSULTA * 5);
      expect(consultasDoPedido(espiao)).toBe(consultas);
    });

    it('para de consultar quando o diálogo fecha (Esc ou o botão)', async () => {
      const espiao = responder({
        '/api/estoque/vitrine': VITRINE,
        'POST /api/pedidos/pedidos': pedido(),
        [`/api/pedidos/pedidos/${ID}`]: emSequencia(pedido()),
      });
      render(<Vitrine abrir={() => {}} />);
      await passar();
      const comprar = screen.getByRole('button', { name: 'Comprar Auroíta' });
      comprar.focus();
      fireEvent.click(comprar);
      await passar();
      await passar(INTERVALO_DA_CONSULTA);
      expect(consultasDoPedido(espiao)).toBe(1);

      fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(comprar).toHaveFocus();

      await passar(INTERVALO_DA_CONSULTA * 5);
      expect(consultasDoPedido(espiao)).toBe(1);

      // de novo, fechando pelo botão
      fireEvent.click(comprar);
      await passar();
      await passar(INTERVALO_DA_CONSULTA);
      const antes = consultasDoPedido(espiao);
      fireEvent.click(screen.getByRole('button', { name: 'Fechar' }));
      await passar(INTERVALO_DA_CONSULTA * 5);
      expect(consultasDoPedido(espiao)).toBe(antes);
    });

    it('o foco fica preso no diálogo', async () => {
      responder({
        '/api/estoque/vitrine': VITRINE,
        'POST /api/pedidos/pedidos': resposta(429, {}, { 'Retry-After': '9' }),
      });
      render(<Vitrine abrir={() => {}} />);
      await passar();
      fireEvent.click(screen.getByRole('button', { name: 'Comprar Auroíta' }));
      await passar();

      const dialogo = screen.getByRole('dialog');
      expect(dialogo).toHaveFocus();
      const [fechar, voltar] = within(dialogo).getAllByRole('button');
      voltar.focus();
      fireEvent.keyDown(dialogo, { key: 'Tab' });
      // o jsdom não move o foco sozinho: o diálogo devolve ao primeiro
      expect(fechar).toHaveFocus();
      fireEvent.keyDown(dialogo, { key: 'Tab', shiftKey: true });
      expect(voltar).toHaveFocus();
    });

    it('o 429 diz quantos segundos esperar', async () => {
      responder({
        '/api/estoque/vitrine': VITRINE,
        'POST /api/pedidos/pedidos': resposta(
          429,
          { detail: 'Calma.' },
          { 'Retry-After': '42' },
        ),
      });
      render(<Vitrine abrir={() => {}} />);
      await passar();
      fireEvent.click(screen.getByRole('button', { name: 'Comprar Auroíta' }));
      await passar();

      expect(screen.getByRole('dialog')).toHaveTextContent(
        'Tente de novo em 42 segundos.',
      );
    });

    it('confirmada leva para Minhas gemas', async () => {
      responder({
        '/api/estoque/vitrine': VITRINE,
        'POST /api/pedidos/pedidos': pedido(),
        [`/api/pedidos/pedidos/${ID}`]: emSequencia(
          pedido({ estado: 'CONFIRMADO' }),
        ),
      });
      const abrir = vi.fn();
      render(<Vitrine abrir={abrir} />);
      await passar();
      fireEvent.click(screen.getByRole('button', { name: 'Comprar Auroíta' }));
      await passar();
      await passar(INTERVALO_DA_CONSULTA);

      expect(screen.getByRole('dialog')).toHaveTextContent('Confirmada!');
      fireEvent.click(
        screen.getByRole('button', { name: 'Veja em Minhas gemas' }),
      );
      expect(abrir).toHaveBeenCalledWith('colecao');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('cancelada mostra o motivo em palavras', async () => {
      responder({
        '/api/estoque/vitrine': VITRINE,
        'POST /api/pedidos/pedidos': pedido(),
        [`/api/pedidos/pedidos/${ID}`]: emSequencia(
          pedido({ estado: 'CANCELADO', motivo: 'PRAZO_DO_PAGAMENTO' }),
        ),
      });
      render(<Vitrine abrir={() => {}} />);
      await passar();
      fireEvent.click(screen.getByRole('button', { name: 'Comprar Auroíta' }));
      await passar();
      await passar(INTERVALO_DA_CONSULTA);

      const dialogo = screen.getByRole('dialog');
      expect(dialogo).toHaveTextContent('Cancelada');
      expect(dialogo).toHaveTextContent(
        'o prazo de 5 minutos para pagar acabou',
      );
    });
  });
});
