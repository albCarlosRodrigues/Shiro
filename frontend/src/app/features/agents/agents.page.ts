import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Api } from '../../core/api';
import { ROLE_LABEL } from '../../core/format';
import type { Agent, AgentRole } from '../../core/models';
import { Avatar } from '../../shell/bits';
import { Icon } from '../../shell/icon';
import { CatalogStore } from '../../state/catalog.store';
import { UiStore } from '../../state/ui.store';

/** Cadastro de agentes, agrupados por papel. Editar, duplicar e exportar acontecem no Estúdio do Agente. */
@Component({
  selector: 'sd-agents-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, Avatar],
  styles: `
    :host { display: block; height: 100%; overflow: auto; }
    .wrap { max-width: 1120px; margin: 0 auto; padding: 24px 28px 40px; }
    .head { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; }
    .list { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 10px; margin-bottom: 22px; }
    .agent { display: grid; grid-template-columns: auto 1fr; gap: 10px; padding: 12px; border: 1px solid var(--line-1); border-radius: var(--r-md);
      background: var(--bg-2); color: var(--fg-1); text-align: left; cursor: pointer; }
    .agent:hover { border-color: var(--line-3); background: var(--bg-3); }
    .name { font-weight: 600; }
    .meta { color: var(--fg-3); font-size: var(--fs-xs); }
    .resp { color: var(--fg-2); font-size: var(--fs-sm); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; margin-top: 4px; }
    .tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
  `,
  template: `
    <div class="wrap">
      <div class="head">
        <h1 class="grow">Agentes</h1>
        <label class="check"><input type="checkbox" [checked]="showArchived()" (change)="toggleArchived($any($event.target).checked)" /> Mostrar arquivados</label>
        <button class="btn primary" (click)="ui.openDrawer({ kind: 'agent', agentId: null })"><sd-icon name="plus" [size]="14" /> Novo agente</button>
      </div>
      @for (group of groups(); track group.role) {
        <div class="section-title" style="margin-bottom: 8px">{{ roleLabel[group.role] }}</div>
        <div class="list">
          @for (a of group.agents; track a.id) {
            <button class="agent" (click)="ui.openDrawer({ kind: 'agent', agentId: a.id })">
              <sd-avatar [agent]="a" [size]="34" />
              <span>
                <span class="name">{{ a.name }}</span> @if (a.archivedAt) { <span class="chip">arquivado</span> }
                <span class="meta" style="display:block">{{ a.specialty || roleLabel[a.role] }}</span>
                @if (a.responsibility) { <span class="resp">{{ a.responsibility }}</span> }
                <span class="tags">
                  <span class="chip">{{ a.model ?? 'modelo padrão' }}</span>
                  @if (a.effort) { <span class="chip">esforço {{ a.effort }}</span> }
                  @if (squadsOf(a.id); as n) { <span class="chip">{{ n }} {{ n === 1 ? 'squad' : 'squads' }}</span> }
                  @if (a.sample) { <span class="chip">exemplo</span> }
                </span>
              </span>
            </button>
          }
        </div>
      } @empty {
        <div class="empty">
          <h3>Nenhum agente</h3>
          <p>Crie um agente do zero ou a partir de um modelo no Estúdio do Agente.</p>
          <button class="btn primary" (click)="ui.openDrawer({ kind: 'agent', agentId: null })">Abrir o Estúdio</button>
        </div>
      }
    </div>
  `,
})
export class AgentsPage {
  protected readonly ui = inject(UiStore);
  private readonly catalog = inject(CatalogStore);
  private readonly api = inject(Api);
  protected readonly roleLabel = ROLE_LABEL;
  protected readonly showArchived = signal(false);
  private readonly archived = signal<Agent[]>([]);

  protected readonly groups = computed(() => {
    const all = [...this.catalog.agents(), ...(this.showArchived() ? this.archived() : [])];
    const roles: AgentRole[] = ['COMMANDER', 'REQUIREMENTS', 'ARCHITECTURE', 'OPERATOR'];
    return roles.map(role => ({ role, agents: all.filter(a => a.role === role).sort((a, b) => a.name.localeCompare(b.name)) })).filter(g => g.agents.length);
  });

  protected squadsOf(agentId: string): number {
    return this.catalog.squads().filter(s => [s.commanderId, s.requirementsId, s.architectureId, ...s.operatorIds].includes(agentId)).length;
  }

  protected async toggleArchived(on: boolean): Promise<void> {
    this.showArchived.set(on);
    if (on) {
      const all = await this.api.get<Agent[]>('agents', { archived: true });
      this.archived.set(all.filter(a => a.archivedAt));
    }
  }
}
