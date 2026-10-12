import { Injectable, signal } from '@angular/core';

/**
 * API exposta pelo preload do Electron em `window.squadDesktop`. Mantenha em sincronia com
 * desktop/src/preload.ts e desktop/src/ipc-contract.ts. Fora do Electron (ng serve no navegador) ela não
 * existe e a interface usa alternativas: digitar o caminho, baixar arquivos pelo navegador.
 */
export interface SquadDesktopBridge {
  setAppearance?(value: { theme: string; bg: string; fg: string }): Promise<void>;
  readonly platform: string;
  readonly appVersion: string;
  readonly windowControlsWidth: number;
  pickFolder(options?: { title?: string; defaultPath?: string }): Promise<string | null>;
  revealFolder(path: string): Promise<{ ok: boolean; error?: string }>;
  saveTextFile(options: { title?: string; defaultName: string; content: string; extensions: string[] }): Promise<string | null>;
  openTextFile(options: { title?: string; extensions: string[] }): Promise<{ path: string; content: string } | null>;
  openDataFolder(): Promise<{ ok: boolean; error?: string }>;
  windowControl(action: 'minimize' | 'toggle-maximize' | 'close'): Promise<void>;
  onWindowState(listener: (state: { maximized: boolean; focused: boolean }) => void): () => void;
  backendLog(): Promise<{ path: string; tail: string }>;
}

declare global {
  interface Window {
    squadDesktop?: SquadDesktopBridge;
  }
}

@Injectable({ providedIn: 'root' })
export class Desktop {
  readonly bridge: SquadDesktopBridge | null = typeof window !== 'undefined' ? window.squadDesktop ?? null : null;
  readonly available = this.bridge !== null;
  readonly maximized = signal(false);
  readonly focused = signal(true);

  constructor() {
    this.bridge?.onWindowState(state => {
      this.maximized.set(state.maximized);
      this.focused.set(state.focused);
    });
  }

  async pickFolder(title: string, defaultPath?: string | null): Promise<string | null> {
    if (!this.bridge) return null;
    return this.bridge.pickFolder({ title, defaultPath: defaultPath ?? undefined });
  }

  async revealFolder(path: string): Promise<boolean> {
    if (!this.bridge) return false;
    return (await this.bridge.revealFolder(path)).ok;
  }

  /** Salva texto num arquivo: diálogo nativo no app; download no navegador. Devolve o nome/caminho gravado. */
  async saveText(defaultName: string, content: string, extensions: string[], mime = 'text/plain'): Promise<string | null> {
    if (this.bridge) {
      return this.bridge.saveTextFile({ defaultName, content, extensions });
    }
    const blob = new Blob([content], { type: mime + ';charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = defaultName;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    return defaultName;
  }

  async openText(extensions: string[]): Promise<{ path: string; content: string } | null> {
    if (this.bridge) return this.bridge.openTextFile({ extensions });
    return new Promise(resolve => {
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = extensions.map(e => '.' + e).join(',');
      input.onchange = async () => {
        const file = input.files?.[0];
        resolve(file ? { path: file.name, content: await file.text() } : null);
      };
      input.click();
    });
  }

  window(action: 'minimize' | 'toggle-maximize' | 'close'): void {
    void this.bridge?.windowControl(action);
  }
}
