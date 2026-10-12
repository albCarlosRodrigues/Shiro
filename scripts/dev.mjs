#!/usr/bin/env node
// Desenvolvimento do Squad Desktop.
//   node scripts/dev.mjs             app desktop: compila o que faltar, recompila a interface a cada mudança
//                                    (ng build --watch) e abre o Electron. Ctrl+R na janela recarrega a interface.
//   node scripts/dev.mjs --browser   sem Electron: serviço Java na porta 4310 e `ng serve` em http://localhost:4200
//                                    (o proxy injeta o token de desenvolvimento; os recursos nativos ficam indisponíveis)
// Opções: --rebuild-backend (recompila o jar mesmo sem mudanças), --data <pasta> (dados de desenvolvimento)
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

import { EXE, IS_WIN, LOGS, ROOT, TOOLCHAIN, ensureDir, exists, resolveJdk, resolveNode, runMaven, runNpm } from './lib/toolchain.mjs';

const args = process.argv.slice(2);
const BROWSER = args.includes('--browser');
const REBUILD = args.includes('--rebuild-backend');
const dataArg = args.indexOf('--data');
const DATA = dataArg >= 0 ? path.resolve(args[dataArg + 1]) : path.join(TOOLCHAIN, 'dev-data');

await ensureDir(LOGS);
const node = resolveNode();
if (node.insufficient) {
  console.error(`Node ${process.version} não atende ao Angular 22. Rode "npm run bootstrap" primeiro.`);
  process.exit(1);
}

const jar = path.join(ROOT, 'backend', 'target', 'squad-desktop-backend.jar');
if (REBUILD || !exists(jar) || newestMtime(path.join(ROOT, 'backend', 'src', 'main')) > fs.statSync(jar).mtimeMs) {
  process.stdout.write('• Backend desatualizado: compilando (sem testes)… ');
  const r = await runMaven(['package', '-DskipTests'], { log: path.join(LOGS, 'dev-backend.log') });
  if (!r.ok) fail(r, 'dev-backend.log');
  console.log('ok');
}

const children = [];
const stopAll = () => {
  for (const child of children) {
    if (child.exitCode === null) {
      if (IS_WIN) spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
      else try { process.kill(-child.pid, 'SIGTERM'); } catch { child.kill('SIGTERM'); }
    }
  }
};
process.on('SIGINT', () => { stopAll(); process.exit(0); });
process.on('SIGTERM', () => { stopAll(); process.exit(0); });

function start(label, file, argv, options) {
  const child = spawn(file, argv, { cwd: ROOT, stdio: ['ignore', 'pipe', 'pipe'], detached: !IS_WIN, windowsHide: true, ...options });
  const prefix = `[${label}] `;
  const pipe = stream => stream.on('data', chunk => {
    for (const line of chunk.toString('utf8').split(/\r?\n/)) if (line.trim()) process.stdout.write(prefix + line + '\n');
  });
  pipe(child.stdout);
  pipe(child.stderr);
  child.on('exit', code => {
    console.log(`${prefix}encerrado (código ${code}).`);
    if (label !== 'interface') { stopAll(); process.exit(code ?? 0); }
  });
  children.push(child);
  return child;
}

const nodeEnv = { ...process.env, PATH: `${path.dirname(node.exe)}${path.delimiter}${process.env.PATH ?? ''}` };
const ngCli = path.join(ROOT, 'frontend', 'node_modules', '@angular', 'cli', 'bin', 'ng.js');
if (!exists(ngCli)) fail({ error: 'dependências da interface ausentes; rode "npm run bootstrap"' });

if (BROWSER) {
  const { chosen: jdk } = resolveJdk();
  const runtimeJava = path.join(TOOLCHAIN, 'runtime', 'bin', `java${EXE}`);
  const java = jdk?.exe ?? (exists(runtimeJava) ? runtimeJava : null);
  if (!java) fail({ error: 'nenhum Java 21+ encontrado; rode "npm run bootstrap"' });
  const token = randomBytes(16).toString('hex');
  fs.mkdirSync(DATA, { recursive: true });
  start('serviço', java, ['-Dfile.encoding=UTF-8', '--enable-native-access=ALL-UNNAMED', '-jar', jar], {
    env: {
      ...process.env, SQUAD_AUTH_TOKEN: token, SQUAD_DATA_DIR: DATA, SQUAD_PORT: '4310',
      SQUAD_ALLOWED_ORIGINS: 'http://localhost:4200,http://127.0.0.1:4200', JAVA_TOOL_OPTIONS: '',
      ...(IS_WIN ? {} : { LC_ALL: 'C.UTF-8', LANG: 'C.UTF-8' }),
    },
  });
  start('interface', node.exe, [ngCli, 'serve', '--port', '4200'], {
    cwd: path.join(ROOT, 'frontend'), env: { ...nodeEnv, SQUAD_AUTH_TOKEN: token, SQUAD_PORT: '4310' },
  });
  console.log(`\nDados de desenvolvimento em ${DATA}\nAbra http://localhost:4200 quando a interface terminar de compilar. Ctrl+C encerra tudo.\n`);
} else {
  process.stdout.write('• Shell desktop (TypeScript)… ');
  const d = await runNpm(node, ['run', 'build'], { cwd: path.join(ROOT, 'desktop'), log: path.join(LOGS, 'dev-desktop.log') });
  if (!d.ok) fail(d, 'dev-desktop.log');
  console.log('ok');
  process.stdout.write('• Interface (primeira compilação)… ');
  const f = await runNpm(node, ['run', 'build', '--', '--configuration', 'development'], { cwd: path.join(ROOT, 'frontend'), log: path.join(LOGS, 'dev-frontend.log') });
  if (!f.ok) fail(f, 'dev-frontend.log');
  console.log('ok');
  start('interface', node.exe, [ngCli, 'build', '--watch', '--configuration', 'development'], { cwd: path.join(ROOT, 'frontend'), env: nodeEnv });
  const electron = path.join(ROOT, 'desktop', 'node_modules', 'electron', 'cli.js');
  if (!exists(electron)) fail({ error: 'Electron não instalado em desktop/node_modules; rode "npm run bootstrap"' });
  start('desktop', node.exe, [electron, '.'], { cwd: path.join(ROOT, 'desktop'), env: { ...nodeEnv, SQUAD_USER_DATA: path.join(TOOLCHAIN, 'dev-userdata'), SQUAD_DEV: '1' } });
  console.log('\nApp em modo de desenvolvimento (dados separados em .toolchain/dev-userdata). Feche a janela ou use Ctrl+C para encerrar.\n');
}

function newestMtime(dir) {
  let newest = 0;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    newest = Math.max(newest, entry.isDirectory() ? newestMtime(p) : fs.statSync(p).mtimeMs);
  }
  return newest;
}

function fail(result, log) {
  console.error(`\nFALHOU: ${result.error ?? 'veja o log'}${log ? `\n  log: ${path.relative(ROOT, path.join(LOGS, log))}` : ''}`);
  process.exit(1);
}
