import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

/**
 * A interface chama /api/pedidos/* e /api/estoque/*. Em dev, o Vite tira o prefixo e repassa para os serviços
 * (portas de dev 8091 e 8092); na VM (fase 8), o Caddy faz o mesmo.
 */
function servico(porta: number, prefixo: string) {
  return {
    target: `http://localhost:${porta}`,
    rewrite: (caminho: string) => caminho.slice(prefixo.length),
  };
}

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5195,
    proxy: {
      '/api/pedidos': servico(8091, '/api/pedidos'),
      '/api/estoque': servico(8092, '/api/estoque'),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/teste/preparar.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
