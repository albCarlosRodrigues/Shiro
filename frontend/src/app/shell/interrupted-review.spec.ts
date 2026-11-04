import { describe, expect, it } from 'vitest';

import { CONTINUE_MESSAGE, continueMode, pendingItems, type InterruptedItem } from './interrupted-review';

function item(runId: string, kind = 'EXECUTION'): InterruptedItem {
  return {
    runId, sessionId: 's-' + runId, sessionTitle: 'Conversa', projectId: 'p', projectName: 'Loja', agentName: 'Ana',
    kind, access: 'WRITE', prompt: 'Implemente o checkout', cwd: '/tmp/loja', interruptedAt: new Date().toISOString(), demo: false,
  };
}

describe('revisão do que ficou pela metade', () => {
  it('tira da lista o que já foi dispensado ou continuado', () => {
    expect(pendingItems([item('a'), item('b'), item('c')], ['b']).map(i => i.runId)).toEqual(['a', 'c']);
    expect(pendingItems([item('a')], [])).toHaveLength(1);
  });

  it('continua execução como execução e o resto como conversa', () => {
    expect(continueMode(item('a', 'EXECUTION'))).toBe('EXECUTION');
    expect(continueMode(item('a', 'CHAT'))).toBe('CHAT');
    expect(continueMode(item('a', 'PLANNING'))).toBe('CHAT');
  });

  it('a continuação pede para conferir a pasta antes de seguir', () => {
    expect(CONTINUE_MESSAGE).toContain('confira o estado atual da pasta');
  });
});
