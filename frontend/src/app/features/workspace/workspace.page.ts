import { ChangeDetectionStrategy, Component, ElementRef, HostListener, computed, effect, inject, input, signal, viewChild, viewChildren } from '@angular/core';
import { Title } from '@angular/platform-browser';

import { Desktop } from '../../core/desktop';
import { baseName } from '../../core/format';
import { Icon } from '../../shell/icon';
import { Nav } from '../../shell/nav';
import { ProjectActions } from '../../shell/project-actions';
import { CatalogStore } from '../../state/catalog.store';
import { PaneMode, capacity, hiddenPanes, visiblePanes } from '../../state/layout';
import { SessionsStore } from '../../state/sessions.store';
import { UiStore } from '../../state/ui.store';
import { WorkspaceStore } from '../../state/workspace.store';
import { DocumentsPanel } from '../documents/documents-panel';
import { FeaturesView } from '../delivery/features-view';
import { PlanningRoom } from '../planning/planning-room';
import { ProjectWorkStore } from '../../state/project-work.store';
import type { ProjectView } from '../../state/layout';
import { Avatar } from '../../shell/bits';
import { Pane } from './pane';
import { RunsPanel } from './runs-panel';

interface Cell {
  sessionId: string;
  area: string;
}

/** Área de trabalho de um projeto: painéis de agentes (um, dois ou quatro), divisores e painel lateral. */
@Component({
  selector: 'sd-workspace-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, Pane, Avatar, DocumentsPanel, RunsPanel, PlanningRoom, FeaturesView],
  styles: `
    :host { display: flex; flex-direction: column; height: 100%; min-height: 0; }
    .toolbar { display: flex; align-items: center; gap: 8px; height: 40px; padding: 0 10px 0 14px; border-bottom: 1px solid var(--line-1); flex: none; }
    .pname { font-weight: 600; white-space: nowrap; }
    .chip-btn { display: inline-flex; align-items: center; gap: 5px; max-width: 280px; height: 24px; padding: 0 8px; border: 0; border-radius: var(--r-sm);
      background: var(--bg-3); color: var(--fg-2); font-size: var(--fs-xs); cursor: pointer; }
    .chip-btn:hover { background: var(--bg-4); color: var(--fg-1); }
    .chip-btn.warn { color: var(--warn); }
    .mono { font-family: var(--font-mono); }
    .body { display: flex; flex: 1 1 auto; min-height: 0; }
    .grid { position: relative; display: grid; flex: 1 1 auto; min-width: 0; min-height: 0; padding: 8px; gap: 0; }
    .cell { display: flex; min-width: 0; min-height: 0; }
    .cell > sd-pane { flex: 1 1 auto; }
    .cell.drop sd-pane { outline: 2px dashed var(--signal); outline-offset: -2px; }
    .split { position: relative; z-index: 2; }
    .split.v { cursor: col-resize; }
    .split.h { cursor: row-resize; }
    .split::after { content: ''; position: absolute; border-radius: 2px; transition: background var(--dur); }
    .split.v::after { top: 30%; bottom: 30%; left: 2px; width: 2px; }
    .split.h::after { left: 30%; right: 30%; top: 2px; height: 2px; }
    .split:hover::after, .split:focus-visible::after, .split.active::after { background: var(--signal); }
    .side { position: relative; display: flex; flex-direction: column; flex: none; min-height: 0; border-left: 1px solid var(--line-1); background: var(--bg-2); }
    .side-head { display: flex; align-items: center; gap: 6px; height: 36px; padding: 0 6px 0 10px; border-bottom: 1px solid var(--line-1); flex: none; }
    .side-resizer { position: absolute; left: -3px; top: 0; bottom: 0; width: 6px; cursor: col-resize; z-index: 3; }
    .side-resizer:hover { background: linear-gradient(90deg, transparent 2px, var(--signal) 2px, var(--signal) 4px, transparent 4px); }
    .hidden-menu { right: 10px; top: 40px; }
    .empty-ws { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; flex: 1; padding: 24px; color: var(--fg-3); text-align: center; }
    .sessions { display: flex; flex-direction: column; gap: 4px; width: min(420px, 90%); }
    .views { margin-left: 6px; }
    .badge { display: inline-grid; place-items: center; min-width: 16px; height: 16px; padding: 0 4px; margin-left: 4px; border-radius: 8px;
      background: var(--warn); color: var(--signal-ink); font-size: 10px; font-weight: 600; }
    @media (max-width: 1280px) { .chip-btn, .toolbar > .chip { display: none; } }
  `,
  template: `
    @if (view(); as v) {
      <div class="toolbar">
        <span class="swatch" [style.--swatch]="v.project.color"></span>
        <span class="pname ellipsis">{{ v.project.name }}</span>
        @if (v.project.sample) { <span class="chip">exemplo</span> }
        @if (v.project.archivedAt) { <span class="chip">arquivado</span> }
        @if (v.project.folderPath) {
          <button class="chip-btn mono" [class.warn]="!v.folderExists" (click)="reveal()" [attr.title]="v.folderExists ? v.project.folderPath : 'Pasta não encontrada: ' + v.project.folderPath">
            <sd-icon [name]="v.folderExists ? 'folder' : 'alert'" [size]="12" /><span class="ellipsis">{{ folderName() }}</span>
          </button>
        } @else {
          <button class="chip-btn warn" (click)="ui.openDrawer({ kind: 'project', projectId: projectId() })" title="Sem pasta, os agentes só conversam e planejam">
            <sd-icon name="folder" [size]="12" /> Definir pasta
          </button>
        }
        @if (squadName(); as s) { <span class="chip" title="Squad do projeto"><sd-icon name="squad" [size]="11" /> {{ s }}</span> }
        <div class="seg views" role="tablist" aria-label="Área do projeto">
          <button role="tab" [attr.aria-selected]="layout().view === 'panes'" (click)="setView('panes')" title="Painéis de agentes (Ctrl+Shift+1)"><sd-icon name="layout-split" [size]="12" /> Painéis</button>
          <button role="tab" [attr.aria-selected]="layout().view === 'planning'" (click)="setView('planning')" title="Sala de planejamento (Ctrl+Shift+2)"><sd-icon name="chat" [size]="12" /> Planejamento</button>
          <button role="tab" [attr.aria-selected]="layout().view === 'features'" (click)="setView('features')" title="Sprints e features (Ctrl+Shift+3)"><sd-icon name="board" [size]="12" /> Features
            @if (reviewCount()) { <span class="badge" [attr.aria-label]="reviewCount() + ' aguardando revisão'">{{ reviewCount() }}</span> }</button>
        </div>
        <span class="spacer"></span>
        @if (layout().view === 'panes') {
        <div class="seg" role="radiogroup" aria-label="Disposição dos painéis">
          <button role="radio" [attr.aria-selected]="layout().mode === 'single'" [attr.aria-checked]="layout().mode === 'single'" (click)="setMode('single')" title="Um painel"><sd-icon name="layout-single" [size]="13" /><span class="sr-only">Um painel</span></button>
          <button role="radio" [attr.aria-selected]="layout().mode === 'split'" [attr.aria-checked]="layout().mode === 'split'" (click)="setMode('split')" title="Dois painéis"><sd-icon name="layout-split" [size]="13" /><span class="sr-only">Dois painéis</span></button>
          <button role="radio" [attr.aria-selected]="layout().mode === 'grid'" [attr.aria-checked]="layout().mode === 'grid'" (click)="setMode('grid')" title="Grade de quatro"><sd-icon name="layout-grid" [size]="13" /><span class="sr-only">Grade de quatro</span></button>
        </div>
        @if (hidden().length) {
          <button class="btn sm" (click)="$event.stopPropagation(); hiddenOpen.set(!hiddenOpen())" [attr.aria-expanded]="hiddenOpen()">
            <sd-icon name="eye" [size]="12" /> {{ hidden().length }} {{ hidden().length === 1 ? 'oculto' : 'ocultos' }}
          </button>
        }
        <button class="btn sm" (click)="newSession()" title="Nova sessão (Ctrl+Shift+N)"><sd-icon name="plus" [size]="12" /> Sessão</button>
        <button class="btn sm" [class.primary]="layout().side.open && layout().side.tab === 'docs'" (click)="toggleSide('docs')" title="Documentos do projeto (Ctrl+Shift+D)">
          <sd-icon name="doc" [size]="12" /> Documentos
        </button>
        <button class="btn sm icon" [class.primary]="layout().side.open && layout().side.tab === 'run'" (click)="toggleSide('run')" title="Execuções do painel em foco" aria-label="Execuções do painel em foco">
          <sd-icon name="history" [size]="13" />
        </button>
        }
      </div>
      @if (hiddenOpen()) {
        <div class="menu hidden-menu" role="menu" (click)="$event.stopPropagation()">
          @for (id of hidden(); track id) {
            <button role="menuitem" (click)="hiddenOpen.set(false); workspace.openPane(projectId(), id)">
              <sd-avatar [agent]="agentOf(id)" [size]="16" /> <span class="ellipsis">{{ sessions.session(id)?.title }}</span>
            </button>
          }
          <hr />
          @for (s of closedSessions(); track s.id) {
            <button role="menuitem" (click)="hiddenOpen.set(false); workspace.openPane(projectId(), s.id)">
              <sd-avatar [agent]="agentOf(s.id)" [size]="16" /> <span class="ellipsis">{{ s.title }}</span>
            </button>
          }
        </div>
      }
      @if (layout().view === 'planning') {
        <div class="body"><sd-planning-room [projectId]="projectId()" /></div>
      } @else if (layout().view === 'features') {
        <div class="body"><sd-features-view [projectId]="projectId()" /></div>
      } @else {
      <div class="body">
        @if (cells().length) {
          <div #grid class="grid" [style.grid-template-columns]="columns()" [style.grid-template-rows]="rows()" [style.grid-template-areas]="areas()">
            @for (cell of cells(); track cell.sessionId; let i = $index) {
              <div class="cell" [style.grid-area]="cell.area" [class.drop]="dropOn() === cell.sessionId"
                (dragover)="onDragOver($event, cell.sessionId)" (dragleave)="dropOn.set(null)" (drop)="onDrop($event, cell.sessionId)">
                <sd-pane [sessionId]="cell.sessionId" [projectId]="projectId()" [focused]="layout().focused === cell.sessionId"
                  [maximized]="layout().maximized === cell.sessionId" (focus)="workspace.focusPane(projectId(), cell.sessionId)"
                  (maximize)="workspace.toggleMaximize(projectId(), cell.sessionId)" (hide)="workspace.closePane(projectId(), cell.sessionId)"
                  (showRun)="workspace.setSide(projectId(), { open: true, tab: 'run' })" (dragStart)="dragging = $event" />
              </div>
            }
            @if (showColSplit()) {
              <div class="split v" style="grid-area: sv" role="separator" aria-orientation="vertical" tabindex="0" aria-label="Divisor entre colunas"
                [attr.aria-valuenow]="round(layout().colSplit * 100)" (pointerdown)="startSplit($event, 'col')" (dblclick)="workspace.setSplit(projectId(), 'col', 0.5)"
                (keydown.arrowleft)="nudge('col', -0.05)" (keydown.arrowright)="nudge('col', 0.05)"></div>
            }
            @if (showRowSplit()) {
              <div class="split h" style="grid-area: sh" role="separator" aria-orientation="horizontal" tabindex="0" aria-label="Divisor entre linhas"
                [attr.aria-valuenow]="round(layout().rowSplit * 100)" (pointerdown)="startSplit($event, 'row')" (dblclick)="workspace.setSplit(projectId(), 'row', 0.5)"
                (keydown.arrowup)="nudge('row', -0.05)" (keydown.arrowdown)="nudge('row', 0.05)"></div>
            }
          </div>
        } @else {
          <div class="empty-ws">
            <h2 style="color: var(--fg-1)">Nenhum painel aberto</h2>
            <p>Abra uma sessão com um agente para conversar, planejar ou executar tarefas neste projeto.</p>
            <div class="sessions">
              @for (s of sessions.sessions(projectId()); track s.id) {
                <button class="list-row" (click)="workspace.openPane(projectId(), s.id)">
                  <sd-avatar [agent]="agentOf(s.id)" [size]="22" /><span class="grow ellipsis">{{ s.title }}</span><sd-icon name="panel" [size]="13" />
                </button>
              }
            </div>
            <button class="btn primary" (click)="newSession()"><sd-icon name="plus" [size]="14" /> Nova sessão</button>
          </div>
        }
        @if (layout().side.open) {
          <aside class="side" [style.width.px]="layout().side.width" [attr.aria-label]="layout().side.tab === 'docs' ? 'Documentos do projeto' : 'Execuções'">
            <div class="side-resizer" (pointerdown)="startSide($event)" role="separator" aria-orientation="vertical" aria-label="Redimensionar painel lateral"></div>
            <div class="side-head">
              <div class="seg" role="tablist">
                <button role="tab" [attr.aria-selected]="layout().side.tab === 'docs'" (click)="workspace.setSide(projectId(), { tab: 'docs' })">Documentos</button>
                <button role="tab" [attr.aria-selected]="layout().side.tab === 'run'" (click)="workspace.setSide(projectId(), { tab: 'run' })">Execuções</button>
              </div>
              <span class="spacer"></span>
              <button class="btn ghost icon sm" (click)="workspace.setSide(projectId(), { open: false })" aria-label="Fechar painel lateral"><sd-icon name="x" [size]="13" /></button>
            </div>
            @if (layout().side.tab === 'docs') { <sd-documents-panel [projectId]="projectId()" /> }
            @else { <sd-runs-panel [sessionId]="layout().focused" /> }
          </aside>
        }
      </div>
      }
    } @else {
      <div class="empty"><h3>Projeto não encontrado</h3><p>Ele pode ter sido arquivado ou excluído.</p>
        <button class="btn" (click)="nav.home()">Voltar ao início</button></div>
    }
  `,
})
export class WorkspacePage {
  /** Vem da rota /projeto/:id (withComponentInputBinding). */
  readonly id = input.required<string>();

