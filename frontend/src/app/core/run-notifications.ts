import { Injectable, inject } from '@angular/core';

import { Desktop } from './desktop';
import { EventStream } from './event-stream';
import type { RunEventEnvelope } from './models';

export interface RunNotice {
  title: string;
  body: string;
}

const MAX_BODY = 200;

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function clip(value: string): string {
  return value.length > MAX_BODY ? value.slice(0, MAX_BODY - 1) + '…' : value;
}

/**
 * Qual aviso do sistema um evento de execução merece. Só três casos interessam a quem não está olhando:
 * terminou, falhou, ou o agente esbarrou numa permissão que não tinha (no modo sem perguntas do Claude Code,
 * isso é o mais perto de "esperando permissão": a pessoa precisa revisar e autorizar).
 */
export function noticeFor(event: RunEventEnvelope): RunNotice | null {
  const data = event.data ?? {};
  switch (event.type) {
    case 'run.finished': {
      const denials = typeof data['permissionDenials'] === 'number' ? data['permissionDenials'] : 0;
      return {
        title: 'Execução concluída',
        body: denials > 0
          ? `O agente terminou, mas ${denials === 1 ? '1 pedido de permissão foi negado' : `${denials} pedidos de permissão foram negados`}. Revise no Shiro.`
          : 'O agente terminou. Abra o Shiro para revisar o resultado.',
      };
    }
    case 'run.failed': {
      const reason = text(data['failureMessage']);
      return {
        title: data['status'] === 'TIMED_OUT' ? 'Execução passou do tempo limite' : 'Execução falhou',
        body: reason ? clip(reason) : 'Abra o Shiro para ver o motivo.',
      };
    }
    case 'permission.denied': {
      const tool = text(data['toolName']);
      return {
        title: 'O agente precisa de permissão',
        body: `Pediu para usar ${tool ?? 'uma ferramenta'} sem autorização. Revise e autorize no Shiro.`,
      };
    }
    default:
      return null;
  }
}

/**
 * Transforma eventos das execuções em avisos do sistema operacional. O shell só mostra o aviso quando a janela
 * está escondida (bandeja) ou sem foco. Eventos antigos (reenviados ao reconectar) não viram aviso, e cada
 * execução gera no máximo um aviso de permissão.
 */
@Injectable({ providedIn: 'root' })
export class RunNotifications {
  private readonly desktop = inject(Desktop);
  private readonly startedAt = Date.now();
  private readonly permissionNotified = new Set<string>();

  constructor() {
    const bridge = this.desktop.bridge;
    if (!bridge?.notify) return;
    inject(EventStream).events$.subscribe(event => this.handle(event));
  }

  private handle(event: RunEventEnvelope): void {
    const at = Date.parse(event.at);
    if (Number.isFinite(at) && at < this.startedAt - 2_000) return;
    if (event.type === 'permission.denied') {
      if (this.permissionNotified.has(event.runId)) return;
      this.permissionNotified.add(event.runId);
    }
    const notice = noticeFor(event);
    if (notice) void this.desktop.bridge?.notify?.(notice).catch(() => undefined);
  }
}
