import { ChangeDetectionStrategy, Component, HostListener, computed, inject, input, output, signal, viewChild } from '@angular/core';

import { Api } from '../../core/api';
import { baseName, isActiveStatus, ROLE_SHORT, ROLE_LABEL } from '../../core/format';
import type { Agent } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { Avatar, StatusBadge } from '../../shell/bits';
import { Icon } from '../../shell/icon';
import { Nav } from '../../shell/nav';
import { ProjectActions } from '../../shell/project-actions';
import { CatalogStore } from '../../state/catalog.store';
import { SessionsStore } from '../../state/sessions.store';
import { UiStore } from '../../state/ui.store';
import { Activity } from './activity';
import { Composer, ComposerSend } from './composer';
import { ConsoleView } from './console-view';
import { Conversation } from './conversation';

type PaneTab = 'chat' | 'activity' | 'console';

/** Painel de um agente: cabeçalho com estado e ações, abas Conversa/Atividade/Console e caixa de mensagem. */
@Component({
  selector: 'sd-pane',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, Avatar, StatusBadge, Conversation, Activity, ConsoleView, Composer],
  host: {
    '[class.focused]': 'focused()',
    '[style.--agent-c]': 'agent()?.color ?? "#85848a"',
    '(focusin)': 'focus.emit()',
    '(pointerdown)': 'focus.emit()',
    '[attr.aria-label]': '"Painel de " + (agent()?.name ?? "agente")',
    role: 'region',
  },
  styles: `
    :host { position: relative; display: flex; flex-direction: column; min-width: 0; min-height: 0; border: 1px solid var(--line-1);
      border-radius: var(--r-md); background: var(--bg-2); overflow: hidden; transition: border-color var(--dur) var(--ease); }
    :host(.focused) { border-color: color-mix(in srgb, var(--signal) 55%, transparent); box-shadow: inset 0 2px 0 var(--signal); }
    :host(.drop-target) { border-color: var(--signal); background: var(--signal-dim); }
    header { display: flex; align-items: center; gap: 7px; height: var(--pane-head-h); padding: 0 6px 0 4px; border-bottom: 1px solid var(--line-1);
      background: var(--bg-3); flex: none; }
    .grip { display: grid; place-items: center; width: 16px; height: 22px; color: var(--fg-4); cursor: grab; }
    .names { display: flex; align-items: baseline; gap: 6px; min-width: 0; }
    .agent { font-weight: 600; font-size: var(--fs-sm); white-space: nowrap; }
    .role { color: color-mix(in srgb, var(--agent-c) var(--agent-ink-mix), var(--fg-1)); font-size: 10px; font-weight: 600; letter-spacing: 0.05em; }
    .title { color: var(--fg-3); font-size: var(--fs-xs); }
    .folder { display: inline-flex; align-items: center; gap: 4px; color: var(--fg-3); font-size: var(--fs-xs); font-family: var(--font-mono); white-space: nowrap; }
    .bar { display: flex; align-items: center; gap: 8px; height: 30px; padding: 0 8px; border-bottom: 1px solid var(--line-1); flex: none; }
    .badge { margin-left: 4px; padding: 0 5px; border-radius: 8px; background: var(--bg-4); font-size: 10px; }
    .badge.warn { background: var(--warn-dim); color: var(--warn); }
    .content { flex: 1 1 auto; min-height: 0; }
    .menu { right: 6px; top: 32px; }
    @container (max-width: 520px) { .folder, .title { display: none; } }
    :host { container-type: inline-size; }
  `,
  template: `
    <header (dblclick)="maximize.emit()">
      <span class="grip" draggable="true" (dragstart)="onDragStart($event)" title="Arraste para trocar de lugar" aria-hidden="true"><sd-icon name="grip" [size]="12" /></span>
      <sd-avatar [agent]="agent()" [size]="20" />
      <div class="names grow">
        <span class="agent ellipsis">{{ agent()?.name ?? 'Agente removido' }}</span>
        @if (agent(); as a) { <span class="role" [attr.title]="roleLabel[a.role]">{{ roleShort[a.role] }}</span> }
        <span class="title ellipsis" [attr.title]="session()?.title">{{ session()?.title }}</span>
      </div>
      @if (folder()) {
        <span class="folder" [attr.title]="folder()"><sd-icon name="folder" [size]="11" />{{ folderName() }}</span>
      }
      @if (live().current?.demo) { <span class="demo-tag" title="Execução simulada">demo</span> }
      <sd-status [status]="live().current?.status ?? null" [label]="statusLabel()" />
      @if (busy()) {
        <button class="btn ghost icon sm" (click)="cancelRun()" title="Cancelar a execução (encerra o processo)" aria-label="Cancelar execução"><sd-icon name="stop" [size]="13" /></button>
      }
      <button class="btn ghost icon sm" (click)="maximize.emit()" [attr.aria-label]="maximized() ? 'Restaurar painel' : 'Maximizar painel'"
        [attr.title]="maximized() ? 'Restaurar (Ctrl+Shift+Enter)' : 'Maximizar (Ctrl+Shift+Enter)'">
        <sd-icon [name]="maximized() ? 'restore' : 'maximize'" [size]="13" />
      </button>
      <button class="btn ghost icon sm" (click)="$event.stopPropagation(); menuOpen.set(!menuOpen())" aria-label="Mais ações" [attr.aria-expanded]="menuOpen()"><sd-icon name="more" [size]="14" /></button>
      <button class="btn ghost icon sm" (click)="hide.emit()" title="Ocultar painel (a execução continua)" aria-label="Ocultar painel"><sd-icon name="x" [size]="13" /></button>
    </header>
    @if (menuOpen()) {
      <div class="menu" role="menu" (click)="$event.stopPropagation()">
        <button role="menuitem" (click)="menuOpen.set(false); actions.renameSession(sessionId())"><sd-icon name="edit" [size]="14" /> Renomear sessão</button>
        <button role="menuitem" (click)="menuOpen.set(false); resetContext()" [disabled]="busy()"><sd-icon name="refresh" [size]="14" /> Recomeçar contexto</button>
        <button role="menuitem" (click)="menuOpen.set(false); ui.openDrawer({ kind: 'agent', agentId: session()?.agentId ?? null })"><sd-icon name="agent" [size]="14" /> Abrir agente no Estúdio</button>
        <button role="menuitem" (click)="menuOpen.set(false); showRun.emit()"><sd-icon name="history" [size]="14" /> Execuções desta sessão</button>
        <hr />
        <button role="menuitem" (click)="menuOpen.set(false); hide.emit()"><sd-icon name="eye" [size]="14" /> Ocultar painel</button>
        <button role="menuitem" class="danger" (click)="menuOpen.set(false); actions.removeSession(projectId(), sessionId())" [disabled]="busy()"><sd-icon name="trash" [size]="14" /> Excluir sessão…</button>
      </div>
    }
    <div class="bar">
      <div class="seg" role="tablist" aria-label="Conteúdo do painel">
        <button role="tab" [attr.aria-selected]="tab() === 'chat'" (click)="tab.set('chat')">Conversa</button>
        <button role="tab" [attr.aria-selected]="tab() === 'activity'" (click)="tab.set('activity')">Atividade
          @if (denials() > 0) { <span class="badge warn" [attr.title]="denials() + ' permissões negadas'">{{ denials() }}</span> }
        </button>
        <button role="tab" [attr.aria-selected]="tab() === 'console'" (click)="tab.set('console')">Console</button>
      </div>
    </div>
    <div class="content" role="tabpanel">
      @switch (tab()) {
        @case ('chat') { <sd-conversation [live]="live()" [agent]="agent()" (action)="onOutcomeAction($event)" /> }
        @case ('activity') { <sd-activity [items]="live().activity" (allow)="allowRule($event)" /> }
        @case ('console') { <sd-console-view [lines]="live().console" /> }
      }
    </div>
    <sd-composer #composer [agentName]="agent()?.name ?? 'o agente'" [folder]="folder()" [busy]="busy()" [disabled]="!agent() || !!agent()?.archivedAt"
      (send)="send($event)" (cancel)="cancelRun()" />
  `,
})
export class Pane {
  readonly sessionId = input.required<string>();
  readonly projectId = input.required<string>();
  readonly focused = input(false);
  readonly maximized = input(false);
  readonly focus = output<void>();
  readonly maximize = output<void>();
  readonly hide = output<void>();
  readonly showRun = output<void>();
  readonly dragStart = output<string>();

