/**
 * O link para pagar vem do servidor e atravessou o Kafka: só vira href se for https (ou http em localhost, no dev).
 * Qualquer outra coisa (javascript:, data:, um esquema estranho) é descartada.
 */
export function linkSeguro(link: string | null | undefined): string | null {
  if (!link) return null;
  try {
    const url = new URL(link);
    if (url.protocol === 'https:') return url.href;
    if (
      url.protocol === 'http:' &&
      (url.hostname === 'localhost' || url.hostname === '127.0.0.1')
    )
      return url.href;
  } catch {
    // não é um endereço
  }
  return null;
}
