import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach, vi } from 'vitest';
import { EventSourceFalso } from './eventSourceFalso';

// Nenhum teste fala com um servidor de verdade: fetch sem resposta combinada falha alto
beforeEach(() => {
  EventSourceFalso.abertas = [];
  vi.stubGlobal('EventSource', EventSourceFalso);
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      throw new Error(`fetch sem resposta combinada no teste: ${url}`);
    }),
  );
  window.scrollTo = () => {};
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
  window.location.hash = '';
  document.documentElement.removeAttribute('data-uv');
  localStorage.clear();
});
