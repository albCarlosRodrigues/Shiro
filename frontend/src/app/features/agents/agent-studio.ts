import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { debounceTime } from 'rxjs';

import { Api, ApiError } from '../../core/api';
import { Desktop } from '../../core/desktop';
import { ROLE_LABEL } from '../../core/format';
import type { Agent, AgentDraft, AgentPreview, AgentRole } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { Avatar } from '../../shell/bits';
import { Icon } from '../../shell/icon';
import { CatalogStore } from '../../state/catalog.store';
import { UiStore } from '../../state/ui.store';

const BUILT_IN_TOOLS = ['Read', 'Glob', 'Grep', 'Edit', 'Write', 'NotebookEdit', 'Bash', 'PowerShell', 'WebFetch', 'WebSearch', 'Agent'];
const RULE = /^[A-Za-z][A-Za-z0-9_]{0,79}(\([^()\r\n]{1,200}\))?$/;
const MODE_LABEL: Record<string, string> = {
  default: 'Padrão (pergunta; sem ninguém para responder, nega)',
  acceptEdits: 'Aceitar edições de arquivo',
  plan: 'Planejamento (não altera nada)',
  dontAsk: 'Não perguntar (nega o que não estiver liberado)',
  auto: 'Automático (classificador revisa as ações)',
};

type StudioTab = 'identity' | 'instructions' | 'execution';

/**
 * Estúdio do Agente: identidade, instruções e diretrizes em Markdown, modelo, esforço, modo de permissão e
 * ferramentas. Modelos e níveis de esforço vêm do Claude Code instalado. A prévia mostra as instruções finais
 * exatamente como serão enviadas.
 */
