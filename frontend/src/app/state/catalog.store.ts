import { Injectable, computed, inject, signal } from '@angular/core';

import { Api } from '../core/api';
import type {
  Agent, AgentOptions, AgentTemplate, AppSettings, ClaudeStatus, Project, ProjectDraft, ProjectGroup, ProjectView,
  Squad, SquadDraft, SystemInfo,
} from '../core/models';

/**
 * Cadastros do workspace (projetos, grupos, agentes, squads) e situação do sistema.
 * Cada lista é um signal; as mutações passam pela API e atualizam a lista local com a resposta.
 */
@Injectable({ providedIn: 'root' })
export class CatalogStore {
  private readonly api = inject(Api);

  readonly projects = signal<ProjectView[]>([]);
  readonly archivedProjects = signal<ProjectView[]>([]);
  readonly groups = signal<ProjectGroup[]>([]);
  readonly agents = signal<Agent[]>([]);
  readonly squads = signal<Squad[]>([]);
  readonly templates = signal<AgentTemplate[]>([]);
  readonly options = signal<AgentOptions | null>(null);
  readonly system = signal<SystemInfo | null>(null);
  readonly claude = signal<ClaudeStatus | null>(null);
  readonly settings = signal<AppSettings | null>(null);
  readonly loaded = signal(false);

  readonly projectById = computed(() => new Map(this.projects().concat(this.archivedProjects()).map(v => [v.project.id, v])));
  readonly agentById = computed(() => new Map(this.agents().map(a => [a.id, a])));
  readonly squadById = computed(() => new Map(this.squads().map(s => [s.id, s])));
  readonly demoMode = computed(() => this.settings()?.demoMode === true);

  async loadAll(): Promise<void> {
    const [system, projects, groups, agents, squads, templates, options] = await Promise.all([
      this.api.get<SystemInfo>('system/info'),
      this.api.get<ProjectView[]>('projects'),
      this.api.get<ProjectGroup[]>('groups'),
      this.api.get<Agent[]>('agents'),
      this.api.get<Squad[]>('squads'),
      this.api.get<AgentTemplate[]>('agent-templates'),
      this.api.get<AgentOptions>('agent-options'),
    ]);
    this.system.set(system);
    this.settings.set(system.settings);
    this.claude.set(system.claude);
    this.projects.set(projects);
    this.groups.set(groups);
    this.agents.set(agents);
    this.squads.set(squads);
    this.templates.set(templates);
    this.options.set(options);
    this.loaded.set(true);
    if (system.claude.problemCode === 'checking') void this.awaitClaude();
  }

  /** A detecção do Claude Code roda em segundo plano no serviço; quando termina, atualiza a situação e as opções. */
  private async awaitClaude(): Promise<void> {
    try {
      this.claude.set(await this.api.get<ClaudeStatus>('system/claude', { wait: true }));
      this.options.set(await this.api.get<AgentOptions>('agent-options'));
    } catch {
      // a barra superior continua mostrando "verificando"; Configurações permite verificar de novo
    }
  }

  async reloadSystem(): Promise<void> {
    const system = await this.api.get<SystemInfo>('system/info');
    this.system.set(system);
    this.settings.set(system.settings);
    this.claude.set(system.claude);
    if (system.claude.problemCode === 'checking') void this.awaitClaude();
  }

  /* ---------- projetos ---------- */

  async reloadProjects(): Promise<void> {
    this.projects.set(await this.api.get<ProjectView[]>('projects'));
  }

  async loadArchived(): Promise<void> {
    const all = await this.api.get<ProjectView[]>('projects', { archived: true });
    this.archivedProjects.set(all.filter(v => v.project.archivedAt));
  }

  async createProject(draft: ProjectDraft): Promise<ProjectView> {
    const view = await this.api.post<ProjectView>('projects', draft);
    this.projects.update(list => [...list, view]);
    return view;
  }

  async updateProject(id: string, draft: ProjectDraft): Promise<ProjectView> {
    const view = await this.api.put<ProjectView>(`projects/${id}`, draft);
    this.replaceProject(view);
    return view;
  }

