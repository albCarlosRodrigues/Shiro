import { ChangeDetectionStrategy, Component, HostListener, computed, inject, signal } from '@angular/core';

import { Desktop } from '../core/desktop';
import type { ProjectGroup, ProjectView } from '../core/models';
import { CatalogStore } from '../state/catalog.store';
import { SessionsStore } from '../state/sessions.store';
import { UiStore } from '../state/ui.store';
import { WorkspaceStore } from '../state/workspace.store';
import { Avatar, StatusBadge } from './bits';
import { Icon } from './icon';
import { Nav } from './nav';
import { ProjectActions } from './project-actions';

interface GroupNode {
  key: string;
  group: ProjectGroup | null;
  projects: ProjectView[];
}

type MenuState = { kind: 'project'; id: string; x: number; y: number } | { kind: 'group'; id: string; x: number; y: number }
  | { kind: 'session'; id: string; projectId: string; x: number; y: number };

@Component({
  selector: 'sd-sidebar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, Avatar, StatusBadge],
  host: { '[class.collapsed]': 'collapsed()' },
  styles: `
    :host { position: relative; display: flex; flex-direction: column; min-height: 0; background: var(--bg-0); }
    .head { display: flex; align-items: center; gap: 4px; padding: 8px 8px 6px 12px; }
    .filter { width: calc(100% - 16px); margin: 0 8px 6px; height: 26px; font-size: var(--fs-sm); }
    .tree { flex: 1 1 auto; overflow: auto; padding: 0 6px 8px; }
    .row { display: flex; align-items: center; gap: 6px; height: 28px; padding: 0 4px 0 calc(4px + var(--depth, 0) * 14px);
      border-radius: var(--r-md); color: var(--fg-2); font-size: var(--fs-sm); cursor: pointer; }
    .row:hover { background: var(--bg-2); color: var(--fg-1); }
    .row[aria-current='true'] { background: var(--bg-3); color: var(--fg-1); }
    .row .more { opacity: 0; }
    .row:hover .more, .row .more:focus-visible { opacity: 1; }
    .group-row { color: var(--fg-3); font-size: var(--fs-xs); font-weight: 600; letter-spacing: 0.05em; text-transform: uppercase; height: 26px; margin-top: 6px; }
    .chev { display: grid; place-items: center; width: 16px; height: 16px; border: 0; padding: 0; background: none; color: var(--fg-3); cursor: pointer; }
    .count { display: inline-flex; align-items: center; gap: 3px; color: var(--fg-3); font-size: var(--fs-xs); white-space: nowrap; flex: none; }
    .running { color: var(--st-running); }
    .missing { color: var(--warn); }
    .foot { display: flex; flex-direction: column; gap: 1px; padding: 6px; border-top: 1px solid var(--line-1); }
    .foot .row { color: var(--fg-2); }
    .resizer { position: absolute; top: 0; right: -3px; bottom: 0; width: 6px; cursor: col-resize; z-index: 5; }
    .resizer:hover, .resizer.active { background: linear-gradient(90deg, transparent 2px, var(--signal) 2px, var(--signal) 4px, transparent 4px); }
    :host(.collapsed) .row { justify-content: center; padding: 0; height: 32px; }
    .mini-projects { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 6px 0; overflow: auto; flex: 1; }
    .mini { position: relative; display: grid; place-items: center; width: 30px; height: 30px; border: 0; border-radius: var(--r-md);
      background: transparent; color: var(--fg-1); font-size: var(--fs-xs); font-weight: 600; cursor: pointer; }
    .mini:hover, .mini[aria-current='true'] { background: var(--bg-3); }
    .mini .swatch { width: 12px; height: 12px; border-radius: 4px; }
    .mini .badge { position: absolute; right: 3px; bottom: 3px; width: 7px; height: 7px; border-radius: 50%; background: var(--st-running); }
    .menu { position: fixed; }
    .empty-tree { padding: 16px 10px; color: var(--fg-3); font-size: var(--fs-sm); }
  `,
  template: `
    @if (!collapsed()) {
      <div class="head">
        <span class="section-title grow">Projetos</span>
        <button class="btn ghost icon sm" (click)="newGroup()" title="Novo grupo" aria-label="Novo grupo"><sd-icon name="folder" [size]="14" /></button>
        <button class="btn ghost icon sm" (click)="ui.openDrawer({ kind: 'project', projectId: null })" title="Novo projeto" aria-label="Novo projeto"><sd-icon name="plus" [size]="14" /></button>
      </div>
      <input class="input filter" type="search" placeholder="Filtrar projetos" aria-label="Filtrar projetos" [value]="filter()" (input)="filter.set($any($event.target).value)" />
      <nav class="tree" aria-label="Projetos e sessões">
        @for (node of tree(); track node.key) {
          @if (node.group || tree().length > 1) {
            <div class="row group-row" role="button" tabindex="0" (click)="workspace.toggleGroup(node.key)" (keydown.enter)="workspace.toggleGroup(node.key)"
              [attr.aria-expanded]="!isGroupCollapsed(node.key)" (contextmenu)="node.group && openMenu($event, 'group', node.group.id)">
              <sd-icon [name]="isGroupCollapsed(node.key) ? 'chevron-right' : 'chevron-down'" [size]="12" />
              @if (node.group) { <span class="swatch" [style.--swatch]="node.group.color"></span> }
              <span class="ellipsis grow">{{ node.group?.name ?? 'Sem grupo' }}</span>
              <span class="count">{{ node.projects.length }}</span>
              @if (node.group) {
                <button class="btn ghost icon sm more" (click)="openMenu($event, 'group', node.group.id)" [attr.aria-label]="'Ações do grupo ' + node.group.name"><sd-icon name="more" [size]="14" /></button>
              }
            </div>
          }
          @if (!isGroupCollapsed(node.key)) {
            @for (view of node.projects; track view.project.id) {
              <div class="row" role="button" tabindex="0" style="--depth: 0" [attr.aria-current]="nav.activeProjectId() === view.project.id"
                (click)="nav.openProject(view.project.id)" (keydown.enter)="nav.openProject(view.project.id)" (contextmenu)="openMenu($event, 'project', view.project.id)">
                <button class="chev" (click)="$event.stopPropagation(); toggleProject(view.project.id)" [attr.aria-label]="isExpanded(view.project.id) ? 'Recolher sessões' : 'Mostrar sessões'"
                  [attr.aria-expanded]="isExpanded(view.project.id)">
                  <sd-icon [name]="isExpanded(view.project.id) ? 'chevron-down' : 'chevron-right'" [size]="12" />
                </button>
                <span class="swatch" [style.--swatch]="view.project.color"></span>
                <span class="ellipsis grow">{{ view.project.name }}</span>
                @if (view.project.sample) { <span class="count" title="Dados de exemplo">exemplo</span> }
                @if (view.project.folderPath && !view.folderExists) {
                  <span class="missing" title="A pasta do projeto não foi encontrada"><sd-icon name="alert" [size]="12" /><span class="sr-only">Pasta não encontrada</span></span>
                }
                @if (active()[view.project.id]; as n) {
                  <span class="count running" [attr.title]="n + ' em andamento'"><sd-icon name="spinner" class="spin" [size]="11" /> {{ n }}</span>
                } @else if (sessionCount(view.project.id); as n) {
                  <span class="count">{{ n }}</span>
                }
                <button class="btn ghost icon sm more" (click)="openMenu($event, 'project', view.project.id)" [attr.aria-label]="'Ações do projeto ' + view.project.name"><sd-icon name="more" [size]="14" /></button>
              </div>
              @if (isExpanded(view.project.id)) {
                @for (session of sessions.sessions(view.project.id); track session.id) {
                  <div class="row" role="button" tabindex="0" style="--depth: 1.6" (click)="nav.openSession(view.project.id, session.id)"
                    (keydown.enter)="nav.openSession(view.project.id, session.id)" (contextmenu)="openMenu($event, 'session', session.id, view.project.id)">
                    <sd-avatar [agent]="catalog.agentById().get(session.agentId)" [size]="18" />
                    <span class="ellipsis grow">{{ session.title }}</span>
                    @if (sessions.runs()[session.id]; as m) {
                      @if (m.status !== 'COMPLETED') { <sd-status [status]="m.status" [compact]="true" /> }
                    }
                  </div>
                } @empty {
                  <div class="row muted" style="--depth: 1.6" role="button" tabindex="0" (click)="ui.openDrawer({ kind: 'new-session', projectId: view.project.id })">
                    <sd-icon name="plus" [size]="12" /> Nova sessão
                  </div>
                }
              }
            }
          }
        } @empty {
          <div class="empty-tree">
            @if (filter()) { Nenhum projeto com “{{ filter() }}”. } @else { Nenhum projeto ainda. Crie um pelo botão + ou pela tela inicial. }
          </div>
        }
      </nav>
    } @else {
      <div class="mini-projects" aria-label="Projetos">
        @for (view of catalog.projects(); track view.project.id) {
          <button class="mini" (click)="nav.openProject(view.project.id)" [attr.aria-current]="nav.activeProjectId() === view.project.id" [attr.title]="view.project.name">
            <span class="swatch" [style.--swatch]="view.project.color"></span>
            @if (active()[view.project.id]) { <span class="badge" aria-label="Em execução"></span> }
          </button>
        }
      </div>
    }
    <div class="foot">
      <div class="row" role="button" tabindex="0" [attr.aria-current]="url().startsWith('/agentes')" (click)="nav.go('agentes')" (keydown.enter)="nav.go('agentes')" title="Agentes">
        <sd-icon name="agent" [size]="15" />@if (!collapsed()) { <span class="grow">Agentes</span><span class="count">{{ catalog.agents().length }}</span> }
      </div>
      <div class="row" role="button" tabindex="0" [attr.aria-current]="url().startsWith('/squads')" (click)="nav.go('squads')" (keydown.enter)="nav.go('squads')" title="Squads">
        <sd-icon name="squad" [size]="15" />@if (!collapsed()) { <span class="grow">Squads</span><span class="count">{{ catalog.squads().length }}</span> }
      </div>
      <div class="row" role="button" tabindex="0" [attr.aria-current]="url().startsWith('/configuracoes')" (click)="nav.go('configuracoes')" (keydown.enter)="nav.go('configuracoes')" title="Configurações (Ctrl+,)">
        <sd-icon name="gear" [size]="15" />@if (!collapsed()) { <span class="grow">Configurações</span> }
      </div>
      <div class="row" role="button" tabindex="0" (click)="workspace.toggleSidebar()" (keydown.enter)="workspace.toggleSidebar()" [attr.title]="collapsed() ? 'Expandir barra lateral (Ctrl+B)' : 'Recolher barra lateral (Ctrl+B)'">
        <sd-icon [name]="collapsed() ? 'chevron-right' : 'sidebar'" [size]="15" />@if (!collapsed()) { <span class="grow">Recolher</span><kbd>Ctrl B</kbd> }
      </div>
    </div>
    @if (!collapsed()) {
      <div class="resizer" [class.active]="resizing()" (pointerdown)="startResize($event)" (dblclick)="workspace.setSidebarWidth(260)"
        role="separator" aria-orientation="vertical" aria-label="Redimensionar barra lateral" tabindex="0"
        [attr.aria-valuenow]="workspace.sidebar().width" (keydown.arrowleft)="workspace.setSidebarWidth(workspace.sidebar().width - 16)"
        (keydown.arrowright)="workspace.setSidebarWidth(workspace.sidebar().width + 16)"></div>
    }
    @if (menu(); as m) {
      <div class="menu" role="menu" [style.left.px]="m.x" [style.top.px]="m.y" (click)="$event.stopPropagation()">
        @switch (m.kind) {
          @case ('project') {
            <button role="menuitem" (click)="closeMenu(); nav.openProject(m.id)"><sd-icon name="panel" [size]="14" /> Abrir</button>
            <button role="menuitem" (click)="closeMenu(); ui.openDrawer({ kind: 'new-session', projectId: m.id })"><sd-icon name="plus" [size]="14" /> Nova sessão</button>
            <button role="menuitem" (click)="closeMenu(); ui.openDrawer({ kind: 'project', projectId: m.id })"><sd-icon name="edit" [size]="14" /> Editar</button>
            @if (desktop.available && catalog.project(m.id)?.folderPath) {
              <button role="menuitem" (click)="closeMenu(); actions.reveal(m.id)"><sd-icon name="folder-open" [size]="14" /> Mostrar pasta</button>
            }
            <hr />
            <button role="menuitem" (click)="closeMenu(); actions.archive(m.id)"><sd-icon name="archive" [size]="14" /> Arquivar</button>
            <button role="menuitem" class="danger" (click)="closeMenu(); actions.remove(m.id)"><sd-icon name="trash" [size]="14" /> Excluir cadastro…</button>
          }
          @case ('group') {
            <button role="menuitem" (click)="closeMenu(); ui.openDrawer({ kind: 'project', projectId: null, groupId: m.id })"><sd-icon name="plus" [size]="14" /> Novo projeto no grupo</button>
            <button role="menuitem" (click)="closeMenu(); renameGroup(m.id)"><sd-icon name="edit" [size]="14" /> Renomear</button>
            <hr />
            <button role="menuitem" class="danger" (click)="closeMenu(); removeGroup(m.id)"><sd-icon name="trash" [size]="14" /> Excluir grupo</button>
          }
          @case ('session') {
            <button role="menuitem" (click)="closeMenu(); nav.openSession(m.projectId, m.id)"><sd-icon name="panel" [size]="14" /> Abrir painel</button>
            <button role="menuitem" (click)="closeMenu(); actions.renameSession(m.id)"><sd-icon name="edit" [size]="14" /> Renomear</button>
            <hr />
            <button role="menuitem" class="danger" (click)="closeMenu(); actions.removeSession(m.projectId, m.id)"><sd-icon name="trash" [size]="14" /> Excluir sessão…</button>
          }
        }
      </div>
    }
  `,
})
export class Sidebar {
  protected readonly nav = inject(Nav);
  protected readonly ui = inject(UiStore);
  protected readonly catalog = inject(CatalogStore);
  protected readonly sessions = inject(SessionsStore);
  protected readonly workspace = inject(WorkspaceStore);
  protected readonly desktop = inject(Desktop);
  protected readonly actions = inject(ProjectActions);