@Component({
  selector: 'sd-agent-studio',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, Icon, Avatar],
  styles: `
    .layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 0.9fr); gap: 0; height: 100%; }
    .left { overflow: auto; padding: 14px 16px; border-right: 1px solid var(--line-1); }
    .right { display: flex; flex-direction: column; min-height: 0; padding: 12px 14px; background: var(--bg-1); }
    .right pre { flex: 1 1 auto; margin: 8px 0 0; padding: 10px; overflow: auto; border-radius: var(--r-md); background: var(--bg-0);
      font-size: var(--fs-xs); line-height: 1.55; white-space: pre-wrap; overflow-wrap: anywhere; }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .full { grid-column: 1 / -1; }
    .icons { display: flex; flex-wrap: wrap; gap: 4px; }
    .icon-opt { display: grid; place-items: center; width: 30px; height: 30px; border: 1px solid var(--line-2); border-radius: var(--r-md); background: var(--bg-1); color: var(--fg-2); cursor: pointer; }
    .icon-opt[aria-checked='true'] { border-color: var(--signal); color: var(--signal); background: var(--signal-dim); }
    .colors { display: flex; gap: 5px; }
    .color { width: 22px; height: 22px; border: 2px solid transparent; border-radius: 6px; background: var(--c); cursor: pointer; }
    .color[aria-checked='true'] { border-color: var(--fg-1); }
    .tools { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 4px 10px; }
    .rules { display: flex; flex-wrap: wrap; gap: 4px; }
    .rules .chip { font-family: var(--font-mono); }
    .rules .chip button { border: 0; padding: 0; background: none; color: var(--fg-3); cursor: pointer; }
    .templates { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; margin-bottom: 14px; }
    .tpl { padding: 8px 10px; border: 1px solid var(--line-2); border-radius: var(--r-md); background: var(--bg-1); color: var(--fg-1); text-align: left; cursor: pointer; }
    .tpl:hover { border-color: var(--signal); }
    .tpl small { display: block; color: var(--fg-3); }
    .tabs { margin-bottom: 12px; }
  `,
  template: `
    <form class="drawer wide" role="dialog" aria-modal="true" aria-labelledby="as-title" [formGroup]="form" (ngSubmit)="save()" (keydown.escape)="ui.closeDrawer()">
      <header>
        <sd-avatar [agent]="avatarAgent()" [size]="26" />
        <h2 id="as-title" class="grow">{{ agentId() ? 'Estúdio do Agente · ' + (current()?.name ?? '') : 'Novo agente' }}</h2>
        @if (agentId()) {
          <button type="button" class="btn sm" (click)="exportMarkdown()"><sd-icon name="download" [size]="12" /> Exportar .md</button>
          <button type="button" class="btn sm" (click)="duplicate()"><sd-icon name="copy" [size]="12" /> Duplicar</button>
        }
        <button type="button" class="btn ghost icon" (click)="ui.closeDrawer()" aria-label="Fechar"><sd-icon name="x" /></button>
      </header>
      <div class="body" style="padding: 0">
        <div class="layout">
          <div class="left">
            @if (!agentId() && !templateChosen()) {
              <div class="section-title" style="margin-bottom: 6px">Começar de um modelo</div>
              <div class="templates">
                @for (t of catalog.templates(); track t.key) {
                  <button type="button" class="tpl" (click)="applyTemplate(t.key)"><b>{{ t.label }}</b><small>{{ t.summary }}</small></button>
                }
                <button type="button" class="tpl" (click)="templateChosen.set(true)"><b>Em branco</b><small>Preencha tudo do zero.</small></button>
              </div>
            }
            <div class="seg tabs" role="tablist">
              <button type="button" role="tab" [attr.aria-selected]="tab() === 'identity'" (click)="tab.set('identity')">Identidade</button>
              <button type="button" role="tab" [attr.aria-selected]="tab() === 'instructions'" (click)="tab.set('instructions')">Instruções</button>
              <button type="button" role="tab" [attr.aria-selected]="tab() === 'execution'" (click)="tab.set('execution')">Execução e ferramentas</button>
            </div>

            @switch (tab()) {
              @case ('identity') {
                <div class="grid">
                  <div class="field"><label for="as-name">Nome</label><input id="as-name" class="input" formControlName="name" maxlength="80" /></div>
                  <div class="field"><label for="as-role">Papel na squad</label>
                    <select id="as-role" class="select" formControlName="role">
                      @for (r of roles; track r) { <option [ngValue]="r">{{ roleLabel[r] }}</option> }
                    </select>
                  </div>
                  <div class="field full"><label for="as-spec">Especialidade</label><input id="as-spec" class="input" formControlName="specialty" maxlength="120" placeholder="Ex.: Angular e acessibilidade" /></div>
                  <div class="field full">
                    <span class="label" id="as-icon">Ícone</span>
                    <div class="icons" role="radiogroup" aria-labelledby="as-icon">
                      @for (i of icons(); track i) {
                        <button type="button" class="icon-opt" role="radio" [attr.aria-checked]="form.controls.icon.value === i" [attr.aria-label]="i" (click)="form.controls.icon.setValue(i)"><sd-icon [name]="i" [size]="15" /></button>
                      }
                    </div>
                  </div>
                  <div class="field full">
                    <span class="label" id="as-color">Cor</span>
                    <div class="colors" role="radiogroup" aria-labelledby="as-color">
                      @for (c of colors(); track c) {
                        <button type="button" class="color" role="radio" [style.--c]="c" [attr.aria-checked]="form.controls.color.value === c" [attr.aria-label]="c" (click)="form.controls.color.setValue(c)"></button>
                      }
                    </div>
                  </div>
                  <div class="field full"><label for="as-resp">Responsabilidade</label><textarea id="as-resp" class="textarea" rows="3" formControlName="responsibility"></textarea></div>
                  <div class="field full"><label for="as-pers">Personalidade</label><textarea id="as-pers" class="textarea" rows="2" formControlName="personality"></textarea></div>
                </div>
              }
              @case ('instructions') {
                <div class="col">
                  <div class="field"><label for="as-inst">Instruções (Markdown)</label><textarea id="as-inst" class="textarea mono" rows="12" formControlName="instructions"></textarea></div>
                  <div class="field"><label for="as-guide">Diretrizes (Markdown)</label><textarea id="as-guide" class="textarea mono" rows="7" formControlName="guidelines"></textarea>
                    <span class="hint">Regras de trabalho que valem em toda execução: idioma, limites, como relatar.</span></div>
                </div>
              }
              @case ('execution') {
                <div class="grid">
                  <div class="field">
                    <label for="as-model">Modelo</label>
                    <input id="as-model" class="input mono" formControlName="model" [attr.list]="'as-models'" placeholder="padrão do Claude Code" />
                    <datalist id="as-models">@for (m of catalog.options()?.modelAliases ?? []; track m) { <option [value]="m"></option> }</datalist>
                    <span class="hint">{{ modelHint() }}</span>
                  </div>
                  <div class="field">
                    <label for="as-effort">Esforço</label>
                    <select id="as-effort" class="select" formControlName="effort" [attr.disabled]="effortSupported() ? null : true">
                      <option [ngValue]="null">Padrão do modelo</option>
                      @for (e of catalog.options()?.effortLevels ?? []; track e) { <option [ngValue]="e">{{ e }}</option> }
                    </select>
                    @if (!effortSupported()) { <span class="hint">O Claude Code instalado não oferece --effort.</span> }
                  </div>
                  <div class="field full">
                    <label for="as-mode">Modo de permissão nas execuções</label>
                    <select id="as-mode" class="select" formControlName="permissionMode">
                      <option [ngValue]="null">Padrão do Shiro (aceitar edições)</option>
                      @for (m of catalog.options()?.permissionModes ?? []; track m) { <option [ngValue]="m">{{ modeLabel[m] ?? m }}</option> }
                    </select>
                    <span class="hint">Conversas e planejamento sempre rodam com ferramentas de leitura. Ignorar todas as permissões não é oferecido.</span>
                  </div>
                  <div class="field full">
                    <span class="label">Ferramentas disponíveis</span>
                    <label class="check"><input type="checkbox" [checked]="form.controls.tools.value === null" (change)="toggleDefaultTools($any($event.target).checked)" /> Conjunto padrão do Claude Code</label>
                    @if (form.controls.tools.value !== null) {
                      <div class="tools">
                        @for (t of builtIn; track t) {
                          <label class="check"><input type="checkbox" [checked]="hasTool(t)" (change)="toggleTool(t, $any($event.target).checked)" /> {{ t }}</label>
                        }
                      </div>
                    }
                  </div>
                  <div class="field full">
                    <span class="label">Liberadas sem confirmação (--allowedTools)</span>
                    <div class="rules">
                      @for (r of form.controls.allowedTools.value; track r) {
                        <span class="chip">{{ r }} <button type="button" (click)="removeRule('allowedTools', r)" [attr.aria-label]="'Remover ' + r"><sd-icon name="x" [size]="10" /></button></span>
                      } @empty { <span class="hint">Nenhuma.</span> }
                    </div>
                    <div class="row"><input #allowInput class="input mono" placeholder="Ex.: Bash(npm test *)" (keydown.enter)="$event.preventDefault(); addRule('allowedTools', allowInput)" />
                      <button type="button" class="btn sm" (click)="addRule('allowedTools', allowInput)">Adicionar</button></div>
                  </div>
                  <div class="field full">
                    <span class="label">Negadas (--disallowedTools)</span>
                    <div class="rules">
                      @for (r of form.controls.disallowedTools.value; track r) {
                        <span class="chip">{{ r }} <button type="button" (click)="removeRule('disallowedTools', r)" [attr.aria-label]="'Remover ' + r"><sd-icon name="x" [size]="10" /></button></span>
                      } @empty { <span class="hint">Nenhuma.</span> }
                    </div>
                    <div class="row"><input #denyInput class="input mono" placeholder="Ex.: Bash(git push *)" (keydown.enter)="$event.preventDefault(); addRule('disallowedTools', denyInput)" />
                      <button type="button" class="btn sm" (click)="addRule('disallowedTools', denyInput)">Adicionar</button></div>
                  </div>
                </div>
              }
            }
            @if (error()) { <div class="banner danger" style="margin-top: 12px"><sd-icon name="alert" [size]="14" />{{ error() }}</div> }
          </div>
          <div class="right">
            <div class="row">
              <span class="section-title grow">Prévia das instruções finais</span>
              <div class="seg" role="tablist">
                <button type="button" role="tab" [attr.aria-selected]="previewMode() === 'CONVERSATION'" (click)="previewMode.set('CONVERSATION'); refreshPreview()">Conversa</button>
                <button type="button" role="tab" [attr.aria-selected]="previewMode() === 'EXECUTION'" (click)="previewMode.set('EXECUTION'); refreshPreview()">Execução</button>
              </div>
            </div>
            <pre class="selectable" aria-live="polite">{{ preview()?.instructions ?? 'Preencha o nome e o papel para ver a prévia.' }}

---
{{ preview()?.turnPreamble ?? '' }}</pre>
          </div>
        </div>
      </div>
      <footer>
        @if (agentId()) { <button type="button" class="btn danger" (click)="remove()"><sd-icon name="trash" [size]="12" /> Excluir…</button> }
        <span class="spacer"></span>
        <button type="button" class="btn" (click)="ui.closeDrawer()">Cancelar</button>
        <button type="submit" class="btn primary" [disabled]="saving()">{{ agentId() ? 'Salvar agente' : 'Criar agente' }}</button>
      </footer>
    </form>
  `,
})
export class AgentStudio implements OnInit {
  readonly agentId = input<string | null>(null);
  readonly templateKey = input<string | null>(null);

