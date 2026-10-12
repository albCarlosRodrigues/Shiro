// Tipos espelhados dos registros Java expostos pela API local. Mantenha em sincronia com o backend.

export type AgentRole = 'COMMANDER' | 'REQUIREMENTS' | 'ARCHITECTURE' | 'OPERATOR';
export type RunKind = 'CHAT' | 'PLANNING' | 'EXECUTION';
export type RunAccess = 'READ_ONLY' | 'WRITE';
export type RunStatus =
  | 'QUEUED' | 'STARTING' | 'RUNNING' | 'CANCELLING'
  | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT' | 'INTERRUPTED';
export type DocumentKind = 'BRIEFING' | 'VISION' | 'SCOPE_IN' | 'SCOPE_OUT' | 'GLOSSARY' | 'ARCHITECTURE' | 'ADR' | 'FREE';
export type AuthorType = 'USER' | 'AGENT';
export type MessageRole = 'USER' | 'AGENT' | 'SYSTEM';

export interface ApiErrorBody {
  code: string;
  message: string;
  details?: Record<string, unknown>;
}

export interface Project {
  id: string;
  groupId: string | null;
  squadId: string | null;
  name: string;
  color: string;
  folderPath: string | null;
  position: number;
  sample: boolean;
  archivedAt: string | null;
  lastOpenedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectView {
  project: Project;
  folderExists: boolean;
}

export interface ProjectDraft {
  name: string;
  color?: string | null;
  folderPath?: string | null;
  groupId?: string | null;
  squadId?: string | null;
}

export interface ProjectGroup {
  id: string;
  name: string;
  color: string;
  position: number;
  createdAt: string;
  updatedAt: string;
}

export interface Agent {
  id: string;
  name: string;
  role: AgentRole;
  specialty: string;
  icon: string;
  color: string;
  responsibility: string;
  personality: string;
  instructions: string;
  guidelines: string;
  model: string | null;
  effort: string | null;
  permissionMode: string | null;
  tools: string[] | null;
  allowedTools: string[];
  disallowedTools: string[];
  templateKey: string | null;
  sample: boolean;
  archivedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export type AgentDraft = Omit<Agent, 'id' | 'sample' | 'archivedAt' | 'createdAt' | 'updatedAt'>;

export interface AgentTemplate {
  key: string;
  label: string;
  summary: string;
  draft: AgentDraft;
}

export interface AgentOptions {
  permissionModes: string[];
  effortLevels: string[];
  modelAliases: string[];
  icons: string[];
  colors: string[];
  cliVersion: string | null;
  effortSupported: boolean;
}

export interface Squad {
  id: string;
  name: string;
  description: string;
  commanderId: string;
  requirementsId: string;
  architectureId: string;
  operatorIds: string[];
  sample: boolean;
  archivedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface SquadDraft {
  name: string;
  description: string;
  commanderId: string | null;
  requirementsId: string | null;
  architectureId: string | null;
  operatorIds: string[];
}

export interface Session {
  id: string;
  projectId: string;
  agentId: string;
  title: string;
  claudeSessionId: string | null;
  position: number;
  archivedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Message {
  id: string;
  sessionId: string;
  runId: string | null;
  role: MessageRole;
  content: string;
  seq: number;
  createdAt: string;
}

export interface Run {
  id: string;
  sessionId: string;
  projectId: string;
  agentId: string;
  kind: RunKind;
  access: RunAccess;
  status: RunStatus;
  demo: boolean;
  prompt: string;
  cwd: string | null;
  command: string[];
  claudeSessionId: string | null;
  model: string | null;
  cliVersion: string | null;
  exitCode: number | null;
  failureCode: string | null;
  failureMessage: string | null;
  resultText: string | null;
  costUsd: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  cacheReadTokens: number | null;
  cacheCreationTokens: number | null;
  numTurns: number | null;
  durationMs: number | null;
  permissionDenials: PermissionDenialRecord[];
  queuedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface PermissionDenialRecord {
  toolName: string;
  toolUseId?: string;
  input?: Record<string, unknown>;
  suggestedRule?: string;
}

export interface Submission {
  message: Message;
  run: Run;
}

export interface QueueState {
  paused: boolean;
  queued: number;
  running: number;
  maxConcurrent: number;
}

export interface StoredEvent {
  id: number;
  runId: string;
  sessionId: string;
  type: string;
  payload: string;
  createdAt: string;
}

/** Envelope transmitido pelo SSE (evento "run"). */
export interface RunEventEnvelope {
  id: number;
  runId: string;
  sessionId: string;
  type: string;
  at: string;
  data: Record<string, unknown>;
}

export interface Document {
  id: string;
  projectId: string;
  kind: DocumentKind;
  title: string;
  content: string;
  version: number;
  position: number;
  updatedByType: AuthorType;
  updatedById: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DocumentVersion {
  documentId: string;
  version: number;
  title: string;
  content: string;
  authorType: AuthorType;
  authorId: string | null;
  note: string | null;
  createdAt: string;
}

export interface AppSettings {
  claudePath: string | null;
  defaultModel: string | null;
  maxConcurrentRuns: number;
  runTimeoutMinutes: number;
  maxBudgetUsd: number | null;
  demoMode: boolean;
  /** Features em paralelo, cada uma em um git worktree próprio. */
  isolatedFeatures: boolean;
}

/* ---------- planejamento, planos e features ---------- */

export type FeatureStatus = 'PLANNED' | 'QUEUED' | 'IN_PROGRESS' | 'IN_REVIEW' | 'APPROVED' | 'FAILED' | 'CANCELLED';
export type Priority = 'HIGH' | 'MEDIUM' | 'LOW';

export interface PlanningParticipant {
  agentId: string;
  name: string;
  handle: string;
  role: AgentRole;
  roleLabel: string;
  specialty: string;
  icon: string;
  color: string;
  commander: boolean;
}

export interface PlanningAttachment {
  type: 'proposal' | 'plan' | 'warning';
  id?: string;
  title: string;
  detail: string;
}

export interface PlanningMessage {
  id: string;
  projectId: string;
  seq: number;
  authorType: 'USER' | 'AGENT' | 'SYSTEM';
  agentId: string | null;
  content: string;
  mentions: string[];
  attachments: PlanningAttachment[];
  runId: string | null;
  roundId: string | null;
  createdAt: string;
}

export interface PlanningRound {
  id: string;
  projectId: string;
  status: 'ACTIVE' | 'DONE' | 'STOPPED';
  pending: string[];
  spoken: string[];
  currentRun: string | null;
}

export interface PlanningRoom {
  projectId: string;
  participants: PlanningParticipant[];
  messages: PlanningMessage[];
  round: PlanningRound | null;
  hasMore: boolean;
}

export type ProposalStatus = 'PENDING' | 'APPLIED' | 'DISCARDED' | 'UNDONE';

export interface Proposal {
  id: string;
  projectId: string;
  documentId: string | null;
  kind: DocumentKind;
  title: string;
  content: string;
  summary: string;
  agentId: string | null;
  messageId: string | null;
  baseVersion: number | null;
  status: ProposalStatus;
  appliedVersion: number | null;
  createdAt: string;
  decidedAt: string | null;
}

export interface PlanSprintItem {
  name: string;
  goal: string;
}

export interface PlanFeatureItem {
  code: string;
  title: string;
  description: string;
  priority: string;
  specialty: string;
  owner: string;
  sprint: string;
  criteria: string[];
  tasks: string[];
  dependsOn: string[];
}

export interface PlanContent {
  summary: string;
  sprints: PlanSprintItem[];
  features: PlanFeatureItem[];
}

export interface PlanProblem {
  severity: 'ERROR' | 'WARNING';
  code: string;
  feature: string | null;
  field: string | null;
  message: string;
}

export interface Plan {
  id: string;
  projectId: string;
  number: number;
  status: 'DRAFT' | 'APPROVED' | 'DISCARDED';
  authorType: 'USER' | 'AGENT';
  agentId: string | null;
  messageId: string | null;
  summary: string;
  content: PlanContent;
  problems: PlanProblem[];
  createdAt: string;
  updatedAt: string;
  approvedAt: string | null;
}

export interface Criterion {
  text: string;
  met: boolean;
}

export interface FeatureTask {
  text: string;
  done: boolean;
}

export interface Feature {
  id: string;
  projectId: string;
  sprintId: string | null;
  planId: string | null;
  code: string;
  title: string;
  description: string;
  priority: Priority;
  specialty: string;
  ownerAgentId: string | null;
  acceptanceCriteria: Criterion[];
  tasks: FeatureTask[];
  status: FeatureStatus;
  revision: number;
  position: number;
  workdir: string | null;
  branch: string | null;
  baseCommit: string | null;
  statusNote: string | null;
  dependsOn: string[];
  createdAt: string;
  updatedAt: string;
}

export interface Sprint {
  id: string;
  projectId: string;
  name: string;
  goal: string;
  position: number;
}

export interface FeatureOverview {
  projectId: string;
  sprints: Sprint[];
  features: Feature[];
  blockedBy: Record<string, string[]>;
  queuePaused: boolean;
  pauseReason: string | null;
}

export interface Handoff {
  objetivo?: string;
  contexto?: string;
  entregas?: string[];
  arquivos?: string[];
  pendencias?: string[];
  criterios_validacao?: string[];
  testes?: string[];
  proximo?: string;
  mensagem_proximo?: string;
  sem_bloco?: boolean;
  permissoes_negadas?: number;
}

export interface FeatureStep {
  id: string;
  featureId: string;
  seq: number;
  revision: number;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  reason: string;
  status: 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED';
  handoff: Handoff | null;
  createdAt: string;
  updatedAt: string;
}

export interface FeatureReview {
  id: string;
  featureId: string;
  revision: number;
  decision: 'APPROVED' | 'CHANGES_REQUESTED';
  comment: string;
  createdAt: string;
}

export interface FeatureDetail {
  feature: Feature;
  steps: FeatureStep[];
  reviews: FeatureReview[];
  blockedBy: string[];
}

export interface FileChange {
  path: string;
  status: 'added' | 'deleted' | 'modified' | 'type';
  additions: number | null;
  deletions: number | null;
}

export interface ChangesView {
  available: boolean;
  reason: string | null;
  workdir: string | null;
  branch: string | null;
  files: FileChange[];
}

export interface FeatureDraft {
  code?: string | null;
  title?: string | null;
  description?: string | null;
  priority?: string | null;
  specialty?: string | null;
  ownerAgentId?: string | null;
  sprintId?: string | null;
  acceptanceCriteria?: Criterion[] | null;
  tasks?: FeatureTask[] | null;
  dependsOn?: string[] | null;
}

export interface ApplyResult {
  planId: string;
  created: number;
  updated: number;
  skipped: string[];
  features: Feature[];
}

export interface ClaudeCapabilities {
  version: string | null;
  streamJson: boolean;
  partialMessages: boolean;
  permissionPrompts: boolean;
  effort: boolean;
  maxBudget: boolean;
  sessionId: boolean;
  resume: boolean;
  toolsFlag: boolean;
  appendSystemPromptFile: boolean;
  systemPromptSnapshot: boolean;
  modelAliases: string[];
  effortLevels: string[];
  permissionModes: string[];
}

export interface ClaudeStatus {
  found: boolean;
  usable: boolean;
  executable: string | null;
  source: string | null;
  version: string | null;
  authenticated: boolean | null;
  authMethod: string | null;
  capabilities: ClaudeCapabilities;
  problemCode: string | null;
  problem: string | null;
  checkedAt: string;
}

export interface ConnectionTestResult {
  ok: boolean;
  message: string;
  model: string | null;
  costUsd: number | null;
  elapsedMs: number;
}

export interface SystemInfo {
  version: string;
  dataDir: string;
  database: string;
  platform: string;
  javaVersion: string;
  settings: AppSettings;
  claude: ClaudeStatus;
  projects: number;
  agents: number;
  firstRun: boolean;
}

export interface ActiveWork {
  active: boolean;
  runs: number;
  queue: QueueState;
}

export interface Starter {
  agents: Agent[];
  squad: Squad | null;
  created: boolean;
}

export interface AgentPreview {
  instructions: string;
  turnPreamble: string;
}
