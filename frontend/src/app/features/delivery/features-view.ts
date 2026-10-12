import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';

import { FEATURE_STATUS, PRIORITY_LABEL } from '../../core/format';
import type { Agent, FeatureOverview, Feature, FeatureStatus } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { Avatar } from '../../shell/bits';
import { Icon } from '../../shell/icon';
import { CatalogStore } from '../../state/catalog.store';
import { ProjectWorkStore } from '../../state/project-work.store';
import { UiStore } from '../../state/ui.store';
import { FeatureDetailPanel } from './feature-detail';

interface Column {
  key: string;
  label: string;
  statuses: FeatureStatus[];
}

const COLUMNS: Column[] = [
  { key: 'planned', label: 'Planejadas', statuses: ['PLANNED'] },
  { key: 'queued', label: 'Na fila', statuses: ['QUEUED'] },
  { key: 'running', label: 'Em execução', statuses: ['IN_PROGRESS'] },
  { key: 'review', label: 'Revisão', statuses: ['IN_REVIEW'] },
  { key: 'done', label: 'Aprovadas', statuses: ['APPROVED'] },
  { key: 'stopped', label: 'Falha ou cancelada', statuses: ['FAILED', 'CANCELLED'] },
];

/** Features do projeto em lista ou quadro, com a fila de execução e o detalhe da entrega ao lado. */
@Component({
  selector: 'sd-features-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, Avatar, FeatureDetailPanel],
  styles: `
    :host { display: flex; flex: 1 1 auto; min-width: 0; min-height: 0; overflow: hidden; }
    .main { display: flex; flex-direction: column; flex: 1 1 auto; min-width: 0; min-height: 0; overflow: hidden; }
    .bar { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-bottom: 1px solid var(--line-1); flex-wrap: wrap; }
    .stats { display: flex; gap: 10px; font-size: var(--fs-xs); color: var(--fg-3); }
    .content { flex: 1 1 auto; overflow: auto; padding: 12px; }
    .board { display: grid; grid-template-columns: repeat(6, minmax(190px, 1fr)); gap: 10px; min-height: 100%; align-items: start; }
    .colm { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
    .colm h3 { display: flex; align-items: center; gap: 6px; margin: 0 2px 2px; font-size: var(--fs-xs); text-transform: uppercase; letter-spacing: .04em; color: var(--fg-3); font-weight: 600; }
    .card-f { display: grid; gap: 6px; padding: 9px 10px; border: 1px solid var(--line-2); border-radius: var(--r-md); background: var(--bg-2); text-align: left; cursor: pointer; color: inherit; font: inherit; width: 100%; }
    .card-f:hover { border-color: var(--line-3); }
    .card-f.sel { border-color: var(--signal); box-shadow: 0 0 0 1px var(--signal); }
    .card-f .t { font-weight: 500; line-height: 1.35; }
    .code { font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--fg-3); }
    .meta { display: flex; align-items: center; gap: 6px; font-size: var(--fs-xs); color: var(--fg-3); flex-wrap: wrap; }
    .note { font-size: var(--fs-xs); color: var(--fg-3); line-height: 1.4; }
    .blocked { color: var(--warn); }
    .prio-HIGH { color: var(--danger); }
    table { width: 100%; border-collapse: collapse; }
    th { text-align: left; font-size: var(--fs-xs); color: var(--fg-3); font-weight: 500; padding: 6px 8px; border-bottom: 1px solid var(--line-2); position: sticky; top: -12px; background: var(--bg-1); }
    td { padding: 7px 8px; border-bottom: 1px solid var(--line-1); vertical-align: top; }
    tr.sprint td { padding-top: 14px; font-weight: 600; color: var(--fg-2); border-bottom-color: var(--line-2); }
    tbody tr.row-f { cursor: pointer; }
    tbody tr.row-f:hover td { background: var(--bg-2); }
    tbody tr.row-f.sel td { background: var(--signal-dim); }
    .detail { width: min(520px, 46vw); flex: none; border-left: 1px solid var(--line-1); background: var(--bg-2); display: flex; min-height: 0; }
  `,
  template: `
    <div class="main">
      <div class="bar">
        <div class="seg" role="radiogroup" aria-label="Visualização">
          <button role="radio" [attr.aria-checked]="mode() === 'board'" [attr.aria-selected]="mode() === 'board'" (click)="mode.set('board')"><sd-icon name="board" [size]="12" /> Quadro</button>
          <button role="radio" [attr.aria-checked]="mode() === 'list'" [attr.aria-selected]="mode() === 'list'" (click)="mode.set('list')"><sd-icon name="list" [size]="12" /> Lista</button>
        </div>
        <div class="stats">
          <span>{{ features().length }} features</span>
          <span>{{ count('IN_REVIEW') }} em revisão</span>
          <span>{{ count('APPROVED') }} aprovadas</span>
        </div>
        <span class="spacer"></span>
        @if (overview(); as o) {
          @if (o.queuePaused) {
            <button class="btn sm primary" (click)="setPaused(false)"><sd-icon name="bolt" [size]="12" /> Retomar fila</button>
          } @else {
            <button class="btn sm" (click)="setPaused(true)" title="Nenhuma feature nova começa; as que estão rodando continuam"><sd-icon name="pause" [size]="12" /> Pausar fila</button>
          }
          @if (count('PLANNED')) {
            <button class="btn sm warn" (click)="authorizeAll()" title="Coloca na fila todas as features planejadas com responsável">
              <sd-icon name="bolt" [size]="12" /> Autorizar {{ count('PLANNED') }} planejadas
            </button>
          }
        }
        <button class="btn sm" (click)="newFeature()"><sd-icon name="plus" [size]="12" /> Feature</button>
        <button class="btn sm ghost" (click)="newSprint()"><sd-icon name="plus" [size]="12" /> Sprint</button>
      </div>
      @if (overview()?.queuePaused) {
        <div class="banner warn" style="margin: 10px 12px 0"><sd-icon name="pause" [size]="13" /> {{ overview()?.pauseReason || 'Fila pausada.' }}</div>
      }
      <div class="content">
        @if (!overview()) {
          <div class="empty"><sd-icon name="spinner" class="spin" /> Carregando…</div>
        } @else if (!features().length) {
          <div class="empty">
            <h3>Nenhuma feature ainda</h3>
            <p>Peça ao comandante um plano na sala de planejamento e aprove-o, ou crie features manualmente.
              Nada é executado até você autorizar.</p>
          </div>
        } @else if (mode() === 'board') {
          <div class="board">
            @for (c of columns; track c.key) {
              <section class="colm" [attr.aria-label]="c.label">
                <h3>{{ c.label }} <span>{{ inColumn(c).length }}</span></h3>
                @for (f of inColumn(c); track f.id) {
                  <button class="card-f" [class.sel]="selected() === f.id" (click)="select(f)">
                    <div class="meta"><span class="code">{{ f.code }}</span>
                      <span class="status" [attr.data-status]="info(f).tone"><sd-icon [name]="info(f).icon" [size]="11" [class.spin]="info(f).icon === 'spinner'" /> {{ info(f).label }}</span>
                    </div>
                    <div class="t">{{ f.title }}</div>
                    <div class="meta">
                      @if (owner(f); as a) { <sd-avatar [agent]="a" [size]="16" /> {{ a.name }} } @else { <span class="blocked">sem responsável</span> }
                      <span [class]="'prio-' + f.priority">· {{ priority(f) }}</span>
                      @if (f.revision) { <span>· rev. {{ f.revision }}</span> }
                    </div>
                    @if (blocked(f); as b) { <div class="note blocked"><sd-icon name="lock" [size]="11" /> Depende de {{ b }}</div> }
                    @else if (f.statusNote && f.status !== 'PLANNED' && f.status !== 'APPROVED') { <div class="note">{{ f.statusNote }}</div> }
                  </button>
                }
              </section>
            }
          </div>
        } @else {
          <table>
            <thead><tr><th style="width: 70px">Código</th><th>Feature</th><th>Responsável</th><th>Prioridade</th><th>Estado</th><th>Dependências</th></tr></thead>
            <tbody>
              @for (group of grouped(); track group.key) {
                <tr class="sprint"><td colspan="6">{{ group.label }}</td></tr>
                @for (f of group.features; track f.id) {
                  <tr class="row-f" [class.sel]="selected() === f.id" (click)="select(f)" tabindex="0" (keydown.enter)="select(f)">
                    <td class="code">{{ f.code }}</td>
                    <td>{{ f.title }} @if (f.statusNote && f.status !== 'PLANNED' && f.status !== 'APPROVED') { <div class="note">{{ f.statusNote }}</div> }</td>
                    <td>@if (owner(f); as a) { <span class="row"><sd-avatar [agent]="a" [size]="16" /> {{ a.name }}</span> } @else { <span class="blocked">—</span> }</td>
                    <td [class]="'prio-' + f.priority">{{ priority(f) }}</td>
                    <td><span class="status" [attr.data-status]="info(f).tone"><sd-icon [name]="info(f).icon" [size]="11" [class.spin]="info(f).icon === 'spinner'" /> {{ info(f).label }}</span></td>
                    <td class="code">{{ depCodes(f) || '—' }}</td>
                  </tr>
                }
              }
            </tbody>
          </table>
        }
      </div>
    </div>
    @if (selected() || creating()) {
      <aside class="detail" aria-label="Detalhe da feature">
        <sd-feature-detail [projectId]="projectId()" [featureId]="selected()" (closed)="closeDetail()" (created)="selected.set($event)" />
      </aside>
    }
  `,
})
export class FeaturesView {
  readonly projectId = input.required<string>();

