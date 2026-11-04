import fs from 'node:fs';
import path from 'node:path';

import { IpcValidationError } from './ipc-contract';

/**
 * Preferência do modo em segundo plano: com ela ligada, o X esconde a janela na bandeja e os agentes continuam
 * trabalhando; sair de verdade é pelo menu da bandeja. Fica em background.json, na pasta do usuário do app.
 */
export interface BackgroundPreference {
  keepRunningInTray: boolean;
  /** O aviso "O Shiro continua rodando aqui" já foi mostrado uma vez. */
  trayNoticeShown: boolean;
}

export const DEFAULT_BACKGROUND: BackgroundPreference = { keepRunningInTray: true, trayNoticeShown: false };

export function loadBackground(file: string): BackgroundPreference {
  try {
    const raw = JSON.parse(fs.readFileSync(file, 'utf8')) as Record<string, unknown>;
    return {
      keepRunningInTray: typeof raw['keepRunningInTray'] === 'boolean' ? raw['keepRunningInTray'] : DEFAULT_BACKGROUND.keepRunningInTray,
      trayNoticeShown: raw['trayNoticeShown'] === true,
    };
  } catch {
    return { ...DEFAULT_BACKGROUND };
  }
}

export function saveBackground(file: string, value: BackgroundPreference): void {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const temp = file + '.tmp';
  fs.writeFileSync(temp, JSON.stringify({ keepRunningInTray: value.keepRunningInTray, trayNoticeShown: value.trayNoticeShown }), 'utf8');
  fs.renameSync(temp, file);
}

/** Valida o que a interface manda pelo IPC: só o liga/desliga do modo em segundo plano. */
export function validateBackgroundSetting(value: unknown): { keepRunningInTray: boolean } {
  if (typeof value !== 'object' || value === null || typeof (value as Record<string, unknown>)['keepRunningInTray'] !== 'boolean') {
    throw new IpcValidationError('preferência de segundo plano inválida');
  }
  return { keepRunningInTray: (value as Record<string, boolean>)['keepRunningInTray'] };
}

export type CloseAction = 'allow' | 'hide' | 'quit';

/**
 * O que fazer quando a janela recebe o pedido de fechar (o X):
 * - `allow`: o app já está saindo; deixa fechar;
 * - `hide`: esconde na bandeja (modo ligado, bandeja disponível e serviço no ar);
 * - `quit`: segue o encerramento normal (com o aviso de trabalho ativo).
 * Sem serviço no ar (falha ao iniciar, por exemplo) não faz sentido esconder: o X sai.
 */
export function closeAction(state: { quitting: boolean; keepRunningInTray: boolean; trayAvailable: boolean; backendReady: boolean }): CloseAction {
  if (state.quitting) return 'allow';
  if (state.keepRunningInTray && state.trayAvailable && state.backendReady) return 'hide';
  return 'quit';
}

/** Aviso do sistema pedido pela interface (fim de execução, permissão). */
export interface NotificationRequest {
  title: string;
  body: string;
}

export function validateNotification(value: unknown): NotificationRequest {
  if (typeof value !== 'object' || value === null) throw new IpcValidationError('aviso inválido');
  const { title, body } = value as Record<string, unknown>;
  if (typeof title !== 'string' || !title.trim() || title.length > 120 ||
      typeof body !== 'string' || body.length > 300) throw new IpcValidationError('aviso inválido');
  return { title: title.trim(), body };
}

/** Só incomoda quem não está olhando: janela escondida na bandeja, minimizada ou sem foco. */
export function shouldNotify(window: { exists: boolean; visible: boolean; minimized: boolean; focused: boolean }): boolean {
  return !window.exists || !window.visible || window.minimized || !window.focused;
}
