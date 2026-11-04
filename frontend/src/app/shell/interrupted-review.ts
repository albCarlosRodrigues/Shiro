import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { Api } from '../core/api';
import { dateTime } from '../core/format';
import { Toasts } from '../core/toasts';
import { WorkspaceStore } from '../state/workspace.store';
import { Icon } from './icon';

export interface InterruptedItem {
  runId: string;
  sessionId: string;
  sessionTitle: string;
  projectId: string;
  projectName: string;
  agentName: string | null;
  kind: string;
  access: string;
  prompt: string;
  cwd: string | null;
  interruptedAt: string;
  demo: boolean;
}

interface FolderChange {
  path: string;
  status: string;
  additions: number | null;
  deletions: number | null;
}

interface FolderChanges {
  cwd: string | null;
  exists: boolean;
  repository: boolean;
  changes: FolderChange[];
  truncated: boolean;
  note: string | null;
}

interface Review {
  dismissed: string[];
}

const UI_STATE_KEY = 'interrupted-review';

/** Mensagem do botão Continuar: retoma a conversa, mas pede ao agente que confira a pasta antes. */
export const CONTINUE_MESSAGE =
  'Continue a tarefa anterior, que foi interrompida quando o Shiro fechou. Antes de seguir, confira o estado atual da pasta: '
  + 'pode haver arquivos alterados pela metade. Diga o que encontrou e o que falta fazer.';

/** O que ainda precisa de revisão: interrompidas que a pessoa não dispensou nem continuou. */
export function pendingItems(items: InterruptedItem[], dismissed: string[]): InterruptedItem[] {
  const skip = new Set(dismissed);
  return items.filter(item => !skip.has(item.runId));
}

/** Modo da mensagem de continuação: execução continua como execução; o resto, como conversa. */
export function continueMode(item: InterruptedItem): 'EXECUTION' | 'CHAT' {
  return item.kind === 'EXECUTION' ? 'EXECUTION' : 'CHAT';
}

function clip(text: string, max = 180): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  return flat.length > max ? flat.slice(0, max - 1) + '…' : flat;
}

/**
 * Ao abrir o app: lista o que ficou pela metade quando ele fechou, com as alterações da pasta e um botão
 * Continuar por item. Nada é retomado sozinho; dispensar ou continuar tira o item da lista.
 */
@Component({
  selector: 'sd-interrupted-review',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  styles: `
    .dialog.review { top: 7vh; width: min(760px, 94vw); max-height: 86vh; display: flex; flex-direction: column; }
    .dialog.review > header p { margin: 4px 0 0; }
    .dialog.review > .body { overflow: auto; min-height: 0; }
    .dialog.review > footer { justify-content: space-between; }
    .item { border-top: 1px solid var(--line-1); padding: 12px 0; }
    .item:first-child { border-top: 0; }
    .meta { font-size: var(--fs-sm); }
    .prompt { margin: 6px 0; font-size: var(--fs-sm); }
    .changes { margin: 8px 0 0; padding: 8px; border-radius: var(--r-md); background: var(--bg-1); font-size: var(--fs-sm); }
    .change { display: flex; gap: 8px; align-items: center; padding: 2px 0; }
    .change button { padding: 0; border: 0; background: none; color: inherit; text-align: left; cursor: pointer; text-decoration: underline; }
    .add { color: var(--st-ok, #4caf50); } .del { color: var(--st-err, #e57373); }
    pre { margin: 6px 0 0; max-height: 260px; overflow: auto; padding: 8px; border-radius: var(--r-md); background: var(--bg-0); font-size: var(--fs-xs); white-space: pre; }
    .actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; }
  `,
  template: `
    @if (open() && items().length) {
      <div class="scrim dialog-scrim" (click)="later()"></div>
      <div class="dialog review" role="dialog" aria-modal="true" aria-labelledby="ir-title" (keydown.escape)="later()">
        <header>
          <h2 id="ir-title" class="row"><sd-icon name="history" /> Ficou pela metade quando o Shiro fechou</h2>
          <p class="muted">Nada foi retomado sozinho. Confira as alterações na pasta e escolha, para cada item, continuar ou dispensar.</p>
        </header>
        <div class="body">
          @for (item of items(); track item.runId) {
            <div class="item">
              <div class="meta"><b>{{ item.projectName }}</b> · {{ item.sessionTitle }}@if (item.agentName) { · {{ item.agentName }} }
                <span class="muted"> · {{ when(item.interruptedAt) }}</span>
                @if (item.demo) { <span class="muted"> · demonstração</span> }</div>
              <div class="prompt dim">{{ short(item.prompt) }}</div>
              @if (changes()[item.runId]; as c) {
                <div class="changes">
                  @if (c.note) { <div class="muted">{{ c.note }}</div> }
                  @if (c.changes.length) {
                    <div class="muted">Alterações na pasta desde o último commit (podem incluir mudanças de antes da execução):</div>
                    @for (f of c.changes; track f.path) {
                      <div class="change">
                        <code>{{ f.status }}</code>
                        <button type="button" (click)="toggleDiff(item, f.path)">{{ f.path }}</button>
                        @if (f.additions !== null) { <span class="add">+{{ f.additions }}</span> }
                        @if (f.deletions !== null) { <span class="del">−{{ f.deletions }}</span> }
                      </div>
                      @if (diffs()[item.runId + '|' + f.path]; as d) { <pre class="selectable">{{ d }}</pre> }
                    }
                    @if (c.truncated) { <div class="muted">Lista cortada nos primeiros 200 arquivos.</div> }
                  }
                </div>
              }
              <div class="actions">
                @if (!changes()[item.runId]) {
                  <button class="btn" (click)="loadChanges(item)" [disabled]="busy() === item.runId"><sd-icon name="eye" [size]="13" /> Ver alterações na pasta</button>
                }
                <button class="btn primary" (click)="continueRun(item)" [disabled]="busy() === item.runId"><sd-icon name="send" [size]="13" /> Continuar</button>
                <button class="btn" (click)="openConversation(item)"><sd-icon name="chat" [size]="13" /> Abrir conversa</button>
                <button class="btn" (click)="dismiss(item)"><sd-icon name="x" [size]="13" /> Dispensar</button>
              </div>
            </div>
          }
        </div>
        <footer>
          <button class="btn" (click)="dismissAll()">Dispensar todos</button>
          <button class="btn" (click)="later()">Depois</button>
        </footer>
      </div>
    }
  `,
})
export class InterruptedReview implements OnInit {
  private readonly api = inject(Api);
  private readonly toasts = inject(Toasts);
  private readonly workspace = inject(WorkspaceStore);

