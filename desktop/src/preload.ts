/**
 * Preload da janela principal. Roda em sandbox: só pode usar o módulo `electron` (nada de imports locais
 * em tempo de execução). Expõe uma API pequena e tipada; cada chamada é validada de novo no processo principal.
 */
import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron';

import type { InitInfo, OpenTextArgs, PickFolderArgs, SaveTextArgs, WindowAction } from './ipc-contract';

// Nomes repetidos de ipc-contract.ts: o preload em sandbox não pode importar outros arquivos.
const CH = {
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

const info = ipcRenderer.sendSync(CH.init) as InitInfo;

const bridge = {
  setAppearance: (value: { theme: string; bg: string; fg: string }): Promise<void> => ipcRenderer.invoke(CH.setAppearance, value),
  platform: info.platform,
  appVersion: info.appVersion,
  windowControlsWidth: info.windowControlsWidth,
  pickFolder: (options?: PickFolderArgs): Promise<string | null> => ipcRenderer.invoke(CH.pickFolder, options),
  revealFolder: (folder: string): Promise<{ ok: boolean; error?: string }> => ipcRenderer.invoke(CH.revealFolder, folder),
  saveTextFile: (options: SaveTextArgs): Promise<string | null> => ipcRenderer.invoke(CH.saveTextFile, options),
  openTextFile: (options: OpenTextArgs): Promise<{ path: string; content: string } | null> => ipcRenderer.invoke(CH.openTextFile, options),
  openDataFolder: (): Promise<{ ok: boolean; error?: string }> => ipcRenderer.invoke(CH.openDataFolder),
  windowControl: (action: WindowAction): Promise<void> => ipcRenderer.invoke(CH.windowControl, action),
  backendLog: (): Promise<{ path: string; tail: string }> => ipcRenderer.invoke(CH.backendLog),
  onWindowState(listener: (state: { maximized: boolean; focused: boolean }) => void): () => void {
    // o evento do Electron não é repassado: só os dois campos esperados
    const wrapped = (_event: IpcRendererEvent, state: { maximized?: unknown; focused?: unknown }) =>
      listener({ maximized: state?.maximized === true, focused: state?.focused !== false });
    ipcRenderer.on(CH.windowState, wrapped);
    return () => ipcRenderer.removeListener(CH.windowState, wrapped);
  },
};

contextBridge.exposeInMainWorld('squadDesktop', Object.freeze(bridge));
