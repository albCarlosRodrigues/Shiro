import { ShiroMark } from './shiro-mark';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Desktop } from '../core/desktop';
import { EventStream } from '../core/event-stream';
import { CatalogStore } from '../state/catalog.store';
import { SessionsStore } from '../state/sessions.store';
import { UiStore } from '../state/ui.store';
import { WorkspaceStore } from '../state/workspace.store';
import { Icon } from './icon';
import { Nav } from './nav';

/**
 * Barra superior baixa: marca, abas dos projetos abertos com indicador de atividade, estado do serviço
 * e do Claude Code, paleta de comandos e espaço para os controles nativos da janela.
 */
@Component({
  selector: 'sd-top-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ShiroMark, Icon],
  host: { '[style.--controls-w.px]': 'controlsWidth' },
  styles: `
    :host { display: flex; align-items: stretch; height: var(--topbar-h); padding-right: var(--controls-w); background: var(--bg-0);
      -webkit-app-region: drag; app-region: drag; }
    button, [role='tab'], .tabs { -webkit-app-region: no-drag; app-region: no-drag; }
    .brand { display: flex; align-items: center; gap: 8px; padding: 0 12px 0 12px; color: var(--fg-2); font-weight: 600; font-size: var(--fs-sm); white-space: nowrap; }
    .mark { display: grid; place-items: center; width: 20px; height: 20px; border-radius: 5px; background: var(--signal); color: var(--signal-ink); }
    .tabs { display: flex; align-items: flex-end; gap: 2px; min-width: 0; overflow-x: auto; scrollbar-width: none; padding-top: 5px; }
    .tab { position: relative; display: flex; align-items: center; gap: 7px; height: 31px; max-width: 220px; padding: 0 6px 0 10px;
      border: 0; border-radius: var(--r-md) var(--r-md) 0 0; background: transparent; color: var(--fg-3); font-size: var(--fs-sm); cursor: pointer; }
    .tab:hover { color: var(--fg-1); background: var(--bg-2); }
    .tab[aria-selected='true'] { color: var(--fg-1); background: var(--bg-1); box-shadow: inset 0 2px 0 var(--tab-c, var(--signal)); }
    .tab .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .tab .close { display: grid; place-items: center; width: 18px; height: 18px; padding: 0; border: 0; border-radius: 4px; background: transparent; color: var(--fg-3); cursor: pointer; opacity: 0; }
    .tab:hover .close, .tab[aria-selected='true'] .close, .tab .close:focus-visible { opacity: 1; }
    .tab .close:hover { background: var(--bg-4); color: var(--fg-1); }
    .count { display: inline-flex; align-items: center; gap: 3px; color: var(--st-running); font-size: var(--fs-xs); font-weight: 600; }
    .home { padding: 0 10px; }
    .right { display: flex; align-items: center; gap: 6px; margin-left: auto; padding: 0 8px; white-space: nowrap; }
    .pill { display: inline-flex; align-items: center; gap: 5px; height: 22px; padding: 0 8px; border: 0; border-radius: 11px;
      background: var(--bg-3); color: var(--fg-2); font-size: var(--fs-xs); cursor: pointer; }
    .pill:hover { background: var(--bg-4); color: var(--fg-1); }
    .pill.bad { color: var(--warn); }
    .search { width: 200px; justify-content: flex-start; color: var(--fg-3); }
    .search kbd { margin-left: auto; }
    .winbtn { width: 44px; height: 100%; border: 0; background: transparent; color: var(--fg-2); display: grid; place-items: center; cursor: pointer; }
    .winbtn:hover { background: var(--bg-3); color: var(--fg-1); }
    .winbtn.close:hover { background: #c4423a; color: #fff; }
    @media (max-width: 1400px) { .search { width: auto; } .search .label, .search kbd { display: none; } }
  `,
  template: `
    <div class="brand"><sd-shiro-mark /><span>Shiro</span></div>
    <div class="tabs" role="tablist" aria-label="Projetos abertos">
      <button class="tab home" role="tab" [attr.aria-selected]="!nav.activeProjectId() && isHome()" (click)="nav.home()" title="Início">
        <sd-icon name="home" [size]="14" /><span class="sr-only">Início</span>
      </button>
      @for (tab of tabs(); track tab.id) {
        <div class="tab" role="tab" tabindex="0" [attr.aria-selected]="nav.activeProjectId() === tab.id" [style.--tab-c]="tab.color"
          (click)="nav.openProject(tab.id)" (keydown.enter)="nav.openProject(tab.id)" (auxclick)="$event.button === 1 && nav.closeProjectTab(tab.id)"
          draggable="true" (dragstart)="drag.set(tab.id)" (dragover)="$event.preventDefault()" (drop)="drop(tab.id)" [attr.title]="tab.name">
          <span class="swatch" [style.--swatch]="tab.color"></span>
          <span class="name">{{ tab.name }}</span>
          @if (tab.active > 0) {
            <span class="count" [attr.title]="tab.active + (tab.active === 1 ? ' execução ativa' : ' execuções ativas')">
              <sd-icon name="spinner" class="spin" [size]="11" />{{ tab.active }}
            </span>
          }
          <button class="close" type="button" (click)="$event.stopPropagation(); nav.closeProjectTab(tab.id)" [attr.aria-label]="'Fechar aba ' + tab.name">
            <sd-icon name="x" [size]="11" />
          </button>
        </div>
      }
      <button class="tab" type="button" (click)="ui.paletteOpen.set(true)" title="Abrir projeto (Ctrl+K)" aria-label="Abrir projeto"><sd-icon name="plus" [size]="13" /></button>
    </div>
    <div class="right">
      @if (catalog.demoMode()) { <span class="demo-tag" title="Execuções simuladas, sem Claude Code e sem consumo de IA">Demonstração</span> }
      @if (sessions.queue()?.paused) {
        <button class="pill bad" type="button" (click)="sessions.pauseQueue(false)" title="Fila pausada: novas execuções não começam. Clique para retomar.">
          <sd-icon name="pause" [size]="12" /> Fila pausada
        </button>
      }
      @if (stream.state() === 'retrying') {
        <span class="pill bad" title="Reconectando ao serviço local para receber eventos"><sd-icon name="refresh" [size]="12" class="spin" /> Reconectando</span>
      }
      <button class="pill" type="button" [class.bad]="!claudeOk()" (click)="nav.go('configuracoes')" [attr.title]="claudeTitle()">
        <sd-icon [name]="claudeOk() ? 'plug' : 'alert'" [size]="12" />{{ claudeLabel() }}
      </button>
      <button class="pill search" type="button" (click)="ui.paletteOpen.set(true)" aria-label="Paleta de comandos">
        <sd-icon name="search" [size]="12" /><span class="label">Buscar ou executar</span><kbd>Ctrl K</kbd>
      </button>
    </div>
    @if (ownControls) {
      <button class="winbtn" (click)="desktop.window('minimize')" aria-label="Minimizar"><sd-icon name="win_min" [size]="12" /></button>
      <button class="winbtn" (click)="desktop.window('toggle-maximize')" [attr.aria-label]="desktop.maximized() ? 'Restaurar' : 'Maximizar'">
        <sd-icon [name]="desktop.maximized() ? 'win_restore' : 'win_max'" [size]="12" />
      </button>
      <button class="winbtn close" (click)="desktop.window('close')" aria-label="Fechar"><sd-icon name="win_close" [size]="12" /></button>
    }
  `,
})
export class TopBar {
  protected readonly nav = inject(Nav);
  protected readonly ui = inject(UiStore);
  protected readonly catalog = inject(CatalogStore);
  protected readonly sessions = inject(SessionsStore);
  protected readonly stream = inject(EventStream);
  protected readonly desktop = inject(Desktop);
  private readonly workspace = inject(WorkspaceStore);