  protected readonly filter = signal('');
  protected readonly menu = signal<MenuState | null>(null);
  protected readonly resizing = signal(false);
  protected readonly collapsed = computed(() => this.workspace.sidebar().collapsed);
  protected readonly active = this.sessions.activeByProject;
  protected readonly url = this.nav.currentUrl;

  protected readonly tree = computed<GroupNode[]>(() => {
    const term = this.filter().trim().toLowerCase();
    const projects = this.catalog.projects().filter(v => !term || v.project.name.toLowerCase().includes(term)
      || (v.project.folderPath ?? '').toLowerCase().includes(term));
    const groups = this.catalog.groups();
    const nodes: GroupNode[] = groups.map(group => ({ key: group.id, group, projects: projects.filter(v => v.project.groupId === group.id) }));
    const loose = projects.filter(v => !v.project.groupId || !groups.some(g => g.id === v.project.groupId));
    if (loose.length || nodes.length === 0) nodes.push({ key: '__none__', group: null, projects: loose });
    return term ? nodes.filter(n => n.projects.length) : nodes.filter(n => n.group || n.projects.length);
  });

  protected isGroupCollapsed(key: string): boolean {
    return !this.filter() && this.workspace.sidebar().collapsedGroups.includes(key);
  }

  protected isExpanded(projectId: string): boolean {
    return this.workspace.sidebar().expandedProjects.includes(projectId);
  }

