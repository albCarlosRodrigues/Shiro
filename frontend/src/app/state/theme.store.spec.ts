import { TestBed } from '@angular/core/testing';
import { Api } from '../core/api';
import { Desktop } from '../core/desktop';
import { Toasts } from '../core/toasts';
import { ThemeStore } from './theme.store';

describe('aparência do workspace', () => {
  function setup(saved: unknown) {
    const api = { getOptional: vi.fn().mockResolvedValue(saved), putJson: vi.fn().mockResolvedValue(undefined) };
    const bridge = { setAppearance: vi.fn().mockResolvedValue(undefined) };
    const toasts = { error: vi.fn() };
    TestBed.configureTestingModule({ providers: [ThemeStore, { provide: Api, useValue: api }, { provide: Desktop, useValue: { bridge } }, { provide: Toasts, useValue: toasts }] });
    return { store: TestBed.inject(ThemeStore), api, bridge, toasts };
  }
  afterEach(() => { TestBed.resetTestingModule(); delete document.documentElement.dataset['theme']; document.documentElement.style.colorScheme = ''; });
  it('restaura um tema claro sem regravar o workspace', async () => {
    const {store,api,bridge} = setup({theme:'min-light'}); await store.load();
    expect(store.current().id).toBe('min-light');
    expect(document.documentElement.style.colorScheme).toBe('light');
    expect(document.documentElement.dataset['theme']).toBe('min-light');
    expect(api.putJson).not.toHaveBeenCalled(); expect(bridge.setAppearance).toHaveBeenCalled();
  });
  it('volta ao Índigo quando o tema salvo não existe', async () => {
    const {store} = setup({theme:'tema-removido'}); await store.load();
    expect(store.current().id).toBe('indigo'); expect(document.documentElement.dataset['theme']).toBeUndefined();
  });
  it('preserva a ordem das gravações quando a seleção muda rapidamente', async () => {
    const {store,api} = setup(null); let finish!: () => void;
    api.putJson.mockImplementationOnce(() => new Promise<void>(resolve => finish = resolve));
    store.select('claro'); store.select('dracula'); await Promise.resolve();
    expect(api.putJson).toHaveBeenCalledTimes(1); finish();
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(api.putJson.mock.calls.map(call => JSON.parse(call[1]).theme)).toEqual(['claro','dracula']);
    expect(store.current().id).toBe('dracula');
  });
  it('informa falha ao salvar sem bloquear a troca visual', async () => {
    const {store,api,toasts} = setup(null); api.putJson.mockRejectedValueOnce(new Error('offline'));
    store.select('nord'); await new Promise(resolve => setTimeout(resolve, 0));
    expect(store.current().id).toBe('nord'); expect(toasts.error).toHaveBeenCalled();
  });
});