  protected readonly ui = inject(UiStore);
  protected readonly nav = inject(Nav);
  protected readonly sessions = inject(SessionsStore);
  protected readonly workspace = inject(WorkspaceStore);
  private readonly catalog = inject(CatalogStore);
  private readonly actions = inject(ProjectActions);
  private readonly desktop = inject(Desktop);
  private readonly title = inject(Title);
  private readonly gridRef = viewChild<ElementRef<HTMLElement>>('grid');
  private readonly panes = viewChildren(Pane);

  protected readonly hiddenOpen = signal(false);
  protected readonly dropOn = signal<string | null>(null);
  protected dragging: string | null = null;
  protected readonly round = Math.round;

  protected readonly projectId = computed(() => this.id());
  protected readonly view = computed(() => this.catalog.projectById().get(this.projectId()));
  protected readonly layout = computed(() => {
    this.workspace.state();
    return this.workspace.layout(this.projectId());
  });
  protected readonly folderName = computed(() => baseName(this.view()?.project.folderPath));
  protected readonly squadName = computed(() => this.catalog.squadById().get(this.view()?.project.squadId ?? '')?.name);
  protected readonly visible = computed(() => visiblePanes(this.layout()).filter(id => !!this.sessions.session(id) || !this.loaded()));
  protected readonly hidden = computed(() => hiddenPanes(this.layout()));
  protected readonly closedSessions = computed(() => {
    const open = new Set(this.layout().panes);
    return this.sessions.sessions(this.projectId()).filter(s => !open.has(s.id));
  });
  private readonly loaded = computed(() => this.sessions.byProject()[this.projectId()] !== undefined);
  private readonly work = inject(ProjectWorkStore);
  protected readonly reviewCount = computed(() => (this.work.overviews()[this.projectId()]?.features ?? []).filter(f => f.status === 'IN_REVIEW').length);

