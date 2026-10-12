import type { AgentRole, DocumentKind, FeatureStatus, Priority, RunKind, RunStatus } from './models';

export type StatusTone = 'running' | 'queued' | 'waiting' | 'done' | 'failed' | 'idle';

export interface StatusInfo {
  tone: StatusTone;
  label: string;
  icon: string;
}

const RUN_STATUS: Record<RunStatus, StatusInfo> = {
  QUEUED: { tone: 'queued', label: 'Na fila', icon: 'clock' },
  STARTING: { tone: 'running', label: 'Iniciando', icon: 'spinner' },
  RUNNING: { tone: 'running', label: 'Executando', icon: 'spinner' },
  CANCELLING: { tone: 'waiting', label: 'Cancelando', icon: 'spinner' },
  COMPLETED: { tone: 'done', label: 'Concluída', icon: 'check' },
  FAILED: { tone: 'failed', label: 'Falhou', icon: 'alert' },
  CANCELLED: { tone: 'idle', label: 'Cancelada', icon: 'stop' },
  TIMED_OUT: { tone: 'failed', label: 'Tempo esgotado', icon: 'alert' },
  INTERRUPTED: { tone: 'waiting', label: 'Interrompida', icon: 'pause' },
};

export function runStatusInfo(status: RunStatus | null | undefined): StatusInfo {
  return status ? RUN_STATUS[status] : { tone: 'idle', label: 'Ociosa', icon: 'dot' };
}

export const FEATURE_STATUS: Record<FeatureStatus, StatusInfo> = {
  PLANNED: { tone: 'idle', label: 'Planejada', icon: 'flag' },
  QUEUED: { tone: 'queued', label: 'Na fila', icon: 'clock' },
  IN_PROGRESS: { tone: 'running', label: 'Em execução', icon: 'spinner' },
  IN_REVIEW: { tone: 'waiting', label: 'Aguardando revisão', icon: 'review' },
  APPROVED: { tone: 'done', label: 'Aprovada', icon: 'check' },
  FAILED: { tone: 'failed', label: 'Falhou', icon: 'alert' },
  CANCELLED: { tone: 'idle', label: 'Cancelada', icon: 'stop' },
};

export const PRIORITY_LABEL: Record<Priority, string> = { HIGH: 'Alta', MEDIUM: 'Média', LOW: 'Baixa' };

export function isActiveStatus(status: RunStatus | null | undefined): boolean {
  return status === 'QUEUED' || status === 'STARTING' || status === 'RUNNING' || status === 'CANCELLING';
}

export function isTerminalStatus(status: RunStatus | null | undefined): boolean {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED' || status === 'TIMED_OUT' || status === 'INTERRUPTED';
}

export const ROLE_LABEL: Record<AgentRole, string> = {
  COMMANDER: 'Comandante',
  REQUIREMENTS: 'Requisitos e PRD',
  ARCHITECTURE: 'Arquitetura e ADR',
  OPERATOR: 'Operador',
};

export const ROLE_SHORT: Record<AgentRole, string> = {
  COMMANDER: 'CMD',
  REQUIREMENTS: 'PRD',
  ARCHITECTURE: 'ADR',
  OPERATOR: 'OP',
};

export const KIND_LABEL: Record<RunKind, string> = {
  CHAT: 'Conversa',
  PLANNING: 'Planejamento',
  EXECUTION: 'Execução',
};

export const DOCUMENT_LABEL: Record<DocumentKind, string> = {
  BRIEFING: 'Briefing',
  VISION: 'Visão do produto',
  SCOPE_IN: 'Escopo incluído',
  SCOPE_OUT: 'Escopo excluído',
  GLOSSARY: 'Glossário',
  ARCHITECTURE: 'Arquitetura',
  ADR: 'Decisão arquitetural',
  FREE: 'Documento',
};

const RELATIVE = new Intl.RelativeTimeFormat('pt-BR', { numeric: 'auto' });
const DATE_TIME = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' });
const TIME = new Intl.DateTimeFormat('pt-BR', { hour: '2-digit', minute: '2-digit' });

export function relativeTime(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '';
  const ms = new Date(iso).getTime();
  if (Number.isNaN(ms)) return '';
  const diff = (ms - now) / 1000;
  const abs = Math.abs(diff);
  if (abs < 45) return 'agora';
  if (abs < 3600) return RELATIVE.format(Math.round(diff / 60), 'minute');
  if (abs < 86_400) return RELATIVE.format(Math.round(diff / 3600), 'hour');
  if (abs < 7 * 86_400) return RELATIVE.format(Math.round(diff / 86_400), 'day');
  return DATE_TIME.format(ms);
}

export function clockTime(iso: string | null | undefined): string {
  if (!iso) return '';
  const ms = new Date(iso).getTime();
  return Number.isNaN(ms) ? '' : TIME.format(ms);
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '';
  const ms = new Date(iso).getTime();
  return Number.isNaN(ms) ? '' : DATE_TIME.format(ms);
}

export function duration(ms: number | null | undefined): string {
  if (ms == null) return '';
  if (ms < 1000) return `${ms} ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(s < 10 ? 1 : 0)} s`;
  const m = Math.floor(s / 60);
  return `${m} min ${Math.round(s % 60)} s`;
}

const USD = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 4 });
const INT = new Intl.NumberFormat('pt-BR');

export function usd(value: number | null | undefined): string {
  return value == null ? '' : USD.format(value);
}

export function integer(value: number | null | undefined): string {
  return value == null ? '' : INT.format(value);
}

/** Última parte de um caminho (Windows ou POSIX), para exibir a pasta do projeto sem ocupar o cabeçalho. */
export function baseName(path: string | null | undefined): string {
  if (!path) return '';
  const parts = path.replace(/[\\/]+$/, '').split(/[\\/]/);
  return parts[parts.length - 1] || path;
}

/** Iniciais para o avatar quando o agente não tem ícone reconhecido. */
export function initials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return '?';
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
  return (words[0][0] + words[words.length - 1][0]).toUpperCase();
}
