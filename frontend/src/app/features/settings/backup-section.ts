import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { Api, ApiError } from '../../core/api';
import { Desktop } from '../../core/desktop';
import { dateTime } from '../../core/format';
import { Toasts } from '../../core/toasts';
import { Icon } from '../../shell/icon';
import { UiStore } from '../../state/ui.store';

interface BackupEntry {
  name: string;
  path: string;
  reason: string;
  bytes: number;
  createdAt: string;
}

interface ImportCheck {
  ok: boolean;
  problems: string[];
  warnings: string[];
  counts: Record<string, number>;
  exportedAt: string | null;
  appVersion: string | null;
}

interface ImportResult {
  backup: BackupEntry;
  counts: Record<string, number>;
}

const COUNT_LABEL: Record<string, string> = {
  groups: 'grupos', projects: 'projetos', agents: 'agentes', squads: 'squads', documents: 'documentos',
  sessions: 'sessões', messages: 'mensagens', plans: 'planos', sprints: 'sprints', features: 'features',
};

/** Exportação e importação do workspace (com validação e backup automático antes de restaurar) e cópias do banco. */
@Component({
  selector: 'sd-backup-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  styles: `
    :host { display: block; margin-bottom: 18px; }
    section { padding: 16px; }
    h2 { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
    .list { margin-top: 12px; }
    .entry { display: flex; align-items: center; gap: 10px; padding: 5px 0; border-top: 1px solid var(--line-1); font-size: var(--fs-sm); }
    .entry code { font-size: var(--fs-xs); }
    ul { margin: 6px 0 0; padding-left: 18px; }
  `,
  template: `
    <section class="card">
      <h2><sd-icon name="download" /> Backup e transferência</h2>
      <p class="muted">A exportação gera um arquivo JSON com projetos, documentos, agentes, squads, planejamento e conversas.
        Ao importar, o arquivo é validado e o banco atual é copiado para a pasta de backups antes de qualquer alteração.</p>
      <div class="row">
        <button class="btn" (click)="exportWorkspace()" [disabled]="busy()"><sd-icon name="download" [size]="13" /> Exportar workspace…</button>
        <button class="btn" (click)="importWorkspace()" [disabled]="busy()"><sd-icon name="upload" [size]="13" /> Importar e substituir…</button>
        <button class="btn" (click)="snapshot()" [disabled]="busy()"><sd-icon name="database" [size]="13" /> Copiar banco agora</button>
        @if (busy()) { <sd-icon name="spinner" class="spin" [size]="14" /> }
      </div>
      @if (check(); as c) {
        <div class="banner" [class.danger]="!c.ok" [class.warn]="c.ok && c.warnings.length" [class.info]="c.ok && !c.warnings.length" style="margin-top: 10px; flex-direction: column">
          <b>{{ c.ok ? 'Arquivo válido' : 'Arquivo recusado' }}</b>
          @if (c.problems.length) { <ul>@for (p of c.problems; track $index) { <li>{{ p }}</li> }</ul> }
          @if (c.warnings.length) { <ul>@for (w of c.warnings; track $index) { <li>{{ w }}</li> }</ul> }
        </div>
      }
      <div class="list">
        <div class="section-title">Cópias do banco</div>
        @for (b of backups(); track b.name) {
          <div class="entry">
            <sd-icon name="database" [size]="13" />
            <span class="grow"><code>{{ b.name }}</code> · {{ reason(b.reason) }} · {{ size(b.bytes) }}</span>
            <span class="muted">{{ dt(b.createdAt) }}</span>
          </div>
        } @empty { <p class="muted" style="margin-top: 6px">Nenhuma cópia ainda.</p> }
      </div>
    </section>
  `,
})
export class BackupSection implements OnInit {
  private readonly api = inject(Api);
  private readonly desktop = inject(Desktop);
  private readonly toasts = inject(Toasts);
  private readonly ui = inject(UiStore);

  protected readonly backups = signal<BackupEntry[]>([]);
  protected readonly check = signal<ImportCheck | null>(null);
  protected readonly busy = signal(false);
  protected readonly dt = dateTime;

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.backups.set(await this.api.get<BackupEntry[]>('backups'));
    } catch {
      this.backups.set([]);
    }
  }

  protected reason(reason: string): string {
    return reason === 'before-import' ? 'antes de importar' : reason === 'manual' ? 'manual' : reason === 'startup' ? 'ao abrir' : reason;
  }

  protected size(bytes: number): string {
    return bytes > 1_048_576 ? (bytes / 1_048_576).toFixed(1) + ' MB' : Math.max(1, Math.round(bytes / 1024)) + ' KB';
  }

  protected async exportWorkspace(): Promise<void> {
    this.busy.set(true);
    try {
      const json = await this.api.getText('workspace/export');
      const stamp = new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-');
      const saved = await this.desktop.saveText(`squad-desktop-${stamp}.json`, json, ['json'], 'application/json');
      if (saved) this.toasts.show('Workspace exportado.', 'ok');
    } catch (e) {
      this.toasts.error(e);
    } finally {
      this.busy.set(false);
    }
  }

  protected async importWorkspace(): Promise<void> {
    const file = await this.desktop.openText(['json']);
    if (!file) return;
    this.busy.set(true);
    this.check.set(null);
    try {
      const check = await this.api.post<ImportCheck>('workspace/import/validate', JSON.parse(file.content));
      this.check.set(check);
      if (!check.ok) return;
      const counts = Object.entries(check.counts).filter(([, n]) => n > 0).map(([k, n]) => `${n} ${COUNT_LABEL[k] ?? k}`).join(', ');
      const ok = await this.ui.confirm({
        title: 'Substituir o workspace atual?',
        body: `O conteúdo de ${file.path} (${counts || 'vazio'}) vai substituir projetos, agentes, squads, documentos e conversas deste computador. `
          + 'Uma cópia do banco atual é salva antes, na pasta de backups. Pastas de código não são tocadas.',
        confirm: 'Importar e substituir',
        danger: true,
      });
      if (!ok) return;
      const result = await this.api.post<ImportResult>('workspace/import', JSON.parse(file.content));
      this.toasts.show(`Importado. Cópia anterior salva em ${result.backup.name}. Recarregando…`, 'ok');
      setTimeout(() => window.location.reload(), 900);
    } catch (e) {
      const err = ApiError.from(e);
      this.toasts.show(e instanceof SyntaxError ? 'O arquivo não é um JSON válido.' : err.message, 'error');
    } finally {
      this.busy.set(false);
    }
  }

  protected async snapshot(): Promise<void> {
    this.busy.set(true);
    try {
      const entry = await this.api.post<BackupEntry>('backups');
      this.toasts.show(`Cópia criada: ${entry.name}`, 'ok');
      await this.load();
    } catch (e) {
      this.toasts.error(e);
    } finally {
      this.busy.set(false);
    }
  }
}
