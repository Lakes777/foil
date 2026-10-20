import { vi } from 'vitest';

/** Combina respostas do fetch por caminho (o começo da URL). O que não estiver aqui falha alto. */
export function responder(rotas: Record<string, unknown | (() => Response)>) {
  const espiao = vi.fn(async (url: string, opcoes?: RequestInit) => {
    const chave = Object.keys(rotas)
      .filter((r) => {
        const [metodo, caminho] = r.includes(' ') ? r.split(' ') : ['GET', r];
        return url.startsWith(caminho) && (opcoes?.method ?? 'GET') === metodo;
      })
      .sort((a, b) => b.length - a.length)[0];
    if (!chave)
      throw new Error(
        `fetch sem resposta combinada no teste: ${opcoes?.method ?? 'GET'} ${url}`,
      );
    const r = rotas[chave];
    if (typeof r === 'function') return (r as () => Response)();
    return new Response(JSON.stringify(r), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', espiao);
  return espiao;
}
