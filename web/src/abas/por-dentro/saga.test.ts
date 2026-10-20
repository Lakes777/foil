import type { EstadoDoPedido } from '../../api/tipos';
import { passosDaSaga, type Marco } from './saga';

const m = (estado: EstadoDoPedido, motivo: string | null = null): Marco => ({
  estado,
  motivo,
  ocorridoEm: '2026-10-20T14:02:07Z',
});

const titulos = (marcos: Marco[]) =>
  passosDaSaga(marcos).map((p) => (p.feito ? p.titulo : `(${p.titulo})`));

describe('passosDaSaga', () => {
  it('no meio do caminho feliz, o que falta fica por fazer', () => {
    expect(titulos([m('RESERVANDO')])).toEqual([
      'Pedido recebido',
      '(Gema separada)',
      '(Esperando o pagamento)',
      '(Confirmada)',
    ]);
  });

  it('quem chegou tarde para na falta de estoque', () => {
    expect(titulos([m('RESERVANDO'), m('SEM_ESTOQUE', 'SEM_ESTOQUE')])).toEqual(
      ['Pedido recebido', 'Sem estoque'],
    );
  });

  it('ainda devolvendo: a devolução e o cancelamento ficam por fazer', () => {
    expect(
      titulos([
        m('AGUARDANDO_PAGAMENTO'),
        m('LIBERANDO', 'PRAZO_DO_PAGAMENTO'),
      ]),
    ).toEqual([
      'Pedido recebido',
      'Gema separada',
      'Esperando o pagamento',
      'O prazo do pagamento venceu',
      '(Gema devolvida)',
      '(Cancelada)',
    ]);
  });

  it('se a reserva venceu, a gema nunca foi separada', () => {
    expect(
      titulos([m('RESERVANDO'), m('CANCELADO', 'PRAZO_DA_RESERVA')]),
    ).toEqual([
      'Pedido recebido',
      'O estoque não respondeu',
      'Gema devolvida',
      'Cancelada',
    ]);
  });

  it('a compra da loja passa pelo Fiel', () => {
    const passos = passosDaSaga([m('AGUARDANDO_PAGAMENTO')], 'FIEL');
    expect(passos[2].detalhe).toMatch(/Fiel/);
  });
});
