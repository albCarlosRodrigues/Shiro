import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';

import { Api, ApiError } from '../../core/api';
import { FEATURE_STATUS, PRIORITY_LABEL, dateTime } from '../../core/format';
import { MarkdownView } from '../../core/markdown';
import type { Agent, FeatureOverview, ChangesView, Feature, FeatureDetail, FeatureDraft, FeatureStep, Handoff } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { Avatar } from '../../shell/bits';
import { Icon } from '../../shell/icon';
import { CatalogStore } from '../../state/catalog.store';
import { ProjectWorkStore } from '../../state/project-work.store';
import { SessionsStore } from '../../state/sessions.store';
import { UiStore } from '../../state/ui.store';
import { WorkspaceStore } from '../../state/workspace.store';

type Tab = 'summary' | 'delivery' | 'history';

interface FormState {
  code: string;
  title: string;
  description: string;
  priority: string;
  specialty: string;
  ownerAgentId: string;
  sprintId: string;
  criteria: string;
  tasks: string;
  dependsOn: string[];
}

const HANDOFF_LISTS: { key: keyof Handoff; label: string }[] = [
  { key: 'entregas', label: 'Entregas' },
  { key: 'arquivos', label: 'Arquivos' },
  { key: 'testes', label: 'Testes e verificações' },
  { key: 'criterios_validacao', label: 'Como validar' },
  { key: 'pendencias', label: 'Pendências' },
];

