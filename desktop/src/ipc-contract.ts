/**
 * Contrato entre a interface (renderer) e o processo principal. Cada canal tem nome fixo e validação
 * de entrada: o renderer é tratado como não confiável, mesmo carregando só o conteúdo do serviço local.
 *
 * Mantenha a interface `SquadDesktopBridge` em frontend/src/app/core/desktop.ts em sincronia.
 */

export const CHANNELS = {
  init: 'sd:init',
  setAppearance: 'sd:set-appearance',
  pickFolder: 'sd:pick-folder',
  revealFolder: 'sd:reveal-folder',
  saveTextFile: 'sd:save-text-file',
  openTextFile: 'sd:open-text-file',
  openDataFolder: 'sd:open-data-folder',
  windowControl: 'sd:window-control',
  windowState: 'sd:window-state',
  backendLog: 'sd:backend-log',
} as const;

export type Channel = (typeof CHANNELS)[keyof typeof CHANNELS];

export interface InitInfo {
  platform: string;
  appVersion: string;
  windowControlsWidth: number;
}

export interface PickFolderArgs {
  title?: string;
  defaultPath?: string;
}

export interface SaveTextArgs {
  title?: string;
  defaultName: string;
  content: string;
  extensions: string[];
}

export interface OpenTextArgs {
  title?: string;
  extensions: string[];
}

export type WindowAction = 'minimize' | 'toggle-maximize' | 'close';

export class IpcValidationError extends Error {}

const MAX_PATH = 4096;
const MAX_TEXT = 64 * 1024 * 1024;
const EXTENSION = /^[a-z0-9]{1,8}$/i;

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function optionalString(value: unknown, field: string, max: number): string | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== 'string' || value.length > max || value.includes('\0')) {
    throw new IpcValidationError(`${field} inválido`);
  }
  return value;
}

function requiredString(value: unknown, field: string, max: number): string {
  const result = optionalString(value, field, max);
  if (!result) throw new IpcValidationError(`${field} obrigatório`);
  return result;
}

function extensions(value: unknown): string[] {
  if (!Array.isArray(value) || value.length === 0 || value.length > 8) throw new IpcValidationError('extensões inválidas');
  return value.map(ext => {
    if (typeof ext !== 'string' || !EXTENSION.test(ext)) throw new IpcValidationError('extensão inválida');
    return ext.toLowerCase();
  });
}

export function validatePickFolder(value: unknown): PickFolderArgs {
  if (value === undefined) return {};
  if (!isObject(value)) throw new IpcValidationError('argumentos inválidos');
  return {
    title: optionalString(value['title'], 'title', 200),
    defaultPath: optionalString(value['defaultPath'], 'defaultPath', MAX_PATH),
  };
}

export function validatePath(value: unknown): string {
  return requiredString(value, 'caminho', MAX_PATH);
}

/** Nome sugerido para salvar: só o nome do arquivo, sem pastas (o diálogo decide onde). */
export function validateSaveText(value: unknown): SaveTextArgs {
  if (!isObject(value)) throw new IpcValidationError('argumentos inválidos');
  const defaultName = requiredString(value['defaultName'], 'defaultName', 255);
  if (/[\\/:*?"<>|]/.test(defaultName) || defaultName === '.' || defaultName === '..') {
    throw new IpcValidationError('nome de arquivo inválido');
  }
  const content = value['content'];
  if (typeof content !== 'string' || content.length > MAX_TEXT) throw new IpcValidationError('conteúdo inválido');
  return { title: optionalString(value['title'], 'title', 200), defaultName, content, extensions: extensions(value['extensions']) };
}

export function validateOpenText(value: unknown): OpenTextArgs {
  if (!isObject(value)) throw new IpcValidationError('argumentos inválidos');
  return { title: optionalString(value['title'], 'title', 200), extensions: extensions(value['extensions']) };
}

export function validateWindowAction(value: unknown): WindowAction {
  if (value === 'minimize' || value === 'toggle-maximize' || value === 'close') return value;
  throw new IpcValidationError('ação de janela inválida');
}

/**
 * Só aceita mensagens da janela principal carregada a partir do serviço local (mesma origem).
 * Bloqueia frames embutidos e páginas de outra origem que tenham sido abertas por engano.
 */
export function isTrustedSender(senderUrl: string | undefined, expectedOrigin: string | null): boolean {
  if (!senderUrl || !expectedOrigin) return false;
  try {
    return new URL(senderUrl).origin === expectedOrigin;
  } catch {
    return false;
  }
}

export interface Appearance { theme: string; bg: string; fg: string; }
export function validateAppearance(value: unknown): Appearance {
  if (!isObject(value) || typeof value['theme'] !== 'string' || !/^[a-z0-9-]{1,32}$/.test(value['theme']) ||
      typeof value['bg'] !== 'string' || !/^#[0-9a-f]{6}$/i.test(value['bg']) ||
      typeof value['fg'] !== 'string' || !/^#[0-9a-f]{6}$/i.test(value['fg'])) throw new IpcValidationError('aparência inválida');
  return { theme: value['theme'], bg: value['bg'], fg: value['fg'] };
}
