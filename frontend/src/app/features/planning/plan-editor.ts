import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';

import { ApiError } from '../../core/api';
import type { Plan, PlanContent, PlanFeatureItem, PlanProblem, PlanningParticipant } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { Icon } from '../../shell/icon';
import { ProjectWorkStore } from '../../state/project-work.store';
import { UiStore } from '../../state/ui.store';

const PRIORITIES = [
  { value: 'HIGH', label: 'Alta' },
  { value: 'MEDIUM', label: 'Média' },
  { value: 'LOW', label: 'Baixa' },
];

/**
 * Revisão de um plano: a pessoa edita sprints e features, vê o que o backend aponta (dependências circulares,
 * campos incompletos) e aprova. Aprovar cria features planejadas; executar exige outra autorização.
 */
@Component({
  selector: 'sd-plan-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  styles: `
    :host { display: flex; flex-direction: column; min-height: 0; }
    .bar { display: flex; align-items: center; gap: 6px; padding: 8px 10px; border-bottom: 1px solid var(--line-1); position: sticky; top: 0; background: var(--bg-2); z-index: 1; }
    .body { padding: 10px; display: flex; flex-direction: column; gap: 12px; }
    .problems { display: flex; flex-direction: column; gap: 4px; }
    .problem { display: flex; gap: 6px; align-items: flex-start; font-size: var(--fs-sm); }
    .problem.error { color: #f5b3a9; }
    .problem.warning { color: var(--fg-3); }
    .feat { border: 1px solid var(--line-2); border-radius: var(--r-md); background: var(--bg-1); padding: 10px; display: grid; gap: 8px; }
    .feat.bad { border-color: rgba(239, 123, 108, 0.5); }
    .grid2 { display: grid; grid-template-columns: 90px 1fr; gap: 8px; }
    .grid3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; }
    label.field > span { font-size: var(--fs-xs); color: var(--fg-3); }
    .sprint { display: grid; grid-template-columns: 160px 1fr 28px; gap: 6px; }
    .deps { display: flex; flex-wrap: wrap; gap: 6px; }
    .deps label { font-size: var(--fs-xs); }
  `,
  template: `
    <div class="bar">
      <button class="btn sm ghost icon" (click)="close.emit()" aria-label="Voltar à lista de planos"><sd-icon name="chevron-left" [size]="13" /></button>
      <b>Plano {{ plan().number }}</b>
      <span class="chip">{{ plan().status === 'DRAFT' ? 'Rascunho' : plan().status === 'APPROVED' ? 'Aprovado' : 'Descartado' }}</span>
      <span class="spacer"></span>
      @if (editable()) {
        <button class="btn sm ghost" (click)="discard()">Descartar</button>
        <button class="btn sm" [disabled]="!dirty() || busy()" (click)="save()">Salvar e validar</button>
        <button class="btn sm primary" [disabled]="busy() || errorCount() > 0" (click)="approve()"
          [attr.title]="errorCount() ? 'Corrija as pendências antes de aprovar' : 'Cria as features como planejadas; nada é executado'">
          <sd-icon name="check" [size]="12" /> Aprovar plano
        </button>
      }
    </div>
    <div class="body">
      @if (editable()) {
        <div class="banner info"><span>Aprovar cria sprints e features <b>planejadas</b>. A execução só começa quando você autorizar cada feature (ou todas) na aba Features.</span></div>
      }
      @if (problems().length) {
        <div class="problems" role="list" aria-label="Pendências do plano">
          @for (p of problems(); track $index) {
            <div class="problem" [class.error]="p.severity === 'ERROR'" [class.warning]="p.severity === 'WARNING'" role="listitem">
              <sd-icon [name]="p.severity === 'ERROR' ? 'alert' : 'info'" [size]="12" /> <span>{{ p.message }}</span>
            </div>
          }
          @if (dirty()) { <div class="muted" style="font-size: var(--fs-xs)">Há alterações não salvas; salve para validar de novo.</div> }
        </div>
      }
      <label class="field"><span>Resumo</span>
        <textarea class="textarea" rows="2" [disabled]="!editable()" [value]="draft().summary" (input)="patch({ summary: val($event) })"></textarea></label>

      <div class="section-title">Sprints</div>
      @for (s of draft().sprints; track $index; let i = $index) {
        <div class="sprint">
          <input class="input" aria-label="Nome da sprint" [disabled]="!editable()" [value]="s.name" (input)="setSprint(i, 'name', val($event))" />
          <input class="input" aria-label="Objetivo da sprint" placeholder="Objetivo" [disabled]="!editable()" [value]="s.goal" (input)="setSprint(i, 'goal', val($event))" />
          @if (editable()) { <button class="btn sm ghost icon" (click)="removeSprint(i)" aria-label="Remover sprint"><sd-icon name="x" [size]="12" /></button> }
        </div>
      }
      @if (editable()) { <button class="btn sm ghost" style="align-self: flex-start" (click)="addSprint()"><sd-icon name="plus" [size]="12" /> Sprint</button> }

      <div class="section-title">Features ({{ draft().features.length }})</div>
      @for (f of draft().features; track $index; let i = $index) {
        <section class="feat" [class.bad]="hasError(f)" [attr.aria-label]="'Feature ' + f.code">
          <div class="grid2">
            <label class="field"><span>Código</span><input class="input" [disabled]="!editable()" [value]="f.code" (input)="setFeature(i, { code: val($event) })" /></label>
            <label class="field"><span>Título</span><input class="input" [disabled]="!editable()" [value]="f.title" (input)="setFeature(i, { title: val($event) })" /></label>
          </div>
          <label class="field"><span>Descrição</span><textarea class="textarea" rows="2" [disabled]="!editable()" [value]="f.description" (input)="setFeature(i, { description: val($event) })"></textarea></label>
          <div class="grid3">
            <label class="field"><span>Responsável</span>
              <select class="select" [disabled]="!editable()" (change)="setFeature(i, { owner: val($event) })">
                <option value="" [selected]="!ownerMatch(f)">—</option>
                @for (p of participants(); track p.agentId) { <option [value]="'@' + p.handle" [selected]="ownerMatch(f) === p.agentId">{{ p.name }}</option> }
                @if (f.owner && !ownerMatch(f)) { <option [value]="f.owner" selected>{{ f.owner }} (não encontrado)</option> }
              </select></label>
            <label class="field"><span>Prioridade</span>
              <select class="select" [disabled]="!editable()" (change)="setFeature(i, { priority: val($event) })">
                @for (p of priorities; track p.value) { <option [value]="p.value" [selected]="priorityOf(f) === p.value">{{ p.label }}</option> }
              </select></label>
            <label class="field"><span>Sprint</span>
              <select class="select" [disabled]="!editable()" (change)="setFeature(i, { sprint: val($event) })">
                <option value="" [selected]="!f.sprint">Sem sprint</option>
                @for (s of draft().sprints; track $index) { <option [value]="s.name" [selected]="f.sprint === s.name">{{ s.name }}</option> }
                @if (f.sprint && !hasSprint(f.sprint)) { <option [value]="f.sprint" selected>{{ f.sprint }} (nova)</option> }
              </select></label>
          </div>
          <label class="field"><span>Especialidade</span><input class="input" [disabled]="!editable()" [value]="f.specialty" (input)="setFeature(i, { specialty: val($event) })" /></label>
          <label class="field"><span>Critérios de aceite (um por linha)</span>
            <textarea class="textarea" rows="3" [disabled]="!editable()" [value]="f.criteria.join('\\n')" (input)="setFeature(i, { criteria: lines($event) })"></textarea></label>
          <label class="field"><span>Tarefas (uma por linha)</span>
            <textarea class="textarea" rows="2" [disabled]="!editable()" [value]="f.tasks.join('\\n')" (input)="setFeature(i, { tasks: lines($event) })"></textarea></label>
          <div class="field"><span>Depende de</span>
            <div class="deps">
              @for (o of draft().features; track $index) {
                @if (o.code && o.code !== f.code) {
                  <label class="check"><input type="checkbox" [disabled]="!editable()" [checked]="f.dependsOn.includes(o.code)" (change)="toggleDep(i, o.code)" /> {{ o.code }}</label>
                }
              }
              @for (d of externalDeps(f); track d) { <span class="chip" title="Feature já existente no projeto">{{ d }}</span> }
            </div>
          </div>
          @if (editable()) { <button class="btn sm ghost danger" style="justify-self: start" (click)="removeFeature(i)"><sd-icon name="trash" [size]="12" /> Remover feature</button> }
        </section>
      }
      @if (editable()) { <button class="btn sm" style="align-self: flex-start" (click)="addFeature()"><sd-icon name="plus" [size]="12" /> Feature</button> }
    </div>
  `,
})
export class PlanEditor {
  readonly plan = input.required<Plan>();
  readonly participants = input<PlanningParticipant[]>([]);
  readonly close = output<void>();

  private readonly work = inject(ProjectWorkStore);
  private readonly toasts = inject(Toasts);
  private readonly ui = inject(UiStore);

  protected readonly priorities = PRIORITIES;
  protected readonly draft = signal<PlanContent>({ summary: '', sprints: [], features: [] });
  protected readonly dirty = signal(false);
  protected readonly busy = signal(false);
  private readonly extraProblems = signal<PlanProblem[] | null>(null);

  protected readonly editable = computed(() => this.plan().status === 'DRAFT');
  protected readonly problems = computed(() => this.extraProblems() ?? this.plan().problems);
  protected readonly errorCount = computed(() => this.problems().filter(p => p.severity === 'ERROR').length);

  constructor() {
    effect(() => {
      const plan = this.plan();
      untracked(() => {
        if (!this.dirty()) {
          this.draft.set(structuredClone(plan.content));
          this.extraProblems.set(null);
        }
      });
    });
  }

  protected val(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected lines(event: Event): string[] {
    return this.val(event).split('\n').map(l => l.replace(/^\s*[-*]\s*/, ''));
  }

  protected patch(change: Partial<PlanContent>): void {
    this.draft.update(d => ({ ...d, ...change }));
    this.dirty.set(true);
  }

  protected setSprint(i: number, field: 'name' | 'goal', value: string): void {
    this.patch({ sprints: this.draft().sprints.map((s, j) => (j === i ? { ...s, [field]: value } : s)) });
  }

  protected addSprint(): void {
    this.patch({ sprints: [...this.draft().sprints, { name: `Sprint ${this.draft().sprints.length + 1}`, goal: '' }] });
  }

  protected removeSprint(i: number): void {
    this.patch({ sprints: this.draft().sprints.filter((_, j) => j !== i) });
  }

  protected setFeature(i: number, change: Partial<PlanFeatureItem>): void {
    const old = this.draft().features[i];
    let features = this.draft().features.map((f, j) => (j === i ? { ...f, ...change } : f));
    if (change.code !== undefined && old.code && change.code !== old.code) {
      features = features.map(f => ({ ...f, dependsOn: f.dependsOn.map(d => (d === old.code ? change.code! : d)) }));
    }
    this.patch({ features });
  }

  protected toggleDep(i: number, code: string): void {
    const f = this.draft().features[i];
    this.setFeature(i, { dependsOn: f.dependsOn.includes(code) ? f.dependsOn.filter(d => d !== code) : [...f.dependsOn, code] });
  }

  protected addFeature(): void {
    const n = this.draft().features.length + 1;
    this.patch({
      features: [...this.draft().features, {
        code: `F${n}`, title: '', description: '', priority: 'MEDIUM', specialty: '', owner: '', sprint: this.draft().sprints[0]?.name ?? '',
        criteria: [], tasks: [], dependsOn: [],
      }],
    });
  }

  protected removeFeature(i: number): void {
    const code = this.draft().features[i].code;
    this.patch({ features: this.draft().features.filter((_, j) => j !== i).map(f => ({ ...f, dependsOn: f.dependsOn.filter(d => d !== code) })) });
  }

  protected ownerMatch(f: PlanFeatureItem): string | null {
    const ref = fold(f.owner.replace(/^@/, ''));
    if (!ref) return null;
    const p = this.participants().find(x => x.agentId === f.owner || fold(x.handle) === ref || fold(x.name) === ref);
    return p?.agentId ?? null;
  }

  protected priorityOf(f: PlanFeatureItem): string {
    const p = fold(f.priority ?? '');
    return p.startsWith('alt') || p === 'high' ? 'HIGH' : p.startsWith('baix') || p === 'low' ? 'LOW' : 'MEDIUM';
  }

  protected hasSprint(name: string): boolean {
    return this.draft().sprints.some(s => s.name === name);
  }

  protected externalDeps(f: PlanFeatureItem): string[] {
    const codes = new Set(this.draft().features.map(x => x.code));
    return f.dependsOn.filter(d => !codes.has(d));
  }

  protected hasError(f: PlanFeatureItem): boolean {
    return this.problems().some(p => p.severity === 'ERROR' && p.feature === f.code);
  }

  protected async save(): Promise<Plan | null> {
    this.busy.set(true);
    try {
      const clean: PlanContent = {
        ...this.draft(),
        features: this.draft().features.map(f => ({ ...f, criteria: f.criteria.filter(c => c.trim()), tasks: f.tasks.filter(t => t.trim()) })),
      };
      const saved = await this.work.updatePlan(this.plan(), clean);
      this.dirty.set(false);
      this.draft.set(structuredClone(saved.content));
      this.extraProblems.set(null);
      return saved;
    } catch (error) {
      this.toasts.error(error);
      return null;
    } finally {
      this.busy.set(false);
    }
  }

  protected async approve(): Promise<void> {
    if (this.dirty() && !(await this.save())) return;
    if (this.errorCount() > 0) return;
    const ok = await this.ui.confirm({
      title: 'Aprovar plano',
      body: 'As sprints e features serão criadas como planejadas. Nenhum agente começa a trabalhar até você autorizar a execução.',
      confirm: 'Aprovar',
    });
    if (!ok) return;
    this.busy.set(true);
    try {
      const result = await this.work.approvePlan(this.plan());
      const skipped = result.skipped.length ? ` ${result.skipped.length} em andamento não foram alteradas.` : '';
      this.toasts.show(`Plano aprovado: ${result.created} features criadas, ${result.updated} atualizadas.${skipped}`, 'ok');
    } catch (error) {
      const e = ApiError.from(error);
      const problems = e.details['problems'];
      if (Array.isArray(problems)) this.extraProblems.set(problems as PlanProblem[]);
      this.toasts.error(e);
    } finally {
      this.busy.set(false);
    }
  }

  protected async discard(): Promise<void> {
    const ok = await this.ui.confirm({ title: 'Descartar plano', body: 'O rascunho fica no histórico, marcado como descartado.', confirm: 'Descartar', danger: true });
    if (!ok) return;
    try {
      await this.work.discardPlan(this.plan());
      this.close.emit();
    } catch (error) {
      this.toasts.error(error);
    }
  }
}

function fold(text: string): string {
  return text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/[^a-z0-9]/g, '');
}
