import { Injectable, computed, inject, signal } from '@angular/core';

import { Api } from '../core/api';
import {
  PaneMode, ProjectLayout, ProjectView, SIDE_MAX, SIDE_MIN, SIDEBAR_MAX, SIDEBAR_MIN, SideTab, WorkspaceState, closePane, defaultLayout,
  defaultWorkspace, focusPane, movePane, openPane, parseWorkspace, prunePanes, setMode, setSplit, swapPanes, toggleMaximize,
} from './layout';

const KEY = 'workspace';

/**
 * Abas abertas, sidebar e disposição dos painéis. Salvo no banco local (ui_state) com atraso curto,
 * para que arrastar um divisor não gere uma gravação por pixel.
 */
@Injectable({ providedIn: 'root' })
export class WorkspaceStore {
  private readonly api = inject(Api);
  private saveTimer: ReturnType<typeof setTimeout> | null = null;
  private loadedOnce = false;

  readonly state = signal<WorkspaceState>(defaultWorkspace());
  readonly tabs = computed(() => this.state().tabs);
  readonly sidebar = computed(() => this.state().sidebar);

  async load(): Promise<void> {
    try {
      const saved = await this.api.getOptional<unknown>(`ui-state/${KEY}`);
      this.state.set(parseWorkspace(saved));
    } catch {
      this.state.set(defaultWorkspace());
    }
    this.loadedOnce = true;
  }

  layout(projectId: string): ProjectLayout {
    return this.state().layouts[projectId] ?? defaultLayout();
  }

  /* ---------- abas ---------- */

  openTab(projectId: string): void {
    this.mutate(s => (s.tabs.includes(projectId) ? s : { ...s, tabs: [...s.tabs, projectId] }));
  }

  /** Fecha a aba. As execuções do projeto continuam; a aba pode ser reaberta pela sidebar. */
  closeTab(projectId: string): string | null {
    const tabs = this.state().tabs;
    const index = tabs.indexOf(projectId);
    this.mutate(s => ({ ...s, tabs: s.tabs.filter(id => id !== projectId) }));
    const rest = this.state().tabs;
    return rest[Math.min(index, rest.length - 1)] ?? null;
  }

  moveTab(projectId: string, toIndex: number): void {
    this.mutate(s => {
      const tabs = s.tabs.filter(id => id !== projectId);
      tabs.splice(Math.max(0, Math.min(toIndex, tabs.length)), 0, projectId);
      return { ...s, tabs };
    });
  }

  /** Remove abas e layouts de projetos que deixaram de existir. */
  prune(projectIds: Set<string>): void {
    this.mutate(s => {
      const tabs = s.tabs.filter(id => projectIds.has(id));
      if (tabs.length === s.tabs.length) return s;
      return { ...s, tabs };
    });
  }

  /* ---------- sidebar ---------- */

  toggleSidebar(): void {
    this.mutate(s => ({ ...s, sidebar: { ...s.sidebar, collapsed: !s.sidebar.collapsed } }));
  }

  setSidebarWidth(width: number, persist = true): void {
    const value = Math.round(Math.max(SIDEBAR_MIN, Math.min(SIDEBAR_MAX, width)));
    this.mutate(s => ({ ...s, sidebar: { ...s.sidebar, width: value } }), persist);
  }

  toggleGroup(groupKey: string): void {
    this.mutate(s => {
      const list = s.sidebar.collapsedGroups;
      const collapsedGroups = list.includes(groupKey) ? list.filter(k => k !== groupKey) : [...list, groupKey];
      return { ...s, sidebar: { ...s.sidebar, collapsedGroups } };
    });
  }

  toggleProject(projectId: string, expanded?: boolean): void {
    this.mutate(s => {
      const list = s.sidebar.expandedProjects;
      const isOpen = list.includes(projectId);
      const want = expanded ?? !isOpen;
      if (want === isOpen) return s;
      const expandedProjects = want ? [...list, projectId] : list.filter(k => k !== projectId);
      return { ...s, sidebar: { ...s.sidebar, expandedProjects } };
    });
  }

  /* ---------- painéis ---------- */

  openPane(projectId: string, sessionId: string): void {
    this.updateLayout(projectId, l => openPane(l, sessionId));
  }

  closePane(projectId: string, sessionId: string): void {
    this.updateLayout(projectId, l => closePane(l, sessionId));
  }

  focusPane(projectId: string, sessionId: string): void {
    this.updateLayout(projectId, l => focusPane(l, sessionId));
  }

  movePane(projectId: string, sessionId: string, toIndex: number): void {
    this.updateLayout(projectId, l => movePane(l, sessionId, toIndex));
  }

  swapPanes(projectId: string, a: string, b: string): void {
    this.updateLayout(projectId, l => swapPanes(l, a, b));
  }

  setMode(projectId: string, mode: PaneMode): void {
    this.updateLayout(projectId, l => setMode(l, mode));
  }

  toggleMaximize(projectId: string, sessionId: string): void {
    this.updateLayout(projectId, l => toggleMaximize(l, sessionId));
  }

  setSplit(projectId: string, axis: 'col' | 'row', fraction: number, persist = true): void {
    this.updateLayout(projectId, l => setSplit(l, axis, fraction), persist);
  }

  setView(projectId: string, view: ProjectView): void {
    this.updateLayout(projectId, l => (l.view === view ? l : { ...l, view }));
  }

  setSide(projectId: string, patch: Partial<{ open: boolean; tab: SideTab; width: number }>, persist = true): void {
    this.updateLayout(projectId, l => {
      const width = patch.width == null ? l.side.width : Math.round(Math.max(SIDE_MIN, Math.min(SIDE_MAX, patch.width)));
      return { ...l, side: { ...l.side, ...patch, width } };
    }, persist);
  }

  prunePanes(projectId: string, sessionIds: Set<string>): void {
    if (!this.state().layouts[projectId]) return;
    this.updateLayout(projectId, l => prunePanes(l, sessionIds));
  }

  /** Grava agora (por exemplo, ao soltar um divisor arrastado). */
  flush(): void {
    this.schedule(0);
  }

  private updateLayout(projectId: string, change: (layout: ProjectLayout) => ProjectLayout, persist = true): void {
    this.mutate(s => {
      const current = s.layouts[projectId] ?? defaultLayout();
      const next = change(current);
      return next === current ? s : { ...s, layouts: { ...s.layouts, [projectId]: next } };
    }, persist);
  }

  private mutate(change: (state: WorkspaceState) => WorkspaceState, persist = true): void {
    const before = this.state();
    const after = change(before);
    if (after === before) return;
    this.state.set(after);
    if (persist) this.schedule(400);
  }

  private schedule(ms: number): void {
    if (!this.loadedOnce) return;
    if (this.saveTimer) clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null;
      void this.api.putJson(`ui-state/${KEY}`, JSON.stringify(this.state())).catch(() => undefined);
    }, ms);
  }
}
