import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Api } from '../../core/api';
import { baseName, relativeTime } from '../../core/format';
import type { ProjectView, Starter } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { Avatar, StatusBadge } from '../../shell/bits';
import { Icon } from '../../shell/icon';
import { Nav } from '../../shell/nav';
import { ProjectActions } from '../../shell/project-actions';
import { CatalogStore } from '../../state/catalog.store';
import { SessionsStore } from '../../state/sessions.store';
import { UiStore } from '../../state/ui.store';

@Component({
  selector: 'sd-home-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, Avatar, StatusBadge],
  styles: `
    :host { display: block; height: 100%; overflow: auto; }
    .wrap { max-width: 1120px; margin: 0 auto; padding: 28px 28px 40px; }
    .hello { display: flex; align-items: flex-end; gap: 16px; margin-bottom: 22px; }
    .hello h1 { font-size: 22px; }
    .cols { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr); gap: 20px; }
    @media (max-width: 1100px) { .cols { grid-template-columns: 1fr; } }
    section { margin-bottom: 22px; }
    .sec-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
    .projects { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 10px; }
    .project { display: flex; flex-direction: column; gap: 6px; padding: 12px; border: 1px solid var(--line-1); border-left: 3px solid var(--c);
      border-radius: var(--r-md); background: var(--bg-2); color: var(--fg-1); text-align: left; cursor: pointer; }
    .project:hover { border-color: var(--line-3); border-left-color: var(--c); background: var(--bg-3); }
    .project .name { font-weight: 600; }
    .project .path { color: var(--fg-3); font-family: var(--font-mono); font-size: var(--fs-xs); }
    .project .foot { display: flex; align-items: center; gap: 8px; color: var(--fg-3); font-size: var(--fs-xs); }
    .new { align-items: center; justify-content: center; border-style: dashed; border-left-width: 1px; color: var(--fg-3); min-height: 92px; }
    .setup { padding: 16px; margin-bottom: 22px; }
    .steps { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; margin-top: 12px; }
    .step { display: flex; flex-direction: column; gap: 6px; padding: 12px; border-radius: var(--r-md); background: var(--bg-1); }
    .step h3 { display: flex; align-items: center; gap: 6px; }
    .step p { color: var(--fg-3); font-size: var(--fs-sm); margin: 0; }
    .ok { color: var(--ok); }
    .warn { color: var(--warn); }
    .session-row .meta { color: var(--fg-3); font-size: var(--fs-xs); }
    .stat { display: flex; gap: 16px; padding: 12px; }
    .stat b { display: block; font-size: 20px; }
  `,
  template: `
    <div class="wrap">
      <div class="hello">
        <div class="grow">
          <div class="section-title">Shiro</div>
          <h1>{{ greeting() }}</h1>
        </div>
        <button class="btn" (click)="ui.paletteOpen.set(true)"><sd-icon name="search" [size]="14" /> Abrir… <kbd>Ctrl K</kbd></button>
        <button class="btn primary" (click)="ui.openDrawer({ kind: 'project', projectId: null })"><sd-icon name="plus" [size]="14" /> Novo projeto</button>
      </div>

      @if (needsSetup()) {
        <div class="card setup">
          <h2>Primeiros passos</h2>
          <div class="steps">
            <div class="step">
              <h3>
                <sd-icon [name]="claudeReady() ? 'check' : 'alert'" [size]="14" [class.ok]="claudeReady()" [class.warn]="!claudeReady()" />
                Claude Code
              </h3>
              <p>{{ claudeText() }}</p>
              <div class="row"><button class="btn sm" (click)="nav.go('configuracoes')">Diagnóstico</button></div>
            </div>
            <div class="step">
              <h3><sd-icon [name]="catalog.agents().length ? 'check' : 'agent'" [size]="14" [class.ok]="catalog.agents().length > 0" /> Agentes e squad</h3>
              <p>Comandante, requisitos, arquitetura e operadores para Angular, Java/Spring, banco de dados e QA. Todos editáveis.</p>
              <div class="row">
                @if (catalog.agents().length === 0) {
                  <button class="btn sm primary" [disabled]="busy()" (click)="starters()">Criar agentes iniciais</button>
                } @else {
                  <button class="btn sm" (click)="nav.go('agentes')">Ver {{ catalog.agents().length }} agentes</button>
                }
              </div>
            </div>
            <div class="step">
              <h3><sd-icon name="folder" [size]="14" /> Primeiro projeto</h3>
              <p>Escolha a pasta do código. Para conhecer a interface, há um projeto de exemplo identificado como tal.</p>
              <div class="row">
                <button class="btn sm primary" (click)="ui.openDrawer({ kind: 'project', projectId: null })">Criar projeto</button>
                <button class="btn sm" [disabled]="busy()" (click)="sample()">Carregar exemplo</button>
              </div>
            </div>
            <div class="step">
              <h3><sd-icon name="sparkle" [size]="14" /> Modo demonstração</h3>
              <p>Execuções simuladas e determinísticas, sem Claude Code e sem consumo de IA. Fica sinalizado na barra superior.</p>
              <div class="row">
                <button class="btn sm" [class.warn]="catalog.demoMode()" (click)="toggleDemo()">{{ catalog.demoMode() ? 'Desativar demonstração' : 'Ativar demonstração' }}</button>
              </div>
            </div>
          </div>
        </div>
      }

      <div class="cols">
        <div>
          <section>
            <div class="sec-head"><span class="section-title grow">Projetos recentes</span>
              @if (catalog.projects().length > 6) { <button class="btn ghost sm" (click)="ui.paletteOpen.set(true)">Todos os projetos</button> }
            </div>
            <div class="projects">
              @for (v of recentProjects(); track v.project.id) {
                <button class="project" [style.--c]="v.project.color" (click)="nav.openProject(v.project.id)">
                  <span class="name ellipsis">{{ v.project.name }}</span>
                  <span class="path ellipsis" [attr.title]="v.project.folderPath">
                    @if (v.project.folderPath) { {{ v.folderExists ? '' : '⚠ ' }}{{ folder(v.project.folderPath) }} } @else { sem pasta }
                  </span>
                  <span class="foot">
                    @if (activeByProject()[v.project.id]; as n) {
                      <span style="color: var(--st-running)"><sd-icon name="spinner" class="spin" [size]="11" /> {{ n }} em andamento</span>
                    } @else { <span>{{ v.project.lastOpenedAt ? 'aberto ' + rel(v.project.lastOpenedAt) : 'criado ' + rel(v.project.createdAt) }}</span> }
                    @if (v.project.sample) { <span class="chip">exemplo</span> }
                  </span>
                </button>
              }
              <button class="project new" (click)="ui.openDrawer({ kind: 'project', projectId: null })"><sd-icon name="plus" /> Novo projeto</button>
            </div>
          </section>
        </div>
        <div>
          <section>
            <div class="sec-head"><span class="section-title grow">Sessões recentes</span></div>
            <div class="card" style="padding: 4px">
              @for (s of sessions.recent(); track s.id) {
                <button class="list-row session-row" (click)="nav.openSession(s.projectId, s.id)">
                  <sd-avatar [agent]="catalog.agentById().get(s.agentId)" [size]="24" />
                  <span class="grow">
                    <span class="ellipsis" style="display:block">{{ s.title }}</span>
                    <span class="meta">{{ catalog.project(s.projectId)?.name }} · {{ rel(s.updatedAt) }}</span>
                  </span>
                  @if (sessions.runs()[s.id]; as m) { <sd-status [status]="m.status" [compact]="true" /> }
                </button>
              } @empty {
                <div class="empty" style="padding: 18px">As conversas com agentes aparecem aqui.</div>
              }
            </div>
          </section>
          <section>
            <div class="sec-head"><span class="section-title grow">Execuções</span></div>
            <div class="card stat">
              <div><b>{{ sessions.queue()?.running ?? 0 }}</b><span class="muted">em execução</span></div>
              <div><b>{{ sessions.queue()?.queued ?? 0 }}</b><span class="muted">na fila</span></div>
              <div><b>{{ sessions.queue()?.maxConcurrent ?? '–' }}</b><span class="muted">simultâneas</span></div>
              <span class="spacer"></span>
              <button class="btn sm" (click)="sessions.pauseQueue(!sessions.queue()?.paused)"
                [attr.title]="sessions.queue()?.paused ? 'Volta a iniciar execuções da fila' : 'Impede que novas execuções comecem; as que estão rodando continuam'">
                <sd-icon [name]="sessions.queue()?.paused ? 'send' : 'pause'" [size]="12" /> {{ sessions.queue()?.paused ? 'Retomar fila' : 'Pausar fila' }}
              </button>
            </div>
          </section>
        </div>
      </div>
    </div>
  `,
})
export class HomePage {
  protected readonly nav = inject(Nav);
  protected readonly ui = inject(UiStore);
  protected readonly catalog = inject(CatalogStore);
  protected readonly sessions = inject(SessionsStore);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly actions = inject(ProjectActions);