  protected readonly ui = inject(UiStore);
  protected readonly actions = inject(ProjectActions);
  private readonly sessions = inject(SessionsStore);
  private readonly catalog = inject(CatalogStore);
  private readonly nav = inject(Nav);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly composer = viewChild<Composer>('composer');

  protected readonly roleShort = ROLE_SHORT;
  protected readonly roleLabel = ROLE_LABEL;
  protected readonly tab = signal<PaneTab>('chat');
  protected readonly menuOpen = signal(false);
  /** Regras liberadas neste painel desde o último envio. */
  private readonly grants = signal<string[]>([]);

  protected readonly session = computed(() => {
    this.sessions.byProject();
    return this.sessions.session(this.sessionId());
  });
  protected readonly live = computed(() => this.sessions.liveState(this.sessionId())());
  protected readonly agent = computed<Agent | undefined>(() => this.catalog.agentById().get(this.session()?.agentId ?? ''));
  protected readonly project = computed(() => this.catalog.projectById().get(this.projectId()));
  protected readonly folder = computed(() => (this.project()?.folderExists ? this.project()?.project.folderPath ?? null : null));
  protected readonly folderName = computed(() => baseName(this.folder()));
  protected readonly busy = computed(() => isActiveStatus(this.live().current?.status));
  protected readonly denials = computed(() => this.live().activity.filter(a => a.kind === 'denied').length);
  protected readonly statusLabel = computed(() => {
    const cur = this.live().current;
    if (!cur) return 'Ociosa';
    if (cur.status === 'RUNNING' && cur.kind === 'EXECUTION') return 'Executando';
    if (cur.status === 'RUNNING') return cur.streaming ? 'Respondendo' : 'Analisando';
    return null;
  });

