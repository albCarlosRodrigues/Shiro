/**
 * Disposição da área de trabalho de um projeto e do aplicativo. Funções puras, persistidas como JSON em
 * /api/ui-state/workspace. Ocultar um painel nunca encerra a execução da sessão: encerrar é uma ação separada.
 */

export type PaneMode = 'single' | 'split' | 'grid';
export type SideTab = 'docs' | 'run';
/** O que a área do projeto mostra: painéis de agentes, sala de planejamento ou features. */
export type ProjectView = 'panes' | 'planning' | 'features';

export interface ProjectLayout {
  view: ProjectView;
  mode: PaneMode;
  /** Sessões abertas, em ordem. As primeiras {@link capacity} aparecem; as demais ficam ocultas. */
  panes: string[];
  focused: string | null;
  maximized: string | null;
  /** Fração da largura da primeira coluna (divisão em dois e grade). */
  colSplit: number;
  /** Fração da altura da primeira linha (grade). */
  rowSplit: number;
  side: { open: boolean; tab: SideTab; width: number };
}

export interface SidebarState {
  width: number;
  collapsed: boolean;
  collapsedGroups: string[];
  expandedProjects: string[];
}

export interface WorkspaceState {
  version: 1;
  tabs: string[];
  sidebar: SidebarState;
  layouts: Record<string, ProjectLayout>;
}

export const SIDEBAR_MIN = 200;
export const SIDEBAR_MAX = 440;
export const SIDE_MIN = 300;
export const SIDE_MAX = 720;
export const SPLIT_MIN = 0.2;
export const SPLIT_MAX = 0.8;

export function capacity(mode: PaneMode): number {
  return mode === 'single' ? 1 : mode === 'split' ? 2 : 4;
}

export function defaultLayout(): ProjectLayout {
  return { view: 'panes', mode: 'split', panes: [], focused: null, maximized: null, colSplit: 0.5, rowSplit: 0.5, side: { open: false, tab: 'docs', width: 400 } };
}

export function defaultWorkspace(): WorkspaceState {
  return {
    version: 1,
    tabs: [],
    sidebar: { width: 260, collapsed: false, collapsedGroups: [], expandedProjects: [] },
    layouts: {},
  };
}

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

/** Painéis visíveis no modo atual. Com um painel maximizado, só ele. */
export function visiblePanes(layout: ProjectLayout): string[] {
  if (layout.maximized && layout.panes.includes(layout.maximized)) return [layout.maximized];
  return layout.panes.slice(0, capacity(layout.mode));
}

export function hiddenPanes(layout: ProjectLayout): string[] {
  const visible = new Set(visiblePanes(layout));
  return layout.panes.filter(id => !visible.has(id));
}

/**
 * Abre (ou traz para a vista) uma sessão. Se ela estiver oculta, ocupa a posição do painel em foco,
 * que passa para logo depois dos visíveis, sem fechar nada.
 */
export function openPane(layout: ProjectLayout, sessionId: string): ProjectLayout {
  const cap = capacity(layout.mode);
  let panes = layout.panes.filter(id => id !== sessionId);
  const wasVisible = layout.panes.indexOf(sessionId);
  if (wasVisible >= 0 && wasVisible < cap) {
    return { ...layout, focused: sessionId, maximized: layout.maximized && layout.maximized !== sessionId ? null : layout.maximized };
  }
  if (panes.length < cap) {
    panes = [...panes, sessionId];
  } else {
    const focusIndex = layout.focused ? panes.indexOf(layout.focused) : -1;
    const slot = focusIndex >= 0 && focusIndex < cap ? focusIndex : cap - 1;
    panes = [...panes.slice(0, slot), sessionId, ...panes.slice(slot)];
  }
  return { ...layout, panes, focused: sessionId, maximized: null };
}

/** Tira o painel da área de trabalho. A sessão e a execução continuam; ela pode ser reaberta pela sidebar. */
export function closePane(layout: ProjectLayout, sessionId: string): ProjectLayout {
  const index = layout.panes.indexOf(sessionId);
  if (index < 0) return layout;
  const panes = layout.panes.filter(id => id !== sessionId);
  const focused = layout.focused === sessionId ? panes[Math.min(index, panes.length - 1)] ?? null : layout.focused;
  return { ...layout, panes, focused, maximized: layout.maximized === sessionId ? null : layout.maximized };
}

