import { BrowserWindow, app, dialog, ipcMain, screen, session, shell, type IpcMainInvokeEvent } from 'electron';
import fs from 'node:fs';
import path from 'node:path';

import { BackendProcess, BackendStartError, resolveLayout } from './backend';
import {
  CHANNELS, IpcValidationError, isTrustedSender, validateOpenText, validatePath, validatePickFolder, validateSaveText,
  validateWindowAction, validateAppearance, type InitInfo,
} from './ipc-contract';
import { loadAppearance, saveAppearance } from './appearance';
import { LogFile } from './log-file';
import { loadWindowState, saveWindowState } from './window-state';

/**
 * Processo principal do Shiro. Responsabilidades mínimas: janela, diálogos nativos, integração com
 * o sistema e o ciclo de vida do serviço Java. Regras de negócio ficam no serviço; a interface fica no Angular.
 */

const PARTITION = 'squad-local'; // sem "persist:": cookies e armazenamento da sessão ficam só em memória
const CONTROLS_WIDTH = process.platform === 'darwin' ? 0 : 138;
const MAX_OPEN_BYTES = 64 * 1024 * 1024;

app.setAppUserModelId('dev.squaddesktop.app');
// mesmo nome (e mesma pasta de dados) no aplicativo instalado e ao rodar a partir do código-fonte
app.setName('Shiro');
app.setPath('userData', process.env['SQUAD_USER_DATA'] ?? path.join(app.getPath('appData'), 'Squad Desktop'));

const userData = app.getPath('userData');
const paths = {
  data: path.join(userData, 'data'),
  logs: path.join(userData, 'logs'),
  run: path.join(userData, 'run'),
  windowState: path.join(userData, 'window-state.json'),
};
const appearanceFile = path.join(userData, 'appearance.json');
let appearance = loadAppearance(appearanceFile);
const backendLog = new LogFile(path.join(paths.logs, 'backend.log'));

let mainWindow: BrowserWindow | null = null;
let backend: BackendProcess | null = null;
let quitting = false;
let closeRequested = false;

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });
  app.whenReady().then(boot).catch(err => {
    dialog.showErrorBox('Shiro', `Falha ao iniciar: ${err instanceof Error ? err.message : String(err)}`);
    app.exit(1);
  });
}

/* ---------- segurança ---------- */

function hardenSession(): void {
  const ses = session.fromPartition(PARTITION);
  // nenhuma permissão de dispositivo ou notificação; só escrita na área de transferência (botão "Copiar")
  ses.setPermissionRequestHandler((_wc, permission, callback) => callback(permission === 'clipboard-sanitized-write'));
  ses.setPermissionCheckHandler((_wc, permission) => permission === 'clipboard-sanitized-write');
  ses.on('will-download', event => event.preventDefault());
}