  protected readonly busy = signal(false);
  protected readonly rel = (iso: string | null) => relativeTime(iso);
  protected readonly folder = baseName;
  protected readonly activeByProject = this.sessions.activeByProject;

  protected readonly greeting = computed(() => {
    const h = new Date().getHours();
    return h < 12 ? 'Bom dia' : h < 18 ? 'Boa tarde' : 'Boa noite';
  });

  protected readonly recentProjects = computed<ProjectView[]>(() => {
    const key = (v: ProjectView) => v.project.lastOpenedAt ?? v.project.createdAt;
    return [...this.catalog.projects()].sort((a, b) => key(b).localeCompare(key(a))).slice(0, 8);
  });

  protected readonly claudeReady = computed(() => {
    const c = this.catalog.claude();
    return !!c?.usable && c.authenticated !== false;
  });

  protected readonly needsSetup = computed(() => this.catalog.projects().length === 0 || this.catalog.agents().length === 0 || !this.claudeReady());

  protected readonly claudeText = computed(() => {
    const c = this.catalog.claude();
    if (c?.problemCode === 'checking') return 'Verificando o executável, a versão e o login…';
    if (!c || !c.found) return 'Não encontrado. Instale o Claude Code ou informe o caminho em Configurações. Até lá, use o modo demonstração.';
    if (!c.usable) return c.problem ?? 'Encontrado, mas não pôde ser iniciado.';
    if (c.authenticated === false) return `Versão ${c.version ?? '?'} encontrada, sem login. Rode “claude” num terminal para entrar na conta.`;
    if (c.authenticated == null) return `Versão ${c.version ?? '?'} encontrada. Não foi possível confirmar o login; use “Testar conexão”.`;
    return `Versão ${c.version ?? '?'} pronta, com login (${c.authMethod ?? 'conta configurada'}).`;
  });

