import { Injectable, inject } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';
import { toSignal } from '@angular/core/rxjs-interop';

import { CatalogStore } from '../state/catalog.store';
import { SessionsStore } from '../state/sessions.store';
import { WorkspaceStore } from '../state/workspace.store';

/** Navegação entre início, projetos (abas) e telas de cadastro. */
@Injectable({ providedIn: 'root' })
export class Nav {
  private readonly router = inject(Router);
  private readonly workspace = inject(WorkspaceStore);
  private readonly catalog = inject(CatalogStore);
  private readonly sessions = inject(SessionsStore);

  /** Projeto da aba ativa, ou null fora da área de trabalho. */
  readonly activeProjectId = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(e => /^\/projeto\/([^/?#]+)/.exec(e.urlAfterRedirects)?.[1] ?? null),
    ),
    { initialValue: null as string | null },
  );

  readonly currentUrl = toSignal(
    this.router.events.pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd), map(e => e.urlAfterRedirects)),
    { initialValue: '/' },
  );

  async openProject(projectId: string, sessionId?: string): Promise<void> {
    this.workspace.openTab(projectId);
    if (sessionId) this.workspace.openPane(projectId, sessionId);
    await this.router.navigate(['/projeto', projectId]);
    void this.catalog.markOpened(projectId);
  }

  closeProjectTab(projectId: string): void {
    const next = this.workspace.closeTab(projectId);
    if (this.activeProjectId() === projectId) {
      void this.router.navigate(next ? ['/projeto', next] : ['/']);
    }
  }

  home(): void {
    void this.router.navigate(['/']);
  }

  go(path: 'agentes' | 'squads' | 'configuracoes'): void {
    void this.router.navigate(['/' + path]);
  }

  /** Abre (ou cria) a sessão de um agente no projeto e a coloca em foco. */
  async openSession(projectId: string, sessionId: string): Promise<void> {
    if (!this.sessions.session(sessionId)) await this.sessions.loadProject(projectId);
    await this.openProject(projectId, sessionId);
  }
}