/** Detalhe da feature: plano, execução etapa por etapa (passagens), alterações nos arquivos e revisão humana. */
@Component({
  selector: 'sd-feature-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, Avatar, MarkdownView],
  styles: `
    :host { display: flex; flex-direction: column; flex: 1 1 auto; min-width: 0; min-height: 0; }
    .head { display: flex; align-items: center; gap: 8px; padding: 10px 10px 8px 14px; border-bottom: 1px solid var(--line-1); }
    .code { font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--fg-3); }
    .title { font-weight: 600; font-size: var(--fs-lg); line-height: 1.3; }
    .tabs { padding: 6px 12px; border-bottom: 1px solid var(--line-1); }
    .body { flex: 1 1 auto; overflow: auto; padding: 12px 14px; display: flex; flex-direction: column; gap: 14px; }
    .actions { display: flex; gap: 6px; flex-wrap: wrap; }
    .check-row { display: flex; gap: 8px; align-items: flex-start; padding: 3px 0; }
    .check-row input { margin-top: 3px; accent-color: var(--signal); }
    .done { color: var(--fg-3); text-decoration: line-through; }
    .kv { display: grid; grid-template-columns: 110px 1fr; gap: 4px 10px; font-size: var(--fs-sm); }
    .kv dt { color: var(--fg-3); }
    .kv dd { margin: 0; min-width: 0; }
    .step { border: 1px solid var(--line-2); border-radius: var(--r-md); background: var(--bg-1); }
    .step-head { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-bottom: 1px solid var(--line-1); font-size: var(--fs-sm); }
    .step-body { padding: 8px 10px; display: grid; gap: 8px; font-size: var(--fs-sm); }
    .step-body h4 { margin: 0 0 2px; font-size: var(--fs-xs); color: var(--fg-3); font-weight: 600; text-transform: uppercase; letter-spacing: .04em; }
    .step-body ul { margin: 0; padding-left: 18px; }
    .files { display: flex; flex-direction: column; }
    .file { display: flex; align-items: center; gap: 8px; padding: 5px 6px; border: 0; background: transparent; color: inherit; font: inherit; text-align: left; border-radius: var(--r-sm); cursor: pointer; }
    .file:hover, .file.sel { background: var(--bg-3); }
    .file .p { font-family: var(--font-mono); font-size: var(--fs-xs); }
    .add { color: var(--ok); font-family: var(--font-mono); font-size: var(--fs-xs); }
    .del { color: var(--danger); font-family: var(--font-mono); font-size: var(--fs-xs); }
    pre.diff { margin: 0; padding: 8px; max-height: 360px; overflow: auto; font-size: var(--fs-xs); background: var(--bg-0); border-radius: var(--r-sm); }
    pre.diff .a { color: var(--ok); } pre.diff .d { color: var(--danger); } pre.diff .h { color: var(--signal); }
    .review { border: 1px solid rgba(233, 180, 76, 0.4); border-radius: var(--r-md); padding: 10px; display: grid; gap: 8px; background: var(--warn-dim); }
    .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    label.field > span { font-size: var(--fs-xs); color: var(--fg-3); }
    .deps { display: flex; flex-wrap: wrap; gap: 8px; }
  `,
  template: `
    <div class="head">
      @if (feature(); as f) {
        <div class="grow">
          <div class="row"><span class="code">{{ f.code }}</span>
            <span class="status" [attr.data-status]="info().tone"><sd-icon [name]="info().icon" [size]="11" [class.spin]="info().icon === 'spinner'" /> {{ info().label }}</span>
            @if (f.revision) { <span class="chip">revisão {{ f.revision }}</span> }
            @if (f.branch) { <span class="chip" title="Worktree isolado">{{ f.branch }}</span> }
          </div>
          <div class="title">{{ f.title }}</div>
        </div>
      } @else {
        <div class="grow title">Nova feature</div>
      }
      <button class="btn ghost icon sm" (click)="closed.emit()" aria-label="Fechar detalhe"><sd-icon name="x" [size]="13" /></button>
    </div>

    @if (editing()) {
      <div class="body">
        <div class="grid2">
          <label class="field"><span>Código</span><input class="input" [value]="form().code" (input)="set('code', $event)" placeholder="automático" /></label>
          <label class="field"><span>Prioridade</span>
            <select class="select" (change)="set('priority', $event)">
              @for (p of priorities; track p[0]) { <option [value]="p[0]" [selected]="form().priority === p[0]">{{ p[1] }}</option> }
            </select></label>
        </div>
        <label class="field"><span>Título</span><input class="input" [value]="form().title" (input)="set('title', $event)" /></label>
        <label class="field"><span>Descrição</span><textarea class="textarea" rows="4" [value]="form().description" (input)="set('description', $event)"></textarea></label>
        <div class="grid2">
          <label class="field"><span>Responsável</span>
            <select class="select" (change)="set('ownerAgentId', $event)">
              <option value="" [selected]="!form().ownerAgentId">—</option>
              @for (a of owners(); track a.id) { <option [value]="a.id" [selected]="form().ownerAgentId === a.id">{{ a.name }}</option> }
            </select></label>
          <label class="field"><span>Sprint</span>
            <select class="select" (change)="set('sprintId', $event)">
              <option value="" [selected]="!form().sprintId">Sem sprint</option>
              @for (s of sprints(); track s.id) { <option [value]="s.id" [selected]="form().sprintId === s.id">{{ s.name }}</option> }
            </select></label>
        </div>
        <label class="field"><span>Especialidade</span><input class="input" [value]="form().specialty" (input)="set('specialty', $event)" /></label>
        <label class="field"><span>Critérios de aceite (um por linha)</span><textarea class="textarea" rows="4" [value]="form().criteria" (input)="set('criteria', $event)"></textarea></label>
        <label class="field"><span>Tarefas (uma por linha)</span><textarea class="textarea" rows="3" [value]="form().tasks" (input)="set('tasks', $event)"></textarea></label>
        <div class="field"><span style="font-size: var(--fs-xs); color: var(--fg-3)">Depende de</span>
          <div class="deps">
            @for (o of others(); track o.id) {
              <label class="check"><input type="checkbox" [checked]="form().dependsOn.includes(o.id)" (change)="toggleDep(o.id)" /> {{ o.code }}</label>
            } @empty { <span class="muted">Nenhuma outra feature.</span> }
          </div>
        </div>
        <div class="actions">
          <button class="btn primary sm" [disabled]="busy() || !form().title.trim()" (click)="saveForm()">Salvar</button>
          <button class="btn sm ghost" (click)="cancelEdit()">Cancelar</button>
        </div>
      </div>
    } @else if (feature(); as f) {
      @if (f.statusNote && f.status !== 'PLANNED') {
        <div class="banner" [class.warn]="f.status === 'FAILED' || f.status === 'IN_REVIEW'" [class.info]="f.status !== 'FAILED' && f.status !== 'IN_REVIEW'"
          style="margin: 10px 14px 0">{{ f.statusNote }}</div>
      }
      <div class="actions" style="padding: 10px 14px 0">
        @switch (f.status) {
          @case ('IN_PROGRESS') {
            <button class="btn sm danger" (click)="cancel()"><sd-icon name="stop" [size]="12" /> Cancelar execução</button>
            @if (runningStep(); as s) { <button class="btn sm" (click)="openSession(s)"><sd-icon name="panel" [size]="12" /> Acompanhar etapa</button> }
          }
          @case ('QUEUED') { <button class="btn sm" (click)="cancel()"><sd-icon name="x" [size]="12" /> Tirar da fila</button> }
          @case ('IN_REVIEW') { }
          @case ('APPROVED') { }
          @default {
            <button class="btn sm warn" (click)="authorize()" [disabled]="busy()"><sd-icon name="bolt" [size]="12" /> {{ f.status === 'PLANNED' ? 'Autorizar execução' : 'Autorizar de novo' }}</button>
            <button class="btn sm" (click)="startEdit()"><sd-icon name="edit" [size]="12" /> Editar</button>
            <button class="btn sm ghost danger" (click)="remove()"><sd-icon name="trash" [size]="12" /> Excluir</button>
          }
        }
      </div>
      @if (f.status === 'IN_REVIEW') {
        <div class="review" style="margin: 10px 14px 0" role="region" aria-label="Revisão da entrega">
          <b>Revise a entrega</b>
          <span class="muted" style="font-size: var(--fs-sm)">Confira o resumo, os arquivos alterados e os testes na aba Entrega. Só a sua aprovação conclui a feature e libera as que dependem dela.</span>
          <textarea class="textarea" rows="3" aria-label="Comentário da revisão" placeholder="Comentário (obrigatório para pedir ajustes)" [value]="comment()" (input)="comment.set(val($event))"></textarea>
          <div class="actions">
            <button class="btn sm primary" [disabled]="busy()" (click)="approve()"><sd-icon name="check" [size]="12" /> Aprovar entrega</button>
            <button class="btn sm" [disabled]="busy() || !comment().trim()" (click)="requestChanges()"><sd-icon name="refresh" [size]="12" /> Pedir ajustes</button>
          </div>
          @if (conflicts().length) {
            <div class="banner danger">Conflitos ao integrar: @for (c of conflicts(); track c) { <code>{{ c }}</code> } </div>
          }
        </div>
      }
      <div class="tabs">
        <div class="seg" role="tablist">
          <button role="tab" [attr.aria-selected]="tab() === 'summary'" (click)="tab.set('summary')">Resumo</button>
          <button role="tab" [attr.aria-selected]="tab() === 'delivery'" (click)="openDelivery()">Entrega @if (detail()?.steps?.length) { ({{ detail()!.steps.length }}) }</button>
          <button role="tab" [attr.aria-selected]="tab() === 'history'" (click)="tab.set('history')">Revisões</button>
        </div>
      </div>
      <div class="body">
        @switch (tab()) {
          @case ('summary') {
            <dl class="kv">
              <dt>Responsável</dt><dd>@if (agent(f.ownerAgentId); as a) { <span class="row"><sd-avatar [agent]="a" [size]="16" /> {{ a.name }}</span> } @else { <span class="muted">não definido</span> }</dd>
              <dt>Prioridade</dt><dd>{{ priorityLabel(f) }}</dd>
              <dt>Sprint</dt><dd>{{ sprintName(f) }}</dd>
              @if (f.specialty) { <dt>Especialidade</dt><dd>{{ f.specialty }}</dd> }
              <dt>Depende de</dt><dd>{{ depCodes(f) || '—' }} @if (detail()?.blockedBy?.length) { <span class="muted">(aguardando {{ detail()!.blockedBy.join(', ') }})</span> }</dd>
            </dl>
            @if (f.description) { <div><div class="section-title">Descrição</div><sd-markdown [source]="f.description" /></div> }
            <div>
              <div class="section-title">Critérios de aceite</div>
              @for (c of f.acceptanceCriteria; track $index; let i = $index) {
                <label class="check-row"><input type="checkbox" [checked]="c.met" (change)="check('criteria', i, $event)" /> <span [class.done]="c.met">{{ c.text }}</span></label>
              } @empty { <p class="muted">Sem critérios.</p> }
            </div>
            @if (f.tasks.length) {
              <div>
                <div class="section-title">Tarefas</div>
                @for (t of f.tasks; track $index; let i = $index) {
                  <label class="check-row"><input type="checkbox" [checked]="t.done" (change)="check('tasks', i, $event)" /> <span [class.done]="t.done">{{ t.text }}</span></label>
                }
              </div>
            }
          }
          @case ('delivery') {
            <div>
              <div class="section-title">Arquivos alterados</div>
              @if (changes(); as ch) {
                @if (!ch.available) { <p class="muted">{{ ch.reason }}</p> }
                @else if (!ch.files.length) { <p class="muted">Nenhuma alteração desde o início da feature.</p> }
                @else {
                  <div class="files">
                    @for (file of ch.files; track file.path) {
                      <button class="file" [class.sel]="diffPath() === file.path" (click)="showDiff(file.path)">
                        <span class="chip">{{ fileStatus(file.status) }}</span><span class="p grow ellipsis">{{ file.path }}</span>
                        @if (file.additions !== null) { <span class="add">+{{ file.additions }}</span><span class="del">−{{ file.deletions }}</span> } @else { <span class="muted">binário</span> }
                      </button>
                      @if (diffPath() === file.path) {
                        <pre class="diff">@for (l of diffLines(); track $index) {<span [class]="l.cls">{{ l.text }}</span>
}</pre>
                      }
                    }
                  </div>
                }
              } @else { <p class="muted"><sd-icon name="spinner" class="spin" [size]="12" /> Lendo alterações…</p> }
            </div>
            <div>
              <div class="section-title">Etapas e passagens</div>
              @for (s of detail()?.steps ?? []; track s.id) {
                <article class="step">
                  <div class="step-head">
                    <sd-avatar [agent]="agent(s.agentId)" [size]="18" />
                    <b>Etapa {{ s.seq }}</b><span class="muted grow ellipsis">{{ agent(s.agentId)?.name }} · {{ s.reason }}</span>
                    <span class="chip">{{ stepLabel(s) }}</span>
                    @if (s.sessionId) { <button class="btn sm ghost icon" (click)="openSession(s)" title="Abrir a sessão desta etapa" aria-label="Abrir a sessão desta etapa"><sd-icon name="panel" [size]="12" /></button> }
                  </div>
                  @if (s.handoff; as h) {
                    <div class="step-body">
                      @if (h.sem_bloco) { <div class="banner warn">O agente não deixou a passagem estruturada; abaixo, o texto final dele.</div> }
                      @if (h.objetivo) { <div><h4>Objetivo</h4>{{ h.objetivo }}</div> }
                      @if (h.contexto) { <div><h4>Contexto</h4><sd-markdown [source]="h.contexto" /></div> }
                      @for (l of handoffLists; track l.key) {
                        @if (list(h, l.key).length) { <div><h4>{{ l.label }}</h4><ul>@for (item of list(h, l.key); track $index) { <li>{{ item }}</li> }</ul></div> }
                      }
                      @if (h.permissoes_negadas) { <div class="banner warn">{{ h.permissoes_negadas }} permissão(ões) negada(s) nesta etapa. Veja a aba Atividade da sessão.</div> }
                      @if (h.proximo) { <div class="muted">Passou para {{ h.proximo }}: {{ h.mensagem_proximo }}</div> }
                    </div>
                  } @else if (s.status === 'RUNNING') {
                    <div class="step-body muted"><span><sd-icon name="spinner" class="spin" [size]="12" /> Em andamento.</span></div>
                  }
                </article>
              } @empty { <p class="muted">A feature ainda não foi executada.</p> }
            </div>
          }
          @case ('history') {
            @for (r of detail()?.reviews ?? []; track r.id) {
              <div class="step"><div class="step-head"><b>{{ r.decision === 'APPROVED' ? 'Aprovada' : 'Ajustes pedidos' }}</b>
                <span class="muted grow">revisão {{ r.revision }} · {{ when(r.createdAt) }}</span></div>
                @if (r.comment) { <div class="step-body">{{ r.comment }}</div> }</div>
            } @empty { <p class="muted">Nenhuma revisão registrada.</p> }
          }
        }
      </div>
    }
  `,
})
export class FeatureDetailPanel {
  readonly projectId = input.required<string>();
  readonly featureId = input<string | null>(null);
  readonly closed = output<void>();
  readonly created = output<string>();

