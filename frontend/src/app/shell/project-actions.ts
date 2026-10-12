import { Injectable, inject } from '@angular/core';

import { ApiError } from '../core/api';
import { Desktop } from '../core/desktop';
import { Toasts } from '../core/toasts';
import { CatalogStore } from '../state/catalog.store';
import { SessionsStore } from '../state/sessions.store';
import { UiStore } from '../state/ui.store';
import { WorkspaceStore } from '../state/workspace.store';
import { Nav } from './nav';

/** Ações com confirmação e mensagens, usadas pela sidebar, pela tela inicial e pela paleta. */
@Injectable({ providedIn: 'root' })
export class ProjectActions {
  private readonly catalog = inject(CatalogStore);
  private readonly sessions = inject(SessionsStore);
  private readonly workspace = inject(WorkspaceStore);
  private readonly ui = inject(UiStore);
  private readonly toasts = inject(Toasts);
  private readonly desktop = inject(Desktop);
  private readonly nav = inject(Nav);

  /** Executa uma ação e transforma erros da API em aviso. Devolve undefined quando falhou. */
  async run<T>(action: () => Promise<T>, success?: string): Promise<T | undefined> {
    try {
      const result = await action();
      if (success) this.toasts.show(success, 'ok');
      return result;
    } catch (error) {
      this.toasts.error(error);
      return undefined;
    }
  }

  /** Como {@link run}, mas informa se deu certo (para ações sem retorno). */
  async attempt(action: () => Promise<unknown>, success?: string): Promise<boolean> {
    try {
      await action();
      if (success) this.toasts.show(success, 'ok');
      return true;
    } catch (error) {
      this.toasts.error(error);
      return false;
    }
  }

  async reveal(projectId: string): Promise<void> {
    const project = this.catalog.project(projectId);
    if (!project?.folderPath) return;
    if (!(await this.desktop.revealFolder(project.folderPath))) {
      this.toasts.show('Não foi possível abrir a pasta. Ela pode ter sido movida ou removida.', 'warn');
    }
  }

  async archive(projectId: string): Promise<void> {
    const project = this.catalog.project(projectId);
    const ok = await this.ui.confirm({
      title: 'Arquivar projeto?',
      body: `“${project?.name}” sai da barra lateral e das abas. Sessões, documentos e histórico ficam guardados e você pode desarquivar quando quiser.`,
      confirm: 'Arquivar',
    });
    if (!ok) return;
    if (await this.attempt(() => this.catalog.archiveProject(projectId, true), 'Projeto arquivado.')) this.nav.closeProjectTab(projectId);
  }

  async remove(projectId: string): Promise<void> {
    const project = this.catalog.project(projectId);
    const ok = await this.ui.confirm({
      title: 'Excluir o cadastro do projeto?',
      body: `O cadastro de “${project?.name}” será removido do Shiro, com sessões, conversas, documentos e histórico de execuções. `
        + (project?.folderPath ? `A pasta de código (${project.folderPath}) não será tocada.` : 'Nenhum arquivo do disco é apagado.'),
      confirm: 'Excluir cadastro',
      danger: true,
    });
    if (!ok) return;
    try {
      await this.catalog.deleteProject(projectId);
      this.nav.closeProjectTab(projectId);
      this.toasts.show('Cadastro excluído. A pasta de código foi preservada.', 'ok');
    } catch (error) {
      const e = ApiError.from(error);
      this.toasts.show(e.code === 'project_busy' ? e.message : 'Não foi possível excluir: ' + e.message, 'error');
    }
  }

  async renameSession(sessionId: string): Promise<void> {
    const session = this.sessions.session(sessionId);
    if (!session) return;
    const title = await this.ui.prompt({ title: 'Renomear sessão', label: 'Título', value: session.title, confirm: 'Salvar' });
    if (title?.trim() && title.trim() !== session.title) await this.run(() => this.sessions.rename(sessionId, title.trim()));
  }

  async removeSession(projectId: string, sessionId: string): Promise<void> {
    const session = this.sessions.session(sessionId);
    const ok = await this.ui.confirm({
      title: 'Excluir sessão?',
      body: `A conversa “${session?.title}”, com mensagens e histórico de execuções, será removida. Arquivos do projeto não são alterados.`,
      confirm: 'Excluir sessão',
      danger: true,
    });
    if (!ok) return;
    if (await this.attempt(() => this.sessions.remove(sessionId), 'Sessão excluída.')) this.workspace.closePane(projectId, sessionId);
  }
}
