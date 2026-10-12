import { spawn, type ChildProcess } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

import { LogFile, redact } from './log-file';
import { killTree } from './process-tree';

export interface BackendLayout {
  java: string;
  jar: string;
  staticDir: string;
  source: 'empacotado' | 'desenvolvimento';
}

export interface LayoutInput {
  packaged: boolean;
  resourcesPath: string;
  appPath: string;
  platform: NodeJS.Platform;
  env: NodeJS.ProcessEnv;
}

const exe = (platform: NodeJS.Platform) => (platform === 'win32' ? 'java.exe' : 'java');

/**
 * Onde estão o runtime Java, o serviço e a interface. No aplicativo instalado tudo vem dos recursos do
 * pacote (runtime Java incluído); no desenvolvimento, das saídas de build do monorepositório.
 */
export function resolveLayout(input: LayoutInput): BackendLayout {
  if (input.packaged) {
    return {
      java: path.join(input.resourcesPath, 'runtime', 'bin', exe(input.platform)),
      jar: path.join(input.resourcesPath, 'backend', 'squad-desktop-backend.jar'),
      staticDir: path.join(input.resourcesPath, 'frontend'),
      source: 'empacotado',
    };
  }
  const root = path.resolve(input.appPath, '..');
  const candidates = [
    input.env['SQUAD_JAVA_HOME'] && path.join(input.env['SQUAD_JAVA_HOME'], 'bin', exe(input.platform)),
    path.join(root, '.toolchain', 'runtime', 'bin', exe(input.platform)),
    input.env['JAVA_HOME'] && path.join(input.env['JAVA_HOME'], 'bin', exe(input.platform)),
  ].filter((c): c is string => !!c);
  const java = candidates.find(c => fs.existsSync(c)) ?? exe(input.platform);
  return {
    java,
    jar: path.join(root, 'backend', 'target', 'squad-desktop-backend.jar'),
    staticDir: path.join(root, 'frontend', 'dist', 'frontend', 'browser'),
    source: 'desenvolvimento',
  };
}

/** Argumentos da JVM: separados (nunca uma linha de shell), o que mantém caminhos com espaços e acentos intactos. */
export function javaArgs(layout: BackendLayout): string[] {
  return [
    '-Xms48m',
    '-Xmx768m',
    '-XX:+UseSerialGC',
    '-XX:TieredStopAtLevel=1',
    '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    '--enable-native-access=ALL-UNNAMED',
    '-jar',
    layout.jar,
  ];
}

/**
 * Fora do Windows, a JVM codifica caminhos de arquivo conforme a localidade do processo. Com localidade
 * POSIX/ASCII, uma pasta com acentos (por exemplo, "Ação") não pode nem ser aberta. Garante UTF-8.
 */
export function utf8Locale(platform: NodeJS.Platform, env: NodeJS.ProcessEnv): Record<string, string> {
  if (platform === 'win32') return {};
  const current = env['LC_ALL'] || env['LC_CTYPE'] || env['LANG'] || '';
  return /utf-?8/i.test(current) ? {} : { LC_ALL: 'C.UTF-8', LANG: 'C.UTF-8' };
}

export interface ReadyInfo {
  port: number;
  pid: number;
  version: string;
  dataDir: string;
}

export function parseReady(text: string): ReadyInfo | null {
  try {
    const value = JSON.parse(text) as Partial<ReadyInfo>;
    if (typeof value.port !== 'number' || value.port <= 0 || value.port > 65535) return null;
    return { port: value.port, pid: Number(value.pid ?? 0), version: String(value.version ?? ''), dataDir: String(value.dataDir ?? '') };
  } catch {
    return null;
  }
}

export class BackendStartError extends Error {
  constructor(message: string, readonly logTail: string) {
    super(message);
  }
}

export interface BackendOptions {
  layout: BackendLayout;
  dataDir: string;
  runDir: string;
  log: LogFile;
  parentPid: number;
  startTimeoutMs?: number;
  extraEnv?: Record<string, string>;
  onUnexpectedExit?: (code: number | null) => void;
}

/** Serviço Java local: inicia em loopback com porta dinâmica e token desta inicialização, e encerra de forma ordenada. */
export class BackendProcess {
  readonly token = randomBytes(32).toString('hex');
  private child: ChildProcess | null = null;
  private ready: ReadyInfo | null = null;
  private stopping = false;

  constructor(private readonly options: BackendOptions) {}

  get origin(): string | null {
    return this.ready ? `http://127.0.0.1:${this.ready.port}` : null;
  }

  get info(): ReadyInfo | null {
    return this.ready;
  }

