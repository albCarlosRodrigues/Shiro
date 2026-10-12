import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { Icon } from '../../shell/icon';
import { CatalogStore } from '../../state/catalog.store';
import { UiStore } from '../../state/ui.store';
import { SquadTree } from './squad-tree';

/** Squads reutilizáveis entre projetos, cada uma apresentada como árvore. */
@Component({
  selector: 'sd-squads-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, SquadTree],
  styles: `
    :host { display: block; height: 100%; overflow: auto; }
    .wrap { max-width: 1120px; margin: 0 auto; padding: 24px 28px 40px; }
    .head { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; }
    .list { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 12px; }
    .squad { padding: 14px; }
    .squad header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
    .desc { color: var(--fg-3); font-size: var(--fs-sm); margin-bottom: 8px; }
    .used { margin-top: 8px; color: var(--fg-3); font-size: var(--fs-xs); }
  `,
  template: `
    <div class="wrap">
      <div class="head">
        <h1 class="grow">Squads</h1>
        <button class="btn primary" (click)="ui.openDrawer({ kind: 'squad', squadId: null })"><sd-icon name="plus" [size]="14" /> Nova squad</button>
      </div>
      <div class="list">
        @for (s of catalog.squads(); track s.id) {
          <div class="card squad">
            <header>
              <sd-icon name="squad" />
              <h2 class="grow ellipsis">{{ s.name }}</h2>
              @if (s.sample) { <span class="chip">exemplo</span> }
              <button class="btn sm" (click)="ui.openDrawer({ kind: 'squad', squadId: s.id })"><sd-icon name="edit" [size]="12" /> Editar</button>
            </header>
            @if (s.description) { <div class="desc">{{ s.description }}</div> }
            <sd-squad-tree [commanderId]="s.commanderId" [requirementsId]="s.requirementsId" [architectureId]="s.architectureId" [operatorIds]="s.operatorIds" />
            <div class="used">{{ projectsUsing(s.id) }}</div>
          </div>
        } @empty {
          <div class="empty">
            <h3>Nenhuma squad</h3>
            <p>Uma squad reúne um comandante, um especialista em requisitos, um em arquitetura e operadores, e pode ser usada em vários projetos.</p>
            <button class="btn primary" (click)="ui.openDrawer({ kind: 'squad', squadId: null })">Montar squad</button>
          </div>
        }
      </div>
    </div>
  `,
})
export class SquadsPage {
  protected readonly ui = inject(UiStore);
  protected readonly catalog = inject(CatalogStore);

  protected projectsUsing(squadId: string): string {
    const names = this.catalog.projects().filter(v => v.project.squadId === squadId).map(v => v.project.name);
    return names.length ? `Em uso por: ${names.join(', ')}` : 'Ainda não usada em projetos.';
  }
}