app.on('web-contents-created', (_event, contents) => {
  contents.on('will-attach-webview', event => event.preventDefault());
  contents.on('will-navigate', (event, url) => {
    if (!backend?.origin || new URL(url).origin !== backend.origin) event.preventDefault();
  });
  contents.setWindowOpenHandler(({ url }) => {
    // links de respostas e documentos abrem no navegador do sistema, nunca dentro do app
    if (/^https?:\/\//i.test(url)) void shell.openExternal(url);
    return { action: 'deny' };
  });
});

/* ---------- inicialização ---------- */

async function boot(): Promise<void> {
  hardenSession();
  registerIpc();
  backendLog.open();
  createWindow();
  await startBackend();
}

function createWindow(): void {
  const state = loadWindowState(paths.windowState, screen.getAllDisplays().map(d => d.workArea));
  mainWindow = new BrowserWindow({
    ...state,
    minWidth: 960,
    minHeight: 600,
    show: false,
    title: 'Shiro',
    backgroundColor: appearance.bg,
    titleBarStyle: 'hidden',
    titleBarOverlay: process.platform === 'darwin' ? undefined : { color: appearance.bg, symbolColor: appearance.fg, height: 36 },
    autoHideMenuBar: true,
    icon: path.join(__dirname, '..', 'resources', process.platform === 'win32' ? 'icon.ico' : 'icon.png'),
    webPreferences: {
      partition: PARTITION,
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      webSecurity: true,
      spellcheck: false,
      devTools: !app.isPackaged,
    },
  });
  if (state.maximized) mainWindow.maximize();
  mainWindow.removeMenu();
  mainWindow.once('ready-to-show', () => mainWindow?.show());
  void mainWindow.loadURL(splash('Iniciando o serviço local…'));

  const sendState = () => mainWindow?.webContents.send(CHANNELS.windowState, {
    maximized: mainWindow.isMaximized(),
    focused: mainWindow.isFocused(),
  });
  mainWindow.on('maximize', sendState);
  mainWindow.on('unmaximize', sendState);
  mainWindow.on('focus', sendState);
  mainWindow.on('blur', sendState);
  mainWindow.webContents.on('did-finish-load', sendState);

  mainWindow.on('close', event => {
    if (mainWindow) {
      const bounds = mainWindow.getNormalBounds();
      saveWindowState(paths.windowState, { ...bounds, maximized: mainWindow.isMaximized() });
    }
    if (quitting) return;
    event.preventDefault();
    void requestQuit();
  });
  mainWindow.on('closed', () => (mainWindow = null));
}

async function startBackend(): Promise<void> {
  const layout = resolveLayout({
    packaged: app.isPackaged,
    resourcesPath: process.resourcesPath,
    appPath: app.getAppPath(),
    platform: process.platform,
    env: process.env,
  });
  const instance = new BackendProcess({
    layout,
    dataDir: paths.data,
    runDir: paths.run,
    log: backendLog,
    parentPid: process.pid,
    extraEnv: { SQUAD_APP_VERSION: app.getVersion() },
    onUnexpectedExit: code => void onBackendCrash(code),
  });
  try {
    await instance.start();
  } catch (error) {
    backend = null;
    await showStartFailure(error);
    return;
  }
  backend = instance;
  const origin = instance.origin!;
  await session.fromPartition(PARTITION).cookies.set({
    url: origin,
    name: 'squad_token',
    value: instance.token,
    httpOnly: true,
    sameSite: 'strict',
  });
  await mainWindow?.loadURL(origin + '/');
}

async function showStartFailure(error: unknown): Promise<void> {
  const message = error instanceof Error ? error.message : String(error);
  const tail = error instanceof BackendStartError ? error.logTail : backendLog.tail();
  const lines = tail.trim().split('\n').slice(-14).join('\n');
  void mainWindow?.loadURL(splash('O serviço local não iniciou.'));
  const { response } = await dialog.showMessageBox(mainWindow!, {
    type: 'error',
    title: 'Shiro',
    message: 'O serviço local do Shiro não iniciou.',
    detail: `${message}\n\n${lines ? 'Últimas linhas do log:\n' + lines : ''}\n\nLog completo: ${backendLog.file}`,
    buttons: ['Tentar de novo', 'Abrir pasta de logs', 'Sair'],
    defaultId: 0,
    cancelId: 2,
  });
  if (response === 0) {
    void mainWindow?.loadURL(splash('Iniciando o serviço local…'));
    await startBackend();
  } else if (response === 1) {
    await shell.openPath(paths.logs);
    await showStartFailure(error);
  } else {
    quitting = true;
    app.quit();
  }
}

async function onBackendCrash(code: number | null): Promise<void> {
  if (quitting || closeRequested) return;
  backend = null;
  void mainWindow?.loadURL(splash('O serviço local parou.'));
  const { response } = await dialog.showMessageBox(mainWindow!, {
    type: 'warning',
    title: 'Shiro',
    message: 'O serviço local foi encerrado inesperadamente.',
    detail: `Código de saída: ${code ?? 'desconhecido'}. Execuções em andamento serão marcadas como interrompidas ao reiniciar; nada é repetido automaticamente.\n\nLog: ${backendLog.file}`,
    buttons: ['Reiniciar o serviço', 'Sair'],
    defaultId: 0,
    cancelId: 1,
  });
  if (response === 0) {
    void mainWindow?.loadURL(splash('Reiniciando o serviço local…'));
    await startBackend();
  } else {
    quitting = true;
    app.quit();
  }
}

/* ---------- encerramento ---------- */

app.on('before-quit', event => {
  if (quitting) return;
  event.preventDefault();
  void requestQuit();
});

app.on('window-all-closed', () => {
  if (quitting) app.quit();
});

/** Avisa sobre trabalho ativo, permite desistir e, confirmado, encerra o serviço e os processos que ele controla. */
async function requestQuit(): Promise<void> {
  if (closeRequested) return;
  closeRequested = true;
  try {
    const work = backend ? await backend.activeWork() : null;
    if (work?.active && mainWindow) {
      const { response } = await dialog.showMessageBox(mainWindow, {
        type: 'warning',
        title: 'Há trabalho em andamento',
        message: work.runs === 1 ? 'Uma execução está em andamento ou na fila.' : `${work.runs} execuções estão em andamento ou na fila.`,
        detail: 'Ao sair, as execuções são canceladas e os processos do Claude Code são encerrados. Na próxima abertura elas aparecem como interrompidas; nada é retomado sozinho.',
        buttons: ['Cancelar execuções e sair', 'Voltar ao aplicativo'],
        defaultId: 1,
        cancelId: 1,
        noLink: true,
      });
      if (response !== 0) {
        closeRequested = false;
        return;
      }
    }
    void mainWindow?.loadURL(splash('Encerrando…'));
    await backend?.stop();
  } finally {
    if (closeRequested) {
      quitting = true;
      backendLog.close();
      app.quit();
    }
  }
}

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => {
    closeRequested = true;
    void (backend?.stop(5_000) ?? Promise.resolve()).finally(() => {
      quitting = true;
      app.quit();
    });
  });
}

/* ---------- IPC ---------- */

