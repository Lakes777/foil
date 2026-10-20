/**
 * A gema lapidada (corte brilhante visto de cima) desenhada a partir de uma paleta. A carta guarda só as chaves:
 * `cor` (de dia) e `fluorescencia` (na luz negra; null = a gema não fluoresce e apaga).
 */

/** Do mais claro (o reflexo da mesa) ao mais escuro (a borda). */
export type Paleta = readonly [string, string, string, string];

export const PALETAS: Record<string, Paleta> = {
  aurora: ['#ffd1e0', '#c9b6ff', '#8fd3ff', '#3b2f6b'],
  tempestade: ['#eef0ff', '#a9b4ff', '#6a5cd6', '#211a4a'],
  lumen: ['#fbfbf7', '#e6e6e0', '#cfd0ca', '#9a9c95'],
  mar: ['#d9fbff', '#8fe3e0', '#4fa3c7', '#16384a'],
  brasa: ['#ffd7c2', '#ff9478', '#c4374b', '#3e1020'],
  ambar: ['#fff1c9', '#f7c66b', '#c98a2e', '#4a2c0c'],
  neblina: ['#eef3fb', '#b9c8e6', '#7389b8', '#22304f'],
  musgo: ['#efffd9', '#b9e39a', '#6fa77a', '#1f3b2a'],
  onix: ['#8a8c95', '#50525b', '#2c2d33', '#141519'],
  lilas: ['#f4ecff', '#cbb8ff', '#9a84e6', '#3a2f6b'],
  cinza: ['#55585f', '#3f424a', '#2c2e34', '#1e2025'],
};

/** Como cada fluorescência aparece na luz negra. */
export const LUZ_NEGRA: Record<string, Paleta> = {
  aurora: ['#ffe6f8', '#ff6fd8', '#c8209e', '#4a0b3c'],
  tempestade: ['#f1e6ff', '#b46bff', '#7a1fd6', '#2a0a4f'],
  lumen: ['#ecfeff', '#5ff3ff', '#18a8d8', '#06364f'],
  ambar: ['#fff6d6', '#ffb02e', '#e0620a', '#4a1c02'],
  musgo: ['#f6ffd6', '#b8ff3a', '#5fc21a', '#1d4a08'],
};

/** Em português, para a ficha: "fluoresce rosa". */
export const NOME_DA_FLUORESCENCIA: Record<string, string> = {
  aurora: 'rosa',
  tempestade: 'violeta',
  lumen: 'ciano',
  ambar: 'laranja',
  musgo: 'verde-limão',
};

export interface Faceta {
  pontos: string;
  cor: string;
  opacidade?: number;
}

const LADOS = 8;
const GIRO = -Math.PI / 2 + Math.PI / 8;

function anel(raio: number, giro: number): [number, number][] {
  return Array.from({ length: LADOS }, (_, i) => {
    const a = (i / LADOS) * Math.PI * 2 + giro;
    return [50 + raio * Math.cos(a), 50 + raio * Math.sin(a)];
  });
}

function texto(pontos: [number, number][]): string {
  return pontos.map(([x, y]) => `${x.toFixed(2)},${y.toFixed(2)}`).join(' ');
}

/** As facetas de uma gema num quadrado de 100 x 100: a borda, 4 facetas por lado e a mesa no meio. */
export function facetas(c: Paleta): Faceta[] {
  const fora = anel(46, GIRO);
  const meio = anel(33, GIRO + Math.PI / LADOS);
  const mesa = anel(20, GIRO);
  const lista: Faceta[] = [{ pontos: texto(fora), cor: c[3] }];
  for (let i = 0; i < LADOS; i++) {
    const j = (i + 1) % LADOS;
    const claro = i % 2 === 0;
    lista.push({
      pontos: texto([fora[i], fora[j], meio[i]]),
      cor: claro ? c[2] : c[3],
      opacidade: 0.95,
    });
    lista.push({
      pontos: texto([fora[i], meio[i], mesa[i]]),
      cor: claro ? c[1] : c[2],
      opacidade: 0.9,
    });
    lista.push({
      pontos: texto([meio[i], mesa[i], mesa[j]]),
      cor: claro ? c[2] : c[1],
      opacidade: 0.85,
    });
    lista.push({
      pontos: texto([meio[i], fora[j], mesa[j]]),
      cor: claro ? c[1] : c[3],
      opacidade: 0.7,
    });
  }
  lista.push({ pontos: texto(mesa), cor: c[1] });
  lista.push({
    pontos: texto([mesa[6], mesa[7], mesa[0], [50, 50]]),
    cor: c[0],
    opacidade: 0.75,
  });
  return lista;
}

/** Chave desconhecida (uma gema nova no banco antes da interface) vira lilás, em vez de quebrar. */
export function paleta(cor: string): Paleta {
  return PALETAS[cor] ?? PALETAS.lilas;
}

export function luzNegra(
  fluorescencia: string | null | undefined,
): Paleta | null {
  return fluorescencia ? (LUZ_NEGRA[fluorescencia] ?? null) : null;
}
