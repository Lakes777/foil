import { facetas, luzNegra, paleta, PALETAS } from './desenho';

describe('desenho da gema', () => {
  it('tem a borda, 4 facetas por lado, a mesa e o reflexo', () => {
    expect(facetas(PALETAS.mar)).toHaveLength(1 + 8 * 4 + 2);
  });

  it('fica dentro do quadrado de 100 x 100', () => {
    const numeros = facetas(PALETAS.aurora).flatMap((f) =>
      f.pontos.split(/[ ,]/).map(Number),
    );
    expect(Math.min(...numeros)).toBeGreaterThanOrEqual(0);
    expect(Math.max(...numeros)).toBeLessThanOrEqual(100);
  });

  it('cor desconhecida vira lilás em vez de quebrar', () => {
    expect(paleta('nao-existe')).toBe(PALETAS.lilas);
  });

  it('só tem luz negra quem fluoresce', () => {
    expect(luzNegra(null)).toBeNull();
    expect(luzNegra('nao-existe')).toBeNull();
    expect(luzNegra('lumen')).not.toBeNull();
  });
});
