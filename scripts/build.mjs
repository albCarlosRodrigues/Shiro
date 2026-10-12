#!/usr/bin/env node
// Build completo do Squad Desktop.
//   node scripts/build.mjs            compila backend (jar), frontend (Angular) e shell (Electron) e gera o runtime Java
//   node scripts/build.mjs --dist     faz o mesmo e empacota o instalador (Windows: NSIS .exe; Linux: AppImage)
// Opções: --skip-tests (pula os testes do backend), --offline (Maven sem rede), --fresh-runtime (refaz o jlink)
import fs from 'node:fs';
import path from 'node:path';

import { EXE, IS_WIN, LOGS, ROOT, TOOLCHAIN, ensureDir, exists, resolveJdk, resolveNode, run, runMaven, runNodeScript, runNpm } from './lib/toolchain.mjs';

const argv = new Set(process.argv.slice(2));
const DIST = argv.has('--dist');
const SKIP_TESTS = argv.has('--skip-tests');
const OFFLINE = argv.has('--offline');
const FRESH_RUNTIME = argv.has('--fresh-runtime');

/** Módulos do JDK usados pelo serviço (levantados com jdeps sobre o jar) mais locale pt-BR e criptografia. */
export const RUNTIME_MODULES = [
  'java.base', 'java.compiler', 'java.desktop', 'java.instrument', 'java.logging', 'java.management', 'java.naming',
  'java.net.http', 'java.prefs', 'java.scripting', 'java.security.jgss', 'java.sql', 'java.xml', 'jdk.crypto.ec',
  'jdk.jfr', 'jdk.localedata', 'jdk.unsupported', 'jdk.zipfs',
];

const RUNTIME = path.join(TOOLCHAIN, 'runtime');
const started = Date.now();
await ensureDir(LOGS);

async function step(title, fn) {
  const t = Date.now();
  process.stdout.write(`• ${title}… `);
  const result = await fn();
  if (!result.ok) {
    process.stdout.write(`FALHOU\n  ${result.error ?? 'veja o log'}${result.log ? `\n  log: ${path.relative(ROOT, result.log)}` : ''}\n`);
    process.exit(1);
  }
  process.stdout.write(`ok (${((Date.now() - t) / 1000).toFixed(1)} s)${result.detail ? ' - ' + result.detail : ''}\n`);
  return result;
}

const node = resolveNode();
if (node.insufficient) {
  console.error(`Node ${process.version} não atende ao Angular 22 (^22.22.3 || ^24.15 || >=26). Rode "npm run bootstrap" primeiro.`);
  process.exit(1);
}

await step('Backend (Maven)', () => runMaven(['clean', SKIP_TESTS ? 'package' : 'verify', ...(SKIP_TESTS ? ['-DskipTests'] : [])],
  { log: path.join(LOGS, 'build-backend.log'), offline: OFFLINE }));

await step('Frontend (Angular)', () => runNpm(node, ['run', 'build'], { cwd: path.join(ROOT, 'frontend'), log: path.join(LOGS, 'build-frontend.log') }));

await step('Shell desktop (TypeScript)', () => runNpm(node, ['run', 'build'], { cwd: path.join(ROOT, 'desktop'), log: path.join(LOGS, 'build-desktop.log') }));

await step('Runtime Java (jlink)', async () => {
  const { chosen: jdk } = resolveJdk();
  if (!jdk) return { ok: false, error: 'nenhum JDK 21+ encontrado; rode "npm run bootstrap"' };
  if (!jdk.hasJlink) return { ok: false, error: `o JDK em ${jdk.home} não tem jlink` };
  const marker = path.join(RUNTIME, 'squad-runtime.json');
  const wanted = JSON.stringify({ jdk: jdk.version, modules: RUNTIME_MODULES, platform: process.platform, arch: process.arch });
  if (!FRESH_RUNTIME && exists(marker) && fs.readFileSync(marker, 'utf8') === wanted) return { ok: true, detail: 'reaproveitado' };
  fs.rmSync(RUNTIME, { recursive: true, force: true });
  const jlink = path.join(jdk.home, 'bin', `jlink${EXE}`);
  const result = await run(jlink, [
    '--add-modules', RUNTIME_MODULES.join(','),
    '--include-locales', 'en,pt',
    '--strip-debug', '--no-man-pages', '--no-header-files', '--compress', 'zip-6',
    '--output', RUNTIME,
  ], { log: path.join(LOGS, 'build-runtime.log') });
  if (result.ok) fs.writeFileSync(marker, wanted);
  return { ...result, detail: `Java ${jdk.version}` };
});

