import { useEffect, useRef, useState } from 'react';
import { ouvirEventos } from '../../api/cliente';
import type { Envelope } from '../../api/tipos';

/**
 * Abre o ao vivo (SSE) enquanto a aba está aberta e fecha ao sair. Cada aba abre o seu: só uma aparece por vez, então
 * nunca há duas conexões do mesmo navegador. Devolve se a conexão está de pé (o EventSource reconecta sozinho).
 */
export function useAoVivo(aoChegar: (envelope: Envelope) => void): boolean {
  const ouvinte = useRef(aoChegar);
  const [conectado, setConectado] = useState(false);

  useEffect(() => {
    ouvinte.current = aoChegar;
  });

  useEffect(
    () =>
      ouvirEventos((envelope) => {
        // chegou mensagem: está conectado, mesmo que o onopen tenha se perdido
        setConectado(true);
        ouvinte.current(envelope);
      }, setConectado),
    [],
  );

  return conectado;
}
