import { Injectable, OnDestroy, signal } from '@angular/core';
import { Subject } from 'rxjs';

import type { RunEventEnvelope } from './models';

export type StreamState = 'connecting' | 'open' | 'retrying' | 'closed';

/**
 * Assinatura única do SSE /api/events. O navegador reenvia o último id recebido ao reconectar
 * (Last-Event-ID) e o backend reenvia o que faltou, sem lacunas. Quando a conexão é encerrada de vez
 * (por exemplo, o serviço reiniciou), reabrimos com `?after=` a partir do último cursor conhecido.
 */
@Injectable({ providedIn: 'root' })
export class EventStream implements OnDestroy {
  readonly state = signal<StreamState>('closed');
  readonly events$ = new Subject<RunEventEnvelope>();
  /** Emite quando a conexão volta depois de cair: quem guarda estado derivado pode recarregar. */
  readonly reconnected$ = new Subject<void>();

  private source: EventSource | null = null;
  private cursor = -1;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private attempts = 0;
  private everOpened = false;

  start(): void {
    if (this.source) return;
    this.open();
  }

  private open(): void {
    this.state.set(this.everOpened ? 'retrying' : 'connecting');
    const url = this.cursor >= 0 ? `/api/events?after=${this.cursor}` : '/api/events';
    const source = new EventSource(url, { withCredentials: true });
    this.source = source;

    source.addEventListener('hello', (event: MessageEvent<string>) => {
      const wasOpened = this.everOpened;
      this.everOpened = true;
      this.attempts = 0;
      this.state.set('open');
      try {
        const hello = JSON.parse(event.data) as { cursor: number };
        if (this.cursor < 0) this.cursor = hello.cursor;
      } catch {
        // mantém o cursor atual
      }
      if (wasOpened) this.reconnected$.next();
    });

    source.addEventListener('run', (event: MessageEvent<string>) => {
      let envelope: RunEventEnvelope;
      try {
        envelope = JSON.parse(event.data) as RunEventEnvelope;
      } catch {
        return;
      }
      if (envelope.id > 0) this.cursor = Math.max(this.cursor, envelope.id);
      this.events$.next(envelope);
    });

    source.onerror = () => {
      if (source.readyState === EventSource.CLOSED) {
        source.close();
        this.source = null;
        this.scheduleRetry();
      } else {
        this.state.set('retrying');
      }
    };
  }

  private scheduleRetry(): void {
    this.state.set('retrying');
    const delay = Math.min(10_000, 500 * 2 ** this.attempts++);
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      this.open();
    }, delay);
  }

  ngOnDestroy(): void {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.source?.close();
    this.source = null;
    this.state.set('closed');
  }
}
