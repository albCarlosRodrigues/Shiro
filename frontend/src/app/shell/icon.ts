import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Ícones próprios em traço de 1,5 px numa grade de 16. */
const PATHS: Record<string, string> = {
  home: 'M2.5 7.5 8 3l5.5 4.5M4 6.5V13h3v-3h2v3h3V6.5',
  folder: 'M2 4.5A1.5 1.5 0 0 1 3.5 3h3l1.5 1.5h4.5A1.5 1.5 0 0 1 14 6v5.5a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 2 11.5z',
  'folder-open': 'M2 11.5V4.5A1.5 1.5 0 0 1 3.5 3h3l1.5 1.5h4A1.5 1.5 0 0 1 13.5 6v1M2 11.5 3.6 7.6a1 1 0 0 1 .9-.6H14l-1.7 5.3a1 1 0 0 1-1 .7H3.5A1.5 1.5 0 0 1 2 11.5',
  plus: 'M8 3v10M3 8h10',
  x: 'M4 4l8 8M12 4l-8 8',
  'chevron-right': 'M6 3.5 10.5 8 6 12.5',
  'chevron-down': 'M3.5 6 8 10.5 12.5 6',
  'chevron-left': 'M10 3.5 5.5 8l4.5 4.5',
  search: 'M7 12A5 5 0 1 0 7 2a5 5 0 0 0 0 10zM10.6 10.6 14 14',
  gear: 'M8 10a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM8 1.5v2M8 12.5v2M1.5 8h2M12.5 8h2M3.4 3.4l1.4 1.4M11.2 11.2l1.4 1.4M3.4 12.6l1.4-1.4M11.2 4.8l1.4-1.4',
  agent: 'M5 6.5h6a1.5 1.5 0 0 1 1.5 1.5v3A1.5 1.5 0 0 1 11 12.5H5A1.5 1.5 0 0 1 3.5 11V8A1.5 1.5 0 0 1 5 6.5zM8 6.5V4M6.5 9.3h.01M9.5 9.3h.01M1.5 9v1.5M14.5 9v1.5M8 3.5a.5.5 0 1 0 0-1',
  squad: 'M6 7.5a2.25 2.25 0 1 0 0-4.5 2.25 2.25 0 0 0 0 4.5zM1.5 13.5c.4-2.3 2.2-3.8 4.5-3.8s4.1 1.5 4.5 3.8M11 3.2a2.2 2.2 0 0 1 0 4.2M12.2 9.9c1.3.6 2.1 1.9 2.3 3.6',
  'layout-single': 'M2.5 3h11v10h-11z',
  'layout-split': 'M2.5 3h11v10h-11zM8 3v10',
  'layout-grid': 'M2.5 3h11v10h-11zM8 3v10M2.5 8h11',
  maximize: 'M9.5 2.5h4v4M6.5 13.5h-4v-4M13.5 2.5 9 7M2.5 13.5 7 9',
  restore: 'M13.5 6.5h-4v-4M2.5 9.5h4v4M9.5 6.5 14 2M6.5 9.5 2 14',
  more: 'M3.5 8h.01M8 8h.01M12.5 8h.01',
  send: 'M2.5 8 13.5 2.5 10.5 13.5 7.5 8.5zM7.5 8.5l6-6',
  stop: 'M4 4h8v8H4z',
  pause: 'M5.5 3.5v9M10.5 3.5v9',
  clock: 'M8 14A6 6 0 1 0 8 2a6 6 0 0 0 0 12zM8 4.5V8l2.5 1.5',
  check: 'M3 8.5 6.5 12 13 4.5',
  alert: 'M8 2 14.5 13.5h-13zM8 6.5v3M8 11.5h.01',
  spinner: 'M8 2a6 6 0 1 1-6 6',
  dot: 'M8 9a1 1 0 1 0 0-2 1 1 0 0 0 0 2z',
  doc: 'M4 2h5l3.5 3.5V14h-8.5zM9 2v3.5h3.5M6 8.5h4.5M6 11h4.5',
  sidebar: 'M2.5 3h11v10h-11zM6 3v10',
  terminal: 'M2.5 3h11v10h-11zM4.5 6.5 6.5 8.5l-2 2M8 10.5h3',
  activity: 'M1.5 8.5h3l2-5 3 9 2-4h3',
  chat: 'M2.5 3.5h11v7.5H7l-3 2.5v-2.5H2.5z',
  copy: 'M5.5 5.5h8v8h-8zM10.5 5.5V2.5h-8v8h3',
  trash: 'M2.5 4h11M6 4V2.5h4V4M4 4l.7 9.5h6.6L12 4M6.8 6.5v4.5M9.2 6.5v4.5',
  archive: 'M2 3h12v3H2zM3 6v7.5h10V6M6.5 8.5h3',
  edit: 'M10.5 2.5l3 3L6 13H3v-3zM9 4l3 3',
  download: 'M8 2.5v8M4.5 7 8 10.5 11.5 7M2.5 13.5h11',
  upload: 'M8 10.5v-8M4.5 6 8 2.5 11.5 6M2.5 13.5h11',
  refresh: 'M13.5 3v3.5H10M2.5 13V9.5H6M3.3 6.5a5 5 0 0 1 9.2-1.3M12.7 9.5a5 5 0 0 1-9.2 1.3',
  external: 'M9.5 2.5h4v4M13.5 2.5 7.5 8.5M12 9.5v4H2.5V4h4',
  bolt: 'M9 1.5 3 9h5l-1 5.5L13 7H8z',
  panel: 'M2.5 3h11v10h-11zM10 3v10',
  grip: 'M6 4h.01M10 4h.01M6 8h.01M10 8h.01M6 12h.01M10 12h.01',
  eye: 'M1.5 8S4 3.5 8 3.5 14.5 8 14.5 8 12 12.5 8 12.5 1.5 8 1.5 8zM8 10a2 2 0 1 0 0-4 2 2 0 0 0 0 4z',
  lock: 'M4 7h8v6.5H4zM5.5 7V5a2.5 2.5 0 0 1 5 0v2',
  key: 'M10 9.5a3.5 3.5 0 1 0-3.4-2.7L2 11.5V14h2.5v-1.5H6V11h1.5l1-1',
  plug: 'M5.5 2v3.5M10.5 2v3.5M3.5 5.5h9v2a4.5 4.5 0 0 1-9 0zM8 12v2.5',
  win_min: 'M3.5 8h9',
  win_max: 'M3.5 3.5h9v9h-9z',
  win_restore: 'M5.5 5.5h7v7h-7zM5.5 3.5h7v2M3.5 5.5v7',
  win_close: 'M4 4l8 8M12 4l-8 8',
  commander: 'M8 1.8l1.8 3.7 4 .6-2.9 2.8.7 4L8 11l-3.6 1.9.7-4L2.2 6.1l4-.6z',
  requirements: 'M5.5 4h8M5.5 8h8M5.5 12h8M2.5 4h.01M2.5 8h.01M2.5 12h.01',
  architecture: 'M2.5 2.5h4.5v4.5H2.5zM9 2.5h4.5v4.5H9zM2.5 9h4.5v4.5H2.5zM9 9h4.5v4.5H9z',
  frontend: 'M2 3h12v10H2zM2 6h12M4 4.5h.01M5.5 4.5h.01',
  backend: 'M2.5 2.5h11v4.5h-11zM2.5 9h11v4.5h-11zM5 4.7h.01M5 11.2h.01',
  database: 'M8 5.5c3 0 5.5-.9 5.5-2s-2.5-2-5.5-2-5.5.9-5.5 2 2.5 2 5.5 2zM2.5 3.5v9c0 1.1 2.5 2 5.5 2s5.5-.9 5.5-2v-9M2.5 8c0 1.1 2.5 2 5.5 2s5.5-.9 5.5-2',
  qa: 'M8 1.5 13.5 4v4c0 3.2-2.4 5.6-5.5 6.5C4.9 13.6 2.5 11.2 2.5 8V4zM5.5 8l2 2 3.5-4',
  devops: 'M5 5.5C3.6 5.5 2.5 6.6 2.5 8s1.1 2.5 2.5 2.5c2.5 0 3.5-5 6-5 1.4 0 2.5 1.1 2.5 2.5s-1.1 2.5-2.5 2.5c-2.5 0-3.5-5-6-5',
  docs: 'M3 2.5h7.5L13 5v8.5H3zM5.5 6h5M5.5 8.5h5M5.5 11h3',
  security: 'M8 1.5 13.5 4v4c0 3.2-2.4 5.6-5.5 6.5C4.9 13.6 2.5 11.2 2.5 8V4zM8 6.5v3',
  mobile: 'M4.5 1.5h7v13h-7zM7 12.5h2',
  sparkle: 'M8 1.5 9.4 6.6 14.5 8l-5.1 1.4L8 14.5 6.6 9.4 1.5 8l5.1-1.4z',
  history: 'M2.5 8a5.5 5.5 0 1 0 1.6-3.9M2.5 2.5v3h3M8 5v3l2 1.5',
  pin: 'M10 2l4 4-2 1-2.5 2.5L9 13l-6-6 3.5-.5L9 4z',
  filter: 'M2 3h12l-4.5 5.5v4l-3 1.5V8.5z',
  info: 'M8 14A6 6 0 1 0 8 2a6 6 0 0 0 0 14zM8 7.5v3.5M8 5h.01',
  board: 'M2.5 3h11v10h-11zM6 3v10M9.5 3v10',
  list: 'M5 4h9M5 8h9M5 12h9M2 4h.01M2 8h.01M2 12h.01',
  flag: 'M3.5 14V2.5M3.5 3h8l-1.5 3 1.5 3h-8',
  review: 'M2.5 3h11v8H6l-3.5 3zM5.5 7l1.5 1.5 3-3',
};

export const ICON_NAMES = Object.keys(PATHS);

@Component({
  selector: 'sd-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-hidden': 'true', '[style.--size.px]': 'size()' },
  styles: `:host { display: inline-flex; flex: none; width: var(--size); height: var(--size); }
    svg { width: 100%; height: 100%; }`,
  template: `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" [attr.stroke-width]="stroke()"
      stroke-linecap="round" stroke-linejoin="round"><path [attr.d]="d()" /></svg>`,
})
export class Icon {
  readonly name = input.required<string>();
  readonly size = input(16);
  readonly stroke = input(1.5);
  protected readonly d = computed(() => PATHS[this.name()] ?? PATHS['dot']);
}
