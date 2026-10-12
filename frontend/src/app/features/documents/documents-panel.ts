import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';

import { Api, ApiError } from '../../core/api';
import { DOCUMENT_LABEL, dateTime } from '../../core/format';
import { MarkdownView } from '../../core/markdown';
import type { Document, DocumentKind, DocumentVersion } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { Icon } from '../../shell/icon';
import { CatalogStore } from '../../state/catalog.store';
import { UiStore } from '../../state/ui.store';

interface Conflict {
  currentVersion: number;
  currentContent: string;
  currentTitle: string;
}

/**
 * Documentos do projeto: briefing, visão, escopo, glossário, arquitetura, decisões (ADR) e livres.
 * Cada gravação informa a versão de base; se o documento mudou no meio tempo (outra edição ou uma proposta
 * aplicada), a gravação é recusada e as duas versões ficam visíveis para a pessoa decidir.
 */
@Component({
  selector: 'sd-documents-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, MarkdownView],
  styles: `
    :host { display: flex; flex-direction: column; flex: 1 1 auto; min-height: 0; }
    .picker { display: flex; gap: 6px; padding: 8px; border-bottom: 1px solid var(--line-1); }
    .editor { display: flex; flex-direction: column; flex: 1 1 auto; min-height: 0; padding: 8px; gap: 8px; }
    .meta { display: flex; align-items: center; gap: 6px; color: var(--fg-3); font-size: var(--fs-xs); }
    textarea { flex: 1 1 auto; min-height: 160px; resize: none; }
    .preview { flex: 1 1 auto; overflow: auto; padding: 8px 10px; border: 1px solid var(--line-1); border-radius: var(--r-md); background: var(--bg-1); }
    .versions { max-height: 220px; overflow: auto; border-top: 1px solid var(--line-1); padding: 6px 8px; }
    .version { display: flex; align-items: center; gap: 8px; padding: 4px 0; font-size: var(--fs-xs); color: var(--fg-2); }
    .conflict { display: flex; flex-direction: column; gap: 6px; }
    .conflict pre { max-height: 140px; overflow: auto; margin: 0; padding: 6px; border-radius: var(--r-sm); background: var(--bg-0); font-size: var(--fs-xs); white-space: pre-wrap; }
    .empty-doc { color: var(--fg-3); font-size: var(--fs-sm); }
  `,
  template: `
    <div class="picker">
      <select class="select" [value]="selectedId() ?? ''" (change)="select($any($event.target).value)" aria-label="Documento">
        @for (d of documents(); track d.id) {
          <option [value]="d.id">{{ d.kind === 'ADR' || d.kind === 'FREE' ? d.title : label[d.kind] }}{{ d.content.trim() ? '' : ' (vazio)' }}</option>
        }
      </select>
      <button class="btn icon" (click)="newDocument('ADR')" title="Nova decisão arquitetural" aria-label="Nova decisão arquitetural"><sd-icon name="architecture" [size]="13" /></button>
      <button class="btn icon" (click)="newDocument('FREE')" title="Novo documento livre" aria-label="Novo documento livre"><sd-icon name="plus" [size]="13" /></button>
    </div>
    @if (selected(); as doc) {
      <div class="editor">
        <div class="meta">
          <span class="grow">Versão {{ doc.version }} · {{ author(doc) }} · {{ dt(doc.updatedAt) }}</span>
          @if (dirty()) { <span style="color: var(--warn)">não salvo</span> }
          <div class="seg" role="tablist">
            <button role="tab" [attr.aria-selected]="!preview()" (click)="preview.set(false)">Editar</button>
            <button role="tab" [attr.aria-selected]="preview()" (click)="preview.set(true)">Visualizar</button>
          </div>
        </div>
        @if (doc.kind === 'ADR' || doc.kind === 'FREE') {
          <input class="input" [value]="titleDraft()" (input)="titleDraft.set($any($event.target).value)" aria-label="Título do documento" maxlength="200" />
        }
        @if (conflict(); as c) {
          <div class="banner warn conflict" role="alert">
            <b>O documento mudou enquanto você editava (agora está na versão {{ c.currentVersion }}).</b>
            <span>Sua edição não foi perdida. Escolha o que manter:</span>
            <pre>{{ c.currentContent || '(vazio)' }}</pre>
            <div class="row">
              <button class="btn sm" (click)="keepMine(c)">Salvar a minha por cima</button>
              <button class="btn sm" (click)="takeTheirs(c)">Usar a versão atual</button>
            </div>
          </div>
        }
        @if (preview()) {
          <div class="preview">@if (draft().trim()) { <sd-markdown [source]="draft()" /> } @else { <span class="empty-doc">Documento vazio.</span> }</div>
        } @else {
          <textarea class="textarea mono" [value]="draft()" (input)="draft.set($any($event.target).value)" [placeholder]="placeholder(doc.kind)"
            (keydown.control.s)="$event.preventDefault(); save()" aria-label="Conteúdo em Markdown"></textarea>
        }
        <div class="row">
          <button class="btn primary sm" [disabled]="!dirty() || saving()" (click)="save()"><sd-icon name="check" [size]="12" /> Salvar</button>
          <button class="btn sm" [disabled]="!dirty()" (click)="discard()">Descartar</button>
          <span class="spacer"></span>
          <button class="btn ghost sm" (click)="toggleVersions()"><sd-icon name="history" [size]="12" /> Versões</button>
          @if (doc.kind === 'ADR' || doc.kind === 'FREE') {
            <button class="btn ghost sm danger" (click)="remove(doc)" aria-label="Excluir documento"><sd-icon name="trash" [size]="12" /></button>
          }
        </div>
      </div>
      @if (versions(); as list) {
        <div class="versions" aria-label="Versões anteriores">
          @for (v of list; track v.version) {
            <div class="version">
              <span class="grow">v{{ v.version }} · {{ v.authorType === 'AGENT' ? agentName(v.authorId) : 'Você' }} · {{ dt(v.createdAt) }}{{ v.note ? ' · ' + v.note : '' }}</span>
              <button class="btn ghost sm" (click)="restore(v)">Restaurar</button>
            </div>
          } @empty { <span class="empty-doc">Ainda não há versões anteriores.</span> }
        </div>
      }
    } @else {
      <div class="empty">Carregando documentos…</div>
    }
  `,
})
export class DocumentsPanel {
  readonly projectId = input.required<string>();

  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly ui = inject(UiStore);
  private readonly catalog = inject(CatalogStore);

