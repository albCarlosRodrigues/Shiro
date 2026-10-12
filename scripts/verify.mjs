#!/usr/bin/env node
// Verificação completa: testes do backend (JUnit), da interface (Vitest), compilação de produção da interface,
// testes do shell desktop e, com --e2e, o teste ponta a ponta do aplicativo empacotado em modo de desenvolvimento
// (Playwright + Electron; precisa de "npm run build" antes).
//   node scripts/verify.mjs [--e2e] [--offline]
import path from 'node:path';

import { LOGS, ROOT, ensureDir, resolveNode, runMaven, runNpm, runNodeScript } from './lib/toolchain.mjs';

const argv = new Set(process.argv.slice(2));
await ensureDir(LOGS);
const node = resolveNode();
if (node.insufficient) {
  console.error(`Node ${process.version} não atende ao Angular 22. Rode "npm run bootstrap" primeiro.`);
  process.exit(1);
}

const results = [];
async function check(title, fn, log) {
  const t = Date.now();
  process.stdout.write(`• ${title}… `);
  const r = await fn();
  const secs = ((Date.now() - t) / 1000).toFixed(1);
  console.log(r.ok ? `ok (${secs} s)` : `FALHOU (${secs} s) — log: ${path.relative(ROOT, path.join(LOGS, log))}`);
  results.push({ title, ok: r.ok });
}

await check('Backend: testes (Maven verify)', () => runMaven(['verify'], { log: path.join(LOGS, 'verify-backend.log'), offline: argv.has('--offline') }), 'verify-backend.log');
await check('Interface: testes (Vitest)', () => runNpm(node, ['test'], { cwd: path.join(ROOT, 'frontend'), log: path.join(LOGS, 'verify-frontend-test.log') }), 'verify-frontend-test.log');
await check('Interface: build de produção', () => runNpm(node, ['run', 'build'], { cwd: path.join(ROOT, 'frontend'), log: path.join(LOGS, 'verify-frontend-build.log') }), 'verify-frontend-build.log');
await check('Shell desktop: compilação', () => runNpm(node, ['run', 'build'], { cwd: path.join(ROOT, 'desktop'), log: path.join(LOGS, 'verify-desktop-build.log') }), 'verify-desktop-build.log');
await check('Shell desktop: testes', () => runNpm(node, ['test'], { cwd: path.join(ROOT, 'desktop'), log: path.join(LOGS, 'verify-desktop-test.log') }), 'verify-desktop-test.log');
if (argv.has('--e2e')) {
  await check('Ponta a ponta (Electron + serviço Java)', () => runNodeScript(node, path.join(ROOT, 'desktop', 'test', 'e2e.mjs'), [],
    { cwd: path.join(ROOT, 'desktop'), log: path.join(LOGS, 'verify-e2e.log') }), 'verify-e2e.log');
}

const failed = results.filter(r => !r.ok);
console.log(failed.length ? `\n${failed.length} de ${results.length} etapas falharam.` : `\nTudo certo: ${results.length} etapas.`);
process.exit(failed.length ? 1 : 0);