  protected readonly ui = inject(UiStore);
  protected readonly catalog = inject(CatalogStore);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly desktop = inject(Desktop);
  private readonly fb = inject(FormBuilder);

  protected readonly roles: AgentRole[] = ['COMMANDER', 'REQUIREMENTS', 'ARCHITECTURE', 'OPERATOR'];
  protected readonly roleLabel = ROLE_LABEL;
  protected readonly modeLabel = MODE_LABEL;
  protected readonly builtIn = BUILT_IN_TOOLS;
  protected readonly tab = signal<StudioTab>('identity');
  protected readonly templateChosen = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly preview = signal<AgentPreview | null>(null);
  protected readonly previewMode = signal<'CONVERSATION' | 'EXECUTION'>('EXECUTION');

  protected readonly current = computed(() => this.catalog.agentById().get(this.agentId() ?? ''));
  protected readonly icons = computed(() => [...(this.catalog.options()?.icons ?? ['agent'])].sort());
  protected readonly colors = computed(() => this.catalog.options()?.colors ?? ['#8fa2ff']);
  protected readonly effortSupported = computed(() => this.catalog.options()?.effortSupported === true);
  protected readonly modelHint = computed(() => {
    const aliases = this.catalog.options()?.modelAliases ?? [];
    return aliases.length ? `Apelidos aceitos pelo Claude Code ${this.catalog.options()?.cliVersion ?? ''}: ${aliases.join(', ')}. Também aceita o nome completo.`
      : 'Use um apelido (por exemplo, sonnet) ou o nome completo do modelo.';
  });

