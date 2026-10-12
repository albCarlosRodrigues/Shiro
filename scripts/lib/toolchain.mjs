// Utilitários compartilhados pelos scripts de desenvolvimento do Squad Desktop.
// Só usam módulos nativos do Node, para rodarem antes de qualquer `npm install`.
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
export const TOOLCHAIN = path.join(ROOT, '.toolchain');
export const LOGS = path.join(TOOLCHAIN, 'logs');
export const IS_WIN = process.platform === 'win32';
export const EXE = IS_WIN ? '.exe' : '';

/** Versões fixadas das ferramentas que os scripts baixam para dentro de .toolchain/. */
export const PINNED = {
  maven: '3.9.11',
  nodeMajor: 24,
  jdkFeature: 21,
};

/** Faixa de Node aceita pelo Angular CLI 22: ^22.22.3 || ^24.15.0 || >=26.0.0 */
export function nodeSatisfiesAngular(version) {
  const m = /^v?(\d+)\.(\d+)\.(\d+)/.exec(version || '');
  if (!m) return false;
  const [maj, min, pat] = [Number(m[1]), Number(m[2]), Number(m[3])];
  if (maj === 22) return min > 22 || (min === 22 && pat >= 3);
  if (maj === 24) return min >= 15;
  return maj >= 26;
}

export function exists(p) {
  try { fs.accessSync(p); return true; } catch { return false; }
}

export async function ensureDir(dir) {
  await fsp.mkdir(dir, { recursive: true });
  return dir;
}

/**
 * Executa um processo sem shell, gravando stdout+stderr num arquivo de log.
 * Nunca lança: devolve { ok, code, ms, log }.
 */
