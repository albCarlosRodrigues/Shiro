import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { validateAppearance } from '../ipc-contract';
import { loadAppearance, saveAppearance } from '../appearance';
test('aparência persiste em arquivo e pode ser restaurada na próxima abertura', () => {
 const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'shiro-appearance-'));
 const file = path.join(dir, 'appearance.json');
 try { const theme = {theme:'min-light',bg:'#ffffff',fg:'#34373c'}; saveAppearance(file,theme); assert.deepEqual(loadAppearance(file),theme); }
 finally { fs.rmSync(dir, {recursive:true,force:true}); }
});
test('aparência inválida ou ausente usa a paleta padrão', () => {
 assert.equal(loadAppearance('missing-shiro-appearance.json').theme,'indigo');
 for(const value of [null,{}, {theme:'../x',bg:'#ffffff',fg:'#000000'}, {theme:'claro',bg:'url(x)',fg:'#000000'}, {theme:'claro',bg:'#ffffff',fg:'red'}]) assert.throws(() => validateAppearance(value));
});
