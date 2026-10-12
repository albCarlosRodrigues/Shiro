import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { ThemeStore } from '../state/theme.store';

@Component({
  selector: 'sd-shiro-mark',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: ':host { display: inline-flex; flex: none; } img { display: block; image-rendering: auto; }',
  template: '<img [src]="src()" [width]="size()" [height]="size()" alt="" />',
})
export class ShiroMark {
  readonly size = input(24);
  private readonly themes = inject(ThemeStore);

  // A arte completa mantém os detalhes da tela de carregamento em qualquer densidade de tela.
  protected readonly src = computed(() => 'brand/shiro-' + this.themes.current().id + '-256.png');
}
