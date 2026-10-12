import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';

import { KIND_LABEL, dateTime, duration, integer, usd } from '../../core/format';
import type { Run } from '../../core/models';
import { StatusBadge } from '../../shell/bits';
import { Icon } from '../../shell/icon';
import { SessionsStore } from '../../state/sessions.store';

/** Histórico de execuções do painel em foco: estado, duração, modelo, consumo informado e o comando usado. */
@Component({
  selector: 'sd-runs-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [StatusBadge, Icon],
  styles: `
    :host { display: block; flex: 1 1 auto; overflow: auto; padding: 8px; }
    .run { border: 1px solid var(--line-1); border-radius: var(--r-md); margin-bottom: 6px; background: var(--bg-1); }
    .head { display: flex; align-items: center; gap: 8px; width: 100%; padding: 8px 10px; border: 0; background: none; color: var(--fg-1); text-align: left; cursor: pointer; }
    .prompt { color: var(--fg-2); font-size: var(--fs-sm); }
    dl { display: grid; grid-template-columns: 110px 1fr; gap: 3px 10px; margin: 0; padding: 0 10px 10px; font-size: var(--fs-xs); }
    dt { color: var(--fg-3); }
    dd { margin: 0; color: var(--fg-1); overflow-wrap: anywhere; }
    code { font-size: var(--fs-xs); }
    .note { color: var(--fg-3); font-size: var(--fs-xs); padding: 4px 2px 10px; }
  `,
  template: `
    @if (!sessionId()) {
      <div class="empty">Coloque um painel em foco para ver as execuções dele.</div>
    } @else {
      <div class="note">Custo e tokens aparecem só quando o Claude Code os informa; são estimativas dele, não a fatura.</div>
      @for (run of runs(); track run.id) {
        <div class="run">
          <button class="head" (click)="toggle(run.id)" [attr.aria-expanded]="open() === run.id">
            <sd-status [status]="run.status" />
            <span class="chip">{{ kind[run.kind] }}</span>
            @if (run.demo) { <span class="demo-tag">demo</span> }
            <span class="grow ellipsis prompt">{{ run.prompt }}</span>
            <sd-icon [name]="open() === run.id ? 'chevron-down' : 'chevron-right'" [size]="12" />
          </button>
          @if (open() === run.id) {
            <dl class="selectable">
              <dt>Enfileirada</dt><dd>{{ dt(run.queuedAt) }}</dd>
              @if (run.startedAt) { <dt>Início</dt><dd>{{ dt(run.startedAt) }}</dd> }
              @if (run.finishedAt) { <dt>Fim</dt><dd>{{ dt(run.finishedAt) }}</dd> }
              @if (run.durationMs != null) { <dt>Duração</dt><dd>{{ dur(run.durationMs) }}</dd> }
              @if (run.model) { <dt>Modelo</dt><dd>{{ run.model }}</dd> }
              @if (run.cliVersion) { <dt>Claude Code</dt><dd>{{ run.cliVersion }}</dd> }
              <dt>Acesso</dt><dd>{{ run.access === 'WRITE' ? 'pode alterar arquivos' : 'somente leitura' }}</dd>
              @if (run.cwd) { <dt>Pasta</dt><dd><code>{{ run.cwd }}</code></dd> }
              @if (run.numTurns != null) { <dt>Turnos</dt><dd>{{ run.numTurns }}</dd> }
              @if (run.inputTokens != null || run.outputTokens != null) {
                <dt>Tokens</dt><dd>{{ int(run.inputTokens) || '?' }} entrada · {{ int(run.outputTokens) || '?' }} saída
                  @if (run.cacheReadTokens) { · {{ int(run.cacheReadTokens) }} do cache }</dd>
              }
              @if (run.costUsd != null) { <dt>Custo estimado</dt><dd>{{ money(run.costUsd) }}</dd> }
              @if (run.exitCode != null) { <dt>Código de saída</dt><dd>{{ run.exitCode }}</dd> }
              @if (run.failureMessage) { <dt>Mensagem</dt><dd>{{ run.failureMessage }}</dd> }
              @if (run.permissionDenials.length) {
                <dt>Negadas</dt><dd>@for (d of run.permissionDenials; track $index) { <code>{{ d.suggestedRule ?? d.toolName }}</code> }</dd>
              }
              @if (run.command.length) { <dt>Comando</dt><dd><code>{{ run.command.join(' ') }}</code></dd> }
              @if (run.claudeSessionId) { <dt>Sessão Claude</dt><dd><code>{{ run.claudeSessionId }}</code></dd> }
            </dl>
          }
        </div>
      } @empty {
        <div class="empty">Nenhuma execução nesta sessão ainda.</div>
      }
    }
  `,
})
export class RunsPanel {
  readonly sessionId = input<string | null>(null);
  private readonly sessions = inject(SessionsStore);

  protected readonly kind = KIND_LABEL;
  protected readonly open = signal<string | null>(null);
  protected readonly dt = dateTime;
  protected readonly dur = duration;
  protected readonly int = integer;
  protected readonly money = usd;

  protected readonly runs = computed<Run[]>(() => {
    const id = this.sessionId();
    if (!id) return [];
    return [...this.sessions.liveState(id)().runs].reverse();
  });

  protected toggle(id: string): void {
    this.open.update(current => (current === id ? null : id));
  }
}
