import { Injectable, Signal, WritableSignal, computed, inject, signal } from '@angular/core';

import { Api } from '../core/api';
import { EventStream } from '../core/event-stream';
import { isActiveStatus } from '../core/format';
import type { Message, QueueState, Run, RunEventEnvelope, RunKind, RunStatus, Session, StoredEvent, Submission } from '../core/models';
import { SessionLive, applyEvent, currentFromRun, emptyLive, isTerminalEvent, replayStored } from './session-live';

export interface RunMarker {
  runId: string;
  status: RunStatus;
}

/**
 * Sessões (painéis) e o estado ao vivo das execuções. O estado vive aqui, fora dos componentes:
 * fechar ou ocultar um painel não interrompe nada e, ao reabri-lo, a conversa continua de onde estava.
 */
@Injectable({ providedIn: 'root' })
export class SessionsStore {
  private readonly api = inject(Api);
  private readonly stream = inject(EventStream);

  readonly byProject = signal<Record<string, Session[]>>({});
  readonly recent = signal<Session[]>([]);
  /** Última execução conhecida de cada sessão (alimenta a sidebar, as abas e os cabeçalhos). */
  readonly runs = signal<Record<string, RunMarker>>({});
  readonly queue = signal<QueueState | null>(null);

  private readonly live = new Map<string, WritableSignal<SessionLive>>();
  private readonly loading = new Map<string, Promise<void>>();
  private readonly sessionIndex = new Map<string, Session>();

  /** Projeto de cada sessão conhecida (para contar execuções por projeto sem carregar tudo). */
  private readonly projectOf = signal<Record<string, string>>({});

  readonly activeCount = computed(() => Object.values(this.runs()).filter(r => isActiveStatus(r.status)).length);

  /** Execuções em andamento ou na fila, por projeto. */
  readonly activeByProject = computed(() => {
    const owners = this.projectOf();
    const counts: Record<string, number> = {};
    for (const [sessionId, marker] of Object.entries(this.runs())) {
      const projectId = owners[sessionId];
      if (projectId && isActiveStatus(marker.status)) counts[projectId] = (counts[projectId] ?? 0) + 1;
    }
    return counts;
  });

  constructor() {
    this.stream.events$.subscribe(event => this.onEvent(event));
    this.stream.reconnected$.subscribe(() => void this.resync());
  }

  async init(): Promise<void> {
    await Promise.all([this.loadActiveRuns(), this.loadRecent()]);
  }

  /* ---------- consultas ---------- */

  sessions(projectId: string): Session[] {
    return this.byProject()[projectId] ?? [];
  }

  session(id: string): Session | undefined {
    return this.sessionIndex.get(id);
  }

  marker(sessionId: string): RunMarker | undefined {
    return this.runs()[sessionId];
  }

  /** Estado ao vivo de uma sessão; carrega histórico na primeira chamada. */
  liveState(sessionId: string): Signal<SessionLive> {
    let state = this.live.get(sessionId);
    if (!state) {
      state = signal(emptyLive());
      this.live.set(sessionId, state);
      void this.loadSession(sessionId);
    }
    return state.asReadonly();
  }

  /* ---------- carga ---------- */

  async loadProject(projectId: string): Promise<Session[]> {
    const list = await this.api.get<Session[]>(`projects/${projectId}/sessions`);
    this.index(list);
    this.byProject.update(map => ({ ...map, [projectId]: list }));
    return list;
  }

  async loadRecent(): Promise<void> {
    const list = await this.api.get<Session[]>('sessions/recent', { limit: 12 });
    this.index(list);
    this.recent.set(list);
  }

  async loadActiveRuns(): Promise<void> {
    const [active, queue] = await Promise.all([this.api.get<Run[]>('runs/active'), this.api.get<QueueState>('runs/queue')]);
    this.queue.set(queue);
    this.runs.update(map => {
      const next = { ...map };
      for (const [sessionId, marker] of Object.entries(next)) {
        if (isActiveStatus(marker.status) && !active.some(r => r.id === marker.runId)) delete next[sessionId];
      }
      for (const run of active) next[run.sessionId] = { runId: run.id, status: run.status };
      return next;
    });
    this.projectOf.update(map => {
      const next = { ...map };
      for (const run of active) next[run.sessionId] = run.projectId;
      return next;
    });
  }

  private loadSession(sessionId: string): Promise<void> {
    const existing = this.loading.get(sessionId);
    if (existing) return existing;
    const task = (async () => {
      const state = this.live.get(sessionId);
      if (!state) return;
      const [messages, runs, events] = await Promise.all([
        this.api.get<Message[]>(`sessions/${sessionId}/messages`),
        this.api.get<Run[]>(`sessions/${sessionId}/runs`, { limit: 30 }),
        this.api.get<StoredEvent[]>(`sessions/${sessionId}/events`, { limit: 600 }),
      ]);
      const ordered = [...runs].sort((a, b) => a.queuedAt.localeCompare(b.queuedAt));
      const latest = ordered[ordered.length - 1];
      state.update(current => {
        const base: SessionLive = { ...current, loaded: true, messages, runs: ordered };
        const replayed = replayStored(base, events);
        // eventos ao vivo que chegaram durante a carga continuam valendo
        const liveCurrent = current.current && (!latest || current.current.runId === latest.id || !isActiveStatus(latest.status)) ? current.current : null;
        return { ...replayed, current: liveCurrent && isActiveStatus(liveCurrent.status) ? liveCurrent : currentFromRun(latest) };
      });
      if (latest) this.setMarker(sessionId, latest.id, latest.status);
    })().finally(() => this.loading.delete(sessionId));
    this.loading.set(sessionId, task);
    return task;
  }

