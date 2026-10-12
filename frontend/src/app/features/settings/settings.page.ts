import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { ThemeStore } from '../../state/theme.store';
import { Api, ApiError } from '../../core/api';
import { Desktop } from '../../core/desktop';
import { dateTime, duration, usd } from '../../core/format';
import type { ConnectionTestResult } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { Icon } from '../../shell/icon';
import { Nav } from '../../shell/nav';
import { CatalogStore } from '../../state/catalog.store';
import { BackupSection } from './backup-section';

@Component({
  selector: 'sd-settings-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, Icon, BackupSection],
  styles: `
    :host { display: block; height: 100%; overflow: auto; }
    .wrap { max-width: 920px; margin: 0 auto; padding: 24px 28px 48px; }
    section { margin-bottom: 18px; padding: 16px; }
    section > h2 { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; }
    .full { grid-column: 1 / -1; }
    dl { display: grid; grid-template-columns: 180px 1fr; gap: 6px 12px; margin: 0 0 12px; font-size: var(--fs-sm); }
    dt { color: var(--fg-3); }
    dd { margin: 0; overflow-wrap: anywhere; }
    .yes { color: var(--ok); }
    .no { color: var(--warn); }
    .caps { display: flex; flex-wrap: wrap; gap: 4px; }
    pre { max-height: 240px; overflow: auto; margin: 8px 0 0; padding: 8px; border-radius: var(--r-md); background: var(--bg-0); font-size: var(--fs-xs); white-space: pre-wrap; }
  `,
  template: `
    <div class="wrap">
      <h1 style="margin-bottom: 16px">Configurações</h1>

      <section class="card">
        <h2>Aparência</h2>
        <div class="field"><label for="appearance-theme">Tema</label>
          <select id="appearance-theme" class="input" (change)="themes.select($any($event.target).value)">
            @for (theme of themes.themes; track theme.id) { <option [value]="theme.id" [selected]="theme.id === themes.current().id">{{ theme.name }}</option> }
          </select><span class="hint">A escolha acompanha o workspace. O ícone da janela muda com o tema.</span>
        </div>
      </section>
      <section class="card">
        <h2><sd-icon name="plug" /> Claude Code</h2>
        @if (catalog.claude(); as c) {
          <dl class="selectable">
            <dt>Executável</dt><dd>@if (c.found) { <span class="yes">encontrado</span> ({{ c.source }}): <code>{{ c.executable }}</code> } @else { <span class="no">não encontrado</span> }</dd>
            <dt>Responde</dt><dd>@if (c.usable) { <span class="yes">sim</span>, versão {{ c.version }} } @else { <span class="no">não</span>{{ c.problem ? ': ' + c.problem : '' }} }</dd>
            <dt>Conta</dt><dd>
              @if (c.authenticated === true) { <span class="yes">conectada</span> ({{ c.authMethod }}) }
              @else if (c.authenticated === false) { <span class="no">sem login</span>: rode “claude” num terminal e entre na conta }
              @else { <span class="no">não confirmado</span>: use “Testar conexão” }
            </dd>
            <dt>Recursos detectados</dt><dd class="caps">
              @for (cap of caps(); track cap.label) { <span class="chip" [style.color]="cap.on ? 'var(--ok)' : 'var(--fg-4)'">{{ cap.on ? '✓' : '–' }} {{ cap.label }}</span> }
            </dd>
            <dt>Verificado em</dt><dd>{{ dt(c.checkedAt) }}</dd>
          </dl>
          <div class="row">
            <button class="btn" (click)="refresh()" [disabled]="busy()"><sd-icon name="refresh" [size]="13" /> Verificar de novo</button>
            <button class="btn" (click)="test()" [disabled]="busy() || !c.usable" title="Faz uma chamada mínima e real ao Claude Code (consome uma fração pequena da cota)">
              <sd-icon name="send" [size]="13" /> Testar conexão
            </button>
            @if (busy()) { <sd-icon name="spinner" class="spin" [size]="14" /> }
          </div>
          @if (testResult(); as t) {
            <div class="banner" [class.info]="t.ok" [class.danger]="!t.ok" style="margin-top: 10px">
              <sd-icon [name]="t.ok ? 'check' : 'alert'" [size]="14" />
              <span>{{ t.message }} @if (t.ok) { ({{ t.model ?? 'modelo padrão' }}, {{ dur(t.elapsedMs) }}{{ t.costUsd != null ? ', ' + money(t.costUsd) + ' estimado' : '' }}) }</span>
            </div>
          }
        }
      </section>

      <form class="card" [formGroup]="form" (ngSubmit)="save()">
        <section style="margin: 0">
          <h2><sd-icon name="gear" /> Execução</h2>
          <div class="grid">
            <div class="field full">
              <label for="st-path">Caminho do Claude Code</label>
              <input id="st-path" class="input mono" formControlName="claudePath" placeholder="detecção automática (PATH e locais conhecidos)" />
              <span class="hint">Deixe vazio para detectar. No Windows, aceite o claude.exe do instalador nativo ou o claude.cmd do npm.</span>
            </div>
            <div class="field">
              <label for="st-model">Modelo padrão</label>
              <input id="st-model" class="input mono" formControlName="defaultModel" list="st-models" placeholder="padrão do Claude Code" />
              <datalist id="st-models">@for (m of catalog.options()?.modelAliases ?? []; track m) { <option [value]="m"></option> }</datalist>
            </div>
            <div class="field">
              <label for="st-conc">Execuções simultâneas</label>
              <input id="st-conc" class="input" type="number" min="1" max="8" formControlName="maxConcurrentRuns" />
              <span class="hint">Execuções que alteram a mesma pasta nunca rodam ao mesmo tempo.</span>
            </div>
            <div class="field">
              <label for="st-timeout">Tempo máximo por execução (minutos)</label>
              <input id="st-timeout" class="input" type="number" min="1" max="720" formControlName="runTimeoutMinutes" />
            </div>
            <div class="field">
              <label for="st-budget">Orçamento por execução (US$)</label>
              <input id="st-budget" class="input" type="number" step="0.01" min="0" formControlName="maxBudgetUsd" placeholder="sem limite"
                [attr.disabled]="budgetSupported() ? null : true" />
              <span class="hint">{{ budgetSupported() ? 'Repassado como --max-budget-usd; a contagem é a estimativa do Claude Code.' : 'O Claude Code instalado não oferece --max-budget-usd.' }}</span>
            </div>
            <div class="field full">
              <label class="check"><input type="checkbox" formControlName="demoMode" /> Modo demonstração</label>
              <span class="hint">Novas execuções passam a ser simuladas de forma determinística, sem Claude Code e sem consumo. A barra superior mostra “Demonstração” enquanto ativo.</span>
            </div>
            <div class="field full">
              <label class="check"><input type="checkbox" formControlName="isolatedFeatures" /> Executar features em paralelo, isoladas</label>
              <span class="hint">Desligado (padrão), cada projeto tem um único escritor: uma feature por vez, direto na pasta. Ligado, em repositórios git com
                pelo menos um commit, cada feature trabalha em um git worktree com branch própria (squad/código), até o limite de execuções
                simultâneas; aprovar a entrega faz o merge na branch atual e conflitos são mostrados na revisão.</span>
            </div>
          </div>
          @if (error()) { <div class="banner danger" style="margin-top: 10px">{{ error() }}</div> }
          <div class="row" style="margin-top: 12px"><button class="btn primary" type="submit" [disabled]="form.pristine || saving()">Salvar</button></div>
        </section>
      </form>

      <section class="card" style="margin-top: 18px">
        <h2><sd-icon name="database" /> Dados locais</h2>
        @if (catalog.system(); as s) {
          <dl class="selectable">
            <dt>Pasta de dados</dt><dd><code>{{ s.dataDir }}</code></dd>
            <dt>Banco</dt><dd><code>{{ s.database }}</code></dd>
            <dt>Versão</dt><dd>Shiro {{ s.version }} · Java {{ s.javaVersion }} · {{ s.platform }}</dd>
          </dl>
          <p class="muted">O banco e as configurações ficam nesta pasta, separados das pastas de código dos projetos. Organização e histórico funcionam sem internet; as execuções de IA dependem do provedor.</p>
          <div class="row">
            @if (desktop.available) {
              <button class="btn" (click)="desktop.bridge?.openDataFolder()"><sd-icon name="folder-open" [size]="13" /> Abrir pasta de dados</button>
              <button class="btn" (click)="loadLog()"><sd-icon name="terminal" [size]="13" /> Ver log do serviço</button>
            }
          </div>
          @if (log()) { <pre class="selectable">{{ log() }}</pre> }
        }
      </section>

      <sd-backup-section />

      <section class="card">
        <h2><sd-icon name="archive" /> Projetos arquivados</h2>
        @for (v of catalog.archivedProjects(); track v.project.id) {
          <div class="row" style="padding: 4px 0">
            <span class="swatch" [style.--swatch]="v.project.color"></span><span class="grow">{{ v.project.name }}</span>
            <button class="btn sm" (click)="unarchive(v.project.id)">Desarquivar</button>
          </div>
        } @empty { <p class="muted">Nenhum projeto arquivado.</p> }
      </section>

      <section class="card">
        <h2><sd-icon name="info" /> Atalhos</h2>
        <dl>
          <dt><kbd>Ctrl K</kbd></dt><dd>Paleta de comandos</dd>
          <dt><kbd>Ctrl B</kbd></dt><dd>Mostrar ou ocultar a barra lateral</dd>
          <dt><kbd>Ctrl Tab</kbd></dt><dd>Próxima aba (Shift volta)</dd>
          <dt><kbd>Ctrl 1</kbd>…<kbd>4</kbd></dt><dd>Foco no painel 1 a 4</dd>
          <dt><kbd>Ctrl Shift Enter</kbd></dt><dd>Maximizar ou restaurar o painel em foco</dd>
          <dt><kbd>Ctrl Shift N</kbd></dt><dd>Nova sessão no projeto</dd>
          <dt><kbd>Ctrl Shift D</kbd></dt><dd>Documentos do projeto</dd>
          <dt><kbd>Ctrl Shift W</kbd></dt><dd>Fechar a aba do projeto (as execuções continuam)</dd>
          <dt><kbd>Ctrl ,</kbd></dt><dd>Configurações</dd>
        </dl>
      </section>
    </div>
  `,
})
export class SettingsPage implements OnInit {
  protected readonly themes = inject(ThemeStore);
  protected readonly catalog = inject(CatalogStore);
  protected readonly desktop = inject(Desktop);
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly nav = inject(Nav);
  private readonly fb = inject(FormBuilder);

