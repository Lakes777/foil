/** O jsdom não tem EventSource: este guarda as fontes abertas e deixa o teste mandar eventos. */
export class EventSourceFalso {
  static abertas: EventSourceFalso[] = [];

  readonly url: string;
  fechada = false;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private ouvintes = new Map<string, ((e: MessageEvent<string>) => void)[]>();

  constructor(url: string) {
    this.url = url;
    EventSourceFalso.abertas.push(this);
  }

  addEventListener(tipo: string, ouvinte: (e: MessageEvent<string>) => void) {
    this.ouvintes.set(tipo, [...(this.ouvintes.get(tipo) ?? []), ouvinte]);
  }

  close() {
    this.fechada = true;
  }

  /** O servidor mandando um envelope. */
  mandar(tipo: string, dados: unknown) {
    const evento = new MessageEvent<string>(tipo, {
      data: JSON.stringify(dados),
    });
    for (const o of this.ouvintes.get(tipo) ?? []) o(evento);
  }

  static ultima(): EventSourceFalso | undefined {
    return EventSourceFalso.abertas.at(-1);
  }
}
