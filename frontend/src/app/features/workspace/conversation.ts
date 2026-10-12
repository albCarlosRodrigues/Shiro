import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { AutoScroll } from '../../core/auto-scroll';
import { failureHelp } from '../../core/failures';
import { clockTime, isActiveStatus } from '../../core/format';
import { MarkdownView } from '../../core/markdown';
import type { Agent, Run } from '../../core/models';
import { Avatar } from '../../shell/bits';
import { Icon } from '../../shell/icon';
import type { SessionLive } from '../../state/session-live';

/** Aba Conversa: mensagens, texto em streaming e o desfecho da última execução com o caminho de resolução. */
@Component({
  selector: 'sd-conversation',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AutoScroll, MarkdownView, Avatar, Icon],
  styles: `
    :host { display: block; height: 100%; min-height: 0; }
    .scroll { height: 100%; overflow: auto; padding: 12px 14px 4px; }
    .msg { display: grid; grid-template-columns: 22px 1fr; gap: 8px; margin-bottom: 14px; }
    .who { display: flex; align-items: baseline; gap: 6px; margin-bottom: 2px; font-size: var(--fs-xs); color: var(--fg-3); }
    .who b { color: var(--fg-2); font-weight: 600; }
    .user .bubble { padding: 7px 10px; border-radius: var(--r-md); background: var(--bg-3); white-space: pre-wrap; overflow-wrap: anywhere; }
    .me { display: grid; place-items: center; width: 22px; height: 22px; border-radius: 6px; background: var(--bg-4); color: var(--fg-2); font-size: 10px; font-weight: 600; }
    .system { margin: 0 0 12px 30px; padding: 6px 10px; border-left: 2px solid var(--line-3); color: var(--fg-3); font-size: var(--fs-sm); }
    .kind { display: inline-flex; align-items: center; gap: 3px; color: var(--warn); }
    .stream { position: relative; }
    .caret { display: inline-block; width: 7px; height: 14px; margin-left: 2px; vertical-align: text-bottom; background: var(--signal); animation: sd-pulse 1s steps(2) infinite; }
    .thinking { display: flex; align-items: center; gap: 6px; color: var(--fg-3); font-size: var(--fs-sm); }
    .outcome { margin: 0 0 12px 30px; }
    .outcome .banner { flex-direction: column; gap: 4px; }
    .outcome .actions { display: flex; gap: 6px; margin-top: 4px; }
    .intro { max-width: 460px; margin: 24px auto; color: var(--fg-3); text-align: center; }
    .intro h3 { margin-bottom: 6px; color: var(--fg-1); }
  `,
  template: `
    <div class="scroll" sdAutoScroll role="log" aria-live="polite" [attr.aria-busy]="busy()">
      @if (live().loaded && live().messages.length === 0 && !live().current) {
        <div class="intro">
          <h3>{{ agent()?.name ?? 'Agente' }}</h3>
          <p>{{ agent()?.responsibility || 'Escreva uma mensagem para começar.' }}</p>
          <p>Em <b>Conversar</b>, o agente lê e analisa com permissões de leitura. Em <b>Executar</b>, você autoriza alterações na pasta do projeto.</p>
        </div>
      }
      @for (m of live().messages; track m.id) {
        @switch (m.role) {
          @case ('USER') {
            <div class="msg user">
              <span class="me" aria-hidden="true">VC</span>
              <div>
                <div class="who"><b>Você</b><span>{{ time(m.createdAt) }}</span>
                  @if (kindOf(m.runId) === 'EXECUTION') { <span class="kind"><sd-icon name="bolt" [size]="11" /> pedido de execução</span> }
                </div>
                <div class="bubble selectable">{{ m.content }}</div>
              </div>
            </div>
          }
          @case ('AGENT') {
            <div class="msg">
              <sd-avatar [agent]="agent()" [size]="22" />
              <div>
                <div class="who"><b>{{ agent()?.name ?? 'Agente' }}</b><span>{{ time(m.createdAt) }}</span></div>
                <sd-markdown [source]="m.content" />
              </div>
            </div>
          }
          @default { <div class="system">{{ m.content }}</div> }
        }
      }
      @if (live().current; as cur) {
        @if (cur.streaming) {
          <div class="msg stream">
            <sd-avatar [agent]="agent()" [size]="22" />
            <div>
              <div class="who"><b>{{ agent()?.name ?? 'Agente' }}</b><span>escrevendo…</span></div>
              <sd-markdown [source]="cur.streaming" /><span class="caret" aria-hidden="true"></span>
            </div>
          </div>
        } @else if (busy()) {
          <div class="msg">
            <sd-avatar [agent]="agent()" [size]="22" />
            <div class="thinking">
              <sd-icon name="spinner" class="spin" [size]="13" />
              {{ cur.status === 'QUEUED' ? 'Na fila: aguardando uma vaga de execução.' : cur.status === 'CANCELLING' ? 'Cancelando…' : cur.thinking ? 'Pensando…' : 'Trabalhando…' }}
            </div>
          </div>
        }
      }
      @if (outcome(); as o) {
        <div class="outcome">
          <div class="banner" [class.danger]="o.tone === 'danger'" [class.warn]="o.tone === 'warn'">
            <b>{{ o.title }}</b>
            <span>{{ o.text }}</span>
            <div class="actions">
              @if (o.action === 'settings') { <button class="btn sm" (click)="action.emit('settings')">Abrir Configurações</button> }
              @if (o.action === 'project') { <button class="btn sm" (click)="action.emit('project')">Editar projeto</button> }
              @if (o.action === 'activity') { <button class="btn sm" (click)="action.emit('activity')">Ver Atividade e Console</button> }
              @if (o.action === 'retry' || o.denials) { <button class="btn sm" (click)="action.emit('retry')">Reaproveitar o último pedido</button> }
              @if (o.denials) { <button class="btn sm" (click)="action.emit('activity')">Ver permissões negadas</button> }
            </div>
          </div>
        </div>
      }
    </div>
  `,
})
export class Conversation {
  readonly live = input.required<SessionLive>();
  readonly agent = input<Agent | undefined>();
  readonly action = output<'settings' | 'project' | 'activity' | 'retry'>();