  async archiveProject(id: string, archived: boolean): Promise<ProjectView> {
    const view = await this.api.post<ProjectView>(`projects/${id}/${archived ? 'archive' : 'unarchive'}`);
    if (archived) {
      this.projects.update(list => list.filter(v => v.project.id !== id));
      this.archivedProjects.update(list => [...list.filter(v => v.project.id !== id), view]);
    } else {
      this.archivedProjects.update(list => list.filter(v => v.project.id !== id));
      this.projects.update(list => [...list.filter(v => v.project.id !== id), view].sort((a, b) => a.project.position - b.project.position));
    }
    return view;
  }

  async deleteProject(id: string): Promise<void> {
    await this.api.delete(`projects/${id}`);
    this.projects.update(list => list.filter(v => v.project.id !== id));
    this.archivedProjects.update(list => list.filter(v => v.project.id !== id));
  }

  async markOpened(id: string): Promise<void> {
    try {
      this.replaceProject(await this.api.post<ProjectView>(`projects/${id}/opened`));
    } catch {
      // registrar a abertura é conveniência; nunca bloqueia a navegação
    }
  }

  private replaceProject(view: ProjectView): void {
    this.projects.update(list => list.map(v => (v.project.id === view.project.id ? view : v)));
    this.archivedProjects.update(list => list.map(v => (v.project.id === view.project.id ? view : v)));
  }

  /* ---------- grupos ---------- */

  async createGroup(name: string, color?: string): Promise<ProjectGroup> {
    const group = await this.api.post<ProjectGroup>('groups', { name, color });
    this.groups.update(list => [...list, group]);
    return group;
  }

  async updateGroup(id: string, name: string, color?: string): Promise<void> {
    const group = await this.api.put<ProjectGroup>(`groups/${id}`, { name, color });
    this.groups.update(list => list.map(g => (g.id === id ? group : g)));
  }

  async deleteGroup(id: string): Promise<void> {
    await this.api.delete(`groups/${id}`);
    this.groups.update(list => list.filter(g => g.id !== id));
    await this.reloadProjects();
  }

  /* ---------- agentes ---------- */

  async reloadAgents(): Promise<void> {
    this.agents.set(await this.api.get<Agent[]>('agents'));
  }

  upsertAgent(agent: Agent): void {
    this.agents.update(list => (list.some(a => a.id === agent.id) ? list.map(a => (a.id === agent.id ? agent : a)) : [...list, agent]));
  }

  async deleteAgent(id: string): Promise<'deleted' | 'archived'> {
    const removal = await this.api.delete<{ result: 'deleted' | 'archived' }>(`agents/${id}`);
    await this.reloadAgents();
    return removal.result;
  }

  /* ---------- squads ---------- */

  async createSquad(draft: SquadDraft): Promise<Squad> {
    const squad = await this.api.post<Squad>('squads', draft);
    this.squads.update(list => [...list, squad]);
    return squad;
  }

  async updateSquad(id: string, draft: SquadDraft): Promise<Squad> {
    const squad = await this.api.put<Squad>(`squads/${id}`, draft);
    this.squads.update(list => list.map(s => (s.id === id ? squad : s)));
    return squad;
  }

  async deleteSquad(id: string): Promise<void> {
    await this.api.delete(`squads/${id}`);
    this.squads.update(list => list.filter(s => s.id !== id));
  }

  /* ---------- preferências e Claude Code ---------- */

  async saveSettings(settings: AppSettings): Promise<AppSettings> {
    const saved = await this.api.put<AppSettings>('settings', settings);
    this.settings.set(saved);
    return saved;
  }

  async refreshClaude(): Promise<ClaudeStatus> {
    const status = await this.api.post<ClaudeStatus>('system/claude/refresh');
    this.claude.set(status);
    this.options.set(await this.api.get<AgentOptions>('agent-options'));
    return status;
  }

  projectSorted(group: string | null): ProjectView[] {
    return this.projects().filter(v => (v.project.groupId ?? null) === group);
  }

  project(id: string | null | undefined): Project | undefined {
    return id ? this.projectById().get(id)?.project : undefined;
  }
}