/** Remove referências a sessões que não existem mais (excluídas ou arquivadas). */
export function prunePanes(layout: ProjectLayout, existing: Set<string>): ProjectLayout {
  const panes = layout.panes.filter(id => existing.has(id));
  if (panes.length === layout.panes.length) return layout;
  return {
    ...layout,
    panes,
    focused: layout.focused && existing.has(layout.focused) ? layout.focused : panes[0] ?? null,
    maximized: layout.maximized && existing.has(layout.maximized) ? layout.maximized : null,
  };
}

export function movePane(layout: ProjectLayout, sessionId: string, toIndex: number): ProjectLayout {
  const from = layout.panes.indexOf(sessionId);
  if (from < 0) return layout;
  const panes = [...layout.panes];
  panes.splice(from, 1);
  const target = clamp(toIndex, 0, panes.length);
  panes.splice(target, 0, sessionId);
  return { ...layout, panes };
}

/** Troca dois painéis de lugar (arrastar e soltar sobre outro painel). */
export function swapPanes(layout: ProjectLayout, a: string, b: string): ProjectLayout {
  const i = layout.panes.indexOf(a);
  const j = layout.panes.indexOf(b);
  if (i < 0 || j < 0 || i === j) return layout;
  const panes = [...layout.panes];
  [panes[i], panes[j]] = [panes[j], panes[i]];
  return { ...layout, panes };
}

export function setMode(layout: ProjectLayout, mode: PaneMode): ProjectLayout {
  const next = { ...layout, mode, maximized: null };
  // mantém o painel em foco visível no novo modo
  if (layout.focused && !visiblePanes(next).includes(layout.focused)) {
    return { ...openPane({ ...next, focused: null }, layout.focused) };
  }
  return next;
}

export function toggleMaximize(layout: ProjectLayout, sessionId: string): ProjectLayout {
  return { ...layout, maximized: layout.maximized === sessionId ? null : sessionId, focused: sessionId };
}

export function focusPane(layout: ProjectLayout, sessionId: string): ProjectLayout {
  return layout.focused === sessionId ? layout : { ...layout, focused: sessionId };
}

export function setSplit(layout: ProjectLayout, axis: 'col' | 'row', fraction: number): ProjectLayout {
  const value = clamp(fraction, SPLIT_MIN, SPLIT_MAX);
  return axis === 'col' ? { ...layout, colSplit: value } : { ...layout, rowSplit: value };
}

/** Lê um JSON salvo, tolerando versões antigas e valores fora do intervalo. */
export function parseWorkspace(json: unknown): WorkspaceState {
  const base = defaultWorkspace();
  if (!json || typeof json !== 'object') return base;
  const raw = json as Partial<WorkspaceState>;
  const strings = (value: unknown) => (Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : []);
  const sidebar = (raw.sidebar ?? {}) as Partial<SidebarState>;
  const layouts: Record<string, ProjectLayout> = {};
  if (raw.layouts && typeof raw.layouts === 'object') {
    for (const [id, value] of Object.entries(raw.layouts)) {
      const l = (value ?? {}) as Partial<ProjectLayout>;
      const d = defaultLayout();
      const mode: PaneMode = l.mode === 'single' || l.mode === 'grid' || l.mode === 'split' ? l.mode : d.mode;
      const panes = [...new Set(strings(l.panes))];
      layouts[id] = {
        view: l.view === 'planning' || l.view === 'features' ? l.view : 'panes',
        mode,
        panes,
        focused: typeof l.focused === 'string' && panes.includes(l.focused) ? l.focused : panes[0] ?? null,
        maximized: typeof l.maximized === 'string' && panes.includes(l.maximized) ? l.maximized : null,
        colSplit: clamp(typeof l.colSplit === 'number' ? l.colSplit : d.colSplit, SPLIT_MIN, SPLIT_MAX),
        rowSplit: clamp(typeof l.rowSplit === 'number' ? l.rowSplit : d.rowSplit, SPLIT_MIN, SPLIT_MAX),
        side: {
          open: l.side?.open === true,
          tab: l.side?.tab === 'run' ? 'run' : 'docs',
          width: clamp(typeof l.side?.width === 'number' ? l.side.width : d.side.width, SIDE_MIN, SIDE_MAX),
        },
      };
    }
  }
  return {
    version: 1,
    tabs: [...new Set(strings(raw.tabs))],
    sidebar: {
      width: clamp(typeof sidebar.width === 'number' ? sidebar.width : base.sidebar.width, SIDEBAR_MIN, SIDEBAR_MAX),
      collapsed: sidebar.collapsed === true,
      collapsedGroups: strings(sidebar.collapsedGroups),
      expandedProjects: strings(sidebar.expandedProjects),
    },
    layouts,
  };
}