  /** Recarrega mensagens e execuções sem perder atividade nem console. */
  private async refreshSession(sessionId: string): Promise<void> {
    const state = this.live.get(sessionId);
    if (!state) return;
    const [messages, runs] = await Promise.all([
      this.api.get<Message[]>(`sessions/${sessionId}/messages`),
      this.api.get<Run[]>(`sessions/${sessionId}/runs`, { limit: 30 }),
    ]);
    const ordered = [...runs].sort((a, b) => a.queuedAt.localeCompare(b.queuedAt));
    state.update(s => ({ ...s, messages, runs: ordered }));
  }

  private async resync(): Promise<void> {
    await this.loadActiveRuns();
    for (const sessionId of this.live.keys()) {
      const state = this.live.get(sessionId)!;
      state.set({ ...emptyLive() });
      await this.loadSession(sessionId);
    }
  }

  /* ---------- eventos ---------- */

  private onEvent(event: RunEventEnvelope): void {
    if (event.type === 'run.queued') this.setMarker(event.sessionId, event.runId, 'QUEUED');
    else if (event.type === 'run.started') this.setMarker(event.sessionId, event.runId, 'RUNNING');
    else if (event.type === 'notice' && event.data['code'] === 'cancelling') this.setMarker(event.sessionId, event.runId, 'CANCELLING');
    else if (isTerminalEvent(event.type)) {
      this.setMarker(event.sessionId, event.runId, (event.data['status'] as RunStatus) ?? 'FAILED');
      void this.api.get<QueueState>('runs/queue').then(q => this.queue.set(q)).catch(() => undefined);
    }

    const state = this.live.get(event.sessionId);
    if (!state) return;
    const before = state();
    const after = applyEvent(before, event);
    if (after !== before) state.set(after);
    if (after.finishedTick !== before.finishedTick) void this.refreshSession(event.sessionId);
  }

  private setMarker(sessionId: string, runId: string, status: RunStatus): void {
    this.runs.update(map => ({ ...map, [sessionId]: { runId, status } }));
  }

  /* ---------- comandos ---------- */

  async create(projectId: string, agentId: string, title?: string): Promise<Session> {
    const session = await this.api.post<Session>('sessions', { projectId, agentId, title: title ?? null });
    this.index([session]);
    this.byProject.update(map => ({ ...map, [projectId]: [...(map[projectId] ?? []), session] }));
    return session;
  }

  async rename(id: string, title: string): Promise<void> {
    const session = await this.api.put<Session>(`sessions/${id}`, { title });
    this.replace(session);
  }

  async resetContext(id: string): Promise<void> {
    const session = await this.api.post<Session>(`sessions/${id}/reset-context`);
    this.replace(session);
  }

  async remove(id: string): Promise<void> {
    const session = this.sessionIndex.get(id);
    await this.api.delete(`sessions/${id}`);
    this.sessionIndex.delete(id);
    this.live.delete(id);
    if (session) this.byProject.update(map => ({ ...map, [session.projectId]: (map[session.projectId] ?? []).filter(s => s.id !== id) }));
    this.recent.update(list => list.filter(s => s.id !== id));
  }

  async send(sessionId: string, content: string, mode: RunKind): Promise<Submission> {
    const submission = await this.api.post<Submission>(`sessions/${sessionId}/messages`, { content, mode });
    this.setMarker(sessionId, submission.run.id, submission.run.status);
    const state = this.live.get(sessionId);
    if (state) {
      state.update(s => {
        const messages = s.messages.some(m => m.id === submission.message.id) ? s.messages : [...s.messages, submission.message];
        const keepCurrent = s.current && s.current.runId === submission.run.id;
        return {
          ...s,
          messages,
          runs: [...s.runs.filter(r => r.id !== submission.run.id), submission.run],
          current: keepCurrent ? s.current : currentFromRun(submission.run),
        };
      });
    }
    void this.loadRecent();
    return submission;
  }

  async cancel(runId: string): Promise<Run> {
    return this.api.post<Run>(`runs/${runId}/cancel`);
  }

  async pauseQueue(paused: boolean): Promise<void> {
    this.queue.set(await this.api.post<QueueState>(`runs/queue/${paused ? 'pause' : 'resume'}`));
  }

  private index(list: Session[]): void {
    if (list.length === 0) return;
    for (const s of list) this.sessionIndex.set(s.id, s);
    this.projectOf.update(map => {
      const next = { ...map };
      for (const s of list) next[s.id] = s.projectId;
      return next;
    });
  }

  private replace(session: Session): void {
    this.sessionIndex.set(session.id, session);
    this.byProject.update(map => ({
      ...map,
      [session.projectId]: (map[session.projectId] ?? []).map(s => (s.id === session.id ? session : s)),
    }));
    this.recent.update(list => list.map(s => (s.id === session.id ? session : s)));
  }
}