  @HostListener('document:click')
  protected closeMenu(): void {
    if (this.menuOpen()) this.menuOpen.set(false);
  }

  focusComposer(): void {
    this.composer()?.focus();
  }

  protected onDragStart(event: DragEvent): void {
    event.dataTransfer?.setData('text/x-squad-pane', this.sessionId());
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
    this.dragStart.emit(this.sessionId());
  }

  protected async send(message: ComposerSend): Promise<void> {
    try {
      await this.sessions.send(this.sessionId(), message.content, message.mode);
      this.composer()?.reset();
      this.tab.set('chat');
    } catch (e) {
      this.toasts.error(e, 'Mensagem não enviada');
    }
  }

  protected async cancelRun(): Promise<void> {
    const runId = this.live().current?.runId;
    if (!runId) return;
    try {
      await this.sessions.cancel(runId);
    } catch (e) {
      this.toasts.error(e, 'Não foi possível cancelar');
    }
  }

  protected async resetContext(): Promise<void> {
    const ok = await this.ui.confirm({
      title: 'Recomeçar o contexto?',
      body: 'A próxima mensagem inicia uma conversa nova no Claude Code, sem retomar a anterior. O histórico continua visível aqui.',
      confirm: 'Recomeçar',
    });
    if (ok) await this.actions.attempt(() => this.sessions.resetContext(this.sessionId()), 'A próxima mensagem começa um contexto novo.');
  }

  protected async allowRule(rule: string): Promise<void> {
    const agentId = this.session()?.agentId;
    if (!agentId) return;
    const agent = this.agent();
    const ok = await this.ui.confirm({
      title: 'Liberar ferramenta?',
      body: `A regra “${rule}” passa a ser permitida sem confirmação para ${agent?.name ?? 'este agente'} nas próximas execuções `
        + '(em qualquer projeto). Você pode remover a regra no Estúdio do Agente.',
      confirm: 'Liberar regra',
    });
    if (!ok) return;
    try {
      this.catalog.upsertAgent(await this.api.post<Agent>(`agents/${agentId}/allowed-tools`, { rule }));
      this.grants.update(list => [...new Set([...list, rule])]);
      this.toasts.show('Regra liberada. O último pedido foi colocado na caixa de mensagem: revise e envie para tentar de novo.', 'ok');
      this.tab.set('chat');
      this.reuseLastPrompt();
    } catch (e) {
      this.toasts.error(e);
    }
  }

  protected onOutcomeAction(action: 'settings' | 'project' | 'activity' | 'retry'): void {
    if (action === 'settings') this.nav.go('configuracoes');
    else if (action === 'project') this.ui.openDrawer({ kind: 'project', projectId: this.projectId() });
    else if (action === 'activity') this.tab.set('activity');
    else this.reuseLastPrompt();
  }

  /**
   * Coloca o último pedido de volta na caixa (sem enviar). Se a pessoa liberou regras desde então, o texto avisa
   * o agente: depois de uma negação o Claude Code orienta o modelo a não tentar de novo, então ele precisa saber.
   */
  private reuseLastPrompt(): void {
    const runs = this.live().runs;
    const last = [...runs].reverse().find(r => r.prompt);
    if (!last) return;
    const grants = this.grants();
    const prefix = grants.length
      ? `Liberei ${grants.length === 1 ? 'a regra' : 'as regras'} ${grants.join(', ')} para você; ${grants.length === 1 ? 'ela vale' : 'elas valem'} a partir desta mensagem. `
      : '';
    this.grants.set([]);
    this.composer()?.fill(prefix + last.prompt.replace(/^Liberei a(s)? regra(s)? .*? a partir desta mensagem\. /, ''), last.kind);
  }

}
