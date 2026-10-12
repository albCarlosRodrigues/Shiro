import { renderMarkdown } from './markdown';

describe('Markdown não confiável', () => {
  it('remove scripts, eventos e links perigosos', () => {
    const html = renderMarkdown('# Título\n\n<script>alert(1)</script><img src=x onerror=alert(1)>\n\n[clique](javascript:alert(1)) e [docs](https://exemplo.com)');
    expect(html).toContain('<h1>Título</h1>');
    expect(html).not.toContain('<script');
    expect(html).not.toContain('onerror');
    expect(html).not.toContain('javascript:');
    expect(html).toContain('href="https://exemplo.com"');
    expect(html).toContain('rel="noopener noreferrer"');
  });

  it('remove imagens remotas e iframes', () => {
    const html = renderMarkdown('![x](https://rastreador.exemplo/pixel.png)\n\n<iframe src="https://exemplo.com"></iframe>');
    expect(html).not.toContain('<img');
    expect(html).not.toContain('<iframe');
  });

  it('mantém blocos de código e tabelas', () => {
    const html = renderMarkdown('```ts\nconst a = 1;\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |');
    expect(html).toContain('<pre><code');
    expect(html).toContain('<table>');
  });
});