  protected sessionCount(projectId: string): number {
    return this.sessions.sessions(projectId).length;
  }

  protected toggleProject(projectId: string): void {
    const opening = !this.isExpanded(projectId);
    this.workspace.toggleProject(projectId);
    if (opening && !this.sessions.byProject()[projectId]) void this.sessions.loadProject(projectId);
  }

  protected openMenu(event: MouseEvent, kind: 'project' | 'group' | 'session', id: string, projectId?: string): void {
    event.preventDefault();
    event.stopPropagation();
    const x = Math.min(event.clientX, window.innerWidth - 200);
    const y = Math.min(event.clientY, window.innerHeight - 240);
    this.menu.set(kind === 'session' ? { kind, id, projectId: projectId!, x, y } : { kind, id, x, y });
  }

  protected closeMenu(): void {
    this.menu.set(null);
  }

  @HostListener('document:click')
  @HostListener('document:keydown.escape')
  protected dismiss(): void {
    if (this.menu()) this.menu.set(null);
  }

  protected startResize(event: PointerEvent): void {
    const handle = event.target as HTMLElement;
    handle.setPointerCapture(event.pointerId);
    this.resizing.set(true);
    const startX = event.clientX;
    const startWidth = this.workspace.sidebar().width;
    const move = (e: PointerEvent) => this.workspace.setSidebarWidth(startWidth + e.clientX - startX, false);
    const up = () => {
      this.resizing.set(false);
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', up);
      this.workspace.flush();
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', up);
  }

  protected async newGroup(): Promise<void> {
    const name = await this.ui.prompt({ title: 'Novo grupo', label: 'Nome do grupo', value: '', confirm: 'Criar grupo' });
    if (name?.trim()) await this.actions.run(() => this.catalog.createGroup(name.trim()), 'Grupo criado.');
  }

  protected async renameGroup(id: string): Promise<void> {
    const group = this.catalog.groups().find(g => g.id === id);
    if (!group) return;
    const name = await this.ui.prompt({ title: 'Renomear grupo', label: 'Nome do grupo', value: group.name, confirm: 'Salvar' });
    if (name?.trim() && name.trim() !== group.name) await this.actions.run(() => this.catalog.updateGroup(id, name.trim(), group.color));
  }

  protected async removeGroup(id: string): Promise<void> {
    const group = this.catalog.groups().find(g => g.id === id);
    const ok = await this.ui.confirm({
      title: 'Excluir grupo?',
      body: `O grupo “${group?.name}” será removido. Os projetos dele continuam, sem grupo.`,
      confirm: 'Excluir grupo',
      danger: true,
    });
    if (ok) await this.actions.run(() => this.catalog.deleteGroup(id), 'Grupo excluído.');
  }
}
