import { ChangeDetectionStrategy, Component, HostListener, computed, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { CatalogStore } from '../state/catalog.store';
import { UiStore } from '../state/ui.store';
import { WorkspaceStore } from '../state/workspace.store';
import { CommandPalette } from './command-palette';
import { DrawerHost } from './drawer-host';
import { Nav } from './nav';
import { Dialogs } from './dialogs';
import { Sidebar } from './sidebar';
import { TopBar } from './top-bar';

@Component({
  selector: 'sd-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, TopBar, Sidebar, CommandPalette, DrawerHost, Dialogs],
  host: { '[style.--sidebar-w.px]': 'sidebarWidth()', '[class.collapsed]': 'workspace.sidebar().collapsed' },
  styles: `
    :host { display: grid; grid-template-rows: var(--topbar-h) 1fr; grid-template-columns: var(--sidebar-w) 1fr; height: 100vh; }
    sd-top-bar { grid-column: 1 / -1; }
    /* sobreposições são fixas: não podem virar células da grade (isso empurrava a área principal para fora da tela) */
    sd-drawer-host, sd-dialogs, sd-command-palette { position: absolute; }
    main { position: relative; min-width: 0; min-height: 0; overflow: hidden; background: var(--bg-1);
      border-top-left-radius: var(--r-lg); border-top: 1px solid var(--line-1); border-left: 1px solid var(--line-1); }
    :host(.collapsed) main { border-top-left-radius: 0; border-left: 0; }
  `,
  template: `
    <sd-top-bar />
    <sd-sidebar />
    <main id="conteudo" tabindex="-1"><router-outlet /></main>
    <sd-drawer-host />
    <sd-dialogs />
    @if (ui.paletteOpen()) { <sd-command-palette /> }
  `,
})
export class Shell {
  protected readonly ui = inject(UiStore);
  protected readonly workspace = inject(WorkspaceStore);
  private readonly nav = inject(Nav);
  private readonly catalog = inject(CatalogStore);

  protected readonly sidebarWidth = computed(() => (this.workspace.sidebar().collapsed ? 48 : this.workspace.sidebar().width));

  @HostListener('window:keydown', ['$event'])
  onKey(event: KeyboardEvent): void {
    const mod = event.ctrlKey || event.metaKey;
    if (!mod) return;
    const key = event.key.toLowerCase();
    if (key === 'k' || (event.shiftKey && key === 'p')) {
      event.preventDefault();
      this.ui.paletteOpen.update(open => !open);
    } else if (key === 'b' && !event.shiftKey) {
      event.preventDefault();
      this.workspace.toggleSidebar();
    } else if (key === 'tab') {
      event.preventDefault();
      this.cycleTabs(event.shiftKey ? -1 : 1);
    } else if (event.shiftKey && key === 'w') {
      const active = this.nav.activeProjectId();
      if (active) {
        event.preventDefault();
        this.nav.closeProjectTab(active);
      }
    } else if (key === ',') {
      event.preventDefault();
      this.nav.go('configuracoes');
    }
  }

  private cycleTabs(step: number): void {
    const tabs = this.workspace.tabs().filter(id => this.catalog.projectById().has(id));
    const order: (string | null)[] = [null, ...tabs];
    const index = order.indexOf(this.nav.activeProjectId());
    const next = order[(index + step + order.length) % order.length];
    if (next) void this.nav.openProject(next);
    else this.nav.home();
  }
}