  protected async starters(): Promise<void> {
    this.busy.set(true);
    try {
      const starter = await this.api.post<Starter>('system/starter-agents');
      await this.catalog.loadAll();
      this.toasts.show(starter.created ? `${starter.agents.length} agentes e a squad padrão foram criados.` : 'Os agentes já existiam.', 'ok');
    } catch (e) {
      this.toasts.error(e);
    } finally {
      this.busy.set(false);
    }
  }

  protected async sample(): Promise<void> {
    this.busy.set(true);
    try {
      const view = await this.api.post<ProjectView>('system/sample-project');
      await this.catalog.loadAll();
      await this.nav.openProject(view.project.id);
      this.toasts.show('Projeto de exemplo carregado. Ele está marcado como “exemplo” e pode ser excluído quando quiser.', 'ok');
    } catch (e) {
      this.toasts.error(e);
    } finally {
      this.busy.set(false);
    }
  }

  protected async toggleDemo(): Promise<void> {
    const settings = this.catalog.settings();
    if (!settings) return;
    await this.actions.attempt(() => this.catalog.saveSettings({ ...settings, demoMode: !settings.demoMode }),
      settings.demoMode ? 'Modo demonstração desativado.' : 'Modo demonstração ativado: novas execuções serão simuladas.');
  }
}
