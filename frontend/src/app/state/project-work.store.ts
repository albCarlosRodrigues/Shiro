import { Injectable, WritableSignal, inject, signal } from '@angular/core';

import { Api } from '../core/api';
import { EventStream } from '../core/event-stream';
import type {
  ApplyResult, Feature, FeatureDetail, FeatureDraft, FeatureOverview, Plan, PlanContent, PlanningRoom, Proposal, RunEventEnvelope, Sprint,
} from '../core/models';

export type WorkScope = 'planning' | 'proposals' | 'plans' | 'features';

/** Texto que o agente está escrevendo agora na sala de planejamento (antes de a fala ser gravada). */
export interface LiveSpeech {
  runId: string;
  agentId: string | null;
  text: string;
}

/**
 * Estado da sala de planejamento, das propostas, dos planos e das features de cada projeto. Recarrega a parte
 * indicada quando o backend avisa ({@code project.changed}) e tudo o que já foi carregado ao reconectar.
 */
@Injectable({ providedIn: 'root' })
export class ProjectWorkStore {
  private readonly api = inject(Api);
  private readonly stream = inject(EventStream);

  readonly rooms = signal<Record<string, PlanningRoom>>({});
  readonly proposals = signal<Record<string, Proposal[]>>({});
  readonly plans = signal<Record<string, Plan[]>>({});
  readonly overviews = signal<Record<string, FeatureOverview>>({});
  readonly speech = signal<Record<string, LiveSpeech>>({});

  private readonly timers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly loaded = new Set<string>();

  constructor() {
    this.stream.events$.subscribe(event => this.onEvent(event));
    this.stream.reconnected$.subscribe(() => {
      for (const key of this.loaded) {
        const [projectId, scope] = key.split('|') as [string, WorkScope];
        this.schedule(projectId, scope, 0);
      }
    });
  }

  /* ---------- carga ---------- */

  async load(projectId: string, scope: WorkScope): Promise<void> {
    this.loaded.add(`${projectId}|${scope}`);
    switch (scope) {
      case 'planning': {
        const room = await this.api.get<PlanningRoom>(`projects/${projectId}/planning`);
        this.set(this.rooms, projectId, room);
        if (this.speech()[projectId]?.runId !== room.round?.currentRun) this.clearSpeech(projectId);
        break;
      }
      case 'proposals':
        this.set(this.proposals, projectId, await this.api.get<Proposal[]>(`projects/${projectId}/proposals`));
        break;
      case 'plans':
        this.set(this.plans, projectId, await this.api.get<Plan[]>(`projects/${projectId}/plans`));
        break;
      case 'features':
        this.set(this.overviews, projectId, await this.api.get<FeatureOverview>(`projects/${projectId}/features`));
        break;
    }
  }

  loadAll(projectId: string): Promise<unknown> {
    return Promise.all((['planning', 'proposals', 'plans', 'features'] as WorkScope[]).map(s => this.load(projectId, s)));
  }

  /* ---------- sala ---------- */

  async post(projectId: string, content: string): Promise<void> {
    this.set(this.rooms, projectId, await this.api.post<PlanningRoom>(`projects/${projectId}/planning/messages`, { content }));
  }

  async stop(projectId: string): Promise<void> {
    this.set(this.rooms, projectId, await this.api.post<PlanningRoom>(`projects/${projectId}/planning/stop`));
    this.clearSpeech(projectId);
  }

  /* ---------- propostas ---------- */

  async applyProposal(proposal: Proposal, force = false): Promise<void> {
    await this.api.post<Proposal>(`proposals/${proposal.id}/apply`, { force });
    await this.load(proposal.projectId, 'proposals');
  }

  async discardProposal(proposal: Proposal): Promise<void> {
    await this.api.post<Proposal>(`proposals/${proposal.id}/discard`);
    await this.load(proposal.projectId, 'proposals');
  }

  async undoProposal(proposal: Proposal): Promise<void> {
    await this.api.post<Proposal>(`proposals/${proposal.id}/undo`);
    await this.load(proposal.projectId, 'proposals');
  }

  /* ---------- planos ---------- */

  async createPlan(projectId: string): Promise<Plan> {
    const plan = await this.api.post<Plan>(`projects/${projectId}/plans`);
    await this.load(projectId, 'plans');
    return plan;
  }

  async updatePlan(plan: Plan, content: PlanContent): Promise<Plan> {
    const saved = await this.api.put<Plan>(`plans/${plan.id}`, content);
    this.replacePlan(saved);
    return saved;
  }

  async discardPlan(plan: Plan): Promise<void> {
    this.replacePlan(await this.api.post<Plan>(`plans/${plan.id}/discard`));
  }

  async approvePlan(plan: Plan): Promise<ApplyResult> {
    const result = await this.api.post<ApplyResult>(`plans/${plan.id}/approve`);
    await Promise.all([this.load(plan.projectId, 'plans'), this.load(plan.projectId, 'features')]);
    return result;
  }

  private replacePlan(plan: Plan): void {
    this.plans.update(map => ({ ...map, [plan.projectId]: (map[plan.projectId] ?? []).map(p => (p.id === plan.id ? plan : p)) }));
  }

  /* ---------- features ---------- */

  detail(featureId: string): Promise<FeatureDetail> {
    return this.api.get<FeatureDetail>(`features/${featureId}`);
  }

