import type { Message, Run, RunEventEnvelope, RunKind, RunStatus, StoredEvent } from '../core/models';

/**
 * Estado ao vivo de uma sessão (um painel), derivado dos eventos do contrato interno.
 * Funções puras: o store aplica cada envelope recebido pelo SSE e os testes as exercitam diretamente.
 */

export type ActivityKind = 'lifecycle' | 'tool' | 'denied' | 'notice' | 'usage' | 'subagent';
export type ActivityTone = 'info' | 'running' | 'ok' | 'warn' | 'error';

export interface ActivityItem {
  key: string;
  runId: string;
  at: string;
  kind: ActivityKind;
  tone: ActivityTone;
  title: string;
  detail?: string;
  toolUseId?: string;
  toolName?: string;
  input?: Record<string, unknown>;
  result?: string;
  resultError?: boolean;
  resultTruncated?: boolean;
  suggestedRule?: string;
  pending?: boolean;
}

export interface ConsoleLine {
  key: string;
  runId: string;
  at: string;
  stream: string;
  text: string;
}

export interface CurrentRun {
  runId: string;
  status: RunStatus;
  kind: RunKind | null;
  demo: boolean;
  streaming: string;
  thinking: boolean;
  model?: string | null;
}

export interface SessionLive {
  loaded: boolean;
  messages: Message[];
  runs: Run[];
  activity: ActivityItem[];
  console: ConsoleLine[];
  current: CurrentRun | null;
  /** Incrementado quando uma execução termina: o store recarrega mensagens e execuções. */
  finishedTick: number;
  lastEventId: number;
}

export const MAX_ACTIVITY = 600;
export const MAX_CONSOLE = 2000;

export function emptyLive(): SessionLive {
  return { loaded: false, messages: [], runs: [], activity: [], console: [], current: null, finishedTick: 0, lastEventId: 0 };
}

const TERMINAL_TYPES = new Set(['run.finished', 'run.failed', 'run.cancelled', 'run.interrupted']);

export function isTerminalEvent(type: string): boolean {
  return TERMINAL_TYPES.has(type);
}

