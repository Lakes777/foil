import { dinheiro } from './formato';

describe('formato', () => {
  it('escreve dinheiro de mentira com vírgula', () => {
    expect(dinheiro(1800)).toBe('F$ 18,00');
    expect(dinheiro(5)).toBe('F$ 0,05');
    expect(dinheiro(123456)).toBe('F$ 1.234,56');
  });
});
