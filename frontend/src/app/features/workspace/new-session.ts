import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';

import { Api } from '../../core/api';
import { ROLE_LABEL } from '../../core/format';
import type { Agent, Starter } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { Avatar } from '../../shell/bits';
import { Icon } from '../../shell/icon';
import { Nav } from '../../shell/nav';
import { CatalogStore } from '../../state/catalog.store';
import { SessionsStore } from '../../state/sessions.store';
import { UiStore } from '../../state/ui.store';

/** Abre uma sessão (painel) com um agente no projeto. Os agentes da squad do projeto aparecem primeiro. */
@Component({
  selector: 'sd-new-session',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, Avatar],
  styles: `
    .list { display: flex; flex-direction: column; gap: 2px; margin-bottom: 16px; }
    .agent { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border: 1px solid transparent; border-radius: var(--r-md);
      background: transparent; color: var(--fg-1); text-align: left; cursor: pointer; }
    .agent:hover { background: var(--bg-3); }
    .agent[aria-checked='true'] { border-color: var(--signal); background: var(--signal-dim); }
    .meta { color: var(--fg-3); font-size: var(--fs-xs); }
    .open { color: var(--fg-3); font-size: var(--fs-xs); }
  `,
  template: `
    <div class="drawer narrow" role="dialog" aria-modal="true" aria-labelledby="ns-title" (keydown.escape)="ui.closeDrawer()">
      <header>
        <sd-icon name="chat" />
        <h2 id="ns-title" class="grow">Nova sessão em {{ project()?.name }}</h2>
        <button class="btn ghost icon" (click)="ui.closeDrawer()" aria-label="Fechar"><sd-icon name="x" /></button>
      </header>
      <div class="body">
        @if (catalog.agents().length === 0) {
          <div class="empty">
            <h3>Nenhum agente cadastrado</h3>
            <p>Crie os agentes iniciais (comandante, requisitos, arquitetura e operadores para Angular, Java/Spring, banco de dados e QA) ou cadastre um no Estúdio do Agente.</p>
            <div class="row">
              <button class="btn primary" (click)="createStarters()"><sd-icon name="sparkle" [size]="14" /> Criar agentes iniciais</button>
              <button class="btn" (click)="ui.openDrawer({ kind: 'agent', agentId: null })">Abrir o Estúdio</button>
            </div>
          </div>
        } @else {
          @for (section of sections(); track section.title) {
            <div class="section-title" style="margin: 4px 0 6px">{{ section.title }}</div>
            <div class="list" role="radiogroup" [attr.aria-label]="section.title">
              @for (agent of section.agents; track agent.id) {
                <button class="agent" role="radio" [attr.aria-checked]="selected() === agent.id" (click)="picked.set(agent.id)" (dblclick)="create(agent.id)">
                  <sd-avatar [agent]="agent" [size]="28" />
                  <span class="grow">
                    <span>{{ agent.name }}</span><br />
                    <span class="meta">{{ roleLabel[agent.role] }}{{ agent.specialty ? ' · ' + agent.specialty : '' }}</span>
                  </span>
                  @if (openCount(agent.id); as n) { <span class="open">{{ n }} {{ n === 1 ? 'sessão' : 'sessões' }}</span> }
                </button>
              }
            </div>
          }
          <div class="field">
            <label for="ns-title-input">Título (opcional)</label>
            <input id="ns-title-input" class="input" maxlength="120" placeholder="Ex.: Revisar o cadastro de clientes" [value]="title()" (input)="title.set($any($event.target).value)"
              (keydown.enter)="create(selected())" />
          </div>
        }
      </div>
      <footer>
        <span class="spacer"></span>
        <button class="btn" (click)="ui.closeDrawer()">Cancelar</button>
        <button class="btn primary" [disabled]="!selected() || busy()" (click)="create(selected())">Abrir sessão</button>
      </footer>
    </div>
  `,
})
export class NewSessionDrawer {
  readonly projectId = input.required<string>();

  protected readonly ui = inject(UiStore);
  protected readonly catalog = inject(CatalogStore);
  private readonly sessions = inject(SessionsStore);
  private readonly nav = inject(Nav);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);

  protected readonly roleLabel = ROLE_LABEL;
  protected readonly picked = signal<string | null>(null);
  protected readonly selected = computed(() => this.picked() ?? this.sections()[0]?.agents[0]?.id ?? null);
  protected readonly title = signal('');
  protected readonly busy = signal(false);
  protected readonly project = computed(() => this.catalog.project(this.projectId()));

  protected readonly sections = computed(() => {
    const squad = this.catalog.squadById().get(this.project()?.squadId ?? '');
    const members = new Set(squad ? [squad.commanderId, squad.requirementsId, squad.architectureId, ...squad.operatorIds] : []);
    const order = { COMMANDER: 0, REQUIREMENTS: 1, ARCHITECTURE: 2, OPERATOR: 3 } as const;
    const sort = (a: Agent, b: Agent) => order[a.role] - order[b.role] || a.name.localeCompare(b.name);
    const agents = this.catalog.agents();
    const inSquad = agents.filter(a => members.has(a.id)).sort(sort);
    const others = agents.filter(a => !members.has(a.id)).sort(sort);
    const out: { title: string; agents: Agent[] }[] = [];
    if (inSquad.length) out.push({ title: squad!.name, agents: inSquad });
    if (others.length) out.push({ title: inSquad.length ? 'Outros agentes' : 'Agentes', agents: others });
    return out;
  });

  protected openCount(agentId: string): number {
    return this.sessions.sessions(this.projectId()).filter(s => s.agentId === agentId).length;
  }

  protected async create(agentId: string | null): Promise<void> {
    if (!agentId || this.busy()) return;
    this.busy.set(true);
    try {
      const session = await this.sessions.create(this.projectId(), agentId, this.title().trim() || undefined);
      this.ui.closeDrawer();
      await this.nav.openProject(this.projectId(), session.id);
    } catch (e) {
      this.toasts.error(e);
    } finally {
      this.busy.set(false);
    }
  }

  protected async createStarters(): Promise<void> {
    try {
      const starter = await this.api.post<Starter>('system/starter-agents');
      await Promise.all([this.catalog.reloadAgents(), this.catalog.loadAll()]);
      this.toasts.show(starter.created ? 'Agentes e squad iniciais criados.' : 'Os agentes iniciais já existiam.', 'ok');
    } catch (e) {
      this.toasts.error(e);
    }
  }
}
