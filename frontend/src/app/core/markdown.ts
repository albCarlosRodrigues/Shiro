import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import DOMPurify from 'dompurify';
import { Marked } from 'marked';

/**
 * Markdown de agentes e documentos é conteúdo não confiável: é convertido com `marked` e sanitizado
 * com DOMPurify antes de entrar no DOM. Links abrem fora do app (o shell desktop bloqueia navegação e
 * encaminha só http/https ao navegador do sistema); imagens remotas são removidas para não vazar dados.
 */
const parser = new Marked({ gfm: true, breaks: false, async: false });

const ALLOWED_TAGS = [
  'p', 'br', 'hr', 'strong', 'em', 'b', 'i', 's', 'del', 'code', 'pre', 'blockquote', 'ul', 'ol', 'li',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'a', 'span', 'input', 'img',
];

let hooksInstalled = false;

function installHooks(): void {
  if (hooksInstalled) return;
  hooksInstalled = true;
  DOMPurify.addHook('afterSanitizeAttributes', node => {
    if (node.tagName === 'A') {
      const href = node.getAttribute('href') ?? '';
      if (!/^(https?:|mailto:|#)/i.test(href)) node.removeAttribute('href');
      node.setAttribute('target', '_blank');
      node.setAttribute('rel', 'noopener noreferrer');
    }
    if (node.tagName === 'IMG') {
      const src = node.getAttribute('src') ?? '';
      if (!/^data:image\/(png|jpe?g|gif|webp);/i.test(src)) node.remove();
    }
    if (node.tagName === 'INPUT') {
      if (node.getAttribute('type') !== 'checkbox') node.remove();
      else node.setAttribute('disabled', '');
    }
  });
}

export function renderMarkdown(source: string | null | undefined): string {
  if (!source) return '';
  installHooks();
  const html = parser.parse(source) as string;
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS,
    ALLOWED_ATTR: ['href', 'title', 'src', 'alt', 'type', 'checked', 'disabled', 'colspan', 'rowspan', 'align', 'target', 'rel'],
    ALLOW_DATA_ATTR: false,
  });
}

@Component({
  selector: 'sd-markdown',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'md' },
  template: `<div [innerHTML]="html()"></div>`,
})
export class MarkdownView {
  readonly source = input<string | null | undefined>('');
  protected readonly html = computed(() => renderMarkdown(this.source()));
}