function guard<A, R>(validate: (value: unknown) => A, handler: (args: A, event: IpcMainInvokeEvent) => Promise<R> | R) {
  return async (event: IpcMainInvokeEvent, raw: unknown): Promise<R> => {
    if (!isTrustedSender(event.senderFrame?.url, backend?.origin ?? null) || event.sender !== mainWindow?.webContents) {
      throw new Error('origem não autorizada');
    }
    try {
      return await handler(validate(raw), event);
    } catch (error) {
      if (error instanceof IpcValidationError) throw new Error(error.message);
      throw error;
    }
  };
}

const none = (value: unknown) => {
  if (value !== undefined) throw new IpcValidationError('argumentos inesperados');
  return undefined;
};

function registerIpc(): void {
  ipcMain.handle(CHANNELS.setAppearance, guard(validateAppearance, value => {
    saveAppearance(appearanceFile, value);
    appearance = value;
    mainWindow?.setBackgroundColor(value.bg);
    if (process.platform !== 'darwin') mainWindow?.setTitleBarOverlay({ color: value.bg, symbolColor: value.fg });
  }));
  ipcMain.on(CHANNELS.init, event => {
    const info: InitInfo = { platform: process.platform, appVersion: app.getVersion(), windowControlsWidth: CONTROLS_WIDTH };
    event.returnValue = info;
  });

  ipcMain.handle(CHANNELS.pickFolder, guard(validatePickFolder, async args => {
    const result = await dialog.showOpenDialog(mainWindow!, {
      title: args.title ?? 'Escolher pasta',
      defaultPath: args.defaultPath && fs.existsSync(args.defaultPath) ? args.defaultPath : app.getPath('documents'),
      properties: ['openDirectory', 'createDirectory', 'dontAddToRecent'],
    });
    return result.canceled ? null : result.filePaths[0] ?? null;
  }));

  ipcMain.handle(CHANNELS.revealFolder, guard(validatePath, async folder => {
    try {
      if (!fs.statSync(folder).isDirectory()) return { ok: false, error: 'não é uma pasta' };
    } catch {
      return { ok: false, error: 'pasta não encontrada' };
    }
    const error = await shell.openPath(folder);
    return error ? { ok: false, error } : { ok: true };
  }));

  ipcMain.handle(CHANNELS.saveTextFile, guard(validateSaveText, async args => {
    const result = await dialog.showSaveDialog(mainWindow!, {
      title: args.title ?? 'Salvar arquivo',
      defaultPath: path.join(app.getPath('documents'), args.defaultName),
      filters: [{ name: args.extensions.map(e => e.toUpperCase()).join(', '), extensions: args.extensions }],
    });
    if (result.canceled || !result.filePath) return null;
    await fs.promises.writeFile(result.filePath, args.content, 'utf8');
    return result.filePath;
  }));

  ipcMain.handle(CHANNELS.openTextFile, guard(validateOpenText, async args => {
    const result = await dialog.showOpenDialog(mainWindow!, {
      title: args.title ?? 'Abrir arquivo',
      properties: ['openFile', 'dontAddToRecent'],
      filters: [{ name: args.extensions.map(e => e.toUpperCase()).join(', '), extensions: args.extensions }],
    });
    const file = result.canceled ? undefined : result.filePaths[0];
    if (!file) return null;
    const stat = await fs.promises.stat(file);
    if (stat.size > MAX_OPEN_BYTES) throw new Error('arquivo grande demais');
    return { path: file, content: await fs.promises.readFile(file, 'utf8') };
  }));

  ipcMain.handle(CHANNELS.openDataFolder, guard(none, async () => {
    const error = await shell.openPath(userData);
    return error ? { ok: false, error } : { ok: true };
  }));

  ipcMain.handle(CHANNELS.windowControl, guard(validateWindowAction, action => {
    if (!mainWindow) return;
    if (action === 'minimize') mainWindow.minimize();
    else if (action === 'toggle-maximize') {
      if (mainWindow.isMaximized()) mainWindow.unmaximize();
      else mainWindow.maximize();
    } else mainWindow.close();
  }));

  ipcMain.handle(CHANNELS.backendLog, guard(none, () => ({ path: backendLog.file, tail: backendLog.tail() })));
}

/* ---------- tela de espera ---------- */

function splash(text: string): string {
  const html = `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:">
<title>Shiro</title><style>
html,body{height:100%;margin:0;background:${appearance.bg};color:${appearance.fg};font:13px system-ui,'Segoe UI',sans-serif;-webkit-app-region:drag}
.c{display:flex;height:100%;align-items:center;justify-content:center;gap:10px}
.m{width:22px;height:22px;border-radius:6px;background:#8fa2ff}
</style></head><body><div class="c"><img width="24" height="24" alt="" src="data:image/png;base64,${fs.readFileSync(path.join(__dirname, '..', 'resources', 'icon.png')).toString('base64')}" /><span>${escapeHtml(text)}</span></div></body></html>`;
  return 'data:text/html;charset=utf-8,' + encodeURIComponent(html);
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
}