  private readonly work = inject(ProjectWorkStore);
  private readonly catalog = inject(CatalogStore);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly ui = inject(UiStore);
  private readonly sessions = inject(SessionsStore);
  private readonly workspace = inject(WorkspaceStore);

  protected readonly priorities = Object.entries(PRIORITY_LABEL);
  protected readonly handoffLists = HANDOFF_LISTS;
  protected readonly tab = signal<Tab>('summary');
  protected readonly detail = signal<FeatureDetail | null>(null);
  protected readonly changes = signal<ChangesView | null>(null);
  protected readonly diffPath = signal<string | null>(null);
  protected readonly diffText = signal('');
  protected readonly comment = signal('');
  protected readonly busy = signal(false);
  protected readonly editing = signal(false);
  protected readonly conflicts = signal<string[]>([]);
  protected readonly form = signal<FormState>(emptyForm());

  private readonly overview = computed<FeatureOverview | undefined>(() => this.work.overviews()[this.projectId()]);
  protected readonly feature = computed(() => this.overview()?.features.find(f => f.id === this.featureId()) ?? null);
  protected readonly info = computed(() => FEATURE_STATUS[this.feature()?.status ?? 'PLANNED']);
  protected readonly sprints = computed(() => this.overview()?.sprints ?? []);
  protected readonly others = computed(() => (this.overview()?.features ?? []).filter(f => f.id !== this.featureId()));
  protected readonly runningStep = computed(() => this.detail()?.steps.find(s => s.status === 'RUNNING') ?? null);
  protected readonly owners = computed(() => {
    const participants = this.work.rooms()[this.projectId()]?.participants ?? [];
    const ids = new Set(participants.map(p => p.agentId));
    const all = this.catalog.agents().filter(a => !a.archivedAt);
    const squad = all.filter(a => ids.has(a.id));
    return squad.length ? squad : all;
  });
  protected readonly diffLines = computed(() => this.diffText().split('\n').map(text => ({
    text,
    cls: text.startsWith('+') && !text.startsWith('+++') ? 'a' : text.startsWith('-') && !text.startsWith('---') ? 'd' : text.startsWith('@@') ? 'h' : '',
  })));

