import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';

import { ApiError } from '../../core/api';
import type { AgentRole } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { Icon } from '../../shell/icon';
import { CatalogStore } from '../../state/catalog.store';
import { UiStore } from '../../state/ui.store';
import { SquadTree } from './squad-tree';

/** Montagem de uma squad: um comandante, um especialista em requisitos, um em arquitetura e operadores. */
@Component({
  selector: 'sd-squad-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, Icon, SquadTree],
  styles: `
    .layout { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 18px; }
    .ops { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px 12px; }
    .preview { padding: 12px; border-radius: var(--r-md); background: var(--bg-1); align-self: start; }
    .col > .field { margin-bottom: 4px; }
  `,
  template: `
    <form class="drawer" role="dialog" aria-modal="true" aria-labelledby="sq-title" [formGroup]="form" (ngSubmit)="save()" (keydown.escape)="ui.closeDrawer()">
      <header>
        <sd-icon name="squad" />
        <h2 id="sq-title" class="grow">{{ squadId() ? 'Editar squad' : 'Nova squad' }}</h2>
        <button type="button" class="btn ghost icon" (click)="ui.closeDrawer()" aria-label="Fechar"><sd-icon name="x" /></button>
      </header>
      <div class="body">
        <div class="layout">
          <div class="col">
            <div class="field"><label for="sq-name">Nome</label><input id="sq-name" class="input" formControlName="name" maxlength="120" /></div>
            <div class="field"><label for="sq-desc">Descrição</label><textarea id="sq-desc" class="textarea" rows="2" formControlName="description"></textarea></div>
            <div class="field"><label for="sq-cmd">Comandante</label>
              <select id="sq-cmd" class="select" formControlName="commanderId">
                <option [ngValue]="null">Escolha…</option>
                @for (a of byRole('COMMANDER'); track a.id) { <option [ngValue]="a.id">{{ a.name }}</option> }
              </select>
            </div>
            <div class="field"><label for="sq-req">Requisitos e PRD</label>
              <select id="sq-req" class="select" formControlName="requirementsId">
                <option [ngValue]="null">Escolha…</option>
                @for (a of byRole('REQUIREMENTS'); track a.id) { <option [ngValue]="a.id">{{ a.name }}</option> }
              </select>
            </div>
            <div class="field"><label for="sq-arch">Arquitetura e ADR</label>
              <select id="sq-arch" class="select" formControlName="architectureId">
                <option [ngValue]="null">Escolha…</option>
                @for (a of byRole('ARCHITECTURE'); track a.id) { <option [ngValue]="a.id">{{ a.name }}</option> }
              </select>
            </div>
            <div class="field">
              <span class="label">Operadores</span>
              <div class="ops">
                @for (a of byRole('OPERATOR'); track a.id) {
                  <label class="check"><input type="checkbox" [checked]="hasOperator(a.id)" (change)="toggleOperator(a.id, $any($event.target).checked)" /> {{ a.name }}
                    <span class="muted">{{ a.specialty }}</span></label>
                } @empty { <span class="hint">Nenhum agente com papel de operador. Crie um no Estúdio do Agente.</span> }
              </div>
            </div>
            @if (missingRoles().length) {
              <div class="banner info"><sd-icon name="info" [size]="14" />Faltam agentes para: {{ missingRoles().join(', ') }}. Crie-os em Agentes (há modelos prontos).</div>
            }
            @if (error()) { <div class="banner danger"><sd-icon name="alert" [size]="14" />{{ error() }}</div> }
          </div>
          <div class="preview">
            <div class="section-title" style="margin-bottom: 8px">Composição</div>
            <sd-squad-tree [commanderId]="value().commanderId ?? null" [requirementsId]="value().requirementsId ?? null"
              [architectureId]="value().architectureId ?? null" [operatorIds]="value().operatorIds ?? []" />
            @if (usedBy().length) { <p class="muted" style="margin-top: 10px">Usada em: {{ usedBy().join(', ') }}</p> }
          </div>
        </div>
      </div>
      <footer>
        @if (squadId()) { <button type="button" class="btn danger" (click)="remove()"><sd-icon name="trash" [size]="12" /> Excluir…</button> }
        <span class="spacer"></span>
        <button type="button" class="btn" (click)="ui.closeDrawer()">Cancelar</button>
        <button type="submit" class="btn primary" [disabled]="saving()">{{ squadId() ? 'Salvar squad' : 'Criar squad' }}</button>
      </footer>
    </form>
  `,
})
export class SquadEditor implements OnInit {
  readonly squadId = input<string | null>(null);