  protected readonly busy = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly log = signal('');
  protected readonly testResult = signal<ConnectionTestResult | null>(null);
  protected readonly dt = dateTime;
  protected readonly dur = duration;
  protected readonly money = usd;

  protected readonly form = this.fb.group({
    claudePath: this.fb.control<string | null>(null),
    defaultModel: this.fb.control<string | null>(null),
    maxConcurrentRuns: this.fb.nonNullable.control(2, [Validators.min(1), Validators.max(8)]),
    runTimeoutMinutes: this.fb.nonNullable.control(30, [Validators.min(1), Validators.max(720)]),
    maxBudgetUsd: this.fb.control<number | null>(null),
    demoMode: this.fb.nonNullable.control(false),
    isolatedFeatures: this.fb.nonNullable.control(false),
  });

  protected readonly budgetSupported = computed(() => this.catalog.claude()?.capabilities?.maxBudget === true);
  protected readonly caps = computed(() => {
    const c = this.catalog.claude()?.capabilities;
    if (!c) return [];
    return [
      { label: 'stream-json', on: c.streamJson }, { label: 'texto parcial', on: c.partialMessages },
      { label: '--permission-prompts', on: c.permissionPrompts }, { label: '--effort', on: c.effort },
      { label: '--max-budget-usd', on: c.maxBudget }, { label: '--session-id', on: c.sessionId }, { label: '--resume', on: c.resume },
      { label: '--tools', on: c.toolsFlag }, { label: '--append-system-prompt-file', on: c.appendSystemPromptFile },
    ];
  });

