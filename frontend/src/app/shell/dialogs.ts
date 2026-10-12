import { AfterViewChecked, ChangeDetectionStrategy, Component, ElementRef, inject, viewChild } from '@angular/core';

import { Toasts } from '../core/toasts';
import { UiStore } from '../state/ui.store';
import { Icon } from './icon';

/** Confirmações, perguntas curtas e avisos. */
@Component({
  selector: 'sd-dialogs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    @if (ui.confirmRequest(); as c) {
      <div class="scrim dialog-scrim" (click)="ui.answerConfirm(false)"></div>
      <div class="dialog" role="alertdialog" aria-modal="true" aria-labelledby="dlg-title" aria-describedby="dlg-body" (keydown.escape)="ui.answerConfirm(false)">
        <header><h2 id="dlg-title">{{ c.title }}</h2></header>
        <div class="body" id="dlg-body">{{ c.body }}</div>
        <footer>
          <button class="btn" (click)="ui.answerConfirm(false)">{{ c.cancel ?? 'Voltar' }}</button>
          <button #confirmBtn class="btn" [class.danger]="c.danger" [class.primary]="!c.danger" (click)="ui.answerConfirm(true)">{{ c.confirm }}</button>
        </footer>
      </div>
    }
    @if (ui.promptRequest(); as p) {
      <div class="scrim dialog-scrim" (click)="ui.answerPrompt(null)"></div>
      <form class="dialog" role="dialog" aria-modal="true" aria-labelledby="prm-title" (submit)="$event.preventDefault(); ui.answerPrompt(promptInput.value)"
        (keydown.escape)="ui.answerPrompt(null)">
        <header><h2 id="prm-title">{{ p.title }}</h2></header>
        <div class="body field">
          <label for="prm-input">{{ p.label }}</label>
          <input #promptInput id="prm-input" class="input" [value]="p.value" maxlength="200" autocomplete="off" />
        </div>
        <footer>
          <button type="button" class="btn" (click)="ui.answerPrompt(null)">Cancelar</button>
          <button type="submit" class="btn primary">{{ p.confirm }}</button>
        </footer>
      </form>
    }
    <div class="toasts" role="status" aria-live="polite">
      @for (t of toasts.items(); track t.id) {
        <div class="toast" [class]="t.tone">
          <sd-icon [name]="t.tone === 'error' ? 'alert' : t.tone === 'ok' ? 'check' : 'info'" [size]="14" />
          <span class="grow selectable">{{ t.text }}</span>
          <button class="btn ghost icon sm" (click)="toasts.dismiss(t.id)" aria-label="Fechar aviso"><sd-icon name="x" [size]="12" /></button>
        </div>
      }
    </div>
  `,
})
export class Dialogs implements AfterViewChecked {
  protected readonly ui = inject(UiStore);
  protected readonly toasts = inject(Toasts);
  private readonly confirmBtn = viewChild<ElementRef<HTMLButtonElement>>('confirmBtn');
  private readonly promptInput = viewChild<ElementRef<HTMLInputElement>>('promptInput');
  private focused: unknown = null;

  ngAfterViewChecked(): void {
    const request = this.ui.confirmRequest() ?? this.ui.promptRequest();
    if (request && request !== this.focused) {
      this.focused = request;
      const input = this.promptInput()?.nativeElement;
      if (input) {
        input.focus();
        input.select();
      } else {
        this.confirmBtn()?.nativeElement.focus();
      }
    } else if (!request) {
      this.focused = null;
    }
  }
}
