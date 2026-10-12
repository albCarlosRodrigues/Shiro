import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { runStatusInfo } from '../core/format';
import type { Agent, RunStatus } from '../core/models';
import { Icon, ICON_NAMES } from './icon';

/** Avatar do agente: ícone próprio sobre a cor do agente. */
@Component({
  selector: 'sd-avatar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  host: { '[style.--c]': 'agent()?.color ?? "#85848a"', '[style.--s.px]': 'size()', '[attr.title]': 'agent()?.name' },
  styles: `:host { display: inline-grid; place-items: center; flex: none; width: var(--s); height: var(--s);
      border-radius: calc(var(--s) * 0.28); color: color-mix(in srgb, var(--c) var(--agent-ink-mix), var(--fg-1)); background: color-mix(in srgb, var(--c) 18%, transparent);
      box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--c) 35%, transparent); }`,
  template: `<sd-icon [name]="icon()" [size]="iconSize()" />`,
})
export class Avatar {
  readonly agent = input<Agent | null | undefined>(null);
  readonly size = input(22);
  protected readonly icon = computed(() => {
    const name = this.agent()?.icon ?? 'agent';
    return ICON_NAMES.includes(name) ? name : 'agent';
  });
  protected readonly iconSize = computed(() => Math.round(this.size() * 0.62));
}

/** Estado de execução com ícone, texto e cor (a cor nunca é a única pista). */
@Component({
  selector: 'sd-status',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <span class="status" [attr.data-status]="info().tone" [attr.title]="compact() ? info().label : null">
      <sd-icon [name]="info().icon" [size]="12" [class.spin]="info().icon === 'spinner'" />
      @if (!compact()) { <span>{{ label() ?? info().label }}</span> } @else { <span class="sr-only">{{ info().label }}</span> }
    </span>`,
})
export class StatusBadge {
  readonly status = input<RunStatus | null | undefined>(null);
  readonly compact = input(false);
  readonly label = input<string | null>(null);
  protected readonly info = computed(() => runStatusInfo(this.status()));
}
