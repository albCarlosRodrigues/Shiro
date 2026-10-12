import fs from 'node:fs';
import path from 'node:path';

/** Arquivo de log com rotação simples por tamanho. O token de autenticação nunca é escrito aqui. */
export class LogFile {
  private stream: fs.WriteStream | null = null;

  constructor(readonly file: string, private readonly maxBytes = 5 * 1024 * 1024) {}

  open(): void {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    try {
      if (fs.statSync(this.file).size > this.maxBytes) {
        fs.renameSync(this.file, this.file + '.1');
      }
    } catch {
      // arquivo ainda não existe
    }
    this.stream = fs.createWriteStream(this.file, { flags: 'a' });
    this.write(`\n===== ${new Date().toISOString()} =====\n`);
  }

  write(text: string): void {
    this.stream?.write(text);
  }

  close(): void {
    this.stream?.end();
    this.stream = null;
  }

  /** Últimas linhas, para o diagnóstico na interface. */
  tail(maxBytes = 12 * 1024): string {
    try {
      const size = fs.statSync(this.file).size;
      const start = Math.max(0, size - maxBytes);
      const fd = fs.openSync(this.file, 'r');
      try {
        const buffer = Buffer.alloc(size - start);
        fs.readSync(fd, buffer, 0, buffer.length, start);
        const text = buffer.toString('utf8');
        return start > 0 ? text.slice(text.indexOf('\n') + 1) : text;
      } finally {
        fs.closeSync(fd);
      }
    } catch {
      return '';
    }
  }
}

/** Remove o token de qualquer texto antes de mostrá-lo ou gravá-lo. */
export function redact(text: string, secret: string | null): string {
  if (!secret || secret.length < 8) return text;
  return text.split(secret).join('<token>');
}