  protected readonly cells = computed<Cell[]>(() => {
    const ids = this.visible();
    const n = ids.length;
    const mode = this.layout().maximized ? 'single' : this.layout().mode;
    if (n === 0) return [];
    if (n === 1 || mode === 'single') return [{ sessionId: ids[0], area: 'a' }];
    if (n === 2 || mode === 'split') return [{ sessionId: ids[0], area: 'a' }, { sessionId: ids[1], area: 'b' }];
    if (n === 3) return [{ sessionId: ids[0], area: 'a' }, { sessionId: ids[1], area: 'b' }, { sessionId: ids[2], area: 'c' }];
    return ids.slice(0, 4).map((sessionId, i) => ({ sessionId, area: 'abcd'[i] }));
  });

  protected readonly showColSplit = computed(() => this.cells().length >= 2);
  protected readonly showRowSplit = computed(() => this.cells().length >= 3);

  protected readonly columns = computed(() => {
    if (!this.showColSplit()) return '1fr';
    const c = this.layout().colSplit;
    return `minmax(0, ${c}fr) 6px minmax(0, ${1 - c}fr)`;
  });
  protected readonly rows = computed(() => {
    if (!this.showRowSplit()) return '1fr';
    const r = this.layout().rowSplit;
    return `minmax(0, ${r}fr) 6px minmax(0, ${1 - r}fr)`;
  });
  protected readonly areas = computed(() => {
    const n = this.cells().length;
    if (n <= 1) return '"a"';
    if (n === 2) return '"a sv b"';
    if (n === 3) return '"a sv b" "sh sh sh" "c c c"';
    return '"a sv b" "sh sh sh" "c sv2 d"';
  });