  constructor() {
    effect(() => {
      const id = this.featureId();
      untracked(() => {
        this.detail.set(null);
        this.changes.set(null);
        this.diffPath.set(null);
        this.comment.set('');
        this.conflicts.set([]);
        this.editing.set(!id);
        if (!id) {
          this.form.set(emptyForm());
          this.tab.set('summary');
        }
      });
    });
    // recarrega o detalhe quando a feature muda (estado, etapas, revisões)
    effect(() => {
      const f = this.feature();
      if (!f) return;
      void f.updatedAt;
      void f.status;
      untracked(() => {
        void this.work.detail(f.id).then(d => this.detail.set(d)).catch(() => undefined);
        if (this.tab() === 'delivery') void this.loadChanges();
      });
    });
  }

  protected val(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected agent(id: string | null): Agent | undefined {
    return id ? this.catalog.agentById().get(id) : undefined;
  }

  protected priorityLabel(f: Feature): string {
    return PRIORITY_LABEL[f.priority];
  }

  protected sprintName(f: Feature): string {
    return this.sprints().find(s => s.id === f.sprintId)?.name ?? 'Sem sprint';
  }

  protected depCodes(f: Feature): string {
    const all = this.overview()?.features ?? [];
    return f.dependsOn.map(id => all.find(x => x.id === id)?.code ?? '?').join(', ');
  }

  protected stepLabel(s: FeatureStep): string {
    return { RUNNING: 'Em andamento', DONE: 'Concluída', FAILED: 'Falhou', CANCELLED: 'Cancelada' }[s.status];
  }

  protected fileStatus(status: string): string {
    return status === 'added' ? 'novo' : status === 'deleted' ? 'removido' : 'alterado';
  }

  protected list(h: Handoff, key: keyof Handoff): string[] {
    const value = h[key];
    return Array.isArray(value) ? value : [];
  }

  protected when(iso: string): string {
    return dateTime(iso);
  }

  protected openDelivery(): void {
    this.tab.set('delivery');
    void this.loadChanges();
  }

  private async loadChanges(): Promise<void> {
    const id = this.featureId();
    if (!id) return;
    try {
      this.changes.set(await this.api.get<ChangesView>(`features/${id}/changes`));
    } catch (error) {
      this.changes.set({ available: false, reason: ApiError.from(error).message, workdir: null, branch: null, files: [] });
    }
  }

  protected async showDiff(path: string): Promise<void> {
    if (this.diffPath() === path) {
      this.diffPath.set(null);
      return;
    }
    this.diffPath.set(path);
    this.diffText.set('Carregando…');
    try {
      const r = await this.api.get<{ diff: string }>(`features/${this.featureId()}/diff`, { path });
      this.diffText.set(r.diff || '(sem diferença de texto)');
    } catch (error) {
      this.diffText.set(ApiError.from(error).message);
    }
  }

  protected async check(list: 'criteria' | 'tasks', index: number, event: Event): Promise<void> {
    const f = this.feature();
    if (!f) return;
    try {
      await this.work.check(f, list, index, (event.target as HTMLInputElement).checked);
    } catch (error) {
      this.toasts.error(error);
    }
  }

  protected async openSession(step: FeatureStep): Promise<void> {
    if (!step.sessionId) return;
    await this.sessions.loadProject(this.projectId());
    this.workspace.openPane(this.projectId(), step.sessionId);
    this.workspace.setView(this.projectId(), 'panes');
  }

  protected async authorize(): Promise<void> {
    const f = this.feature();
    if (!f) return;
    const owner = this.agent(f.ownerAgentId)?.name ?? 'o responsável';
    const ok = await this.ui.confirm({
      title: `Autorizar ${f.code}`,
      body: `${owner} vai trabalhar na pasta do projeto, podendo criar e alterar arquivos com as ferramentas liberadas. `
        + 'A feature começa quando as dependências estiverem aprovadas e a fila estiver livre.',
      confirm: 'Autorizar',
    });
    if (!ok) return;
    await this.run(() => this.work.authorize(f));
  }

  protected async cancel(): Promise<void> {
    const f = this.feature();
    if (!f) return;
    if (f.status === 'IN_PROGRESS') {
      const ok = await this.ui.confirm({ title: 'Cancelar execução', body: 'O processo do agente será encerrado. Alterações já feitas continuam na pasta.', confirm: 'Cancelar execução', cancel: 'Voltar', danger: true });
      if (!ok) return;
    }
    await this.run(() => this.work.cancelFeature(f));
  }

  protected async approve(): Promise<void> {
    const f = this.feature();
    if (!f) return;
    this.conflicts.set([]);
    try {
      this.busy.set(true);
      await this.work.approveFeature(f, this.comment());
      this.comment.set('');
      this.toasts.show(`${f.code} aprovada.`, 'ok');
    } catch (error) {
      const e = ApiError.from(error);
      const list = e.details['conflicts'];
      if (Array.isArray(list)) this.conflicts.set(list as string[]);
      this.toasts.error(e);
    } finally {
      this.busy.set(false);
    }
  }

  protected async requestChanges(): Promise<void> {
    const f = this.feature();
    if (!f || !this.comment().trim()) return;
    await this.run(async () => {
      await this.work.requestChanges(f, this.comment().trim());
      this.comment.set('');
    });
  }

  protected async remove(): Promise<void> {
    const f = this.feature();
    if (!f) return;
    const ok = await this.ui.confirm({ title: `Excluir ${f.code}`, body: 'A feature e o histórico dela serão removidos. Arquivos do projeto não são alterados.', confirm: 'Excluir', danger: true });
    if (!ok) return;
    await this.run(async () => {
      await this.work.deleteFeature(f);
      this.closed.emit();
    });
  }

  protected startEdit(): void {
    const f = this.feature();
    if (!f) return;
    this.form.set({
      code: f.code, title: f.title, description: f.description, priority: f.priority, specialty: f.specialty, ownerAgentId: f.ownerAgentId ?? '',
      sprintId: f.sprintId ?? '', criteria: f.acceptanceCriteria.map(c => c.text).join('\n'), tasks: f.tasks.map(t => t.text).join('\n'),
      dependsOn: [...f.dependsOn],
    });
    this.editing.set(true);
  }

  protected cancelEdit(): void {
    if (this.featureId()) this.editing.set(false);
    else this.closed.emit();
  }

  protected set(field: keyof FormState, event: Event): void {
    const value = this.val(event);
    this.form.update(f => ({ ...f, [field]: value }));
  }

  protected toggleDep(id: string): void {
    this.form.update(f => ({ ...f, dependsOn: f.dependsOn.includes(id) ? f.dependsOn.filter(d => d !== id) : [...f.dependsOn, id] }));
  }

  protected async saveForm(): Promise<void> {
    const v = this.form();
    const current = this.feature();
    const split = (text: string) => text.split('\n').map(t => t.trim()).filter(Boolean);
    const criteria = split(v.criteria).map(text => ({ text, met: current?.acceptanceCriteria.find(c => c.text === text)?.met === true }));
    const tasks = split(v.tasks).map(text => ({ text, done: current?.tasks.find(t => t.text === text)?.done === true }));
    const draft: FeatureDraft = {
      code: v.code.trim() || null,
      title: v.title.trim(),
      description: v.description,
      priority: v.priority,
      specialty: v.specialty,
      ownerAgentId: v.ownerAgentId || '',
      sprintId: v.sprintId || '',
      acceptanceCriteria: criteria,
      tasks,
      dependsOn: v.dependsOn,
    };
    await this.run(async () => {
      if (current) {
        await this.work.updateFeature(current, draft);
        this.editing.set(false);
      } else {
        const created = await this.work.createFeature(this.projectId(), draft);
        this.created.emit(created.id);
      }
    });
  }

  private async run(action: () => Promise<unknown>): Promise<void> {
    this.busy.set(true);
    try {
      await action();
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.busy.set(false);
    }
  }
}

function emptyForm(): FormState {
  return { code: '', title: '', description: '', priority: 'MEDIUM', specialty: '', ownerAgentId: '', sprintId: '', criteria: '', tasks: '', dependsOn: [] };
}