  private readonly work = inject(ProjectWorkStore);
  private readonly catalog = inject(CatalogStore);
  private readonly toasts = inject(Toasts);
  private readonly ui = inject(UiStore);

  protected readonly columns = COLUMNS;
  protected readonly mode = signal<'board' | 'list'>('board');
  protected readonly selected = signal<string | null>(null);
  protected readonly creating = signal(false);

  protected readonly overview = computed<FeatureOverview | undefined>(() => this.work.overviews()[this.projectId()]);
  protected readonly features = computed(() => this.overview()?.features ?? []);
  private readonly byId = computed(() => new Map(this.features().map(f => [f.id, f])));
  protected readonly grouped = computed(() => {
    const o = this.overview();
    if (!o) return [];
    const groups = o.sprints.map(s => ({ key: s.id, label: s.name + (s.goal ? ' — ' + s.goal : ''), features: [] as Feature[] }));
    const none = { key: 'none', label: 'Sem sprint', features: [] as Feature[] };
    for (const f of o.features) (groups.find(g => g.key === f.sprintId) ?? none).features.push(f);
    return [...groups, none].filter(g => g.features.length);
  });

  constructor() {
    effect(() => {
      const id = this.projectId();
      this.selected.set(null);
      void this.work.load(id, 'features').catch(error => this.toasts.error(error, 'Não foi possível carregar as features'));
      void this.work.load(id, 'planning').catch(() => undefined);
    });
  }

