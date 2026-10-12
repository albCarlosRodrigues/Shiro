import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';

import { AutoScroll } from '../../core/auto-scroll';
import { clockTime } from '../../core/format';
import { Icon } from '../../shell/icon';
import type { ActivityItem } from '../../state/session-live';

const ICON: Record<ActivityItem['kind'], string> = {
  lifecycle: 'activity',
  tool: 'terminal',
  denied: 'lock',
  notice: 'info',
  usage: 'clock',
  subagent: 'agent',
};

/** Aba Atividade: ciclo da execução, ferramentas usadas (com entrada e resultado), permissões negadas e consumo. */
@Component({
  selector: 'sd-activity',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AutoScroll, Icon],
  styles: `
    :host { display: block; height: 100%; min-height: 0; }
    .scroll { height: 100%; overflow: auto; padding: 8px 10px; }
    .item { display: grid; grid-template-columns: 18px 1fr auto; gap: 8px; align-items: start; padding: 5px 4px; border-radius: var(--r-sm); }
    .item:hover { background: var(--bg-2); }
    .ic { margin-top: 2px; color: var(--tone); }
    .item[data-tone='info'] { --tone: var(--fg-3); }
    .item[data-tone='running'] { --tone: var(--st-running); }
    .item[data-tone='ok'] { --tone: var(--st-done); }
    .item[data-tone='warn'] { --tone: var(--st-waiting); }
    .item[data-tone='error'] { --tone: var(--st-failed); }
    .title { font-size: var(--fs-sm); color: var(--fg-1); }
    .title .state { margin-left: 6px; color: var(--tone); font-size: var(--fs-xs); }
    .detail { color: var(--fg-3); font-family: var(--font-mono); font-size: var(--fs-xs); overflow-wrap: anywhere; }
    .time { color: var(--fg-4); font-size: var(--fs-xs); white-space: nowrap; }
    .toggle { border: 0; padding: 0; background: none; color: inherit; text-align: left; cursor: pointer; width: 100%; }
    pre { margin: 6px 0 2px; padding: 6px 8px; max-height: 260px; overflow: auto; border-radius: var(--r-sm); background: var(--bg-0);
      font-size: var(--fs-xs); white-space: pre-wrap; overflow-wrap: anywhere; }
    .rule { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-top: 6px; }
    .rule code { padding: 2px 6px; border-radius: var(--r-sm); background: var(--bg-3); font-size: var(--fs-xs); }
    .hint { color: var(--fg-3); font-size: var(--fs-xs); }
  `,
  template: `
    <div class="scroll" sdAutoScroll>
      @for (item of items(); track item.key) {
        <div class="item" [attr.data-tone]="item.tone">
          <sd-icon class="ic" [name]="item.pending ? 'spinner' : icon[item.kind]" [class.spin]="item.pending" [size]="14" />
          <div class="selectable">
            @if (item.kind === 'tool') {
              <button class="toggle" (click)="toggle(item.key)" [attr.aria-expanded]="open().has(item.key)">
                <div class="title">{{ item.title }}
                  <span class="state">{{ item.pending ? 'executando' : item.resultError ? 'erro' : item.tone === 'warn' ? 'negada' : 'ok' }}</span>
                </div>
                @if (item.detail) { <div class="detail">{{ item.detail }}</div> }
              </button>
              @if (open().has(item.key)) {
                @if (item.input) { <pre>{{ json(item.input) }}</pre> }
                @if (item.result != null) { <pre>{{ item.result }}{{ item.resultTruncated ? '\\n… (cortado)' : '' }}</pre> }
              }
            } @else {
              <div class="title">{{ item.title }}</div>
              @if (item.detail) { <div class="detail">{{ item.detail }}</div> }
              @if (item.kind === 'denied') {
                <div class="rule">
                  @if (item.suggestedRule) {
                    <code>{{ item.suggestedRule }}</code>
                    <button class="btn sm" (click)="allow.emit(item.suggestedRule)"><sd-icon name="key" [size]="12" /> Liberar para este agente</button>
                  }
                  <span class="hint">Liberar adiciona a regra às ferramentas permitidas do agente (--allowedTools) nas próximas execuções. Nada é repetido sozinho.</span>
                </div>
              }
            }
          </div>
          <span class="time">{{ time(item.at) }}</span>
        </div>
      } @empty {
        <div class="empty">Nenhuma atividade ainda. Ferramentas, permissões e consumo aparecem aqui durante a execução.</div>
      }
    </div>
  `,
})
export class Activity {
  readonly items = input.required<ActivityItem[]>();
  readonly allow = output<string>();

  protected readonly icon = ICON;
  protected readonly time = clockTime;
  protected readonly open = signal(new Set<string>());

  protected toggle(key: string): void {
    this.open.update(set => {
      const next = new Set(set);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  protected json(value: unknown): string {
    try {
      return JSON.stringify(value, null, 2);
    } catch {
      return String(value);
    }
  }
}
