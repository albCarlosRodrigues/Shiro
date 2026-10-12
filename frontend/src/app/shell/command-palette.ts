import { AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';

import { ThemeStore } from '../state/theme.store';
import { CatalogStore } from '../state/catalog.store';
import { SessionsStore } from '../state/sessions.store';
import { UiStore } from '../state/ui.store';
import { WorkspaceStore } from '../state/workspace.store';
import { Icon } from './icon';
import { Nav } from './nav';

export interface Command {
  id: string;
  group: string;
  label: string;
  hint?: string;
  icon: string;
  run: () => void;
}

/** Pontua a busca por subsequência: letras na ordem, com bônus para início de palavra e sequência contínua. */
export function fuzzyScore(query: string, text: string): number {
  const q = query.trim().toLowerCase().normalize('NFD').replace(/\p{Diacritic}/gu, '');
  if (!q) return 1;
  const t = text.toLowerCase().normalize('NFD').replace(/\p{Diacritic}/gu, '');
  let score = 0;
  let ti = 0;
  let streak = 0;
  for (const ch of q) {
    if (ch === ' ') continue;
    const found = t.indexOf(ch, ti);
    if (found < 0) return 0;
    streak = found === ti ? streak + 1 : 0;
    score += 1 + streak * 2 + (found === 0 || t[found - 1] === ' ' ? 3 : 0);
    ti = found + 1;
  }
  return score - t.length * 0.01;
}

@Component({
  selector: 'sd-command-palette',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="scrim palette-scrim" (click)="close()"></div>
    <div class="palette" role="dialog" aria-modal="true" aria-label="Paleta de comandos" (keydown)="onKey($event)">
      <input #search type="text" placeholder="Abrir projeto, sessão ou executar um comando…" [value]="query()" (input)="setQuery($any($event.target).value)"
        role="combobox" aria-controls="palette-list" [attr.aria-activedescendant]="'cmd-' + selected()" aria-expanded="true" autocomplete="off" />
      <ul id="palette-list" role="listbox">
        @for (c of results(); track c.id; let i = $index) {
          <li>
            <button type="button" role="option" [id]="'cmd-' + i" [attr.aria-selected]="i === selected()" (click)="execute(c)" (mousemove)="selected.set(i)">
              <sd-icon [name]="c.icon" [size]="14" />
              <span class="grow ellipsis">{{ c.label }}</span>
              @if (c.hint) { <span class="group">{{ c.hint }}</span> }
              <span class="group">{{ c.group }}</span>
            </button>
          </li>
        } @empty {
          <li class="empty">Nada encontrado.</li>
        }
      </ul>
    </div>
  `,
})
export class CommandPalette implements AfterViewInit {
  private readonly themes = inject(ThemeStore);
  private readonly ui = inject(UiStore);
  private readonly nav = inject(Nav);
  private readonly catalog = inject(CatalogStore);
  private readonly sessions = inject(SessionsStore);
  private readonly workspace = inject(WorkspaceStore);
  private readonly search = viewChild.required<ElementRef<HTMLInputElement>>('search');

  protected readonly query = signal('');
  protected readonly selected = signal(0);

  private readonly commands = computed<Command[]>(() => {
    const list: Command[] = this.themes.themes.map(theme => ({ id: 'theme:' + theme.id, group: 'Aparência', label: 'Tema: ' + theme.name, icon: 'gear', run: () => this.themes.select(theme.id) }));
    const activeProject = this.nav.activeProjectId();
    for (const view of this.catalog.projects()) {
      list.push({ id: 'p:' + view.project.id, group: 'Projeto', label: view.project.name, icon: 'folder', hint: view.project.folderPath ?? undefined,
        run: () => void this.nav.openProject(view.project.id) });
    }
    for (const session of this.sessions.recent()) {
      const project = this.catalog.project(session.projectId);
      if (!project) continue;
      list.push({ id: 's:' + session.id, group: 'Sessão', label: session.title, hint: project.name, icon: 'chat',
        run: () => void this.nav.openSession(session.projectId, session.id) });
    }
    if (activeProject) {
      list.push(
        { id: 'a:new-session', group: 'Projeto atual', label: 'Nova sessão com um agente', icon: 'plus', run: () => this.ui.openDrawer({ kind: 'new-session', projectId: activeProject }) },
        { id: 'a:layout-1', group: 'Projeto atual', label: 'Layout: um painel', icon: 'layout-single', run: () => this.workspace.setMode(activeProject, 'single') },
        { id: 'a:layout-2', group: 'Projeto atual', label: 'Layout: dois painéis', icon: 'layout-split', run: () => this.workspace.setMode(activeProject, 'split') },
        { id: 'a:layout-4', group: 'Projeto atual', label: 'Layout: grade de quatro', icon: 'layout-grid', run: () => this.workspace.setMode(activeProject, 'grid') },
        { id: 'a:docs', group: 'Projeto atual', label: 'Mostrar documentos do projeto', icon: 'doc', run: () => this.workspace.setSide(activeProject, { open: true, tab: 'docs' }) },
        { id: 'a:edit-project', group: 'Projeto atual', label: 'Editar projeto', icon: 'edit', run: () => this.ui.openDrawer({ kind: 'project', projectId: activeProject }) },
      );
    }
    const paused = this.sessions.queue()?.paused === true;
    list.push(
      { id: 'g:home', group: 'Ir para', label: 'Início', icon: 'home', run: () => this.nav.home() },
      { id: 'g:agents', group: 'Ir para', label: 'Agentes', icon: 'agent', run: () => this.nav.go('agentes') },
      { id: 'g:squads', group: 'Ir para', label: 'Squads', icon: 'squad', run: () => this.nav.go('squads') },
      { id: 'g:settings', group: 'Ir para', label: 'Configurações e diagnóstico', hint: 'Ctrl ,', icon: 'gear', run: () => this.nav.go('configuracoes') },
      { id: 'c:new-project', group: 'Criar', label: 'Novo projeto', icon: 'plus', run: () => this.ui.openDrawer({ kind: 'project', projectId: null }) },
      { id: 'c:new-agent', group: 'Criar', label: 'Novo agente', icon: 'agent', run: () => this.ui.openDrawer({ kind: 'agent', agentId: null }) },
      { id: 'c:new-squad', group: 'Criar', label: 'Nova squad', icon: 'squad', run: () => this.ui.openDrawer({ kind: 'squad', squadId: null }) },
      { id: 'x:sidebar', group: 'Interface', label: 'Mostrar ou ocultar barra lateral', hint: 'Ctrl B', icon: 'sidebar', run: () => this.workspace.toggleSidebar() },
      { id: 'x:queue', group: 'Execuções', label: paused ? 'Retomar a fila de execuções' : 'Pausar a fila (nada novo começa)', icon: paused ? 'send' : 'pause',
        run: () => void this.sessions.pauseQueue(!paused) },
    );
    return list;
  });

  protected readonly results = computed(() => {
    const q = this.query();
    const scored = this.commands().map(c => ({ c, s: Math.max(fuzzyScore(q, c.label), fuzzyScore(q, c.group + ' ' + c.label) * 0.8) }));
    return scored.filter(x => x.s > 0).sort((a, b) => (q ? b.s - a.s : 0)).slice(0, 40).map(x => x.c);
  });

  ngAfterViewInit(): void {
    this.search().nativeElement.focus();
  }

  protected setQuery(value: string): void {
    this.query.set(value);
    this.selected.set(0);
  }

  protected onKey(event: KeyboardEvent): void {
    const count = this.results().length;
    if (event.key === 'Escape') {
      event.preventDefault();
      this.close();
    } else if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.selected.update(i => (count ? (i + 1) % count : 0));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.selected.update(i => (count ? (i - 1 + count) % count : 0));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const command = this.results()[this.selected()];
      if (command) this.execute(command);
    }
  }

  protected execute(command: Command): void {
    this.close();
    command.run();
  }

  protected close(): void {
    this.ui.paletteOpen.set(false);
  }
}
