#!/usr/bin/env node
// Teste ponta a ponta do aplicativo desktop (Electron + serviço Java + interface Angular), sem Claude Code:
// usa o modo demonstração. Exige build prévio (node scripts/build.mjs) e o pacote "playwright-core"
// (dependência de desenvolvimento de desktop/; "playwright" completo também serve).
//
//   node desktop/test/e2e.mjs [--no-sandbox] [--keep]
//
// Cobre: serviço iniciado pelo app em loopback com token fora da URL; ponte do preload; aviso de trabalho ativo
// ao fechar (com opção de voltar); encerramento do serviço e dos processos; reabertura com a execução marcada
// como interrompida, sem reinício automático; pasta de dados com espaços e acentos.
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const desktopDir = path.resolve(here, '..');
const require = createRequire(import.meta.url);
let playwright;
for (const base of [desktopDir, '/opt/node22/lib/node_modules/', '/usr/local/lib/node_modules/']) {
  try {
    const req = createRequire(path.join(base, 'x.js'));
    for (const name of ['playwright-core', 'playwright']) {
      try {
        playwright = req(name);
        break;
      } catch {
        // tenta o próximo pacote
      }
    }
    if (playwright) break;
  } catch {
    // tenta o próximo local
  }
}
if (!playwright) {
  console.error('Pacote "playwright-core" não encontrado. Rode "npm install" na pasta desktop (ou "npm run bootstrap").');
  process.exit(2);
}
const { _electron: electron } = playwright;
const electronBin = require('electron');