  protected readonly label = DOCUMENT_LABEL;
  protected readonly dt = dateTime;
  protected readonly documents = signal<Document[]>([]);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly draft = signal('');
  protected readonly titleDraft = signal('');
  protected readonly preview = signal(false);
  protected readonly saving = signal(false);
  protected readonly conflict = signal<Conflict | null>(null);
  protected readonly versions = signal<DocumentVersion[] | null>(null);

  protected readonly selected = computed(() => this.documents().find(d => d.id === this.selectedId()) ?? null);
  protected readonly dirty = computed(() => {
    const doc = this.selected();
    return !!doc && (doc.content !== this.draft() || ((doc.kind === 'ADR' || doc.kind === 'FREE') && doc.title !== this.titleDraft()));
  });

  constructor() {
    effect(() => {
      const id = this.projectId();
      untracked(() => void this.load(id));
    });
  }

  private async load(projectId: string, keep?: string): Promise<void> {
    try {
      const docs = await this.api.get<Document[]>(`projects/${projectId}/documents`);
      this.documents.set(docs);
      const target = keep ?? this.selectedId();
      this.open(docs.find(d => d.id === target) ?? docs[0] ?? null);
    } catch (e) {
      this.toasts.error(e, 'Documentos');
    }
  }

  private open(doc: Document | null): void {
    this.selectedId.set(doc?.id ?? null);
    this.draft.set(doc?.content ?? '');
    this.titleDraft.set(doc?.title ?? '');
    this.conflict.set(null);
    this.versions.set(null);
  }

  protected async select(id: string): Promise<void> {
    if (this.dirty()) {
      const ok = await this.ui.confirm({ title: 'Descartar alterações?', body: 'O documento atual tem alterações não salvas.', confirm: 'Descartar', danger: true });
      if (!ok) return;
    }
    this.open(this.documents().find(d => d.id === id) ?? null);
  }

