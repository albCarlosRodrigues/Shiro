import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { ApiError } from '../../core/api';
import { Desktop } from '../../core/desktop';
import { Toasts } from '../../core/toasts';
import { Icon } from '../../shell/icon';
import { Nav } from '../../shell/nav';
import { CatalogStore } from '../../state/catalog.store';
import { UiStore } from '../../state/ui.store';

const PALETTE = ['#8fa2ff', '#4cc2b0', '#e9b44c', '#ef7b6c', '#c58af9', '#6fb3e8', '#8ccf7e', '#e58fb6'];

@Component({
  selector: 'sd-project-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, Icon],
  styles: `
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
    .full { grid-column: 1 / -1; }
    .colors { display: flex; gap: 6px; flex-wrap: wrap; }
    .color { width: 24px; height: 24px; border: 2px solid transparent; border-radius: 6px; background: var(--c); cursor: pointer; }
    .color[aria-checked='true'] { border-color: var(--fg-1); }
    .folder { display: flex; gap: 6px; }
    .note { display: flex; gap: 8px; color: var(--fg-3); font-size: var(--fs-sm); }
  `,
  template: `
    <form class="drawer narrow" role="dialog" aria-modal="true" aria-labelledby="pf-title" [formGroup]="form" (ngSubmit)="save()" (keydown.escape)="ui.closeDrawer()">
      <header>
        <sd-icon name="folder" />
        <h2 id="pf-title" class="grow">{{ projectId() ? 'Editar projeto' : 'Novo projeto' }}</h2>
        <button type="button" class="btn ghost icon" (click)="ui.closeDrawer()" aria-label="Fechar"><sd-icon name="x" /></button>
      </header>
      <div class="body">
        <div class="grid">
          <div class="field full">
            <label for="pf-name">Nome</label>
            <input id="pf-name" class="input" formControlName="name" maxlength="120" autocomplete="off" placeholder="Ex.: Portal do cliente" />
            @if (form.controls.name.touched && form.controls.name.invalid) { <span class="error">Informe o nome do projeto.</span> }
          </div>

          <div class="field full">
            <label for="pf-folder">Pasta do código</label>
            <div class="folder">
              <input id="pf-folder" class="input mono" formControlName="folderPath" placeholder="C:\\projetos\\portal" spellcheck="false" />
              @if (desktop.available) { <button type="button" class="btn" (click)="pickFolder()"><sd-icon name="folder-open" [size]="14" /> Escolher…</button> }
            </div>
            <span class="hint">Onde os agentes leem e escrevem código. Pode ficar vazio para só conversar e planejar; para executar tarefas, defina a pasta.</span>
          </div>

          <div class="field">
            <label for="pf-group">Grupo</label>
            <select id="pf-group" class="select" formControlName="groupId">
              <option [ngValue]="null">Sem grupo</option>
              @for (g of catalog.groups(); track g.id) { <option [ngValue]="g.id">{{ g.name }}</option> }
            </select>
          </div>

          <div class="field">
            <label for="pf-squad">Squad</label>
            <select id="pf-squad" class="select" formControlName="squadId">
              <option [ngValue]="null">Nenhuma</option>
              @for (s of catalog.squads(); track s.id) { <option [ngValue]="s.id">{{ s.name }}</option> }
            </select>
          </div>

          <div class="field full">
            <span class="label" id="pf-color">Cor</span>
            <div class="colors" role="radiogroup" aria-labelledby="pf-color">
              @for (c of palette; track c) {
                <button type="button" class="color" role="radio" [style.--c]="c" [attr.aria-checked]="form.controls.color.value === c"
                  [attr.aria-label]="'Cor ' + c" (click)="form.controls.color.setValue(c)"></button>
              }
            </div>
          </div>

          <div class="note full">
            <sd-icon name="lock" [size]="14" />
            <span>O banco e as configurações do Shiro ficam em {{ dataDir() }}, separados da pasta do projeto. Excluir o cadastro nunca apaga a pasta de código.</span>
          </div>
          @if (error()) { <div class="banner danger full"><sd-icon name="alert" [size]="14" />{{ error() }}</div> }
        </div>
      </div>
      <footer>
        @if (projectId() && current()?.archivedAt) {
          <button type="button" class="btn" (click)="unarchive()"><sd-icon name="archive" [size]="14" /> Desarquivar</button>
        }
        <span class="spacer"></span>
        <button type="button" class="btn" (click)="ui.closeDrawer()">Cancelar</button>
        <button type="submit" class="btn primary" [disabled]="saving()">{{ projectId() ? 'Salvar' : 'Criar projeto' }}</button>
      </footer>
    </form>
  `,
})
export class ProjectForm implements OnInit {
  readonly projectId = input<string | null>(null);
  readonly groupId = input<string | null>(null);

  protected readonly ui = inject(UiStore);
  protected readonly catalog = inject(CatalogStore);
  protected readonly desktop = inject(Desktop);
  private readonly toasts = inject(Toasts);
  private readonly nav = inject(Nav);
  private readonly fb = inject(FormBuilder);

  protected readonly palette = PALETTE;
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly current = computed(() => this.catalog.project(this.projectId()));
  protected readonly dataDir = computed(() => this.catalog.system()?.dataDir ?? 'a pasta de dados do aplicativo');

  protected readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    folderPath: [''],
    groupId: this.fb.control<string | null>(null),
    squadId: this.fb.control<string | null>(null),
    color: [PALETTE[0]],
  });

  ngOnInit(): void {
    const p = this.current();
    if (p) {
      this.form.setValue({ name: p.name, folderPath: p.folderPath ?? '', groupId: p.groupId, squadId: p.squadId, color: p.color });
    } else {
      const index = this.catalog.projects().length;
      this.form.patchValue({
        groupId: this.groupId(),
        squadId: this.catalog.squads()[0]?.id ?? null,
        color: PALETTE[index % PALETTE.length],
      });
    }
  }

  protected async pickFolder(): Promise<void> {
    const folder = await this.desktop.pickFolder('Pasta do projeto', this.form.controls.folderPath.value || undefined);
    if (folder) {
      this.form.controls.folderPath.setValue(folder);
      if (!this.form.controls.name.value) {
        const parts = folder.replace(/[\\/]+$/, '').split(/[\\/]/);
        this.form.controls.name.setValue(parts[parts.length - 1] ?? '');
      }
    }
  }

  protected async save(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    this.saving.set(true);
    this.error.set('');
    const value = this.form.getRawValue();
    const draft = { ...value, folderPath: value.folderPath.trim() || null };
    try {
      const id = this.projectId();
      if (id) {
        await this.catalog.updateProject(id, draft);
        this.toasts.show('Projeto salvo.', 'ok');
        this.ui.closeDrawer();
      } else {
        const view = await this.catalog.createProject(draft);
        this.ui.closeDrawer();
        await this.nav.openProject(view.project.id);
        if (!view.folderExists && view.project.folderPath) {
          this.toasts.show('A pasta informada ainda não existe. Crie-a antes de executar tarefas.', 'warn');
        }
      }
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    } finally {
      this.saving.set(false);
    }
  }

  protected async unarchive(): Promise<void> {
    const id = this.projectId();
    if (!id) return;
    try {
      await this.catalog.archiveProject(id, false);
      this.ui.closeDrawer();
      await this.nav.openProject(id);
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    }
  }
}
