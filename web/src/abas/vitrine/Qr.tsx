import qrcode from 'qrcode-generator';
import { useMemo } from 'react';

/**
 * O QR da cobrança, desenhado a partir do texto que o pedido devolve em `qr` (o mesmo jeito do Fiel). Sempre escuro
 * sobre claro, também na luz negra: leitor de QR não lê invertido.
 */
export function Qr({
  texto,
  tamanho = 196,
}: {
  texto: string;
  tamanho?: number;
}) {
  const modulos = useMemo(() => {
    const codigo = qrcode(0, 'M');
    codigo.addData(texto);
    codigo.make();
    const n = codigo.getModuleCount();
    const lista: { x: number; y: number }[] = [];
    for (let y = 0; y < n; y++)
      for (let x = 0; x < n; x++) if (codigo.isDark(y, x)) lista.push({ x, y });
    return { n, lista };
  }, [texto]);
  const margem = 2;
  const lado = modulos.n + margem * 2;
  return (
    <svg
      className="qr"
      viewBox={`0 0 ${lado} ${lado}`}
      width={tamanho}
      height={tamanho}
      role="img"
      aria-label="QR da cobrança no Fiel"
      shapeRendering="crispEdges"
    >
      <rect width={lado} height={lado} fill="#fbfaf6" rx="1.5" />
      {modulos.lista.map(({ x, y }) => (
        <rect
          key={`${x}-${y}`}
          x={x + margem}
          y={y + margem}
          width="1"
          height="1"
          fill="#111216"
        />
      ))}
    </svg>
  );
}