const argv = new Set(process.argv.slice(2));
const extraArgs = argv.has('--no-sandbox') ? ['--no-sandbox'] : [];
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'squad-e2e-'));
const userData = path.join(root, 'Dados do Usuário Ação');
const shots = path.join(root, 'telas');
fs.mkdirSync(shots, { recursive: true });

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'ok  ' : 'FALHA'} ${name}${detail ? ' - ' + detail : ''}`);
}

async function launch() {
  const app = await electron.launch({
    executablePath: electronBin,
    args: [...extraArgs, desktopDir],
    env: { ...process.env, SQUAD_USER_DATA: userData, SQUAD_DEMO_STEP_MS: '350', ELECTRON_ENABLE_LOGGING: '0' },
    timeout: 60_000,
  });
  const window = await app.firstWindow();
  await window.waitForURL(/^http:\/\/127\.0\.0\.1:\d+\//, { timeout: 90_000 });
  await window.waitForSelector('sd-shell', { timeout: 30_000 });
  return { app, window };
}

/** Chama a API local a partir da página (cookie HttpOnly + cabeçalho do aplicativo). */
function api(window, method, url, body) {
  return window.evaluate(async ([m, u, b]) => {
    const res = await fetch(u, { method: m, headers: { 'Content-Type': 'application/json', 'X-Squad-Client': 'e2e' }, body: b ? JSON.stringify(b) : undefined });
    const text = await res.text();
    return { status: res.status, body: text ? JSON.parse(text) : null };
  }, [method, url, body]);
}

async function javaPid(window) {
  const info = await api(window, 'GET', '/api/system/info');
  return info.status === 200 ? info.body : null;
}

function alive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function exited(pid, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (!alive(pid)) return true;
    await new Promise(r => setTimeout(r, 200));
  }
  return false;
}

let app;
try {
  /* ---------- primeira abertura ---------- */
  let started = Date.now();
  ({ app, window: globalThis.win } = await launch());
  const window = globalThis.win;
  check('app abre e carrega a interface servida pelo serviço local', true, `${((Date.now() - started) / 1000).toFixed(1)} s`);
  const url = window.url();
  check('token não aparece na URL', !/token|[0-9a-f]{64}/i.test(url), url);
  check('cookie de sessão é HttpOnly (invisível para a página)', (await window.evaluate(() => document.cookie)) === '');
  const bridge = await window.evaluate(() => ({ type: typeof window.squadDesktop, keys: Object.keys(window.squadDesktop ?? {}).sort(), node: typeof require }));
  check('preload expõe só a API do Squad Desktop', bridge.type === 'object' && bridge.node === 'undefined', bridge.keys.join(','));
  const outsider = await window.evaluate(async () => {
    try {
      const r = await fetch('/api/projects', { headers: { 'X-Squad-Client': 'x' }, credentials: 'omit' });
      return r.status;
    } catch (e) {
      return String(e);
    }
  });
  check('API recusa chamada sem credencial', outsider === 401, String(outsider));

  await window.screenshot({ path: path.join(shots, '01-inicio.png') });

  /* ---------- preparar um projeto e uma execução de demonstração ---------- */
  await api(window, 'POST', '/api/system/starter-agents');
  const settings = (await api(window, 'GET', '/api/settings')).body;
  await api(window, 'PUT', '/api/settings', { ...settings, demoMode: true });
  const sample = (await api(window, 'POST', '/api/system/sample-project')).body;
  const projectId = sample.project.id;
  const agents = (await api(window, 'GET', '/api/agents')).body;
  const session = (await api(window, 'POST', '/api/sessions', { projectId, agentId: agents[0].id, title: 'Teste e2e' })).body;
  const submission = await api(window, 'POST', `/api/sessions/${session.id}/messages`, { content: 'Explique o projeto em detalhes.', mode: 'CHAT' });
  check('mensagem aceita e execução enfileirada', submission.status === 202, submission.body?.run?.status);
  const runId = submission.body.run.id;
  // os dados foram criados pela API: recarrega a interface, como a pessoa faria ao reabrir
  await window.evaluate(p => { location.hash = `#/projeto/${p}`; location.reload(); }, projectId);
  await window.waitForSelector('sd-pane', { timeout: 20_000 });
  await window.waitForTimeout(1500);
  await window.screenshot({ path: path.join(shots, '02-execucao.png') });

  const system = await javaPid(window);
  check('serviço informa a pasta de dados (com espaços e acentos)', system?.dataDir?.includes('Dados do Usuário Ação'), system?.dataDir);

  /* ---------- fechar com trabalho ativo: voltar ---------- */
  const dialogs = [];
  await app.evaluate(({ dialog }) => {
    globalThis.__dialogCalls = [];
    dialog.showMessageBox = async (_win, options) => {
      globalThis.__dialogCalls.push(options.message);
      return { response: globalThis.__dialogAnswer ?? 1 };
    };
  });
  await app.evaluate(() => { globalThis.__dialogAnswer = 1; });
  await window.evaluate(() => window.squadDesktop.windowControl('close'));
  await window.waitForTimeout(1500);
  dialogs.push(...(await app.evaluate(() => globalThis.__dialogCalls)));
  const stillOpen = await window.evaluate(() => document.querySelector('sd-shell') !== null).catch(() => false);
  check('fechar com execução ativa pergunta antes', dialogs.length === 1, dialogs[0] ?? '');
  check('escolher "Voltar" mantém o app e a execução', stillOpen);

  /* ---------- fechar com trabalho ativo: sair ---------- */
  const runBefore = (await api(window, 'GET', `/api/runs/${runId}`)).body;
  check('execução ainda em andamento antes de sair', ['QUEUED', 'STARTING', 'RUNNING'].includes(runBefore.status), runBefore.status);
  // pid do serviço Java filho deste app, pela tabela de processos do sistema
  const appPid = await app.evaluate(() => process.pid);
  const javaPids = javaChildren(appPid);
  const canListProcesses = javaPids !== null;
  if (canListProcesses) check('serviço Java em execução', javaPids.length === 1, String(javaPids));
  await app.evaluate(() => { globalThis.__dialogAnswer = 0; });
  started = Date.now();
  await window.evaluate(() => window.squadDesktop.windowControl('close')).catch(() => undefined);
  check('app encerra depois de confirmar', await exited(appPid, 30_000), `${((Date.now() - started) / 1000).toFixed(1)} s`);
  await new Promise(r => setTimeout(r, 500));
  if (canListProcesses) check('serviço Java encerrado junto', javaPids.every(pid => !alive(pid)));

  /* ---------- reabrir: histórico preservado, execução interrompida sem reinício ---------- */
  ({ app, window: globalThis.win } = await launch());
  const again = globalThis.win;
  const run = (await api(again, 'GET', `/api/runs/${runId}`)).body;
  check('execução aparece como interrompida', run.status === 'INTERRUPTED', run.status);
  const active = (await api(again, 'GET', '/api/runs/active')).body;
  check('nada foi reiniciado automaticamente', Array.isArray(active) && active.length === 0, String(active?.length));
  const messages = (await api(again, 'GET', `/api/sessions/${session.id}/messages`)).body;
  check('mensagens da sessão foram preservadas', messages.some(m => m.content === 'Explique o projeto em detalhes.'));
  const layout = (await api(again, 'GET', '/api/ui-state/workspace')).body;
  check('abas e painéis foram restaurados', layout?.tabs?.includes(projectId), JSON.stringify(layout?.tabs));
  await again.evaluate(p => { location.hash = `#/projeto/${p}`; }, projectId);
  await again.waitForSelector('sd-pane', { timeout: 15_000 });
  await again.waitForTimeout(800);
  await again.screenshot({ path: path.join(shots, '03-reaberto.png') });
  await app.evaluate(({ dialog }) => {
    globalThis.__asked = 0;
    dialog.showMessageBox = async () => { globalThis.__asked++; return { response: 0 }; };
  });
  const pid2 = await app.evaluate(() => process.pid);
  await again.evaluate(() => window.squadDesktop.windowControl('close')).catch(() => undefined);
  check('sem trabalho ativo, fecha direto e sem pergunta', await exited(pid2, 30_000));
} catch (error) {
  check('execução do teste', false, error instanceof Error ? error.stack : String(error));
  try {
    await app?.close();
  } catch {
    // já fechado
  }
}

const failed = results.filter(r => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} verificações ok. Telas e dados em ${root}`);
if (!argv.has('--keep') && failed.length === 0) fs.rmSync(root, { recursive: true, force: true });
process.exit(failed.length ? 1 : 0);

/** Processos java filhos do app (Linux: /proc; Windows: CIM). null quando não é possível listar. */
function javaChildren(parentPid) {
  if (process.platform === 'win32') {
    try {
      const out = execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
        `Get-CimInstance Win32_Process -Filter "ParentProcessId=${parentPid} and Name='java.exe'" | ForEach-Object { $_.ProcessId }`],
      { encoding: 'utf8', windowsHide: true, timeout: 20_000 });
      return out.split(/\r?\n/).map(l => l.trim()).filter(Boolean).map(Number);
    } catch {
      return null;
    }
  }
  if (!fs.existsSync('/proc')) return null;
  return fs.readdirSync('/proc').filter(n => /^\d+$/.test(n)).filter(n => {
    try {
      const cmd = fs.readFileSync(`/proc/${n}/cmdline`, 'utf8');
      const ppid = Number(fs.readFileSync(`/proc/${n}/stat`, 'utf8').split(') ')[1].split(' ')[1]);
      return cmd.includes('squad-desktop-backend.jar') && ppid === parentPid;
    } catch {
      return false;
    }
  }).map(Number);
}