  readonly items = signal<InterruptedItem[]>([]);
  readonly open = signal(false);
  readonly busy = signal<string | null>(null);
  readonly changes = signal<Record<string, FolderChanges>>({});
  readonly diffs = signal<Record<string, string>>({});
  private dismissed: string[] = [];

  async ngOnInit(): Promise<void> {
    try {
      const [items, review] = await Promise.all([
        this.api.get<InterruptedItem[]>('/api/interrupted-runs'),
        this.api.getOptional<Review>(`/api/ui-state/${UI_STATE_KEY}`),
      ]);
      this.dismissed = Array.isArray(review?.dismissed) ? review!.dismissed : [];
      const pending = pendingItems(items, this.dismissed);
      this.items.set(pending);
      this.open.set(pending.length > 0);
    } catch {
      // serviço antigo ou indisponível: nada a revisar
    }
  }

  when(at: string): string {
    return dateTime(at);
  }

  short(text: string): string {
    return clip(text);
  }

  async loadChanges(item: InterruptedItem): Promise<void> {
    this.busy.set(item.runId);
    try {
      const c = await this.api.get<FolderChanges>(`/api/interrupted-runs/${item.runId}/changes`);
      this.changes.update(all => ({ ...all, [item.runId]: c }));
    } catch (e) {
      this.toasts.error(e);
    } finally {
      this.busy.set(null);
    }
  }

  async toggleDiff(item: InterruptedItem, path: string): Promise<void> {
    const key = item.runId + '|' + path;
    if (this.diffs()[key] !== undefined) {
      this.diffs.update(all => {
        const { [key]: _removed, ...rest } = all;
        return rest;
      });
      return;
    }
    try {
      const r = await this.api.get<{ diff: string }>(`/api/interrupted-runs/${item.runId}/diff`, { path });
      this.diffs.update(all => ({ ...all, [key]: r.diff || '(sem diferença de texto)' }));
    } catch (e) {
      this.toasts.error(e);
    }
  }

  async continueRun(item: InterruptedItem): Promise<void> {
    this.busy.set(item.runId);
    try {
      await this.api.post(`/api/sessions/${item.sessionId}/messages`, { content: CONTINUE_MESSAGE, mode: continueMode(item) });
      await this.remove(item);
      this.openConversation(item);
      this.toasts.show('Pedido de continuação enviado ao agente.', 'ok');
    } catch (e) {
      this.toasts.error(e);
    } finally {
      this.busy.set(null);
    }
  }

  openConversation(item: InterruptedItem): void {
    this.workspace.openTab(item.projectId);
    this.workspace.openPane(item.projectId, item.sessionId);
    location.hash = `/projeto/${item.projectId}`;
    this.open.set(false);
  }

  async dismiss(item: InterruptedItem): Promise<void> {
    await this.remove(item);
  }

  async dismissAll(): Promise<void> {
    const ids = this.items().map(i => i.runId);
    await this.save([...this.dismissed, ...ids]);
    this.items.set([]);
    this.open.set(false);
  }

  /** Fecha por agora; a lista volta na próxima abertura do app. */
  later(): void {
    this.open.set(false);
  }

  private async remove(item: InterruptedItem): Promise<void> {
    await this.save([...this.dismissed, item.runId]);
    this.items.update(list => list.filter(i => i.runId !== item.runId));
    if (!this.items().length) this.open.set(false);
  }

  private async save(dismissed: string[]): Promise<void> {
    // guarda só os 200 mais recentes: a lista do serviço cobre 14 dias
    this.dismissed = dismissed.slice(-200);
    try {
      await this.api.putJson(`/api/ui-state/${UI_STATE_KEY}`, JSON.stringify({ dismissed: this.dismissed }));
    } catch (e) {
      this.toasts.error(e);
    }
  }
}