  protected readonly form = this.fb.group({
    name: this.fb.nonNullable.control('', [Validators.required, Validators.maxLength(80)]),
    role: this.fb.nonNullable.control<AgentRole>('OPERATOR'),
    specialty: this.fb.nonNullable.control(''),
    icon: this.fb.nonNullable.control('agent'),
    color: this.fb.nonNullable.control('#8fa2ff'),
    responsibility: this.fb.nonNullable.control(''),
    personality: this.fb.nonNullable.control(''),
    instructions: this.fb.nonNullable.control(''),
    guidelines: this.fb.nonNullable.control(''),
    model: this.fb.control<string | null>(null),
    effort: this.fb.control<string | null>(null),
    permissionMode: this.fb.control<string | null>(null),
    tools: this.fb.control<string[] | null>(null),
    allowedTools: this.fb.nonNullable.control<string[]>([]),
    disallowedTools: this.fb.nonNullable.control<string[]>([]),
    templateKey: this.fb.control<string | null>(null),
  });

  private readonly formValue = toSignal(this.form.valueChanges.pipe(debounceTime(350)));
  protected readonly avatarAgent = computed(() => {
    this.formValue();
    return { ...(this.current() ?? {}), icon: this.form.controls.icon.value, color: this.form.controls.color.value, name: this.form.controls.name.value } as Agent;
  });

  constructor() {
    this.form.valueChanges.pipe(debounceTime(350)).subscribe(() => void this.refreshPreview());
  }

  ngOnInit(): void {
    const agent = this.current();
    if (agent) {
      this.load(agent);
      this.templateChosen.set(true);
    } else if (this.templateKey()) {
      this.applyTemplate(this.templateKey()!);
    }
    void this.refreshPreview();
  }

  private load(draft: AgentDraft): void {
    this.form.setValue({
      name: draft.name, role: draft.role, specialty: draft.specialty, icon: draft.icon, color: draft.color,
      responsibility: draft.responsibility, personality: draft.personality, instructions: draft.instructions, guidelines: draft.guidelines,
      model: draft.model, effort: draft.effort, permissionMode: draft.permissionMode, tools: draft.tools ? [...draft.tools] : null,
      allowedTools: [...draft.allowedTools], disallowedTools: [...draft.disallowedTools], templateKey: draft.templateKey,
    });
  }

