#!/usr/bin/env node
// Prepara o ambiente de desenvolvimento do Squad Desktop numa única execução:
//   node scripts/bootstrap.mjs            (ou: npm run bootstrap)
//
// O que faz, sempre dentro da pasta do projeto (nada é instalado no sistema):
//   1. registra o ambiente (Node, Java, Git, Claude Code) em .toolchain/env-report.json
//   2. garante Node compatível com o Angular, JDK 21+ e Maven em .toolchain/ (baixa só o que faltar)
//   3. instala as dependências de frontend/ e desktop/ (npm) e de backend/ (Maven, repositório em .toolchain/m2)
//   4. tenta compilar cada parte e guarda os logs em .toolchain/logs/
//   5. opcional (--sandbox-cache, padrão no Windows): baixa também os binários Linux das dependências,
//      para que um ambiente Linux sem rede consiga compilar e testar o mesmo código
//
// Nenhuma etapa interrompe as demais: o resultado de cada uma fica em .toolchain/status.json.
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {
  ROOT, TOOLCHAIN, LOGS, IS_WIN, PINNED,
  capture, download, ensureDir, exists, extract, latestNodeRelease, mavenCommand, mavenHome,
  nodeArchiveName, nodeSatisfiesAngular, npmCliFor, resolveJdk, resolveNode, run, runNpm,
  runNodeScript, temurinUrl, which,
} from './lib/toolchain.mjs';

const argv = new Set(process.argv.slice(2));
const flag = name => argv.has(`--${name}`);
const SANDBOX_CACHE = flag('sandbox-cache') || (IS_WIN && !flag('no-sandbox-cache'));
const SKIP_BUILD = flag('skip-build');

const status = { startedAt: new Date().toISOString(), finishedAt: null, platform: process.platform, arch: process.arch, steps: [] };
const logFile = name => path.join(LOGS, `${name}.log`);
let stepNo = 0;

async function step(name, title, fn) {
  stepNo += 1;
  const id = `${String(stepNo).padStart(2, '0')}-${name}`;
  const started = Date.now();
  process.stdout.write(`\n[${stepNo}] ${title}\n`);
  const entry = { id, title, ok: false, ms: 0, log: path.relative(ROOT, logFile(id)), detail: null };
  try {
    const result = await fn(logFile(id));
    entry.ok = result === undefined ? true : !!result.ok;
    entry.detail = result && result.detail !== undefined ? result.detail : (result && result.error) || null;
  } catch (err) {
    entry.ok = false;
    entry.detail = String(err && err.stack ? err.stack : err);
    await fsp.appendFile(logFile(id), `\nEXCEÇÃO: ${entry.detail}\n`).catch(() => {});
  }
  entry.ms = Date.now() - started;
  status.steps.push(entry);
  process.stdout.write(`    ${entry.ok ? 'ok' : 'FALHOU'} (${(entry.ms / 1000).toFixed(1)} s)${entry.detail && typeof entry.detail === 'string' ? ` - ${entry.detail.split('\n')[0].slice(0, 160)}` : ''}\n`);
  await writeStatus();
  return entry;
}

async function writeStatus() {
  await ensureDir(TOOLCHAIN);
  await fsp.writeFile(path.join(TOOLCHAIN, 'status.json'), JSON.stringify(status, null, 2));
}

function firstLine(text) {
  return (text || '').split(/\r?\n/).map(s => s.trim()).find(Boolean) || null;
}

/* ---------- 1. relatório do ambiente ---------- */

