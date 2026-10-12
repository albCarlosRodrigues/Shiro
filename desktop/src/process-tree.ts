import { execFile, type ChildProcess } from 'node:child_process';

/**
 * Comando que encerra um processo e todos os filhos dele. No Windows, `taskkill /T` percorre a árvore;
 * nos demais sistemas o serviço é iniciado num grupo próprio e o sinal vai para o grupo inteiro.
 */
export function killTreeCommand(pid: number, platform: NodeJS.Platform): { file: string; args: string[] } | null {
  if (!Number.isInteger(pid) || pid <= 0) return null;
  if (platform === 'win32') {
    const systemRoot = process.env['SystemRoot'] ?? 'C:\\Windows';
    return { file: `${systemRoot}\\System32\\taskkill.exe`, args: ['/pid', String(pid), '/T', '/F'] };
  }
  return null;
}

/** Encerra o processo e seus filhos. Não lança: o processo pode já ter terminado. */
export function killTree(child: ChildProcess, platform: NodeJS.Platform = process.platform): Promise<void> {
  return new Promise(resolve => {
    const pid = child.pid;
    if (!pid || child.exitCode !== null) {
      resolve();
      return;
    }
    const command = killTreeCommand(pid, platform);
    if (command) {
      execFile(command.file, command.args, { windowsHide: true }, () => resolve());
      return;
    }
    try {
      process.kill(-pid, 'SIGKILL');
    } catch {
      try {
        child.kill('SIGKILL');
      } catch {
        // já encerrado
      }
    }
    resolve();
  });
}
