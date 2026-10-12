import { ChangeDetectionStrategy, Component, ElementRef, HostListener, effect, inject } from '@angular/core';

import { AgentStudio } from '../features/agents/agent-studio';
import { NewSessionDrawer } from '../features/workspace/new-session';
import { ProjectForm } from '../features/projects/project-form';
import { SquadEditor } from '../features/squads/squad-editor';
import { UiStore } from '../state/ui.store';

/** Um drawer por vez, à direita, sobre a área de trabalho. As execuções continuam por baixo. */
@Component({
  selector: 'sd-drawer-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ProjectForm, AgentStudio, SquadEditor, NewSessionDrawer],
  // o host e os componentes de drawer não ocupam espaço no layout da janela: só o scrim e o drawer (fixos) aparecem
  styles: `
    :host, sd-project-form, sd-agent-studio, sd-squad-editor, sd-new-session { display: contents; }
  `,
  template: `
    @if (ui.drawer(); as d) {
      <div class="scrim" (click)="ui.closeDrawer()"></div>
      @switch (d.kind) {
        @case ('project') { <sd-project-form [projectId]="d.projectId" [groupId]="d.groupId ?? null" /> }
        @case ('agent') { <sd-agent-studio [agentId]="d.agentId" [templateKey]="d.templateKey ?? null" /> }
        @case ('squad') { <sd-squad-editor [squadId]="d.squadId" /> }
        @case ('new-session') { <sd-new-session [projectId]="d.projectId" /> }
      }
    }
  `,
})
export class DrawerHost {
  protected readonly ui = inject(UiStore);
  private readonly host = inject(ElementRef<HTMLElement>);

  constructor() {
    // ao abrir, leva o foco para dentro do drawer (teclado e leitores de tela começam nele)
    effect(() => {
      if (!this.ui.drawer()) return;
      setTimeout(() => {
        const drawer = (this.host.nativeElement as HTMLElement).querySelector<HTMLElement>('.drawer');
        if (!drawer || drawer.contains(document.activeElement)) return;
        const first = drawer.querySelector<HTMLElement>('input:not([disabled]), select, textarea, button:not([disabled])');
        (first ?? drawer).focus({ preventScroll: true });
      });
    });
  }

  /** Esc fecha o drawer mesmo com o foco fora dele; confirmações e paleta têm prioridade. */
  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    if (this.ui.drawer() && !this.ui.confirmRequest() && !this.ui.promptRequest() && !this.ui.paletteOpen()) this.ui.closeDrawer();
  }
}
