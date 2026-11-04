import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { Desktop } from '../../core/desktop';
import { Toasts } from '../../core/toasts';
import { Icon } from '../../shell/icon';

interface BackgroundMode {
  keepRunningInTray: boolean;
  trayAvailable: boolean;
}

/** Preferência do app desktop: o X esconde na bandeja (agentes continuam) ou encerra. Só aparece no Electron. */
@Component({
  selector: 'sd-desktop-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  styles: `
    :host { display: block; margin-bottom: 18px; }
    :host:empty { display: none; }
    section { padding: 16px; }
    h2 { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
    p { margin-top: 8px; }
  `,
  template: `
    @if (mode(); as m) {
      <section class="card">
        <h2><sd-icon name="panel" /> Ao fechar a janela</h2>
        <label class="check">
          <input type="checkbox" [checked]="m.keepRunningInTray" [disabled]="!m.trayAvailable || saving()" (change)="toggle($event)" />
          Manter o Shiro rodando na bandeja
        </label>
        @if (!m.trayAvailable) {
          <p class="muted">A bandeja do sistema não está disponível neste computador; o X sempre encerra o app.</p>
        } @else if (m.keepRunningInTray) {
          <p class="muted">O X esconde a janela e os agentes continuam trabalhando. Para sair de verdade, use o ícone do Shiro na bandeja → Sair.</p>
        } @else {
          <p class="muted">O X encerra o app. Se houver execuções em andamento, o Shiro pergunta antes de cancelá-las.</p>
        }
      </section>
    }
  `,
})
export class DesktopSection implements OnInit {
  private readonly desktop = inject(Desktop);
  private readonly toasts = inject(Toasts);
  readonly mode = signal<BackgroundMode | null>(null);
  readonly saving = signal(false);

  async ngOnInit(): Promise<void> {
    const bridge = this.desktop.bridge;
    if (!bridge?.getBackgroundMode) return;
    try {
      this.mode.set(await bridge.getBackgroundMode());
    } catch {
      // shell sem suporte: a seção fica escondida
    }
  }

  async toggle(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const keep = input.checked;
    const bridge = this.desktop.bridge;
    if (!bridge?.setBackgroundMode) return;
    this.saving.set(true);
    try {
      await bridge.setBackgroundMode(keep);
      this.mode.update(m => (m ? { ...m, keepRunningInTray: keep } : m));
    } catch (e) {
      input.checked = !keep;
      this.toasts.error(e);
    } finally {
      this.saving.set(false);
    }
  }
}
