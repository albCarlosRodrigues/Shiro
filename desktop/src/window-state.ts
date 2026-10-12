import fs from 'node:fs';

export interface WindowState {
  x?: number;
  y?: number;
  width: number;
  height: number;
  maximized: boolean;
}

export const DEFAULT_STATE: WindowState = { width: 1366, height: 820, maximized: false };

interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Lê o estado salvo e descarta posições fora das telas atuais (monitor desconectado, por exemplo). */
export function loadWindowState(file: string, displays: Rect[]): WindowState {
  let saved: Partial<WindowState> = {};
  try {
    saved = JSON.parse(fs.readFileSync(file, 'utf8')) as Partial<WindowState>;
  } catch {
    return { ...DEFAULT_STATE };
  }
  const width = clamp(Number(saved.width) || DEFAULT_STATE.width, 960, 10_000);
  const height = clamp(Number(saved.height) || DEFAULT_STATE.height, 600, 10_000);
  const state: WindowState = { width, height, maximized: saved.maximized === true };
  if (typeof saved.x === 'number' && typeof saved.y === 'number') {
    const visible = displays.some(d => saved.x! + 80 > d.x && saved.y! + 40 > d.y && saved.x! < d.x + d.width - 80 && saved.y! < d.y + d.height - 40);
    if (visible) {
      state.x = saved.x;
      state.y = saved.y;
    }
  }
  return state;
}

export function saveWindowState(file: string, state: WindowState): void {
  try {
    fs.writeFileSync(file, JSON.stringify(state));
  } catch {
    // preferência de janela não é essencial
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
