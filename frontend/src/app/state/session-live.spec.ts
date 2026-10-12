import type { RunEventEnvelope, StoredEvent } from '../core/models';
import { applyEvent, emptyLive, replayStored, summarizeToolInput } from './session-live';

let id = 0;
const ev = (type: string, data: Record<string, unknown> = {}, runId = 'r1'): RunEventEnvelope =>
  ({ id: ++id, runId, sessionId: 's1', type, at: '2026-10-08T10:00:00Z', data });

describe('estado ao vivo da sessão', () => {
  beforeEach(() => (id = 0));

  it('acompanha uma execução do início ao fim', () => {
    let s = { ...emptyLive(), loaded: true };
    s = applyEvent(s, ev('run.queued', { kind: 'CHAT', access: 'READ_ONLY', demo: false }));
    expect(s.current?.status).toBe('QUEUED');
    s = applyEvent(s, ev('run.started', { model: 'sonnet', cwd: '/proj' }));
    expect(s.current?.status).toBe('RUNNING');
    s = applyEvent(s, ev('text.delta', { text: 'Olá, ' }));
    s = applyEvent(s, ev('text.delta', { text: 'mundo' }));
    expect(s.current?.streaming).toBe('Olá, mundo');
    s = applyEvent(s, ev('message', { text: 'Olá, mundo', messageId: 'm1' }));
    expect(s.messages.map(m => m.content)).toEqual(['Olá, mundo']);
    expect(s.current?.streaming).toBe('');
    const tick = s.finishedTick;
    s = applyEvent(s, ev('run.finished', { status: 'COMPLETED' }));
    expect(s.current?.status).toBe('COMPLETED');
    expect(s.finishedTick).toBe(tick + 1);
    expect(s.console.some(c => c.text.includes('/proj'))).toBe(true);
  });

  it('liga o resultado à chamada de ferramenta e registra negações', () => {
    let s = emptyLive();
    s = applyEvent(s, ev('run.started'));
    s = applyEvent(s, ev('tool.call', { toolUseId: 't1', name: 'Bash', input: { command: 'npm test' } }));
    expect(s.activity.at(-1)).toMatchObject({ kind: 'tool', pending: true, detail: 'npm test' });
    s = applyEvent(s, ev('tool.result', { toolUseId: 't1', content: '3 passing', error: false }));
    expect(s.activity.filter(a => a.kind === 'tool')).toHaveLength(1);
    expect(s.activity.at(-1)).toMatchObject({ pending: false, result: '3 passing', tone: 'ok' });
    s = applyEvent(s, ev('tool.call', { toolUseId: 't2', name: 'Write', input: { file_path: 'a.txt' } }));
    s = applyEvent(s, ev('permission.denied', { toolName: 'Write', toolUseId: 't2', suggestedRule: 'Write' }));
    expect(s.activity.find(a => a.kind === 'denied')?.suggestedRule).toBe('Write');
    expect(s.activity.find(a => a.toolUseId === 't2' && a.kind === 'tool')?.pending).toBe(false);
  });

  it('ignora eventos repetidos (reconexão do SSE)', () => {
    let s = emptyLive();
    const e = ev('tool.call', { toolUseId: 't1', name: 'Read', input: {} });
    s = applyEvent(s, e);
    s = applyEvent(s, e);
    expect(s.activity).toHaveLength(1);
  });

  it('não inventa custo nem tokens ausentes', () => {
    const s = applyEvent(emptyLive(), ev('usage', { numTurns: 2 }));
    expect(s.activity.at(-1)?.detail).toBe('2 turnos');
    const none = applyEvent(emptyLive(), ev('usage', {}));
    expect(none.activity.at(-1)?.detail).toContain('não informou');
  });

  it('reconstrói atividade a partir do histórico sem disparar recargas', () => {
    const stored: StoredEvent[] = [
      { id: 1, runId: 'r1', sessionId: 's1', type: 'run.started', payload: '{}', createdAt: 'x' },
      { id: 2, runId: 'r1', sessionId: 's1', type: 'tool.call', payload: '{"toolUseId":"t","name":"Read","input":{"file_path":"README.md"}}', createdAt: 'x' },
      { id: 3, runId: 'r1', sessionId: 's1', type: 'message', payload: '{"text":"oi","messageId":"m"}', createdAt: 'x' },
      { id: 4, runId: 'r1', sessionId: 's1', type: 'run.finished', payload: '{"status":"COMPLETED"}', createdAt: 'x' },
    ];
    const s = replayStored({ ...emptyLive(), finishedTick: 7 }, stored);
    expect(s.finishedTick).toBe(7);
    expect(s.messages).toHaveLength(0);
    expect(s.activity.map(a => a.kind)).toEqual(['lifecycle', 'tool', 'lifecycle']);
    expect(s.lastEventId).toBe(4);
  });

  it('resume a entrada das ferramentas comuns', () => {
    expect(summarizeToolInput('Read', { file_path: 'src/app.ts' })).toBe('src/app.ts');
    expect(summarizeToolInput('Grep', { pattern: 'TODO' })).toBe('TODO');
    expect(summarizeToolInput('Bash', { command: 'x'.repeat(300) })).toHaveLength(160);
  });
});