  protected count(status: FeatureStatus): number {
    return this.features().filter(f => f.status === status).length;
  }

  protected inColumn(c: Column): Feature[] {
    return this.features().filter(f => c.statuses.includes(f.status));
  }

  protected info(f: Feature) {
    return FEATURE_STATUS[f.status];
  }

  protected priority(f: Feature): string {
    return PRIORITY_LABEL[f.priority];
  }

  protected owner(f: Feature): Agent | undefined {
    return f.ownerAgentId ? this.catalog.agentById().get(f.ownerAgentId) : undefined;
  }

  protected blocked(f: Feature): string | null {
    if (f.status === 'APPROVED' || f.status === 'IN_PROGRESS' || f.status === 'IN_REVIEW') return null;
    const b = this.overview()?.blockedBy[f.id];
    return b?.length ? b.join(', ') : null;
  }

  protected depCodes(f: Feature): string {
    return f.dependsOn.map(id => this.byId().get(id)?.code ?? '?').join(', ');
  }

  protected select(f: Feature): void {
    this.creating.set(false);
    this.selected.set(this.selected() === f.id ? null : f.id);
  }

  protected newFeature(): void {
    this.selected.set(null);
    this.creating.set(true);
  }

  protected closeDetail(): void {
    this.selected.set(null);
    this.creating.set(false);
  }

  protected async setPaused(paused: boolean): Promise<void> {
    try {
      await this.work.setPaused(this.projectId(), paused);
    } catch (error) {
      this.toasts.error(error);
    }
  }

  protected async authorizeAll(): Promise<void> {
    const n = this.count('PLANNED');
    const ok = await this.ui.confirm({
      title: 'Autorizar execução',
      body: `${n} ${n === 1 ? 'feature planejada entra' : 'features planejadas entram'} na fila. Cada uma começa quando as dependências forem aprovadas; `
        + 'os agentes vão alterar arquivos na pasta do projeto com as ferramentas liberadas para eles.',
      confirm: 'Autorizar',
    });
    if (!ok) return;
    try {
      const count = await this.work.authorizeAll(this.projectId());
      this.toasts.show(`${count} ${count === 1 ? 'feature autorizada' : 'features autorizadas'}.`, 'ok');
    } catch (error) {
      this.toasts.error(error);
    }
  }

  protected async newSprint(): Promise<void> {
    const name = await this.ui.prompt({ title: 'Nova sprint', label: 'Nome', value: `Sprint ${(this.overview()?.sprints.length ?? 0) + 1}`, confirm: 'Criar' });
    if (!name?.trim()) return;
    try {
      await this.work.createSprint(this.projectId(), name.trim());
    } catch (error) {
      this.toasts.error(error);
    }
  }
}
