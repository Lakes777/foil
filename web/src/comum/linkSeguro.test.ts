import { linkSeguro } from './linkSeguro';

describe('linkSeguro', () => {
  it('aceita https e o http do dev', () => {
    expect(linkSeguro('https://fiel.andrelagos.dev/pagar?qr=x')).toBe(
      'https://fiel.andrelagos.dev/pagar?qr=x',
    );
    expect(linkSeguro('http://localhost:5173/pagar?qr=x')).toBe(
      'http://localhost:5173/pagar?qr=x',
    );
  });

  it('recusa o resto', () => {
    expect(linkSeguro('javascript:alert(1)')).toBeNull();
    expect(linkSeguro('data:text/html,oi')).toBeNull();
    expect(linkSeguro('http://exemplo.com/pagar')).toBeNull();
    expect(linkSeguro('não é link')).toBeNull();
    expect(linkSeguro(null)).toBeNull();
  });
});
