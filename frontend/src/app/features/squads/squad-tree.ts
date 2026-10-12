import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { ROLE_LABEL } from '../../core/format';
import type { Agent } from '../../core/models';
import { Avatar } from '../../shell/bits';
import { CatalogStore } from '../../state/catalog.store';

/** Composição de uma squad como árvore: comandante no topo, especialistas de planejamento e operadores abaixo. */
@Component({
  selector: 'sd-squad-tree',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Avatar],
  styles: `
    ul { list-style: none; margin: 0; padding: 0; }
    ul ul { margin-left: 11px; padding-left: 14px; border-left: 1px solid var(--line-2); }
    li { position: relative; }
    ul ul > li::before { content: ''; position: absolute; left: -14px; top: 15px; width: 10px; border-top: 1px solid var(--line-2); }
    .node { display: flex; align-items: center; gap: 8px; min-height: 30px; padding: 3px 0; min-width: 0; }
    .node > b, .name, .role { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .node > b, .name { flex: 0 1 auto; }
    .role { flex: 0 4 auto; }
    .role { color: var(--fg-3); font-size: var(--fs-xs); }
    .missing { color: var(--warn); font-size: var(--fs-sm); }
    .group { color: var(--fg-3); font-size: var(--fs-xs); font-weight: 600; letter-spacing: 0.05em; text-transform: uppercase; height: 26px; display: flex; align-items: center; }
  `,
  template: `
    <ul role="tree" aria-label="Composição da squad">
      <li role="treeitem" aria-expanded="true">
        <div class="node">
          @if (commander(); as a) { <sd-avatar [agent]="a" [size]="22" /><b>{{ a.name }}</b><span class="role">{{ label.COMMANDER }}</span> }
          @else { <span class="missing">Sem comandante</span> }
        </div>
        <ul role="group">
          <li role="treeitem" aria-expanded="true">
            <div class="group">Planejamento</div>
            <ul role="group">
              <li role="treeitem"><div class="node">
                @if (requirements(); as a) { <sd-avatar [agent]="a" [size]="20" /><span class="name" [attr.title]="a.name">{{ a.name }}</span><span class="role">{{ label.REQUIREMENTS }}</span> }
                @else { <span class="missing">Sem especialista em requisitos</span> }
              </div></li>
              <li role="treeitem"><div class="node">
                @if (architecture(); as a) { <sd-avatar [agent]="a" [size]="20" /><span class="name" [attr.title]="a.name">{{ a.name }}</span><span class="role">{{ label.ARCHITECTURE }}</span> }
                @else { <span class="missing">Sem especialista em arquitetura</span> }
              </div></li>
            </ul>
          </li>
          <li role="treeitem" aria-expanded="true">
            <div class="group">Operadores ({{ operators().length }})</div>
            <ul role="group">
              @for (a of operators(); track a.id) {
                <li role="treeitem"><div class="node"><sd-avatar [agent]="a" [size]="20" /><span class="name" [attr.title]="a.name">{{ a.name }}</span><span class="role">{{ a.specialty }}</span></div></li>
              } @empty {
                <li role="treeitem"><div class="node missing">Nenhum operador</div></li>
              }
            </ul>
          </li>
        </ul>
      </li>
    </ul>
  `,
})
export class SquadTree {
  readonly commanderId = input<string | null>(null);
  readonly requirementsId = input<string | null>(null);
  readonly architectureId = input<string | null>(null);
  readonly operatorIds = input<string[]>([]);

  private readonly catalog = inject(CatalogStore);
  protected readonly label = ROLE_LABEL;
  private readonly byId = computed(() => this.catalog.agentById());

  protected readonly commander = computed(() => this.byId().get(this.commanderId() ?? ''));
  protected readonly requirements = computed(() => this.byId().get(this.requirementsId() ?? ''));
  protected readonly architecture = computed(() => this.byId().get(this.architectureId() ?? ''));
  protected readonly operators = computed(() => this.operatorIds().map(id => this.byId().get(id)).filter((a): a is Agent => !!a));
}