  ngOnInit(): void {
    const s = this.catalog.settings();
    if (s) this.form.reset({ ...s });
    void this.catalog.loadArchived();
  }

  protected async save(): Promise<void> {
    if (this.form.invalid) {
      this.error.set('Confira os valores: simultâneas entre 1 e 8, tempo entre 1 e 720 minutos.');
      return;
    }
    this.saving.set(true);
    this.error.set('');
    try {
      const v = this.form.getRawValue();
      const saved = await this.catalog.saveSettings({
        ...v, claudePath: v.claudePath?.trim() || null, defaultModel: v.defaultModel?.trim() || null,
        maxBudgetUsd: v.maxBudgetUsd == null || (v.maxBudgetUsd as unknown) === '' ? null : Number(v.maxBudgetUsd),
      });
      this.form.reset({ ...saved });
      this.toasts.show('Configurações salvas.', 'ok');
      await this.refresh();
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    } finally {
      this.saving.set(false);
    }
  }

  protected async refresh(): Promise<void> {
    this.busy.set(true);
    try {
      await this.catalog.refreshClaude();
    } catch (e) {
      this.toasts.error(e);
    } finally {
      this.busy.set(false);
    }
  }

  protected async test(): Promise<void> {
    this.busy.set(true);
    this.testResult.set(null);
    try {
      this.testResult.set(await this.api.post<ConnectionTestResult>('system/claude/test'));
      await this.catalog.refreshClaude();
    } catch (e) {
      this.toasts.error(e);
    } finally {
      this.busy.set(false);
    }
  }

  protected async loadLog(): Promise<void> {
    try {
      this.log.set((await this.desktop.bridge!.backendLog()).tail || '(log vazio)');
    } catch {
      this.log.set('Não foi possível ler o log.');
    }
  }

  protected async unarchive(id: string): Promise<void> {
    try {
      await this.catalog.archiveProject(id, false);
      await this.nav.openProject(id);
    } catch (e) {
      this.toasts.error(e);
    }
  }

}