  /** Com titleBarOverlay o Electron desenha os botões nativos; reservamos o espaço informado pelo preload. */
  protected readonly controlsWidth = this.desktop.bridge?.windowControlsWidth ?? 0;
  protected readonly ownControls = this.desktop.available && this.controlsWidth === 0 && this.desktop.bridge?.platform !== 'darwin';
  protected readonly drag = signal<string | null>(null);

  protected readonly tabs = computed(() => {
    const byId = this.catalog.projectById();
    const active = this.sessions.activeByProject();
    return this.workspace.tabs().flatMap(id => {
      const view = byId.get(id);
      return view ? [{ id, name: view.project.name, color: view.project.color, active: active[id] ?? 0 }] : [];
    });
  });

  protected readonly isHome = computed(() => {
    const url = this.nav.currentUrl();
    return url === '/' || url === '';
  });

  protected readonly claudeOk = computed(() => {
    const c = this.catalog.claude();
    return this.catalog.demoMode() || c?.problemCode === 'checking' || (!!c?.usable && c.authenticated !== false);
  });

  protected readonly claudeLabel = computed(() => {
    const c = this.catalog.claude();
    if (c?.problemCode === 'checking') return 'Verificando Claude Code…';
    if (!c || !c.found) return 'Claude Code ausente';
    if (!c.usable) return 'Claude Code com problema';
    if (c.authenticated === false) return 'Claude Code sem login';
    return 'Claude Code ' + (c.capabilities?.version ?? c.version ?? '');
  });

  protected readonly claudeTitle = computed(() => {
    const c = this.catalog.claude();
    return c?.problem ?? (c?.executable ? `Executável: ${c.executable}` : 'Ver diagnóstico do Claude Code');
  });

  protected drop(targetId: string): void {
    const dragged = this.drag();
    this.drag.set(null);
    if (!dragged || dragged === targetId) return;
    this.workspace.moveTab(dragged, this.workspace.tabs().indexOf(targetId));
  }
}