  protected readonly time = clockTime;
  protected readonly busy = computed(() => isActiveStatus(this.live().current?.status));

  private readonly runKinds = computed(() => new Map(this.live().runs.map(r => [r.id, r.kind])));

  protected kindOf(runId: string | null): string | undefined {
    return runId ? this.runKinds().get(runId) : undefined;
  }

  /** Desfecho da execução mais recente quando ela não terminou bem (ou terminou com permissões negadas). */
  protected readonly outcome = computed(() => {
    const live = this.live();
    if (isActiveStatus(live.current?.status)) return null;
    const last: Run | undefined = live.runs[live.runs.length - 1];
    if (!last || (live.current && live.current.runId !== last.id)) return null;
    const denials = last.permissionDenials?.length ?? 0;
    if (last.status === 'COMPLETED' && denials === 0) return null;
    if (last.status === 'CANCELLED') return null;
    if (last.status === 'COMPLETED') {
      return {
        tone: 'warn', title: denials === 1 ? 'Uma ferramenta foi negada' : `${denials} ferramentas foram negadas`, denials,
        text: 'O agente terminou sem poder usar tudo o que precisava. Libere a regra na aba Atividade (vale para as próximas execuções) e peça de novo.',
        action: undefined,
      };
    }
    const help = failureHelp(last.failureCode ?? (last.status === 'INTERRUPTED' ? 'app_closed' : last.status === 'TIMED_OUT' ? 'timeout' : 'claude_error'));
    return {
      tone: last.status === 'INTERRUPTED' ? 'warn' : 'danger',
      title: help?.title ?? 'Falha',
      text: (last.failureMessage ? last.failureMessage + ' ' : '') + (help?.fix ?? ''),
      action: help?.action,
      denials: 0,
    };
  });
}
