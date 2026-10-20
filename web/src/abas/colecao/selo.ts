/**
 * O selo do certificado: um desenho 9 x 9 espelhado, gerado do id do pedido (o mesmo pedido dá sempre o mesmo selo).
 * Devolve as casas acesas.
 */
export function selo(texto: string): { x: number; y: number }[] {
  let h = 0;
  for (let i = 0; i < texto.length; i++)
    h = (h * 31 + texto.charCodeAt(i)) >>> 0;
  const casas: { x: number; y: number }[] = [];
  for (let y = 0; y < 9; y++)
    for (let x = 0; x < 5; x++) {
      h = (Math.imul(h, 1103515245) + 12345) >>> 0;
      if ((h >>> 16) & 1) {
        casas.push({ x, y });
        if (x < 4) casas.push({ x: 8 - x, y });
      }
    }
  return casas;
}
