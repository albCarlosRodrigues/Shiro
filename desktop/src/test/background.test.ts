import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';

import { DEFAULT_BACKGROUND, closeAction, loadBackground, saveBackground, validateBackgroundSetting } from '../background';
import { IpcValidationError } from '../ipc-contract';

describe('modo em segundo plano', () => {
  it('vem ligado por padrão e sem o aviso mostrado', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'shiro-bg-'));
    assert.deepEqual(loadBackground(path.join(dir, 'nao-existe.json')), DEFAULT_BACKGROUND);
    assert.equal(DEFAULT_BACKGROUND.keepRunningInTray, true);
  });

  it('grava e lê a preferência e ignora arquivo corrompido', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'shiro-bg-'));
    const file = path.join(dir, 'sub', 'background.json');
    saveBackground(file, { keepRunningInTray: false, trayNoticeShown: true });
    assert.deepEqual(loadBackground(file), { keepRunningInTray: false, trayNoticeShown: true });
    fs.writeFileSync(file, '{quebrado', 'utf8');
    assert.deepEqual(loadBackground(file), DEFAULT_BACKGROUND);
  });

  it('aceita só o liga/desliga vindo da interface', () => {
    assert.deepEqual(validateBackgroundSetting({ keepRunningInTray: false }), { keepRunningInTray: false });
    for (const bad of [undefined, null, 'sim', { keepRunningInTray: 'true' }, {}]) {
      assert.throws(() => validateBackgroundSetting(bad), IpcValidationError, JSON.stringify(bad));
    }
  });

  it('decide o que o X faz', () => {
    const base = { quitting: false, keepRunningInTray: true, trayAvailable: true, backendReady: true };
    assert.equal(closeAction(base), 'hide');
    assert.equal(closeAction({ ...base, quitting: true }), 'allow');
    assert.equal(closeAction({ ...base, keepRunningInTray: false }), 'quit');
    assert.equal(closeAction({ ...base, trayAvailable: false }), 'quit', 'sem bandeja, esconder deixaria o app inacessível');
    assert.equal(closeAction({ ...base, backendReady: false }), 'quit', 'sem serviço no ar, o X sai');
  });
});