  protected async save(base?: number): Promise<void> {
    const doc = this.selected();
    if (!doc || this.saving()) return;
    this.saving.set(true);
    try {
      const saved = await this.api.put<Document>(`documents/${doc.id}`, {
        title: this.titleDraft(), content: this.draft(), baseVersion: base ?? doc.version,
      });
      this.replace(saved);
      this.conflict.set(null);
      if (this.versions()) void this.toggleVersions(true);
      this.toasts.show('Documento salvo.', 'ok', 1800);
    } catch (e) {
      const err = ApiError.from(e);
      if (err.code === 'document_conflict') {
        this.conflict.set(err.details as unknown as Conflict);
      } else {
        this.toasts.error(err);
      }
    } finally {
      this.saving.set(false);
    }
  }

  protected keepMine(c: Conflict): void {
    void this.save(c.currentVersion);
  }

  protected async takeTheirs(c: Conflict): Promise<void> {
    await this.load(this.projectId(), this.selectedId() ?? undefined);
    this.draft.set(c.currentContent);
  }

  protected discard(): void {
    this.open(this.selected());
  }

  protected async toggleVersions(force = false): Promise<void> {
    const doc = this.selected();
    if (!doc) return;
    if (this.versions() && !force) {
      this.versions.set(null);
      return;
    }
    this.versions.set(await this.api.get<DocumentVersion[]>(`documents/${doc.id}/versions`));
  }

  protected async restore(v: DocumentVersion): Promise<void> {
    const doc = this.selected();
    if (!doc) return;
    try {
      const restored = await this.api.post<Document>(`documents/${doc.id}/restore`, { version: v.version, baseVersion: doc.version });
      this.replace(restored);
      void this.toggleVersions(true);
      this.toasts.show(`Versão ${v.version} restaurada como versão ${restored.version}.`, 'ok');
    } catch (e) {
      this.toasts.error(e);
    }
  }

  protected async newDocument(kind: DocumentKind): Promise<void> {
    const title = await this.ui.prompt({
      title: kind === 'ADR' ? 'Nova decisão arquitetural' : 'Novo documento',
      label: 'Título', value: kind === 'ADR' ? 'ADR: ' : '', confirm: 'Criar',
    });
    if (!title?.trim()) return;
    try {
      const doc = await this.api.post<Document>(`projects/${this.projectId()}/documents`, {
        kind, title: title.trim(), content: kind === 'ADR' ? ADR_TEMPLATE : '',
      });
      this.documents.update(list => [...list, doc]);
      this.open(doc);
    } catch (e) {
      this.toasts.error(e);
    }
  }

  protected async remove(doc: Document): Promise<void> {
    const ok = await this.ui.confirm({ title: 'Excluir documento?', body: `“${doc.title}” e as versões anteriores serão removidos.`, confirm: 'Excluir', danger: true });
    if (!ok) return;
    try {
      await this.api.delete(`documents/${doc.id}`);
      this.documents.update(list => list.filter(d => d.id !== doc.id));
      this.open(this.documents()[0] ?? null);
    } catch (e) {
      this.toasts.error(e);
    }
  }

  protected author(doc: Document): string {
    return doc.updatedByType === 'AGENT' ? `proposta de ${this.agentName(doc.updatedById)}` : doc.version === 1 && !doc.content ? 'ainda vazio' : 'editado por você';
  }

  protected agentName(id: string | null): string {
    return (id && this.catalog.agentById().get(id)?.name) || 'agente';
  }

  protected placeholder(kind: DocumentKind): string {
    switch (kind) {
      case 'BRIEFING': return 'Qual problema o projeto resolve, para quem, e o que já se sabe. Markdown.';
      case 'VISION': return 'Visão do produto: resultado esperado, público, diferenciais.';
      case 'SCOPE_IN': return '- O que entra nesta entrega';
      case 'SCOPE_OUT': return '- O que fica de fora (e por quê)';
      case 'GLOSSARY': return '**Termo**: definição';
      case 'ARCHITECTURE': return 'Visão de arquitetura: componentes, integrações, dados, restrições.';
      default: return 'Markdown';
    }
  }

  private replace(doc: Document): void {
    this.documents.update(list => list.map(d => (d.id === doc.id ? doc : d)));
    this.draft.set(doc.content);
    this.titleDraft.set(doc.title);
  }
}

const ADR_TEMPLATE = `## Contexto

## Decisão

## Consequências
`;
