import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';

import { javaArgs, parseReady, resolveLayout, utf8Locale } from '../backend';
import {
  IpcValidationError, isTrustedSender, validateOpenText, validatePickFolder, validateSaveText, validateWindowAction,
} from '../ipc-contract';
import { LogFile, redact } from '../log-file';
import { killTreeCommand } from '../process-tree';
import { loadWindowState } from '../window-state';

describe('contrato de IPC', () => {
  it('aceita apenas a origem do serviço local', () => {
    assert.equal(isTrustedSender('http://127.0.0.1:4310/#/projeto/1', 'http://127.0.0.1:4310'), true);
    assert.equal(isTrustedSender('http://127.0.0.1:9999/', 'http://127.0.0.1:4310'), false);
    assert.equal(isTrustedSender('https://exemplo.com/', 'http://127.0.0.1:4310'), false);
    assert.equal(isTrustedSender('data:text/html,oi', 'http://127.0.0.1:4310'), false);
    assert.equal(isTrustedSender('http://127.0.0.1:4310/', null), false, 'sem serviço iniciado, nada é confiável');
    assert.equal(isTrustedSender(undefined, 'http://127.0.0.1:4310'), false);
  });

  it('valida nomes de arquivo sugeridos e extensões', () => {
    const ok = validateSaveText({ defaultName: 'agente.md', content: '# Agente', extensions: ['md'] });
    assert.equal(ok.defaultName, 'agente.md');
    for (const name of ['../segredo.txt', 'C:\\Windows\\x.md', 'a/b.md', 'con:fig', '..']) {
      assert.throws(() => validateSaveText({ defaultName: name, content: '', extensions: ['md'] }), IpcValidationError, name);
    }
    assert.throws(() => validateSaveText({ defaultName: 'x.md', content: 1, extensions: ['md'] }), IpcValidationError);
    assert.throws(() => validateOpenText({ extensions: ['js;rm -rf'] }), IpcValidationError);
    assert.throws(() => validateOpenText({ extensions: [] }), IpcValidationError);
    assert.deepEqual(validateOpenText({ extensions: ['JSON'] }).extensions, ['json']);
  });

  it('recusa entradas inesperadas', () => {
    assert.deepEqual(validatePickFolder(undefined), {});
    assert.throws(() => validatePickFolder('C:\\'), IpcValidationError);
    assert.throws(() => validatePickFolder({ defaultPath: 'a\0b' }), IpcValidationError);
    assert.equal(validateWindowAction('minimize'), 'minimize');
    assert.throws(() => validateWindowAction('devtools'), IpcValidationError);
  });
});

describe('serviço Java', () => {
  it('usa os recursos do pacote quando instalado', () => {
    const layout = resolveLayout({ packaged: true, resourcesPath: 'C:\\Program Files\\Squad Desktop\\resources', appPath: '', platform: 'win32', env: {} });
    assert.equal(layout.source, 'empacotado');
    assert.match(layout.java, /runtime[\\/]bin[\\/]java\.exe$/);
    assert.match(layout.jar, /backend[\\/]squad-desktop-backend\.jar$/);
  });

  it('passa caminhos com espaços e acentos como argumento único', () => {
    const jar = 'C:\\Usuários\\João da Silva\\Squad Desktop\\backend\\squad-desktop-backend.jar';
    const args = javaArgs({ java: 'java', jar, staticDir: '', source: 'empacotado' });
    assert.equal(args[args.length - 1], jar);
    assert.equal(args[args.length - 2], '-jar');
    assert.ok(args.every(a => !a.includes('"')), 'nenhum argumento depende de aspas de shell');
  });

  it('lê o arquivo de prontidão', () => {
    assert.deepEqual(parseReady('{"port":4310,"pid":12,"version":"0.1.0","dataDir":"/d"}'), { port: 4310, pid: 12, version: '0.1.0', dataDir: '/d' });
    assert.equal(parseReady('{"port":0}'), null);
    assert.equal(parseReady('{"port":70000}'), null);
    assert.equal(parseReady('incompleto'), null);
  });

  it('força UTF-8 fora do Windows quando a localidade não é UTF-8', () => {
    assert.deepEqual(utf8Locale('linux', { LANG: 'C' }), { LC_ALL: 'C.UTF-8', LANG: 'C.UTF-8' });
    assert.deepEqual(utf8Locale('linux', { LANG: 'pt_BR.UTF-8' }), {});
    assert.deepEqual(utf8Locale('win32', {}), {});
  });

  it('encerra a árvore de processos no Windows com taskkill', () => {
    const command = killTreeCommand(4321, 'win32');
    assert.ok(command);
    assert.match(command.file, /taskkill\.exe$/);
    assert.deepEqual(command.args, ['/pid', '4321', '/T', '/F']);
    assert.equal(killTreeCommand(-1, 'win32'), null);
    assert.equal(killTreeCommand(4321, 'linux'), null, 'fora do Windows o sinal vai para o grupo do processo');
  });
});

describe('log e janela', () => {
  it('nunca grava o token', () => {
    const token = 'a'.repeat(64);
    assert.equal(redact(`Authorization: Bearer ${token}`, token), 'Authorization: Bearer <token>');
  });

  it('devolve as últimas linhas do log', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sd-log-'));
    const log = new LogFile(path.join(dir, 'Serviço com espaço', 'backend.log'));
    log.open();
    for (let i = 0; i < 2000; i++) log.write(`linha ${i}\n`);
    log.close();
    return new Promise<void>(resolve => setTimeout(() => {
      const tail = log.tail(200);
      assert.ok(tail.endsWith('linha 1999\n'));
      assert.ok(!tail.includes('linha 10\n'));
      fs.rmSync(dir, { recursive: true, force: true });
      resolve();
    }, 50));
  });

  it('descarta posição de janela fora das telas atuais', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sd-win-'));
    const file = path.join(dir, 'window-state.json');
    fs.writeFileSync(file, JSON.stringify({ x: 5000, y: 100, width: 1200, height: 800, maximized: true }));
    const state = loadWindowState(file, [{ x: 0, y: 0, width: 1920, height: 1080 }]);
    assert.equal(state.x, undefined);
    assert.equal(state.maximized, true);
    fs.writeFileSync(file, JSON.stringify({ x: 100, y: 100, width: 10, height: 10 }));
    const small = loadWindowState(file, [{ x: 0, y: 0, width: 1920, height: 1080 }]);
    assert.equal(small.x, 100);
    assert.equal(small.width, 960, 'tamanho mínimo respeitado');
    assert.deepEqual(loadWindowState(path.join(dir, 'nao-existe.json'), []), { width: 1366, height: 820, maximized: false });
    fs.rmSync(dir, { recursive: true, force: true });
  });
});
