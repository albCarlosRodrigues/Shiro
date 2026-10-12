import { fuzzyScore } from './command-palette';

describe('busca da paleta', () => {
  it('encontra por subsequência, ignorando acentos', () => {
    expect(fuzzyScore('cfg', 'Configurações')).toBeGreaterThan(0);
    expect(fuzzyScore('configuracoes', 'Configurações')).toBeGreaterThan(0);
    expect(fuzzyScore('xyz', 'Configurações')).toBe(0);
  });

  it('prefere início de palavra e sequência contínua', () => {
    expect(fuzzyScore('nov', 'Novo projeto')).toBeGreaterThan(fuzzyScore('nov', 'Abrir sessão nova'));
  });
});
