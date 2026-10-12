import {
  ProjectLayout, capacity, closePane, defaultLayout, hiddenPanes, movePane, openPane, parseWorkspace, prunePanes, setMode, setSplit, swapPanes,
  toggleMaximize, visiblePanes,
} from './layout';

describe('layout dos painéis', () => {
  it('abre painéis até a capacidade do modo e mantém os demais ocultos', () => {
    let l: ProjectLayout = { ...defaultLayout(), mode: 'split' };
    l = openPane(l, 'a');
    l = openPane(l, 'b');
    expect(visiblePanes(l)).toEqual(['a', 'b']);
    l = openPane(l, 'c');
    expect(l.focused).toBe('c');
    expect(visiblePanes(l)).toContain('c');
    expect(visiblePanes(l)).toHaveLength(capacity('split'));
    expect(hiddenPanes(l)).toHaveLength(1);
    expect(l.panes).toHaveLength(3);
  });

  it('ocultar não perde a sessão e passa o foco adiante', () => {
    let l: ProjectLayout = openPane(openPane(defaultLayout(), 'a'), 'b');
    l = closePane(l, 'b');
    expect(l.panes).toEqual(['a']);
    expect(l.focused).toBe('a');
  });

  it('maximizar mostra só o painel escolhido e restaurar volta ao modo', () => {
    let l: ProjectLayout = { ...defaultLayout(), mode: 'grid' };
    for (const id of ['a', 'b', 'c', 'd']) l = openPane(l, id);
    l = toggleMaximize(l, 'c');
    expect(visiblePanes(l)).toEqual(['c']);
    l = toggleMaximize(l, 'c');
    expect(visiblePanes(l)).toEqual(['a', 'b', 'c', 'd']);
  });

  it('trocar de modo mantém o painel em foco visível', () => {
    let l: ProjectLayout = { ...defaultLayout(), mode: 'grid' };
    for (const id of ['a', 'b', 'c', 'd']) l = openPane(l, id);
    l = { ...l, focused: 'd' };
    l = setMode(l, 'single');
    expect(visiblePanes(l)).toEqual(['d']);
  });

  it('reordena e troca painéis', () => {
    let l: ProjectLayout = { ...defaultLayout(), panes: ['a', 'b', 'c'] };
    l = movePane(l, 'c', 0);
    expect(l.panes).toEqual(['c', 'a', 'b']);
    l = swapPanes(l, 'c', 'b');
    expect(l.panes).toEqual(['b', 'a', 'c']);
  });

  it('limita os divisores e remove sessões que não existem mais', () => {
    expect(setSplit(defaultLayout(), 'col', 0.99).colSplit).toBe(0.8);
    expect(setSplit(defaultLayout(), 'row', -1).rowSplit).toBe(0.2);
    const l = prunePanes({ ...defaultLayout(), panes: ['a', 'x'], focused: 'x', maximized: 'x' }, new Set(['a']));
    expect(l.panes).toEqual(['a']);
    expect(l.focused).toBe('a');
    expect(l.maximized).toBeNull();
  });

  it('restaura estado salvo tolerando valores inválidos', () => {
    const state = parseWorkspace({
      tabs: ['p1', 'p1', 3],
      sidebar: { width: 9999, collapsed: true },
      layouts: { p1: { mode: 'xyz', panes: ['s1', 's1', 's2'], focused: 'nope', colSplit: 5, side: { open: true, tab: 'run', width: 10 } } },
    });
    expect(state.tabs).toEqual(['p1']);
    expect(state.sidebar.width).toBe(440);
    expect(state.sidebar.collapsed).toBe(true);
    const l = state.layouts['p1'];
    expect(l.mode).toBe('split');
    expect(l.panes).toEqual(['s1', 's2']);
    expect(l.focused).toBe('s1');
    expect(l.colSplit).toBe(0.8);
    expect(l.side).toEqual({ open: true, tab: 'run', width: 300 });
    expect(parseWorkspace(null).tabs).toEqual([]);
  });

  it('guarda a área escolhida do projeto e recusa valores desconhecidos', () => {
    const parsed = parseWorkspace({ layouts: { a: { view: 'features' }, b: { view: 'outra' }, c: {} } });
    expect(parsed.layouts['a'].view).toBe('features');
    expect(parsed.layouts['b'].view).toBe('panes');
    expect(parsed.layouts['c'].view).toBe('panes');
  });
});