async function envReport() {
  const report = {
    at: new Date().toISOString(),
    os: { platform: process.platform, release: os.release(), arch: process.arch, cpus: os.cpus().length, memGb: Math.round(os.totalmem() / 2 ** 30) },
    node: { version: process.version, execPath: process.execPath, npmCli: npmCliFor(process.execPath), satisfiesAngular: nodeSatisfiesAngular(process.version) },
    java: resolveJdk(0).found.map(j => ({ version: j.version, home: j.home, isJdk: j.isJdk, hasJlink: j.hasJlink })),
    javaHomeEnv: process.env.JAVA_HOME || null,
    git: null,
    claude: { path: null, version: null, loggedIn: null, authMethod: null, error: null },
  };
  const git = which('git');
  if (git) report.git = firstLine(capture(git, ['--version']).stdout);

  // Claude Code: só detecção e versão. Nada de credenciais é lido ou gravado aqui.
  const claude = which('claude');
  if (claude) {
    report.claude.path = claude;
    const isScript = /\.(cmd|bat)$/i.test(claude);
    const call = args => (isScript
      ? capture(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', `""${claude}" ${args.join(' ')}"`], { timeoutMs: 30000, verbatim: true })
      : capture(claude, args, { timeoutMs: 30000 }));
    const v = call(['--version']);
    report.claude.version = firstLine(v.stdout) || null;
    if (!v.ok) report.claude.error = firstLine(v.stderr) || v.error;
    const auth = call(['auth', 'status']);
    try {
      const parsed = JSON.parse(auth.stdout);
      report.claude.loggedIn = !!parsed.loggedIn;
      report.claude.authMethod = parsed.authMethod || null;
    } catch {
      report.claude.loggedIn = null;
    }
    const help = call(['--help']);
    if (help.stdout) await fsp.writeFile(path.join(TOOLCHAIN, 'claude-help.txt'), help.stdout);
  }
  await fsp.writeFile(path.join(TOOLCHAIN, 'env-report.json'), JSON.stringify(report, null, 2));
  return { ok: true, detail: `node ${report.node.version}, java ${report.java.map(j => j.version).join('/') || 'ausente'}, claude ${report.claude.version || 'ausente'}` };
}

/* ---------- 2. ferramentas ---------- */

async function ensureNode(log) {
  let node = resolveNode();
  if (!node.insufficient) return { ok: true, detail: `${node.version} (${node.source})` };
  const version = await latestNodeRelease(PINNED.nodeMajor);
  const name = nodeArchiveName(version);
  const archive = path.join(TOOLCHAIN, 'downloads', name);
  if (!exists(archive)) await download(`https://nodejs.org/dist/${version}/${name}`, archive, { label: name });
  const ok = await extract(archive, path.join(TOOLCHAIN, 'node'), log);
  node = resolveNode();
  return { ok: ok && !node.insufficient, detail: ok ? `${node.version} baixado para .toolchain/node (o Node do sistema, ${process.version}, não atende ao Angular)` : 'extração do Node falhou' };
}

async function ensureJdk(log) {
  let { chosen, found } = resolveJdk();
  if (chosen) return { ok: true, detail: `${chosen.version} em ${chosen.home}` };
  const ext = IS_WIN ? 'zip' : 'tar.gz';
  const archive = path.join(TOOLCHAIN, 'downloads', `temurin-${PINNED.jdkFeature}.${ext}`);
  if (!exists(archive)) await download(temurinUrl(), archive, { label: `Temurin ${PINNED.jdkFeature}` });
  const ok = await extract(archive, path.join(TOOLCHAIN, 'jdk'), log);
  ({ chosen, found } = resolveJdk());
  if (!chosen) return { ok: false, detail: `nenhum JDK ${PINNED.jdkFeature}+ (encontrados: ${found.map(j => j.version).join(', ') || 'nenhum'}); extração ${ok ? 'ok' : 'falhou'}` };
  return { ok: true, detail: `Temurin ${chosen.version} baixado para .toolchain/jdk` };
}

async function ensureMaven(log) {
  if (mavenHome()) return { ok: true, detail: mavenHome() };
  const name = `apache-maven-${PINNED.maven}-bin.zip`;
  const archive = path.join(TOOLCHAIN, 'downloads', name);
  const urls = [
    `https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${PINNED.maven}/${name}`,
    `https://archive.apache.org/dist/maven/maven-3/${PINNED.maven}/binaries/${name}`,
  ];
  let lastError = null;
  if (!exists(archive)) {
    for (const url of urls) {
      try { await download(url, archive, { label: name }); lastError = null; break; } catch (err) { lastError = err; }
    }
  }
  if (lastError) throw lastError;
  const ok = await extract(archive, path.join(TOOLCHAIN, 'maven'), log);
  return { ok: ok && !!mavenHome(), detail: mavenHome() || 'extração do Maven falhou' };
}

/* ---------- 3 e 4. dependências e primeira compilação ---------- */

async function npmInstall(dir, log) {
  const node = resolveNode();
  const cwd = path.join(ROOT, dir);
  const hasLock = exists(path.join(cwd, 'package-lock.json'));
  const r = await runNpm(node, [hasLock ? 'ci' : 'install'], { cwd, log });
  if (!r.ok && hasLock) return runNpm(node, ['install'], { cwd, log });
  return r;
}

async function mavenStep(args, log) {
  const { chosen: jdk } = resolveJdk();
  const home = mavenHome();
  if (!jdk || !home) return { ok: false, detail: 'JDK ou Maven ausentes' };
  const backend = path.join(ROOT, 'backend');
  const cmd = mavenCommand(jdk, home, backend, args);
  return run(cmd.file, cmd.args, { cwd: backend, env: cmd.env, log });
}

/* ---------- 5. cache Linux para ambientes sem rede ---------- */

async function linuxNodeModules(dir, log) {
  const node = resolveNode();
  const src = path.join(ROOT, dir);
  const dest = await ensureDir(path.join(TOOLCHAIN, 'linux', dir));
  for (const f of ['package.json', 'package-lock.json']) {
    if (!exists(path.join(src, f))) return { ok: false, detail: `${dir}/${f} não existe (o npm install desta parte falhou?)` };
    await fsp.copyFile(path.join(src, f), path.join(dest, f));
  }
  return runNpm(node, ['ci', '--os=linux', '--cpu=x64', '--libc=glibc', '--ignore-scripts'], { cwd: dest, log });
}

async function linuxElectronBinary(log) {
  const node = resolveNode();
  const dest = path.join(TOOLCHAIN, 'linux', 'desktop');
  const installer = path.join(dest, 'node_modules', 'electron', 'install.js');
  if (!exists(installer)) return { ok: false, detail: 'node_modules/electron ausente no cache Linux' };
  return runNodeScript(node, installer, [], { cwd: path.dirname(installer), log, env: { npm_config_platform: 'linux', npm_config_arch: 'x64', ELECTRON_SKIP_BINARY_DOWNLOAD: '' } });
}

async function linuxNodeRuntime() {
  const version = await latestNodeRelease(PINNED.nodeMajor);
  const name = nodeArchiveName(version, 'linux', 'x64');
  const archive = path.join(TOOLCHAIN, 'linux', name);
  if (!exists(archive)) await download(`https://nodejs.org/dist/${version}/${name}`, archive, { label: name });
  return { ok: true, detail: name };
}

async function angularScaffold(log) {
  const node = resolveNode();
  const ng = path.join(ROOT, 'frontend', 'node_modules', '@angular', 'cli', 'bin', 'ng.js');
  if (!exists(ng)) return { ok: false, detail: '@angular/cli não instalado' };
  const base = await ensureDir(path.join(TOOLCHAIN, 'ng-scaffold'));
  if (exists(path.join(base, 'ref-app', 'angular.json'))) return { ok: true, detail: 'já existe' };
  return runNodeScript(node, ng, ['new', 'ref-app', '--defaults', '--skip-install', '--skip-git', '--style=scss', '--ssr=false'], { cwd: base, log, env: { NG_CLI_ANALYTICS: 'false', CI: 'true' } });
}

/* ---------- roteiro ---------- */

await ensureDir(LOGS);
process.stdout.write(`Squad Desktop - bootstrap\nPasta: ${ROOT}\n`);

await step('ambiente', 'Registrando o ambiente', envReport);
await step('node', 'Verificando o Node', ensureNode);
await step('jdk', `Verificando o JDK ${PINNED.jdkFeature}+`, ensureJdk);
await step('maven', `Verificando o Maven ${PINNED.maven}`, ensureMaven);

await step('frontend-npm', 'Instalando dependências do frontend (Angular)', log => npmInstall('frontend', log));
await step('desktop-npm', 'Instalando dependências do desktop (Electron)', log => npmInstall('desktop', log));
await step('backend-deps', 'Baixando dependências do backend (Maven)', log => mavenStep(['dependency:go-offline'], log));

if (!SKIP_BUILD) {
  await step('backend-verify', 'Compilando e testando o backend', log => mavenStep(['verify'], log));
  await step('frontend-build', 'Compilando o frontend', log => runNpm(resolveNode(), ['run', 'build'], { cwd: path.join(ROOT, 'frontend'), log }));
  await step('frontend-test', 'Testando o frontend', log => runNpm(resolveNode(), ['test'], { cwd: path.join(ROOT, 'frontend'), log }));
  await step('desktop-build', 'Compilando o shell Electron', log => runNpm(resolveNode(), ['run', 'build'], { cwd: path.join(ROOT, 'desktop'), log }));
}

if (SANDBOX_CACHE) {
  await step('linux-frontend', 'Cache Linux: dependências do frontend', log => linuxNodeModules('frontend', log));
  await step('linux-desktop', 'Cache Linux: dependências do desktop', log => linuxNodeModules('desktop', log));
  await step('linux-electron', 'Cache Linux: binário do Electron', linuxElectronBinary);
  await step('linux-node', 'Cache Linux: runtime do Node', linuxNodeRuntime);
}
await step('ng-scaffold', 'Gerando projeto Angular de referência (para conferir a configuração)', angularScaffold);

status.finishedAt = new Date().toISOString();
await writeStatus();

const failed = status.steps.filter(s => !s.ok);
process.stdout.write(`\nConcluído: ${status.steps.length - failed.length} de ${status.steps.length} etapas ok.\n`);
if (failed.length) process.stdout.write(`Etapas com falha: ${failed.map(s => s.id).join(', ')}\nLogs em ${path.relative(ROOT, LOGS)}${path.sep}\n`);
process.stdout.write(`Resumo em ${path.join('.toolchain', 'status.json')}\n`);
process.exitCode = 0;
