import { Injectable, signal } from '@angular/core';

import { ApiError } from './api';

export type ToastTone = 'info' | 'ok' | 'warn' | 'error';

export interface Toast {
  id: number;
  tone: ToastTone;
  text: string;
}

@Injectable({ providedIn: 'root' })
export class Toasts {
  readonly items = signal<Toast[]>([]);
  private next = 1;

  show(text: string, tone: ToastTone = 'info', ms = 4500): void {
    const id = this.next++;
    this.items.update(list => [...list.slice(-3), { id, tone, text }]);
    setTimeout(() => this.dismiss(id), tone === 'error' ? Math.max(ms, 8000) : ms);
  }

  error(error: unknown, prefix?: string): void {
    const message = ApiError.from(error).message;
    this.show(prefix ? `${prefix}: ${message}` : message, 'error');
  }

  dismiss(id: number): void {
    this.items.update(list => list.filter(t => t.id !== id));
  }
}