export function run(file, args, { cwd = ROOT, env = {}, log, echo = false, timeoutMs = 0 } = {}) {
  return new Promise(resolve => {
    const started = Date.now();
    let out = null;
    if (log) {
      fs.mkdirSync(path.dirname(log), { recursive: true });
      out = fs.createWriteStream(log, { flags: 'a' });
      out.write(`\n$ ${[file, ...args].join(' ')}\n(cwd: ${cwd})\n`);
    }
    let child;
    try {
      child = spawn(file, args, { cwd, env: { ...process.env, ...env }, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (err) {
      if (out) out.end(`falha ao iniciar: ${err.message}\n`);
      resolve({ ok: false, code: null, ms: 0, log, error: err.message });
      return;
    }
    let timer = null;
    if (timeoutMs > 0) timer = setTimeout(() => { try { child.kill(); } catch { /* já saiu */ } }, timeoutMs);
    const sink = chunk => { if (out) out.write(chunk); if (echo) process.stdout.write(chunk); };
    child.stdout.on('data', sink);
    child.stderr.on('data', sink);
    child.on('error', err => {
      if (timer) clearTimeout(timer);
      if (out) out.end(`erro: ${err.message}\n`);
      resolve({ ok: false, code: null, ms: Date.now() - started, log, error: err.message });
    });
    child.on('close', code => {
      if (timer) clearTimeout(timer);
      if (out) out.end(`\n[exit ${code}] ${Date.now() - started} ms\n`);
      resolve({ ok: code === 0, code, ms: Date.now() - started, log });
    });
  });
}

/** Executa e captura a saída (para sondagens curtas, como `java -version`). */
export function capture(file, args, { cwd = ROOT, env = {}, timeoutMs = 20000, verbatim = false } = {}) {
  try {
    const r = spawnSync(file, args, { cwd, env: { ...process.env, ...env }, encoding: 'utf8', windowsHide: true, timeout: timeoutMs, windowsVerbatimArguments: verbatim });
    return { ok: r.status === 0, code: r.status, stdout: r.stdout || '', stderr: r.stderr || '', error: r.error ? r.error.message : null };
  } catch (err) {
    return { ok: false, code: null, stdout: '', stderr: '', error: err.message };
  }
}

/** Procura um executável no PATH sem usar shell. */
export function which(name) {
  const exts = IS_WIN ? (process.env.PATHEXT || '.EXE;.CMD;.BAT').split(';').filter(Boolean) : [''];
  const dirs = (process.env.PATH || '').split(path.delimiter).filter(Boolean);
  for (const dir of dirs) {
    for (const ext of exts) {
      const candidate = path.join(dir, name + ext.toLowerCase());
      if (exists(candidate)) return candidate;
      const upper = path.join(dir, name + ext);
      if (exists(upper)) return upper;
    }
  }
  return null;
}

export async function download(url, dest, { label = url } = {}) {
  await ensureDir(path.dirname(dest));
  const res = await fetch(url, { redirect: 'follow' });
  if (!res.ok || !res.body) throw new Error(`download de ${label} falhou: HTTP ${res.status}`);
  const tmp = `${dest}.part`;
  await pipeline(Readable.fromWeb(res.body), fs.createWriteStream(tmp));
  await fsp.rename(tmp, dest);
  return dest;
}

/** Extrai .zip, .tar.gz ou .tar.xz. No Windows usa o tar.exe nativo (bsdtar) e cai para Expand-Archive. */
export async function extract(archive, destDir, log) {
  await ensureDir(destDir);
  const tarBin = IS_WIN ? path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'tar.exe') : 'tar';
  const viaTar = await run(exists(tarBin) || !IS_WIN ? tarBin : 'tar', ['-xf', archive, '-C', destDir], { log });
  if (viaTar.ok) return true;
  if (IS_WIN && archive.toLowerCase().endsWith('.zip')) {
    const ps = await run('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
      `Expand-Archive -LiteralPath '${archive.replace(/'/g, "''")}' -DestinationPath '${destDir.replace(/'/g, "''")}' -Force`], { log });
    return ps.ok;
  }
  return false;
}

/* ---------- Node ---------- */

/** Localiza o npm-cli.js que acompanha um node.exe, para chamar o npm sem passar por .cmd. */
export function npmCliFor(nodeExe) {
  const dir = path.dirname(nodeExe);
  const candidates = [
    path.join(dir, 'node_modules', 'npm', 'bin', 'npm-cli.js'),
    path.join(dir, '..', 'lib', 'node_modules', 'npm', 'bin', 'npm-cli.js'),
  ];
  return candidates.find(exists) || null;
}

function portableNodeHome() {
  const base = path.join(TOOLCHAIN, 'node');
  if (!exists(base)) return null;
  for (const entry of fs.readdirSync(base)) {
    const home = path.join(base, entry);
    const exe = IS_WIN ? path.join(home, 'node.exe') : path.join(home, 'bin', 'node');
    if (exists(exe)) return { home, exe };
  }
  return null;
}

/** Resolve qual Node usar: o atual, se o Angular o aceita e o npm-cli.js foi encontrado; senão o portátil em .toolchain/node. */
export function resolveNode() {
  const current = { exe: process.execPath, version: process.version, npmCli: npmCliFor(process.execPath), source: 'sistema' };
  if (nodeSatisfiesAngular(current.version) && current.npmCli) return current;
  const portable = portableNodeHome();
  if (portable) {
    const v = capture(portable.exe, ['--version']).stdout.trim();
    return { exe: portable.exe, version: v, npmCli: npmCliFor(portable.exe), source: 'toolchain' };
  }
  return { ...current, insufficient: true };
}

export async function latestNodeRelease(major) {
  const res = await fetch('https://nodejs.org/dist/index.json');
  if (!res.ok) throw new Error(`nodejs.org respondeu HTTP ${res.status}`);
  const list = await res.json();
  const hit = list.find(r => r.version.startsWith(`v${major}.`));
  if (!hit) throw new Error(`nenhuma versão v${major} em nodejs.org/dist/index.json`);
  return hit.version;
}

export function nodeArchiveName(version, platform = process.platform, arch = process.arch) {
  const a = arch === 'arm64' ? 'arm64' : 'x64';
  if (platform === 'win32') return `node-${version}-win-${a}.zip`;
  if (platform === 'darwin') return `node-${version}-darwin-${a}.tar.gz`;
  return `node-${version}-linux-${a}.tar.xz`;
}

/* ---------- Java ---------- */

export function probeJava(javaExe) {
  if (!javaExe || !exists(javaExe)) return null;
  const r = capture(javaExe, ['-XshowSettings:properties', '-version']);
  const text = `${r.stdout}\n${r.stderr}`;
  const version = /java\.version = (\S+)/.exec(text)?.[1] || /version "([^"]+)"/.exec(text)?.[1] || null;
  const home = /java\.home = (.+)/.exec(text)?.[1]?.trim() || path.dirname(path.dirname(javaExe));
  if (!version) return null;
  const feature = Number(version.split(/[.+-]/)[0]);
  return {
    exe: javaExe, home, version, feature,
    isJdk: exists(path.join(home, 'bin', `javac${EXE}`)),
    hasJlink: exists(path.join(home, 'bin', `jlink${EXE}`)),
  };
}

function toolchainJdks() {
  const base = path.join(TOOLCHAIN, 'jdk');
  if (!exists(base)) return [];
  const out = [];
  for (const entry of fs.readdirSync(base)) {
    for (const rel of [['bin'], ['Contents', 'Home', 'bin']]) {
      const exe = path.join(base, entry, ...rel, `java${EXE}`);
      if (exists(exe)) out.push(exe);
    }
  }
  return out;
}

