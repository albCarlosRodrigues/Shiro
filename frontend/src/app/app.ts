import { ThemeStore } from './state/theme.store';
import { ShiroMark } from './shell/shiro-mark';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { ApiError } from './core/api';
import { Desktop } from './core/desktop';
import { EventStream } from './core/event-stream';
import { Shell } from './shell/shell';
import { Icon } from './shell/icon';
import { CatalogStore } from './state/catalog.store';
import { SessionsStore } from './state/sessions.store';
import { WorkspaceStore } from './state/workspace.store';

type Boot = { phase: 'loading' } | { phase: 'ready' } | { phase: 'error'; code: string; message: string };

/** Raiz: carrega o workspace do serviço local antes de liberar a interface e mostra diagnóstico se falhar. */
@Component({
  selector: 'sd-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ShiroMark, Shell, Icon],
  styles: `
    .boot { display: grid; place-items: center; height: 100vh; background: var(--bg-0); }
    .boot-card { display: flex; flex-direction: column; gap: 12px; width: min(520px, 92vw); padding: 24px; }
    .brand { display: flex; align-items: center; gap: 10px; font-weight: 600; font-size: var(--fs-lg); }
    .mark { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 7px; background: var(--signal); color: var(--signal-ink); }
    pre { margin: 0; max-height: 200px; overflow: auto; padding: 8px; border-radius: var(--r-md); background: var(--bg-1); font-size: var(--fs-xs); white-space: pre-wrap; }
  `,
  template: `
    @switch (boot().phase) {
      @case ('ready') { <sd-shell /> }
      @case ('loading') {
        <div class="boot" role="status" aria-live="polite">
          <div class="boot-card">
            <div class="brand"><sd-shiro-mark /> Shiro</div>
            <div class="row muted"><sd-icon name="spinner" class="spin" [size]="14" /> Carregando o workspace…</div>
          </div>
        </div>
      }
      @case ('error') {
        <div class="boot">
          <div class="boot-card card">
            <div class="brand"><sd-shiro-mark /> Shiro</div>
            <div class="banner danger"><sd-icon name="alert" [size]="14" /><span>{{ errorMessage() }}</span></div>
            <p class="dim">O serviço local precisa estar ativo para abrir o workspace. Seus dados ficam no banco local e não são afetados.</p>
            @if (log()) { <div class="section-title">Últimas linhas do log do serviço</div><pre class="selectable">{{ log() }}</pre> }
            <div class="row">
              <button class="btn primary" (click)="start()"><sd-icon name="refresh" [size]="14" /> Tentar de novo</button>
              @if (desktop.available) { <button class="btn" (click)="openData()"><sd-icon name="folder" [size]="14" /> Abrir pasta de dados</button> }
            </div>
          </div>
        </div>
      }
    }
  `,
})
export class App implements OnInit {
  protected readonly desktop = inject(Desktop);
  private readonly catalog = inject(CatalogStore);
  private readonly sessions = inject(SessionsStore);
  private readonly themes = inject(ThemeStore);
  private readonly workspace = inject(WorkspaceStore);
  private readonly stream = inject(EventStream);

  protected readonly boot = signal<Boot>({ phase: 'loading' });
  protected readonly log = signal('');

  ngOnInit(): void {
    void this.start();
  }

  protected errorMessage(): string {
    const b = this.boot();
    return b.phase === 'error' ? b.message : '';
  }

  protected async start(): Promise<void> {
    this.boot.set({ phase: 'loading' });
    try {
      await Promise.all([this.catalog.loadAll(), this.workspace.load(), this.themes.load()]);
      await this.sessions.init();
      this.workspace.prune(new Set(this.catalog.projects().map(v => v.project.id)));
      this.stream.start();
      this.boot.set({ phase: 'ready' });
    } catch (error) {
      const e = ApiError.from(error);
      const message = e.code === 'unauthorized'
        ? 'A sessão local expirou. Feche e abra o aplicativo de novo.'
        : e.code === 'backend_unreachable' ? 'Não foi possível falar com o serviço local do Shiro.' : e.message;
      this.boot.set({ phase: 'error', code: e.code, message });
      if (this.desktop.bridge) {
        try {
          this.log.set((await this.desktop.bridge.backendLog()).tail);
        } catch {
          this.log.set('');
        }
      }
    }
  }

  protected openData(): void {
    void this.desktop.bridge?.openDataFolder();
  }
}