function str(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function num(value: unknown): number | undefined {
  return typeof value === 'number' ? value : undefined;
}

function cap<T>(list: T[], max: number): T[] {
  return list.length > max ? list.slice(list.length - max) : list;
}

/** Resumo curto da entrada de uma ferramenta para o título do item de atividade. */
export function summarizeToolInput(name: string, input: Record<string, unknown> | undefined): string {
  if (!input) return '';
  const pick = (...keys: string[]) => {
    for (const key of keys) {
      const value = input[key];
      if (typeof value === 'string' && value.trim()) return value.trim();
    }
    return '';
  };
  const text = (() => {
    switch (name) {
      case 'Bash':
      case 'PowerShell':
        return pick('command');
      case 'Read':
      case 'Write':
      case 'Edit':
      case 'NotebookEdit':
        return pick('file_path', 'notebook_path');
      case 'Glob':
      case 'Grep':
        return pick('pattern');
      case 'WebFetch':
        return pick('url');
      case 'WebSearch':
        return pick('query');
      case 'Agent':
      case 'Task':
        return pick('description', 'subagent_type');
      default:
        return pick('file_path', 'command', 'pattern', 'path', 'url', 'query', 'description');
    }
  })();
  return text.length > 160 ? text.slice(0, 159) + '…' : text;
}

export function applyEvent(state: SessionLive, event: RunEventEnvelope): SessionLive {
  if (event.id > 0 && event.id <= state.lastEventId) return state;
  const data = event.data ?? {};
  const next: SessionLive = { ...state, lastEventId: event.id > 0 ? event.id : state.lastEventId };
  const key = event.id > 0 ? 'e' + event.id : 't' + event.at + Math.random().toString(36).slice(2, 6);
  const current = state.current && state.current.runId === event.runId ? state.current : null;
  const activity = (item: Omit<ActivityItem, 'key' | 'runId' | 'at'>) => {
    next.activity = cap([...next.activity, { key, runId: event.runId, at: event.at, ...item }], MAX_ACTIVITY);
  };

  switch (event.type) {
    case 'run.queued': {
      next.current = {
        runId: event.runId,
        status: 'QUEUED',
        kind: (str(data['kind']) as RunKind) ?? null,
        demo: data['demo'] === true,
        streaming: '',
        thinking: false,
      };
      activity({ kind: 'lifecycle', tone: 'info', title: data['kind'] === 'EXECUTION' ? 'Execução na fila' : 'Mensagem na fila' });
      break;
    }
    case 'run.started': {
      const base: CurrentRun = current ?? { runId: event.runId, status: 'RUNNING', kind: null, demo: false, streaming: '', thinking: false };
      next.current = { ...base, status: 'RUNNING', model: str(data['model']) ?? null };
      const parts = [str(data['model']), data['resumed'] === true ? 'contexto retomado' : undefined, str(data['permissionMode'])]
        .filter(Boolean);
      activity({ kind: 'lifecycle', tone: 'running', title: 'Agente iniciado', detail: parts.join(' · ') || undefined });
      if (str(data['cwd'])) {
        next.console = cap([...next.console, { key: key + 'c', runId: event.runId, at: event.at, stream: 'info', text: 'pasta: ' + str(data['cwd']) }], MAX_CONSOLE);
      }
      break;
    }
    case 'text.delta': {
      if (current) next.current = { ...current, streaming: current.streaming + (str(data['text']) ?? ''), thinking: false };
      break;
    }
    case 'thinking': {
      if (current) next.current = { ...current, thinking: true };
      break;
    }
    case 'message': {
      const text = str(data['text']) ?? '';
      const parent = str(data['parentToolUseId']);
      if (parent) {
        activity({ kind: 'subagent', tone: 'info', title: 'Subagente', detail: text.length > 600 ? text.slice(0, 599) + '…' : text });
        break;
      }
      const messageId = str(data['messageId']);
      if (messageId && !next.messages.some(m => m.id === messageId)) {
        const seq = next.messages.length ? next.messages[next.messages.length - 1].seq + 1 : 1;
        next.messages = [...next.messages, {
          id: messageId, sessionId: event.sessionId, runId: event.runId, role: 'AGENT', content: text, seq, createdAt: event.at,
        }];
      }
      if (current) next.current = { ...current, streaming: '', thinking: false };
      break;
    }
    case 'tool.call': {
      const name = str(data['name']) ?? 'ferramenta';
      const input = (data['input'] as Record<string, unknown> | undefined) ?? undefined;
      activity({
        kind: 'tool', tone: 'running', title: name, detail: summarizeToolInput(name, input),
        toolUseId: str(data['toolUseId']), toolName: name, input, pending: true,
      });
      break;
    }
    case 'tool.result': {
      const id = str(data['toolUseId']);
      const index = id ? next.activity.findIndex(a => a.kind === 'tool' && a.toolUseId === id) : -1;
      const error = data['error'] === true;
      if (index >= 0) {
        const updated = [...next.activity];
        updated[index] = {
          ...updated[index], pending: false, tone: error ? 'error' : 'ok', result: str(data['content']) ?? '',
          resultError: error, resultTruncated: data['truncated'] === true,
        };
        next.activity = updated;
      } else {
        activity({ kind: 'tool', tone: error ? 'error' : 'ok', title: str(data['name']) ?? 'Resultado', result: str(data['content']), resultError: error });
      }
      break;
    }
    case 'permission.denied': {
      const tool = str(data['toolName']) ?? 'ferramenta';
      const input = (data['input'] as Record<string, unknown> | undefined) ?? undefined;
      activity({
        kind: 'denied', tone: 'warn', title: `Permissão negada: ${tool}`, detail: summarizeToolInput(tool, input) || str(data['reason']),
        toolName: tool, input, suggestedRule: str(data['suggestedRule']),
      });
      // a chamada negada não terá resultado normal
      const id = str(data['toolUseId']);
      if (id) next.activity = next.activity.map(a => (a.toolUseId === id && a.kind === 'tool' ? { ...a, pending: false, tone: 'warn' } : a));
      break;
    }
    case 'notice': {
      const level = str(data['level']);
      activity({ kind: 'notice', tone: level === 'error' ? 'error' : level === 'warning' ? 'warn' : 'info', title: str(data['text']) ?? '' });
      if (str(data['code']) === 'cancelling' && current) next.current = { ...current, status: 'CANCELLING' };
      break;
    }
    case 'usage': {
      const parts: string[] = [];
      const input = num(data['inputTokens']);
      const output = num(data['outputTokens']);
      const cost = num(data['costUsd']);
      const turns = num(data['numTurns']);
      if (input != null || output != null) parts.push(`${input ?? '?'} → ${output ?? '?'} tokens`);
      if (turns != null) parts.push(`${turns} ${turns === 1 ? 'turno' : 'turnos'}`);
      if (cost != null) parts.push(`US$ ${cost.toFixed(4)} (estimativa do Claude Code)`);
      activity({ kind: 'usage', tone: 'info', title: 'Consumo informado', detail: parts.join(' · ') || 'O Claude Code não informou consumo.' });
      break;
    }
    case 'console': {
      next.console = cap([...next.console, { key, runId: event.runId, at: event.at, stream: str(data['stream']) ?? 'stdout', text: str(data['text']) ?? '' }], MAX_CONSOLE);
      break;
    }
    default: {
      if (isTerminalEvent(event.type)) {
        const status = (str(data['status']) as RunStatus) ?? 'FAILED';
        if (current || !state.current) {
          next.current = { ...(current ?? { runId: event.runId, kind: null, demo: false, streaming: '', thinking: false }), status, streaming: '', thinking: false };
        }
        const message = str(data['failureMessage']);
        activity({
          kind: 'lifecycle',
          tone: status === 'COMPLETED' ? 'ok' : status === 'CANCELLED' || status === 'INTERRUPTED' ? 'warn' : 'error',
          title: status === 'COMPLETED' ? 'Execução concluída' : status === 'CANCELLED' ? 'Execução cancelada'
            : status === 'INTERRUPTED' ? 'Execução interrompida' : status === 'TIMED_OUT' ? 'Tempo esgotado' : 'Execução falhou',
          detail: message ?? undefined,
        });
        next.finishedTick = state.finishedTick + 1;
      }
    }
  }
  return next;
}

/** Reconstrói atividade e console a partir dos eventos gravados (abertura do painel ou retorno ao app). */
export function replayStored(state: SessionLive, events: StoredEvent[]): SessionLive {
  let next: SessionLive = { ...state, activity: [], console: [], lastEventId: 0 };
  for (const event of events) {
    let data: Record<string, unknown> = {};
    try {
      data = JSON.parse(event.payload) as Record<string, unknown>;
    } catch {
      continue;
    }
    // mensagens já vêm da lista de mensagens; texto incremental antigo não precisa ser remontado
    if (event.type === 'text.delta' || event.type === 'message') {
      next = { ...next, lastEventId: Math.max(next.lastEventId, event.id) };
      if (event.type === 'message' && typeof data['parentToolUseId'] === 'string') {
        next = applyEvent({ ...next, lastEventId: event.id - 1 }, envelope(event, data));
      }
      continue;
    }
    next = applyEvent(next, envelope(event, data));
  }
  // o histórico não dispara recargas nem redefine a execução corrente: isso vem da lista de execuções
  return { ...next, finishedTick: state.finishedTick, current: state.current };
}

function envelope(event: StoredEvent, data: Record<string, unknown>): RunEventEnvelope {
  return { id: event.id, runId: event.runId, sessionId: event.sessionId, type: event.type, at: event.createdAt, data };
}

/** Estado atual a partir da execução mais recente, quando não há eventos ao vivo. */
export function currentFromRun(run: Run | undefined): CurrentRun | null {
  if (!run) return null;
  return { runId: run.id, status: run.status, kind: run.kind, demo: run.demo, streaming: '', thinking: false, model: run.model };
}