/** Escolhe o JDK mais novo (>= minFeature) entre JAVA_HOME, PATH e .toolchain/jdk. */
export function resolveJdk(minFeature = PINNED.jdkFeature) {
  const candidates = [];
  if (process.env.SQUAD_JAVA_HOME) candidates.push(path.join(process.env.SQUAD_JAVA_HOME, 'bin', `java${EXE}`));
  if (process.env.JAVA_HOME) candidates.push(path.join(process.env.JAVA_HOME, 'bin', `java${EXE}`));
  const onPath = which('java');
  if (onPath) candidates.push(onPath);
  candidates.push(...toolchainJdks());
  const seen = new Set();
  const found = [];
  for (const c of candidates) {
    const info = probeJava(c);
    if (!info || seen.has(info.home)) continue;
    seen.add(info.home);
    found.push(info);
  }
  const usable = found.filter(j => j.isJdk && j.feature >= minFeature).sort((a, b) => b.feature - a.feature);
  return { chosen: usable[0] || null, found };
}

export function temurinUrl(feature = PINNED.jdkFeature, platform = process.platform, arch = process.arch) {
  const os = platform === 'win32' ? 'windows' : platform === 'darwin' ? 'mac' : 'linux';
  const a = arch === 'arm64' ? 'aarch64' : 'x64';
  return `https://api.adoptium.net/v3/binary/latest/${feature}/ga/${os}/${a}/jdk/hotspot/normal/eclipse`;
}

/* ---------- Maven ---------- */

export function mavenHome() {
  if (process.env.SQUAD_MAVEN_HOME && exists(process.env.SQUAD_MAVEN_HOME)) return process.env.SQUAD_MAVEN_HOME;
  const home = path.join(TOOLCHAIN, 'maven', `apache-maven-${PINNED.maven}`);
  return exists(path.join(home, 'boot')) ? home : null;
}

export function mavenRepo() {
  return process.env.SQUAD_MAVEN_REPO || path.join(TOOLCHAIN, 'm2');
}

/**
 * Monta a linha de comando que o mvn(.cmd) executa, chamando o java diretamente.
 * Evita depender de .cmd no Windows e de o Maven estar no PATH.
 */
export function mavenCommand(jdk, mvnHome, projectDir, args) {
  const boot = path.join(mvnHome, 'boot');
  const classworlds = fs.readdirSync(boot).find(f => /^plexus-classworlds-.*\.jar$/.test(f));
  if (!classworlds) throw new Error(`plexus-classworlds não encontrado em ${boot}`);
  return {
    file: jdk.exe,
    args: [
      '-classpath', path.join(boot, classworlds),
      `-Dclassworlds.conf=${path.join(mvnHome, 'bin', 'm2.conf')}`,
      `-Dmaven.home=${mvnHome}`,
      `-Dlibrary.jansi.path=${path.join(mvnHome, 'lib', 'jansi-native')}`,
      `-Dmaven.multiModuleProjectDirectory=${projectDir}`,
      'org.codehaus.plexus.classworlds.launcher.Launcher',
      '--batch-mode', '--no-transfer-progress',
      `-Dmaven.repo.local=${mavenRepo()}`,
      ...args,
    ],
    env: { JAVA_HOME: jdk.home },
  };
}

export async function runMaven(args, { log, echo = false, offline = false } = {}) {
  const { chosen: jdk } = resolveJdk();
  if (!jdk) return { ok: false, error: `nenhum JDK ${PINNED.jdkFeature}+ encontrado; rode "npm run bootstrap"` };
  const home = mavenHome();
  if (!home) return { ok: false, error: 'Maven não encontrado em .toolchain/maven; rode "npm run bootstrap"' };
  const backend = path.join(ROOT, 'backend');
  const cmd = mavenCommand(jdk, home, backend, [...(offline ? ['--offline'] : []), ...args]);
  return run(cmd.file, cmd.args, { cwd: backend, env: cmd.env, log, echo });
}

export async function runNpm(node, args, { cwd, log, echo = false, env = {} } = {}) {
  if (!node.npmCli) return { ok: false, error: 'npm-cli.js não encontrado ao lado do Node em uso' };
  const binDir = path.dirname(node.exe);
  return run(node.exe, [node.npmCli, ...args], {
    cwd, log, echo,
    env: { PATH: `${binDir}${path.delimiter}${process.env.PATH || ''}`, npm_config_update_notifier: 'false', npm_config_fund: 'false', npm_config_audit: 'false', ...env },
  });
}

export async function runNodeScript(node, script, args, { cwd, log, echo = false, env = {} } = {}) {
  const binDir = path.dirname(node.exe);
  return run(node.exe, [script, ...args], { cwd, log, echo, env: { PATH: `${binDir}${path.delimiter}${process.env.PATH || ''}`, ...env } });
}