  protected applyTemplate(key: string): void {
    const template = this.catalog.templates().find(t => t.key === key);
    if (!template) return;
    this.load(template.draft);
    this.templateChosen.set(true);
  }

  private draft(): AgentDraft {
    const v = this.form.getRawValue();
    return { ...v, model: v.model?.trim() || null };
  }

  protected async refreshPreview(): Promise<void> {
    if (!this.form.controls.name.value.trim()) {
      this.preview.set(null);
      return;
    }
    try {
      this.preview.set(await this.api.post<AgentPreview>('agent-preview', { draft: this.draft(), projectId: null, mode: this.previewMode() }));
    } catch {
      // a prévia é informativa; erros de validação aparecem ao salvar
    }
  }

  protected hasTool(tool: string): boolean {
    return this.form.controls.tools.value?.includes(tool) ?? false;
  }

  protected toggleDefaultTools(useDefault: boolean): void {
    this.form.controls.tools.setValue(useDefault ? null : ['Read', 'Glob', 'Grep', 'Edit', 'Write']);
  }

  protected toggleTool(tool: string, on: boolean): void {
    const list = this.form.controls.tools.value ?? [];
    this.form.controls.tools.setValue(on ? [...new Set([...list, tool])] : list.filter(t => t !== tool));
  }

  protected addRule(field: 'allowedTools' | 'disallowedTools', inputEl: HTMLInputElement): void {
    const rule = inputEl.value.trim();
    if (!rule) return;
    if (!RULE.test(rule)) {
      this.toasts.show('Regra inválida. Use o nome da ferramenta e, se quiser, um padrão entre parênteses: Bash(npm test *).', 'warn');
      return;
    }
    const control = this.form.controls[field];
    control.setValue([...new Set([...control.value, rule])]);
    inputEl.value = '';
  }

  protected removeRule(field: 'allowedTools' | 'disallowedTools', rule: string): void {
    const control = this.form.controls[field];
    control.setValue(control.value.filter(r => r !== rule));
  }

  protected async save(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.tab.set('identity');
      this.error.set('Informe o nome do agente.');
      return;
    }
    this.saving.set(true);
    this.error.set('');
    try {
      const id = this.agentId();
      const agent = id
        ? await this.api.put<Agent>(`agents/${id}`, this.draft())
        : await this.api.post<Agent>('agents', { templateKey: null, draft: this.draft() });
      this.catalog.upsertAgent(agent);
      this.toasts.show(id ? 'Agente salvo. Vale a partir da próxima execução.' : 'Agente criado.', 'ok');
      this.ui.closeDrawer();
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    } finally {
      this.saving.set(false);
    }
  }

  protected async duplicate(): Promise<void> {
    try {
      const copy = await this.api.post<Agent>(`agents/${this.agentId()}/duplicate`);
      this.catalog.upsertAgent(copy);
      this.ui.openDrawer({ kind: 'agent', agentId: copy.id });
      this.toasts.show('Cópia criada.', 'ok');
    } catch (e) {
      this.toasts.error(e);
    }
  }

  protected async exportMarkdown(): Promise<void> {
    const agent = this.current();
    if (!agent) return;
    try {
      const md = await this.api.getText(`agents/${agent.id}/export`);
      const name = agent.name.normalize('NFD').replace(/\p{Diacritic}/gu, '').replace(/[^A-Za-z0-9]+/g, '-').replace(/^-|-$/g, '').toLowerCase() || 'agente';
      const saved = await this.desktop.saveText(`${name}.md`, md, ['md'], 'text/markdown');
      if (saved) this.toasts.show('Definição exportada.', 'ok');
    } catch (e) {
      this.toasts.error(e);
    }
  }

  protected async remove(): Promise<void> {
    const agent = this.current();
    if (!agent) return;
    const ok = await this.ui.confirm({
      title: `Excluir ${agent.name}?`,
      body: 'Se o agente tiver sessões ou histórico, ele é arquivado para preservar o registro. Agentes que ainda compõem uma squad ativa não podem sair: troque-os na squad antes.',
      confirm: 'Excluir',
      danger: true,
    });
    if (!ok) return;
    try {
      const result = await this.catalog.deleteAgent(agent.id);
      this.toasts.show(result === 'archived' ? 'Agente arquivado (tem histórico).' : 'Agente excluído.', 'ok');
      this.ui.closeDrawer();
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    }
  }
}