  constructor() {
    effect(() => {
      const id = this.projectId();
      const view = this.view();
      this.title.setTitle(`${view?.project.name ?? 'Projeto'} · Shiro`);
      if (!view) return;
      this.workspace.openTab(id);
      void this.work.load(id, 'features').catch(() => undefined);
      void this.sessions.loadProject(id).then(list => {
        this.workspace.prunePanes(id, new Set(list.map(s => s.id)));
        // primeira abertura: mostra as sessões existentes até a capacidade do layout
        const layout = this.workspace.layout(id);
        if (layout.panes.length === 0 && list.length) {
          for (const s of list.slice(0, capacity(layout.mode))) this.workspace.openPane(id, s.id);
        }
      });
    });
  }

  @HostListener('document:click')
  protected closeMenus(): void {
    if (this.hiddenOpen()) this.hiddenOpen.set(false);
  }

  @HostListener('window:keydown', ['$event'])
  protected onKey(event: KeyboardEvent): void {
    if (this.ui.drawer() || this.ui.paletteOpen() || this.ui.confirmRequest()) return;
    const mod = event.ctrlKey || event.metaKey;
    if (!mod) return;
    const key = event.key;
    if (event.shiftKey && /^Digit[1-3]$/.test(event.code)) {
      event.preventDefault();
      this.setView((['panes', 'planning', 'features'] as const)[Number(event.code.slice(5)) - 1]);
      return;
    }
    if (this.layout().view !== 'panes') return;
    if (!event.shiftKey && /^[1-4]$/.test(key)) {
      const id = this.cells()[Number(key) - 1]?.sessionId;
      if (id) {
        event.preventDefault();
        this.workspace.focusPane(this.projectId(), id);
        this.panes().find(p => p.sessionId() === id)?.focusComposer();
      }
    } else if (event.shiftKey && key === 'Enter') {
      const id = this.layout().focused;
      if (id) {
        event.preventDefault();
        this.workspace.toggleMaximize(this.projectId(), id);
      }
    } else if (event.shiftKey && key.toLowerCase() === 'n') {
      event.preventDefault();
      this.newSession();
    } else if (event.shiftKey && key.toLowerCase() === 'd') {
      event.preventDefault();
      this.toggleSide('docs');
    }
  }

