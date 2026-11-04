import { describe, expect, it } from 'vitest';

import type { RunEventEnvelope } from './models';
import { noticeFor } from './run-notifications';

function event(type: string, data: Record<string, unknown> = {}): RunEventEnvelope {
  return { id: 1, runId: 'r1', sessionId: 's1', type, at: new Date().toISOString(), data };
}

describe('avisos de execução', () => {
  it('avisa quando a execução termina', () => {
    expect(noticeFor(event('run.finished', { status: 'COMPLETED', permissionDenials: 0 }))).toEqual({
      title: 'Execução concluída',
      body: 'O agente terminou. Abra o Shiro para revisar o resultado.',
    });
  });

  it('destaca permissões negadas no fim da execução', () => {
    expect(noticeFor(event('run.finished', { permissionDenials: 2 }))?.body).toContain('2 pedidos de permissão foram negados');
    expect(noticeFor(event('run.finished', { permissionDenials: 1 }))?.body).toContain('1 pedido de permissão foi negado');
  });

  it('avisa falha e tempo esgotado com o motivo', () => {
    expect(noticeFor(event('run.failed', { status: 'FAILED', failureMessage: 'Claude Code saiu com erro' }))).toEqual({
      title: 'Execução falhou',
      body: 'Claude Code saiu com erro',
    });
    expect(noticeFor(event('run.failed', { status: 'TIMED_OUT' }))?.title).toBe('Execução passou do tempo limite');
    expect(noticeFor(event('run.failed', { failureMessage: 'x'.repeat(500) }))!.body.length).toBeLessThanOrEqual(200);
  });

  it('avisa quando o agente esbarra numa permissão', () => {
    expect(noticeFor(event('permission.denied', { toolName: 'Bash' }))?.body).toContain('Bash');
  });

  it('ignora o resto (texto, ferramentas, cancelamento pela própria pessoa)', () => {
    for (const type of ['text.delta', 'tool.call', 'run.cancelled', 'run.interrupted', 'project.changed']) {
      expect(noticeFor(event(type))).toBeNull();
    }
  });
});