  async createFeature(projectId: string, draft: FeatureDraft): Promise<Feature> {
    const feature = await this.api.post<Feature>(`projects/${projectId}/features`, draft);
    await this.load(projectId, 'features');
    return feature;
  }

  async updateFeature(feature: Feature, draft: FeatureDraft): Promise<Feature> {
    const saved = await this.api.put<Feature>(`features/${feature.id}`, draft);
    await this.load(feature.projectId, 'features');
    return saved;
  }

  async deleteFeature(feature: Feature): Promise<void> {
    await this.api.delete(`features/${feature.id}`);
    await this.load(feature.projectId, 'features');
  }

  async check(feature: Feature, list: 'criteria' | 'tasks', index: number, value: boolean): Promise<Feature> {
    const saved = await this.api.put<Feature>(`features/${feature.id}/${list}/${index}`, { value });
    this.replaceFeature(saved);
    return saved;
  }

  async authorize(feature: Feature): Promise<void> {
    this.replaceFeature(await this.api.post<Feature>(`features/${feature.id}/authorize`));
  }

  async authorizeAll(projectId: string): Promise<number> {
    const result = await this.api.post<{ authorized: number }>(`projects/${projectId}/features/authorize-all`);
    await this.load(projectId, 'features');
    return result.authorized;
  }

  async setPaused(projectId: string, paused: boolean): Promise<void> {
    this.set(this.overviews, projectId, await this.api.post<FeatureOverview>(`projects/${projectId}/features/${paused ? 'pause' : 'resume'}`));
  }

  async cancelFeature(feature: Feature): Promise<void> {
    this.replaceFeature(await this.api.post<Feature>(`features/${feature.id}/cancel`));
  }

  async approveFeature(feature: Feature, comment: string): Promise<void> {
    this.replaceFeature(await this.api.post<Feature>(`features/${feature.id}/approve`, { comment }));
    await this.load(feature.projectId, 'features');
  }

  async requestChanges(feature: Feature, comment: string): Promise<void> {
    this.replaceFeature(await this.api.post<Feature>(`features/${feature.id}/request-changes`, { comment }));
  }

  async createSprint(projectId: string, name: string, goal = ''): Promise<Sprint> {
    const sprint = await this.api.post<Sprint>(`projects/${projectId}/sprints`, { name, goal });
    await this.load(projectId, 'features');
    return sprint;
  }

  async updateSprint(sprint: Sprint, name: string, goal: string): Promise<void> {
    await this.api.put<Sprint>(`sprints/${sprint.id}`, { name, goal });
    await this.load(sprint.projectId, 'features');
  }

  async deleteSprint(sprint: Sprint): Promise<void> {
    await this.api.delete(`sprints/${sprint.id}`);
    await this.load(sprint.projectId, 'features');
  }

  private replaceFeature(feature: Feature): void {
    this.overviews.update(map => {
      const current = map[feature.projectId];
      if (!current) return map;
      return { ...map, [feature.projectId]: { ...current, features: current.features.map(f => (f.id === feature.id ? feature : f)) } };
    });
  }

  /* ---------- eventos ---------- */

  private onEvent(event: RunEventEnvelope): void {
    if (event.type === 'project.changed') {
      const projectId = String(event.data['projectId'] ?? '');
      const scope = String(event.data['scope'] ?? '') as WorkScope;
      if (this.loaded.has(`${projectId}|${scope}`)) this.schedule(projectId, scope, 120);
      // propostas e planos nascem de falas da sala
      if (scope === 'planning' && this.loaded.has(`${projectId}|planning`)) {
        if (this.loaded.has(`${projectId}|proposals`)) this.schedule(projectId, 'proposals', 160);
        if (this.loaded.has(`${projectId}|plans`)) this.schedule(projectId, 'plans', 160);
      }
      return;
    }
    // fala em andamento na sala de planejamento
    for (const [projectId, room] of Object.entries(this.rooms())) {
      const runId = room.round?.currentRun;
      if (!runId || runId !== event.runId) continue;
      if (event.type === 'text.delta') {
        const text = String(event.data['text'] ?? '');
        this.speech.update(map => {
          const current = map[projectId]?.runId === runId ? map[projectId] : { runId, agentId: null, text: '' };
          return { ...map, [projectId]: { ...current, text: current.text + text } };
        });
      } else if (event.type === 'run.started' && !this.speech()[projectId]) {
        this.speech.update(map => ({ ...map, [projectId]: { runId, agentId: null, text: '' } }));
      }
    }
  }

  private clearSpeech(projectId: string): void {
    if (!this.speech()[projectId]) return;
    this.speech.update(map => {
      const next = { ...map };
      delete next[projectId];
      return next;
    });
  }

  private schedule(projectId: string, scope: WorkScope, ms: number): void {
    const key = `${projectId}|${scope}`;
    const existing = this.timers.get(key);
    if (existing) clearTimeout(existing);
    this.timers.set(key, setTimeout(() => {
      this.timers.delete(key);
      void this.load(projectId, scope).catch(() => undefined);
    }, ms));
  }

  private set<T>(target: WritableSignal<Record<string, T>>, projectId: string, value: T): void {
    target.update(map => ({ ...map, [projectId]: value }));
  }
}