await step('Verificação do runtime', async () => {
  const java = path.join(RUNTIME, 'bin', `java${EXE}`);
  const jar = path.join(ROOT, 'backend', 'target', 'squad-desktop-backend.jar');
  // sobe o serviço com o runtime reduzido e confirma que os módulos bastam (o serviço anuncia a porta e encerra)
  let dataDir = path.join(TOOLCHAIN, 'smoke-data');
  const readyFile = path.join(TOOLCHAIN, 'smoke-ready.json');
  try {
    fs.rmSync(dataDir, { recursive: true, force: true, maxRetries: 10, retryDelay: 300 });
    fs.rmSync(readyFile, { force: true });
  } catch {
    // sobra de um build anterior ainda bloqueada: usa uma pasta nova
    dataDir = path.join(TOOLCHAIN, `smoke-data-${Date.now()}`);
  }
  const { spawn } = await import('node:child_process');
  const child = spawn(java, ['-jar', jar], {
    windowsHide: true,
    env: { ...process.env, JAVA_TOOL_OPTIONS: '', SQUAD_AUTH_TOKEN: 'smoke-' + Date.now(), SQUAD_DATA_DIR: dataDir, SQUAD_READY_FILE: readyFile, SQUAD_PORT: '0' },
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  let stderr = '';
  child.stderr.on('data', d => (stderr += d));
  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline && !exists(readyFile) && child.exitCode === null) await new Promise(r => setTimeout(r, 200));
  const ok = exists(readyFile);
  // espera o processo terminar de verdade: no Windows, o banco continua bloqueado até o Java sair
  const exitedNow = child.exitCode !== null ? Promise.resolve() : new Promise(r => child.once('exit', r));
  child.kill();
  await Promise.race([exitedNow, new Promise(r => setTimeout(r, 10_000))]);
  // limpeza é acessória: se algo ainda estiver bloqueado (antivírus, indexador), não derruba o build
  for (const target of [dataDir, readyFile]) {
    try {
      fs.rmSync(target, { recursive: true, force: true, maxRetries: 10, retryDelay: 300 });
    } catch (error) {
      console.warn(`\n  aviso: não foi possível apagar ${path.relative(ROOT, target)} (${error.code ?? error.message}); pode ser removido depois.`);
    }
  }
  return ok ? { ok, detail: 'serviço iniciou com o runtime reduzido' } : { ok, error: 'o serviço não iniciou com o runtime reduzido:\n' + stderr.slice(-2000) };
});

if (DIST) {
  await step('Instalador (electron-builder)', () => {
    const cli = path.join(ROOT, 'desktop', 'node_modules', 'electron-builder', 'cli.js');
    const target = IS_WIN ? ['--win', 'nsis'] : process.platform === 'darwin' ? ['--mac', 'dmg'] : ['--linux', 'AppImage'];
    return runNodeScript(node, cli, [...target, '--config', 'electron-builder.json', '--publish', 'never'],
      { cwd: path.join(ROOT, 'desktop'), log: path.join(LOGS, 'build-dist.log'), env: { CSC_IDENTITY_AUTO_DISCOVERY: 'false' } });
  });
  const release = path.join(ROOT, 'desktop', 'release');
  const artifacts = exists(release) ? fs.readdirSync(release).filter(f => /\.(exe|AppImage|dmg)$/.test(f)) : [];
  console.log(`\nInstalador: ${artifacts.map(f => path.join('desktop', 'release', f)).join(', ') || '(não encontrado)'}`);
}

console.log(`\nBuild concluído em ${((Date.now() - started) / 1000).toFixed(0)} s.`);
