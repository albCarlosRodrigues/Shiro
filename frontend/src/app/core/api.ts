import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import type { ApiErrorBody } from './models';

/** Erro devolvido pela API local, com o código estável que a interface usa para decidir a mensagem e a saída. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details: Record<string, unknown> = {},
  ) {
    super(message);
    this.name = 'ApiError';
  }

  static from(error: unknown): ApiError {
    if (error instanceof ApiError) return error;
    if (error instanceof HttpErrorResponse) {
      const body = error.error as Partial<ApiErrorBody> | string | null;
      if (body && typeof body === 'object' && typeof body.code === 'string') {
        return new ApiError(error.status, body.code, body.message ?? 'Erro no serviço local.', body.details ?? {});
      }
      if (error.status === 0) {
        return new ApiError(0, 'backend_unreachable', 'O serviço local não respondeu. Ele pode ter sido encerrado.');
      }
      return new ApiError(error.status, 'http_' + error.status, 'O serviço local respondeu com erro ' + error.status + '.');
    }
    return new ApiError(0, 'unknown', error instanceof Error ? error.message : String(error));
  }
}

/**
 * Cliente da API local. A autenticação vai em cookie HttpOnly (gravado pelo shell desktop) ou, no
 * desenvolvimento com `ng serve`, no cabeçalho que o proxy injeta. O cabeçalho X-Squad-Client marca as
 * chamadas como vindas do aplicativo; o backend o exige em métodos que alteram dados.
 */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  private readonly headers = new HttpHeaders({ 'X-Squad-Client': 'squad-desktop' });

  get<T>(path: string, params?: Record<string, string | number | boolean>): Promise<T> {
    return this.call(this.http.get<T>(this.url(path), { headers: this.headers, params }));
  }

  /** GET que aceita 204 (sem conteúdo) e devolve null. */
  getOptional<T>(path: string): Promise<T | null> {
    return this.call(this.http.get<T | null>(this.url(path), { headers: this.headers }));
  }

  getText(path: string): Promise<string> {
    return this.call(this.http.get(this.url(path), { headers: this.headers, responseType: 'text' }));
  }

  post<T>(path: string, body: unknown = {}): Promise<T> {
    return this.call(this.http.post<T>(this.url(path), body, { headers: this.headers }));
  }

  put<T>(path: string, body: unknown): Promise<T> {
    return this.call(this.http.put<T>(this.url(path), body, { headers: this.headers }));
  }

  /** PUT de um JSON já serializado (estado da interface). */
  putJson(path: string, json: string): Promise<void> {
    return this.call(this.http.put<void>(this.url(path), json, {
      headers: this.headers.set('Content-Type', 'application/json'),
    }));
  }

  delete<T = void>(path: string): Promise<T> {
    return this.call(this.http.delete<T>(this.url(path), { headers: this.headers }));
  }

  private url(path: string): string {
    return path.startsWith('/api/') ? path : '/api/' + path.replace(/^\//, '');
  }

  private async call<T>(request: import('rxjs').Observable<T>): Promise<T> {
    try {
      return await firstValueFrom(request);
    } catch (error) {
      throw ApiError.from(error);
    }
  }
}
