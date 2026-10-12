import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';

import { AutoScroll } from '../../core/auto-scroll';
import { clockTime } from '../../core/format';
import { Toasts } from '../../core/toasts';
import { Icon } from '../../shell/icon';
import type { ConsoleLine } from '../../state/session-live';

/**
 * Aba Console: visualizador somente leitura da saída do processo (stderr, linhas não reconhecidas e o
 * comando usado). Não é um terminal interativo: não aceita entrada nem redimensiona um PTY.
 */
@Component({
  selector: 'sd-console-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AutoScroll, Icon],
  styles: `
    :host { display: flex; flex-direction: column; height: 100%; min-height: 0; background: var(--bg-0); }
    .bar { display: flex; align-items: center; gap: 8px; height: 28px; padding: 0 8px 0 12px; border-bottom: 1px solid var(--line-1);
      color: var(--fg-3); font-size: var(--fs-xs); flex: none; }
    .scroll { flex: 1 1 auto; overflow: auto; padding: 6px 12px; font-family: var(--font-mono); font-size: var(--fs-xs); line-height: 1.6; }
    .line { display: grid; grid-template-columns: 44px 52px 1fr; gap: 8px; white-space: pre-wrap; overflow-wrap: anywhere; }
    .t { color: var(--fg-4); }
    .s { color: var(--fg-3); }
    .line[data-stream='stderr'] .x { color: var(--danger-fg); }
    .line[data-stream='command'] .x, .line[data-stream='info'] .x { color: var(--signal); }
  `,
  template: `
    <div class="bar">
      <sd-icon name="terminal" [size]="12" />
      <span class="grow">Console somente leitura · saída do processo do Claude Code</span>
      <button class="btn ghost sm" (click)="copy()" [disabled]="lines().length === 0"><sd-icon name="copy" [size]="12" /> Copiar</button>
    </div>
    <div class="scroll selectable" sdAutoScroll role="log" aria-label="Saída do processo">
      @for (line of lines(); track line.key) {
        <div class="line" [attr.data-stream]="line.stream"><span class="t">{{ time(line.at) }}</span><span class="s">{{ line.stream }}</span><span class="x">{{ line.text }}</span></div>
      } @empty {
        <div class="muted">Sem saída. O console mostra o comando executado, mensagens de erro do processo e linhas que não fazem parte do fluxo estruturado.</div>
      }
    </div>
  `,
})
export class ConsoleView {
  readonly lines = input.required<ConsoleLine[]>();
  private readonly toasts = inject(Toasts);
  protected readonly time = clockTime;

  protected async copy(): Promise<void> {
    const text = this.lines().map(l => `[${l.at}] ${l.stream}: ${l.text}`).join('\n');
    try {
      await navigator.clipboard.writeText(text);
      this.toasts.show('Console copiado.', 'ok', 2000);
    } catch {
      this.toasts.show('Não foi possível copiar.', 'warn');
    }
  }
}