  async start(): Promise<ReadyInfo> {
    const { layout, dataDir, runDir, log } = this.options;
    for (const [file, label] of [[layout.jar, 'serviço (jar)'], [layout.staticDir, 'interface compilada']] as const) {
      if (!fs.existsSync(file)) throw new BackendStartError(`Arquivo do ${label} não encontrado: ${file}`, '');
    }
    fs.mkdirSync(runDir, { recursive: true });
    fs.mkdirSync(dataDir, { recursive: true });
    const readyFile = path.join(runDir, `ready-${process.pid}.json`);
    fs.rmSync(readyFile, { force: true });

    log.write(`Iniciando serviço (${layout.source}): ${layout.java} ${javaArgs(layout).join(' ')}\n`);
    const child = spawn(layout.java, javaArgs(layout), {
      cwd: dataDir,
      windowsHide: true,
      detached: process.platform !== 'win32',
      stdio: ['ignore', 'pipe', 'pipe'],
      env: {
        ...process.env,
        ...this.options.extraEnv,
        SQUAD_AUTH_TOKEN: this.token,
        SQUAD_DATA_DIR: dataDir,
        SQUAD_READY_FILE: readyFile,
        SQUAD_PARENT_PID: String(this.options.parentPid),
        SQUAD_STATIC_DIR: layout.staticDir,
        SQUAD_PORT: '0',
        // o JDK não deve herdar opções de outras ferramentas instaladas na máquina
        JAVA_TOOL_OPTIONS: '',
        _JAVA_OPTIONS: '',
        ...utf8Locale(process.platform, process.env),
      },
    });
    this.child = child;
    const pipe = (chunk: Buffer) => log.write(redact(chunk.toString('utf8'), this.token));
    child.stdout?.on('data', pipe);
    child.stderr?.on('data', pipe);

    const exited = new Promise<number | null>(resolve => {
      child.once('error', err => {
        log.write(`Falha ao iniciar o Java: ${err.message}\n`);
        resolve(-1);
      });
      child.once('exit', code => resolve(code));
    });
    void exited.then(code => {
      log.write(`Serviço encerrado (código ${code}).\n`);
      const wasReady = this.ready !== null;
      this.child = null;
      if (wasReady && !this.stopping) this.options.onUnexpectedExit?.(code);
    });

    const deadline = Date.now() + (this.options.startTimeoutMs ?? 90_000);
    let exitCode: number | null | undefined;
    void exited.then(code => (exitCode = code));
    while (Date.now() < deadline) {
      if (exitCode !== undefined) {
        throw new BackendStartError(exitCode === -1
          ? `Não foi possível executar o Java em ${layout.java}.`
          : `O serviço local terminou durante a inicialização (código ${exitCode}).`, log.tail());
      }
      const ready = fs.existsSync(readyFile) ? parseReady(fs.readFileSync(readyFile, 'utf8')) : null;
      if (ready) {
        this.ready = ready;
        fs.rmSync(readyFile, { force: true });
        await this.request('GET', '/api/health');
        log.write(`Serviço pronto na porta ${ready.port}.\n`);
        return ready;
      }
      await delay(100);
    }
    await killTree(child);
    throw new BackendStartError('O serviço local não ficou pronto a tempo.', log.tail());
  }

  /** Chamada autenticada à API local (o token vai no cabeçalho, nunca na URL). */
  async request<T = unknown>(method: 'GET' | 'POST', pathname: string, timeoutMs = 8_000): Promise<T> {
    if (!this.origin) throw new Error('serviço não iniciado');
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch(this.origin + pathname, {
        method,
        headers: { Authorization: `Bearer ${this.token}`, 'X-Squad-Client': 'squad-desktop-shell' },
        signal: controller.signal,
      });
      const text = await response.text();
      if (!response.ok) throw new Error(`HTTP ${response.status} em ${pathname}`);
      return (text ? JSON.parse(text) : null) as T;
    } finally {
      clearTimeout(timer);
    }
  }

  async activeWork(): Promise<{ active: boolean; runs: number } | null> {
    try {
      return await this.request<{ active: boolean; runs: number }>('GET', '/api/system/active-work', 3_000);
    } catch {
      return null;
    }
  }

  /** Encerramento ordenado: o serviço cancela execuções e encerra os processos do Claude Code; depois força, se preciso. */
  async stop(graceMs = 12_000): Promise<void> {
    const child = this.child;
    if (!child) return;
    this.stopping = true;
    const exited = new Promise<void>(resolve => {
      if (child.exitCode !== null) resolve();
      else child.once('exit', () => resolve());
    });
    try {
      await this.request('POST', '/api/system/shutdown', 3_000);
    } catch {
      // serviço não respondeu: segue para o encerramento forçado
    }
    const finished = await Promise.race([exited.then(() => true), delay(graceMs).then(() => false)]);
    if (!finished) {
      this.options.log.write('O serviço não encerrou no prazo; encerrando a árvore de processos.\n');
      await killTree(child);
    }
  }
}

function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}
