import { ChangeDetectionStrategy, Component, ElementRef, computed, input, output, signal, viewChild } from '@angular/core';

import { baseName } from '../../core/format';
import type { RunKind } from '../../core/models';
import { Icon } from '../../shell/icon';

export interface ComposerSend {
  content: string;
  mode: RunKind;
}

/**
 * Caixa de mensagem do painel. Conversar é o padrão (leitura e análise); Executar é uma escolha explícita
 * por mensagem, que autoriza o agente a alterar a pasta do projeto, e volta para Conversar após o envio.
 */
@Component({
  selector: 'sd-composer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  styles: `
    :host { display: block; padding: 8px 10px 10px; border-top: 1px solid var(--line-1); background: var(--bg-2); }
    .box { border: 1px solid var(--line-2); border-radius: var(--r-md); background: var(--bg-1); }
    .box:focus-within { border-color: var(--signal); }
    .box.exec { border-color: rgba(233, 180, 76, 0.55); }
    textarea { display: block; width: 100%; min-height: 40px; max-height: 220px; padding: 8px 10px 4px; border: 0; background: transparent;
      resize: none; outline: none; line-height: 1.5; }
    .tools { display: flex; align-items: center; gap: 8px; padding: 4px 6px 6px 8px; }
    .warn-line { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; color: var(--warn); font-size: var(--fs-xs); }
    .hint { color: var(--fg-4); font-size: var(--fs-xs); }
    .seg button[data-mode='EXECUTION'][aria-selected='true'] { background: var(--warn-dim); color: var(--warn); }
  `,
  template: `
    @if (mode() === 'EXECUTION') {
      <div class="warn-line" role="note">
        <sd-icon name="bolt" [size]="12" />
        @if (folder()) { O agente poderá criar e alterar arquivos em {{ folderName() }} com as ferramentas liberadas para ele. }
        @else { Este projeto não tem pasta definida: execuções que alteram arquivos ficam indisponíveis. }
      </div>
    }
    <div class="box" [class.exec]="mode() === 'EXECUTION'">
      <textarea #input rows="2" [attr.aria-label]="'Mensagem para ' + agentName()" [placeholder]="placeholder()" [disabled]="disabled()"
        [value]="text()" (input)="onInput($event)" (keydown)="onKey($event)"></textarea>
      <div class="tools">
        <div class="seg" role="tablist" aria-label="Tipo de pedido">
          <button role="tab" data-mode="CHAT" [attr.aria-selected]="mode() === 'CHAT'" (click)="mode.set('CHAT')"
            title="O agente lê e analisa; não altera arquivos">Conversar</button>
          <button role="tab" data-mode="EXECUTION" [attr.aria-selected]="mode() === 'EXECUTION'" (click)="mode.set('EXECUTION')"
            [disabled]="!folder()" title="Autoriza o agente a alterar arquivos do projeto nesta mensagem">
            <sd-icon name="bolt" [size]="11" /> Executar
          </button>
        </div>
        <span class="hint grow">{{ mode() === 'EXECUTION' ? 'Ctrl+Enter para executar' : 'Enter envia · Shift+Enter quebra linha' }}</span>
        @if (busy()) {
          <button class="btn sm danger" (click)="cancel.emit()" title="Encerra o processo desta execução"><sd-icon name="stop" [size]="12" /> Cancelar</button>
        } @else {
          <button class="btn sm" [class.primary]="mode() === 'CHAT'" [class.warn]="mode() === 'EXECUTION'" [disabled]="!canSend()" (click)="submit()">
            <sd-icon [name]="mode() === 'EXECUTION' ? 'bolt' : 'send'" [size]="12" /> {{ mode() === 'EXECUTION' ? 'Executar' : 'Enviar' }}
          </button>
        }
      </div>
    </div>
  `,
})
export class Composer {
  readonly agentName = input('agente');
  readonly folder = input<string | null>(null);
  readonly busy = input(false);
  readonly disabled = input(false);
  readonly send = output<ComposerSend>();
  readonly cancel = output<void>();

  protected readonly text = signal('');
  readonly mode = signal<RunKind>('CHAT');
  private readonly inputRef = viewChild<ElementRef<HTMLTextAreaElement>>('input');

  protected readonly folderName = computed(() => baseName(this.folder()));
  protected readonly canSend = computed(() => !this.busy() && !this.disabled() && this.text().trim().length > 0);
  protected readonly placeholder = computed(() => (this.busy()
    ? 'Aguardando a execução atual terminar…'
    : this.mode() === 'EXECUTION' ? `Descreva a tarefa que ${this.agentName()} deve executar…` : `Mensagem para ${this.agentName()}…`));

  focus(): void {
    this.inputRef()?.nativeElement.focus();
  }

  /** Coloca um texto na caixa (por exemplo, para repetir o último pedido) sem enviar. */
  fill(text: string, mode: RunKind): void {
    this.text.set(text);
    this.mode.set(mode === 'EXECUTION' && this.folder() ? 'EXECUTION' : 'CHAT');
    queueMicrotask(() => {
      const el = this.inputRef()?.nativeElement;
      if (el) {
        this.autosize(el);
        el.focus();
      }
    });
  }

  protected onInput(event: Event): void {
    const el = event.target as HTMLTextAreaElement;
    this.text.set(el.value);
    this.autosize(el);
  }

  protected onKey(event: KeyboardEvent): void {
    if (event.key !== 'Enter' || event.isComposing) return;
    const ctrl = event.ctrlKey || event.metaKey;
    if (this.mode() === 'EXECUTION') {
      if (ctrl) {
        event.preventDefault();
        this.submit();
      }
      return;
    }
    if (!event.shiftKey) {
      event.preventDefault();
      this.submit();
    }
  }

  protected submit(): void {
    if (!this.canSend()) return;
    this.send.emit({ content: this.text().trim(), mode: this.mode() });
  }

  /** Chamado pelo painel depois que o envio foi aceito. */
  reset(): void {
    this.text.set('');
    this.mode.set('CHAT');
    const el = this.inputRef()?.nativeElement;
    if (el) {
      el.value = '';
      this.autosize(el);
    }
  }

  private autosize(el: HTMLTextAreaElement): void {
    el.style.height = 'auto';
    el.style.height = Math.min(220, el.scrollHeight) + 'px';
  }
}