  protected readonly ui = inject(UiStore);
  private readonly catalog = inject(CatalogStore);
  private readonly toasts = inject(Toasts);
  private readonly fb = inject(FormBuilder);

  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected readonly form = this.fb.group({
    name: this.fb.nonNullable.control('', [Validators.required]),
    description: this.fb.nonNullable.control(''),
    commanderId: this.fb.control<string | null>(null),
    requirementsId: this.fb.control<string | null>(null),
    architectureId: this.fb.control<string | null>(null),
    operatorIds: this.fb.nonNullable.control<string[]>([]),
  });
  protected readonly value = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });

  protected readonly usedBy = computed(() => {
    const id = this.squadId();
    return id ? this.catalog.projects().filter(v => v.project.squadId === id).map(v => v.project.name) : [];
  });

  protected readonly missingRoles = computed(() => {
    const labels: [AgentRole, string][] = [['COMMANDER', 'comandante'], ['REQUIREMENTS', 'requisitos'], ['ARCHITECTURE', 'arquitetura'], ['OPERATOR', 'operadores']];
    return labels.filter(([role]) => this.byRole(role).length === 0).map(([, l]) => l);
  });

  ngOnInit(): void {
    const squad = this.catalog.squadById().get(this.squadId() ?? '');
    if (squad) {
      this.form.setValue({ name: squad.name, description: squad.description, commanderId: squad.commanderId, requirementsId: squad.requirementsId,
        architectureId: squad.architectureId, operatorIds: [...squad.operatorIds] });
    } else {
      this.form.patchValue({
        commanderId: this.byRole('COMMANDER')[0]?.id ?? null,
        requirementsId: this.byRole('REQUIREMENTS')[0]?.id ?? null,
        architectureId: this.byRole('ARCHITECTURE')[0]?.id ?? null,
      });
    }
  }

  protected byRole(role: AgentRole) {
    return this.catalog.agents().filter(a => a.role === role && !a.archivedAt);
  }

  protected hasOperator(id: string): boolean {
    return this.form.controls.operatorIds.value.includes(id);
  }

  protected toggleOperator(id: string, on: boolean): void {
    const list = this.form.controls.operatorIds.value;
    this.form.controls.operatorIds.setValue(on ? [...list, id] : list.filter(x => x !== id));
  }

  protected async save(): Promise<void> {
    this.form.markAllAsTouched();
    this.error.set('');
    if (this.form.controls.name.invalid) {
      this.error.set('Dê um nome à squad.');
      return;
    }
    this.saving.set(true);
    try {
      const id = this.squadId();
      const draft = this.form.getRawValue();
      if (id) await this.catalog.updateSquad(id, draft);
      else await this.catalog.createSquad(draft);
      this.toasts.show('Squad salva.', 'ok');
      this.ui.closeDrawer();
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    } finally {
      this.saving.set(false);
    }
  }

  protected async remove(): Promise<void> {
    const id = this.squadId();
    if (!id) return;
    const used = this.usedBy();
    const ok = await this.ui.confirm({
      title: 'Excluir squad?',
      body: (used.length ? `Os projetos ${used.join(', ')} ficarão sem squad. ` : '') + 'Os agentes continuam cadastrados.',
      confirm: 'Excluir squad',
      danger: true,
    });
    if (!ok) return;
    try {
      await this.catalog.deleteSquad(id);
      await this.catalog.reloadProjects();
      this.ui.closeDrawer();
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    }
  }
}