  protected agentOf(sessionId: string) {
    return this.catalog.agentById().get(this.sessions.session(sessionId)?.agentId ?? '');
  }

  protected setView(view: ProjectView): void {
    this.workspace.setView(this.projectId(), view);
  }

  protected setMode(mode: PaneMode): void {
    this.workspace.setMode(this.projectId(), mode);
  }

  protected newSession(): void {
    this.ui.openDrawer({ kind: 'new-session', projectId: this.projectId() });
  }

  protected toggleSide(tab: 'docs' | 'run'): void {
    const side = this.layout().side;
    this.workspace.setSide(this.projectId(), side.open && side.tab === tab ? { open: false } : { open: true, tab });
  }

  protected reveal(): void {
    if (this.desktop.available) void this.actions.reveal(this.projectId());
  }

  protected nudge(axis: 'col' | 'row', delta: number): void {
    const current = axis === 'col' ? this.layout().colSplit : this.layout().rowSplit;
    this.workspace.setSplit(this.projectId(), axis, current + delta);
  }

  protected startSplit(event: PointerEvent, axis: 'col' | 'row'): void {
    const grid = this.gridRef()?.nativeElement;
    const handle = event.target as HTMLElement;
    if (!grid) return;
    event.preventDefault();
    handle.setPointerCapture(event.pointerId);
    handle.classList.add('active');
    const rect = grid.getBoundingClientRect();
    const move = (e: PointerEvent) => {
      const fraction = axis === 'col' ? (e.clientX - rect.left - 8) / (rect.width - 22) : (e.clientY - rect.top - 8) / (rect.height - 22);
      this.workspace.setSplit(this.projectId(), axis, fraction, false);
    };
    const up = () => {
      handle.classList.remove('active');
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', up);
      this.workspace.flush();
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', up);
  }

  protected startSide(event: PointerEvent): void {
    const handle = event.target as HTMLElement;
    event.preventDefault();
    handle.setPointerCapture(event.pointerId);
    const startX = event.clientX;
    const startWidth = this.layout().side.width;
    const move = (e: PointerEvent) => this.workspace.setSide(this.projectId(), { width: startWidth - (e.clientX - startX) }, false);
    const up = () => {
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', up);
      this.workspace.flush();
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', up);
  }

  protected onDragOver(event: DragEvent, target: string): void {
    if (!this.dragging || this.dragging === target) return;
    event.preventDefault();
    this.dropOn.set(target);
  }

  protected onDrop(event: DragEvent, target: string): void {
    event.preventDefault();
    this.dropOn.set(null);
    if (this.dragging && this.dragging !== target) this.workspace.swapPanes(this.projectId(), this.dragging, target);
    this.dragging = null;
  }
}
