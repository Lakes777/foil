import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from './App';

describe('App', () => {
  it('abre na vitrine e troca de aba pelo clique e pelo endereço', async () => {
    render(<App />);
    expect(screen.getByRole('tab', { name: 'Vitrine' })).toHaveAttribute(
      'aria-selected',
      'true',
    );

    await userEvent.click(screen.getByRole('tab', { name: 'Minhas gemas' }));

    expect(screen.getByRole('tab', { name: 'Minhas gemas' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(window.location.hash).toBe('#colecao');
    expect(screen.getByRole('tabpanel')).toHaveAttribute(
      'aria-labelledby',
      'aba-colecao',
    );
  });

  it('as setas andam entre as abas', async () => {
    render(<App />);
    screen.getByRole('tab', { name: 'Vitrine' }).focus();

    await userEvent.keyboard('{ArrowRight}');

    expect(screen.getByRole('tab', { name: 'Lançamento' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(screen.getByRole('tab', { name: 'Lançamento' })).toHaveFocus();
  });

  it('abre direto na aba do endereço', () => {
    window.location.hash = '#por-dentro';
    render(<App />);
    expect(screen.getByRole('tab', { name: 'Por dentro' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
  });

  it('a luz negra liga pelo botão e pela tecla L, e o navegador lembra', async () => {
    const { unmount } = render(<App />);
    const botao = screen.getByRole('button', { name: /luz negra/i });

    await userEvent.click(botao);
    expect(document.documentElement).toHaveAttribute('data-uv');
    expect(botao).toHaveAttribute('aria-pressed', 'true');

    await userEvent.keyboard('l');
    expect(document.documentElement).not.toHaveAttribute('data-uv');

    await userEvent.keyboard('l');
    unmount();
    render(<App />);
    expect(screen.getByRole('button', { name: /luz negra/i })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });
});
