import { Injectable, inject, signal } from '@angular/core';

import { Api } from '../core/api';
import { Toasts } from '../core/toasts';
import { Desktop } from '../core/desktop';

export interface ThemeInfo {
  id: string;
  name: string;
  scheme: 'dark' | 'light';
}

/** Índigo é a paleta de :root: não tem bloco [data-theme] e serve de fallback. */
export const FALLBACK_THEME = 'indigo';

export const THEMES: ThemeInfo[] = [
  { id: 'indigo', name: 'Índigo', scheme: 'dark' },
  { id: 'escuro', name: 'Escuro', scheme: 'dark' },
  { id: 'claro', name: 'Claro', scheme: 'light' },
  { id: 'dracula', name: 'Dracula', scheme: 'dark' },
  { id: 'nord', name: 'Nord', scheme: 'dark' },
  { id: 'gruvbox', name: 'Gruvbox', scheme: 'dark' },
  { id: 'solarized', name: 'Solarized', scheme: 'dark' },
  { id: 'tokyo-night', name: 'Tokyo Night', scheme: 'dark' },
  { id: 'vscode', name: 'VS Code', scheme: 'dark' },
  { id: 'min-dark', name: 'Min Dark', scheme: 'dark' },
  { id: 'min-light', name: 'Min Light', scheme: 'light' },
  { id: 'dark-lemon', name: 'Dark Lemon', scheme: 'dark' },
];

const KEY = 'appearance'; // chave em ui_state, pela mesma API que o WorkspaceStore usa

@Injectable({ providedIn: 'root' })
export class ThemeStore {
  private readonly api = inject(Api);
  private readonly desktop = inject(Desktop);

  private readonly toasts = inject(Toasts);
  private saves: Promise<void> = Promise.resolve();
  readonly themes = THEMES;
  readonly current = signal<ThemeInfo>(THEMES[0]);

  /** Chamado uma vez na inicialização, antes de mostrar a interface. Qualquer falha deixa o Índigo. */
  async load(): Promise<void> {
    try {
      const saved = await this.api.getOptional<{ theme?: unknown }>(`ui-state/${KEY}`);
      this.apply(typeof saved?.theme === 'string' ? saved.theme : FALLBACK_THEME, false);
    } catch {
      this.apply(FALLBACK_THEME, false);
    }
  }

  select(id: string): void {
    this.apply(id, true);
  }

  private apply(id: string, persist: boolean): void {
    const theme = THEMES.find(t => t.id === id) ?? THEMES[0];
    const root = document.documentElement;
    if (theme.id === FALLBACK_THEME) delete root.dataset['theme'];
    else root.dataset['theme'] = theme.id;
    root.style.colorScheme = theme.scheme;
    this.current.set(theme);

    // A janela nativa (fundo e botões da barra de título) é pintada pelo Electron: ele recebe as duas cores
    // já resolvidas e as guarda para a próxima abertura. Ver electron-main.exemplo.ts.
    const css = getComputedStyle(root);
    void this.desktop.bridge?.setAppearance?.({
      theme: theme.id,
      bg: css.getPropertyValue('--bg-0').trim(),
      fg: css.getPropertyValue('--fg-2').trim(),
    })?.catch(error => this.toasts.error(error));

    if (persist) {
      this.saves = this.saves.then(() => this.api.putJson(`ui-state/${KEY}`, JSON.stringify({ theme: theme.id }))).catch(error => this.toasts.error(error));
    }
  }
}
