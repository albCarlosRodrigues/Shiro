import fs from 'node:fs';
import path from 'node:path';
import { validateAppearance, type Appearance } from './ipc-contract';
export function loadAppearance(file: string): Appearance {
  try { return validateAppearance(JSON.parse(fs.readFileSync(file, 'utf8'))); }
  catch { return { theme: 'indigo', bg: '#0e0f11', fg: '#b9b8b3' }; }
}
export function saveAppearance(file: string, value: Appearance): void {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const temp = file + '.tmp';
  fs.writeFileSync(temp, JSON.stringify(validateAppearance(value)), 'utf8');
  fs.renameSync(temp, file);
}
