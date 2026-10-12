import { Injectable, signal } from '@angular/core';

export type DrawerRequest =
  | { kind: 'project'; projectId: string | null; groupId?: string | null }
  | { kind: 'agent'; agentId: string | null; templateKey?: string | null }
  | { kind: 'squad'; squadId: string | null }
  | { kind: 'new-session'; projectId: string };

export interface ConfirmRequest {
  title: string;
  body: string;
  confirm: string;
  cancel?: string;
  danger?: boolean;
  resolve: (ok: boolean) => void;
}

export interface PromptRequest {
  title: string;
  label: string;
  value: string;
  confirm: string;
  resolve: (value: string | null) => void;
}

/** Sobreposições da interface: drawers, confirmações e paleta de comandos. */
@Injectable({ providedIn: 'root' })
export class UiStore {
  readonly drawer = signal<DrawerRequest | null>(null);
  readonly confirmRequest = signal<ConfirmRequest | null>(null);
  readonly promptRequest = signal<PromptRequest | null>(null);
  readonly paletteOpen = signal(false);

  openDrawer(request: DrawerRequest): void {
    this.drawer.set(request);
  }

  closeDrawer(): void {
    this.drawer.set(null);
  }

  confirm(options: Omit<ConfirmRequest, 'resolve'>): Promise<boolean> {
    return new Promise(resolve => {
      this.confirmRequest()?.resolve(false);
      this.confirmRequest.set({ ...options, resolve });
    });
  }

  prompt(options: Omit<PromptRequest, 'resolve'>): Promise<string | null> {
    return new Promise(resolve => {
      this.promptRequest()?.resolve(null);
      this.promptRequest.set({ ...options, resolve });
    });
  }

  answerConfirm(ok: boolean): void {
    const request = this.confirmRequest();
    this.confirmRequest.set(null);
    request?.resolve(ok);
  }

  answerPrompt(value: string | null): void {
    const request = this.promptRequest();
    this.promptRequest.set(null);
    request?.resolve(value);
  }
}
