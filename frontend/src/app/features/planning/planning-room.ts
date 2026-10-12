import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, input, signal, viewChild } from '@angular/core';

import { ApiError } from '../../core/api';
import { clockTime, DOCUMENT_LABEL } from '../../core/format';
import { MarkdownView } from '../../core/markdown';
import type { Agent, PlanningAttachment, PlanningRoom as Room, PlanningMessage, PlanningParticipant, Proposal } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { Avatar } from '../../shell/bits';
import { Icon } from '../../shell/icon';
import { CatalogStore } from '../../state/catalog.store';
import { ProjectWorkStore } from '../../state/project-work.store';
import { UiStore } from '../../state/ui.store';
import { DocumentsPanel } from '../documents/documents-panel';
import { PlanEditor } from './plan-editor';

type SideTab = 'docs' | 'proposals' | 'plans';

/**
 * Sala de planejamento: conversa com a squad (à esquerda) e documentos, propostas e planos (à direita).
 * Os agentes só leem e propõem; nada aqui inicia implementação.
 */
@Component({
  selector: 'sd-planning-room',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, Avatar, MarkdownView, DocumentsPanel, PlanEditor],
  styles: `
    :host { display: grid; grid-template-columns: minmax(360px, 1fr) minmax(360px, 1fr); flex: 1 1 auto; min-width: 0; min-height: 0; overflow: hidden; }
    .chat { display: flex; flex-direction: column; min-width: 0; min-height: 0; border-right: 1px solid var(--line-1); }
    .who { display: flex; align-items: center; gap: 6px; padding: 8px 14px; border-bottom: 1px solid var(--line-1); flex-wrap: wrap; }
    .who .p { display: inline-flex; align-items: center; gap: 5px; padding: 2px 8px 2px 3px; border-radius: 99px; background: var(--bg-3);
      font-size: var(--fs-xs); color: var(--fg-2); border: 0; cursor: pointer; }
    .who .p:hover { background: var(--bg-4); color: var(--fg-1); }
    .log { flex: 1 1 auto; overflow: auto; padding: 14px; display: flex; flex-direction: column; gap: 14px; }
    .msg { display: grid; grid-template-columns: 26px 1fr; gap: 10px; }
    .msg.user .bubble { background: var(--bg-3); }
    .msg.system { grid-template-columns: 1fr; color: var(--fg-3); font-size: var(--fs-sm); text-align: center; }
    .head { display: flex; align-items: baseline; gap: 8px; font-size: var(--fs-sm); margin-bottom: 3px; }
    .head b { color: var(--fg-1); font-weight: 600; }
    .bubble { padding: 8px 11px; border-radius: var(--r-md); background: var(--bg-2); border: 1px solid var(--line-1); min-width: 0; }
    .you { display: grid; place-items: center; width: 26px; height: 26px; border-radius: 7px; background: var(--bg-4); font-size: var(--fs-xs); color: var(--fg-2); }
    .att { display: flex; align-items: center; gap: 8px; margin-top: 8px; padding: 7px 9px; border-radius: var(--r-sm); border: 1px solid var(--line-2); background: var(--bg-1); font-size: var(--fs-sm); }
    .att.warning { border-color: rgba(233, 180, 76, 0.35); color: #f1d49a; }
    .typing { color: var(--fg-3); font-size: var(--fs-sm); }
    .composer { padding: 8px 10px 10px; border-top: 1px solid var(--line-1); position: relative; }
    .box { border: 1px solid var(--line-2); border-radius: var(--r-md); background: var(--bg-1); }
    .box:focus-within { border-color: var(--signal); }
    textarea { display: block; width: 100%; min-height: 44px; max-height: 200px; padding: 8px 10px 4px; border: 0; background: transparent; resize: none; outline: none; line-height: 1.5; }
    .tools { display: flex; align-items: center; gap: 8px; padding: 4px 6px 6px 10px; }
    .hint { color: var(--fg-4); font-size: var(--fs-xs); }
    .suggest { position: absolute; left: 12px; bottom: calc(100% - 4px); min-width: 240px; }
    .suggest button[aria-selected='true'] { background: var(--bg-5); }
    .side { display: flex; flex-direction: column; min-width: 0; min-height: 0; background: var(--bg-2); }
    .side-head { display: flex; align-items: center; gap: 8px; height: 38px; padding: 0 10px; border-bottom: 1px solid var(--line-1); flex: none; }
    .side-body { display: flex; flex-direction: column; flex: 1 1 auto; min-height: 0; overflow: auto; }
    .list { padding: 10px; display: flex; flex-direction: column; gap: 10px; }
    .prop { border: 1px solid var(--line-2); border-radius: var(--r-md); background: var(--bg-1); }
    .prop.focus { border-color: var(--signal); }
    .prop-head { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-bottom: 1px solid var(--line-1); }
    .prop-body { padding: 8px 10px; max-height: 320px; overflow: auto; }
    .prop-foot { display: flex; align-items: center; gap: 6px; padding: 8px 10px; border-top: 1px solid var(--line-1); flex-wrap: wrap; }
    .compare { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; padding: 8px 10px; }
    .compare pre { margin: 0; max-height: 220px; overflow: auto; white-space: pre-wrap; font-size: var(--fs-xs); background: var(--bg-2); padding: 6px; border-radius: var(--r-sm); }
    .count { display: inline-grid; place-items: center; min-width: 16px; height: 16px; padding: 0 4px; border-radius: 8px; background: var(--signal); color: var(--signal-ink); font-size: 10px; margin-left: 4px; }
    @media (max-width: 1100px) { :host { grid-template-columns: 1fr; } .side { display: none; } :host(.side-only) .chat { display: none; } :host(.side-only) .side { display: flex; } }
  `,
  host: { '[class.side-only]': 'narrowSide()' },
  template: `
    <section class="chat" aria-label="Conversa com a squad">
      <div class="who">
        <span class="muted" style="font-size: var(--fs-xs)">Squad:</span>
        @for (p of room()?.participants ?? []; track p.agentId) {
          <button class="p" (click)="mention(p)" [attr.title]="p.roleLabel + (p.specialty ? ' · ' + p.specialty : '') + ' — clique para mencionar'">
            <sd-avatar [agent]="agentOf(p)" [size]="18" /> @{{ p.handle }} @if (p.commander) { <sd-icon name="commander" [size]="11" /> }
          </button>
        } @empty {
          <span class="muted" style="font-size: var(--fs-sm)">Este projeto não tem squad. Escolha uma nas configurações do projeto.</span>
        }
      </div>
      <div class="log" #log role="log" aria-live="polite">
        @if (room()?.hasMore) { <p class="muted" style="text-align: center; font-size: var(--fs-xs)">Mensagens mais antigas não exibidas.</p> }
        @for (m of room()?.messages ?? []; track m.id) {
          @if (m.authorType === 'SYSTEM') {
            <div class="msg system">{{ m.content }}</div>
          } @else {
            <div class="msg" [class.user]="m.authorType === 'USER'">
              @if (m.authorType === 'USER') { <span class="you" aria-hidden="true">Você</span> }
              @else { <sd-avatar [agent]="agentById(m.agentId)" [size]="26" /> }
              <div style="min-width: 0">
                <div class="head"><b>{{ m.authorType === 'USER' ? 'Você' : nameOf(m.agentId) }}</b><span class="muted">{{ time(m.createdAt) }}</span></div>
                <div class="bubble"><sd-markdown [source]="m.content" />
                  @for (a of m.attachments; track $index) {
                    <div class="att" [class.warning]="a.type === 'warning'">
                      <sd-icon [name]="a.type === 'plan' ? 'list' : a.type === 'proposal' ? 'doc' : 'alert'" [size]="13" />
                      <span class="grow"><b>{{ a.title }}</b> @if (a.detail) { <span class="muted">· {{ a.detail }}</span> }</span>
                      @if (a.type !== 'warning' && a.id) {
                        <span class="chip">{{ attachmentStatus(a) }}</span>
                        <button class="btn sm" (click)="openAttachment(a)">Revisar</button>
                      }
                    </div>
                  }
                </div>
              </div>
            </div>
          }
        } @empty {
          <div class="empty">
            <h3>Comece pelo briefing</h3>
            <p>Descreva o que quer construir. O comandante responde e coordena; mencione um agente com &#64; para falar com ele diretamente.
              Propostas de documentos e planos aparecem para você revisar; nada é implementado sem a sua autorização.</p>
          </div>
        }
        @if (round(); as r) {
          <div class="msg">
            <sd-avatar [agent]="speakingAgent()" [size]="26" />
            <div style="min-width: 0">
              <div class="head"><b>{{ speakingAgent()?.name ?? 'Squad' }}</b><span class="typing"><sd-icon name="spinner" [size]="11" class="spin" /> respondendo…</span></div>
              @if (speech()) { <div class="bubble"><sd-markdown [source]="speech()" /></div> }
              @if (r.pending.length) { <div class="typing">Depois: {{ pendingNames() }}</div> }
            </div>
          </div>
        }
      </div>
      <div class="composer">
        @if (suggestions().length) {
          <div class="menu suggest" role="listbox" aria-label="Mencionar agente">
            @for (p of suggestions(); track p.agentId; let i = $index) {
              <button role="option" [attr.aria-selected]="i === suggestIndex()" (mousedown)="$event.preventDefault(); pick(p)">
                <sd-avatar [agent]="agentOf(p)" [size]="16" /> @{{ p.handle }} <span class="muted">{{ p.roleLabel }}</span>
              </button>
            }
          </div>
        }
        <div class="box">
          <textarea #input rows="2" aria-label="Mensagem para a squad" [value]="text()" [disabled]="!room()?.participants?.length"
            placeholder="Mensagem para a squad (use @ para chamar um agente)…" (input)="onInput($event)" (keydown)="onKey($event)"></textarea>
          <div class="tools">
            <span class="hint grow">Enter envia · Shift+Enter quebra linha · sem menção, o comandante responde</span>
            @if (round()) {
              <button class="btn sm danger" (click)="stop()" title="Cancela a fala em andamento e as próximas"><sd-icon name="stop" [size]="12" /> Interromper</button>
            } @else {
              <button class="btn sm primary" [disabled]="!text().trim() || sending()" (click)="send()"><sd-icon name="send" [size]="12" /> Enviar</button>
            }
          </div>
        </div>
      </div>
    </section>

    <aside class="side" aria-label="Documentos, propostas e planos">
      <div class="side-head">
        <div class="seg" role="tablist">
          <button role="tab" [attr.aria-selected]="tab() === 'docs'" (click)="tab.set('docs')">Documentos</button>
          <button role="tab" [attr.aria-selected]="tab() === 'proposals'" (click)="tab.set('proposals')">Propostas
            @if (pendingProposals()) { <span class="count">{{ pendingProposals() }}</span> }</button>
          <button role="tab" [attr.aria-selected]="tab() === 'plans'" (click)="tab.set('plans')">Planos
            @if (draftPlans()) { <span class="count">{{ draftPlans() }}</span> }</button>
        </div>
        <span class="spacer"></span>
        @if (tab() === 'plans') { <button class="btn sm" (click)="newPlan()"><sd-icon name="plus" [size]="12" /> Plano manual</button> }
      </div>
      <div class="side-body">
        @switch (tab()) {
          @case ('docs') { <sd-documents-panel [projectId]="projectId()" /> }
          @case ('proposals') {
            <div class="list">
              @for (p of proposals(); track p.id) {
                <article class="prop" [class.focus]="focusId() === p.id" [attr.id]="'prop-' + p.id">
                  <div class="prop-head">
                    <sd-avatar [agent]="agentById(p.agentId)" [size]="20" />
                    <div class="grow"><b>{{ p.title }}</b> <span class="muted">· {{ docLabel(p) }}</span>
                      @if (p.summary) { <div class="muted" style="font-size: var(--fs-sm)">{{ p.summary }}</div> }</div>
                    <span class="chip">{{ proposalLabel(p) }}</span>
                  </div>
                  @if (conflict()?.id === p.id) {
                    <div class="banner warn" style="margin: 8px 10px 0">O documento mudou depois da proposta (versão {{ conflict()!.base }} → {{ conflict()!.current }}).
                      Compare antes de aplicar; a sua versão continua no histórico.</div>
                    <div class="compare"><div><div class="muted">Atual</div><pre>{{ conflict()!.currentContent }}</pre></div>
                      <div><div class="muted">Proposta</div><pre>{{ p.content }}</pre></div></div>
                  } @else if (expanded() === p.id || p.status === 'PENDING') {
                    <div class="prop-body"><sd-markdown [source]="p.content" /></div>
                  }
                  <div class="prop-foot">
                    @if (p.status === 'PENDING') {
                      @if (conflict()?.id === p.id) {
                        <button class="btn sm warn" (click)="apply(p, true)">Aplicar mesmo assim</button>
                        <button class="btn sm ghost" (click)="conflict.set(null)">Voltar</button>
                      } @else {
                        <button class="btn sm primary" (click)="apply(p, false)"><sd-icon name="check" [size]="12" /> Aplicar</button>
                      }
                      <button class="btn sm ghost" (click)="discard(p)">Descartar</button>
                    } @else {
                      <button class="btn sm ghost" (click)="expanded.set(expanded() === p.id ? null : p.id)">{{ expanded() === p.id ? 'Recolher' : 'Ver conteúdo' }}</button>
                      @if (p.status === 'APPLIED') { <button class="btn sm" (click)="undo(p)"><sd-icon name="history" [size]="12" /> Desfazer</button> }
                    }
                  </div>
                </article>
              } @empty {
                <div class="empty"><p>Nenhuma proposta ainda. Quando um agente sugerir mudanças em um documento, elas aparecem aqui para você aplicar ou descartar.</p></div>
              }
            </div>
          }
          @case ('plans') {
            @if (selectedPlan(); as plan) {
              <sd-plan-editor [plan]="plan" [participants]="room()?.participants ?? []" (close)="planId.set(null)" />
            } @else {
              <div class="list">
                @for (p of plans(); track p.id) {
                  <button class="list-row" (click)="planId.set(p.id)">
                    <sd-icon name="list" [size]="14" />
                    <span class="grow ellipsis"><b>Plano {{ p.number }}</b> · {{ p.content.features.length }} features
                      @if (p.summary) { <span class="muted">· {{ p.summary }}</span> }</span>
                    <span class="chip">{{ planLabel(p.status) }}</span>
                    @if (p.status === 'DRAFT' && errors(p)) { <span class="chip" style="color: var(--danger)">{{ errors(p) }} pendências</span> }
                  </button>
                } @empty {
                  <div class="empty"><p>Peça ao comandante para propor um plano de features, ou crie um plano manualmente.</p></div>
                }
              </div>
            }
          }
        }
      </div>
    </aside>
  `,
})
export class PlanningRoom {
  readonly projectId = input.required<string>();

  private readonly work = inject(ProjectWorkStore);
  private readonly catalog = inject(CatalogStore);
  private readonly toasts = inject(Toasts);
  private readonly ui = inject(UiStore);
  private readonly logRef = viewChild<ElementRef<HTMLElement>>('log');
  private readonly inputRef = viewChild<ElementRef<HTMLTextAreaElement>>('input');

  protected readonly tab = signal<SideTab>('docs');
  protected readonly text = signal('');
  protected readonly sending = signal(false);
  protected readonly planId = signal<string | null>(null);
  protected readonly focusId = signal<string | null>(null);
  protected readonly expanded = signal<string | null>(null);
  protected readonly conflict = signal<{ id: string; base: number; current: number; currentContent: string } | null>(null);
  protected readonly narrowSide = signal(false);
  protected readonly suggestIndex = signal(0);
  private readonly mentionQuery = signal<string | null>(null);

  protected readonly room = computed<Room | undefined>(() => this.work.rooms()[this.projectId()]);
  protected readonly round = computed(() => this.room()?.round ?? null);
  protected readonly proposals = computed(() => this.work.proposals()[this.projectId()] ?? []);
  protected readonly plans = computed(() => this.work.plans()[this.projectId()] ?? []);
  protected readonly pendingProposals = computed(() => this.proposals().filter(p => p.status === 'PENDING').length);
  protected readonly draftPlans = computed(() => this.plans().filter(p => p.status === 'DRAFT').length);
  protected readonly selectedPlan = computed(() => this.plans().find(p => p.id === this.planId()) ?? null);
  /** Texto parcial da fala; blocos estruturados viram um aviso até a fala terminar e o anexo ser criado. */
  protected readonly speech = computed(() => {
    const text = this.work.speech()[this.projectId()]?.text ?? '';
    const cut = text.indexOf('```squad-');
    return cut < 0 ? text : text.slice(0, cut).trimEnd() + '\n\n*Preparando anexo para revisão…*';
  });
  protected readonly speakingAgent = computed(() => {
    const r = this.round();
    const id = r?.spoken[r.spoken.length - 1];
    return id ? this.catalog.agentById().get(id) : undefined;
  });
  protected readonly pendingNames = computed(() => (this.round()?.pending ?? []).map(id => this.catalog.agentById().get(id)?.name ?? 'agente').join(', '));
  protected readonly suggestions = computed(() => {
    const q = this.mentionQuery();
    if (q === null) return [];
    const key = fold(q);
    return (this.room()?.participants ?? []).filter(p => fold(p.handle).startsWith(key) || fold(p.name).includes(key)).slice(0, 6);
  });

  constructor() {
    effect(() => {
      const id = this.projectId();
      void this.work.loadAll(id).catch(error => this.toasts.error(error, 'Não foi possível abrir o planejamento'));
    });
    effect(() => {
      this.room()?.messages.length;
      this.speech();
      queueMicrotask(() => {
        const el = this.logRef()?.nativeElement;
        if (el && el.scrollHeight - el.scrollTop - el.clientHeight < 160) el.scrollTop = el.scrollHeight;
      });
    });
  }

  protected agentById(id: string | null): Agent | undefined {
    return id ? this.catalog.agentById().get(id) : undefined;
  }

  protected agentOf(p: PlanningParticipant): Agent | undefined {
    return this.catalog.agentById().get(p.agentId);
  }

  protected nameOf(id: string | null): string {
    return this.agentById(id)?.name ?? 'Agente removido';
  }

  protected time(iso: string): string {
    return clockTime(iso);
  }

  protected docLabel(p: Proposal): string {
    return DOCUMENT_LABEL[p.kind] ?? p.kind;
  }

  protected proposalLabel(p: Proposal): string {
    return { PENDING: 'Aguardando você', APPLIED: 'Aplicada', DISCARDED: 'Descartada', UNDONE: 'Desfeita' }[p.status];
  }

  protected planLabel(status: string): string {
    return status === 'DRAFT' ? 'Rascunho' : status === 'APPROVED' ? 'Aprovado' : 'Descartado';
  }

  protected errors(plan: { problems: { severity: string }[] }): number {
    return plan.problems.filter(p => p.severity === 'ERROR').length;
  }

  protected attachmentStatus(a: PlanningAttachment): string {
    if (a.type === 'proposal') {
      const p = this.proposals().find(x => x.id === a.id);
      return p ? this.proposalLabel(p) : 'Proposta';
    }
    const plan = this.plans().find(x => x.id === a.id);
    return plan ? this.planLabel(plan.status) : 'Plano';
  }

  protected openAttachment(a: PlanningAttachment): void {
    if (a.type === 'proposal') {
      this.tab.set('proposals');
      this.focusId.set(a.id ?? null);
      this.expanded.set(a.id ?? null);
      queueMicrotask(() => document.getElementById('prop-' + a.id)?.scrollIntoView({ block: 'nearest' }));
    } else if (a.type === 'plan') {
      this.tab.set('plans');
      this.planId.set(a.id ?? null);
    }
    this.narrowSide.set(window.innerWidth <= 1100);
  }

  protected mention(p: PlanningParticipant): void {
    const current = this.text();
    this.text.set((current && !current.endsWith(' ') ? current + ' ' : current) + '@' + p.handle + ' ');
    this.focusInput();
  }

  protected onInput(event: Event): void {
    const el = event.target as HTMLTextAreaElement;
    this.text.set(el.value);
    el.style.height = 'auto';
    el.style.height = Math.min(200, el.scrollHeight) + 'px';
    const before = el.value.slice(0, el.selectionStart ?? el.value.length);
    const match = /(?:^|\s)@([\p{L}0-9_]*)$/u.exec(before);
    this.mentionQuery.set(match ? match[1] : null);
    this.suggestIndex.set(0);
  }

  protected onKey(event: KeyboardEvent): void {
    const list = this.suggestions();
    if (list.length) {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        const delta = event.key === 'ArrowDown' ? 1 : -1;
        this.suggestIndex.set((this.suggestIndex() + delta + list.length) % list.length);
        return;
      }
      if (event.key === 'Enter' || event.key === 'Tab') {
        event.preventDefault();
        this.pick(list[this.suggestIndex()]);
        return;
      }
      if (event.key === 'Escape') {
        this.mentionQuery.set(null);
        return;
      }
    }
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      if (!this.round()) void this.send();
    }
  }

  protected pick(p: PlanningParticipant): void {
    const el = this.inputRef()?.nativeElement;
    const value = this.text();
    const caret = el?.selectionStart ?? value.length;
    const before = value.slice(0, caret).replace(/@([\p{L}0-9_]*)$/u, '@' + p.handle + ' ');
    const next = before + value.slice(caret);
    this.text.set(next);
    this.mentionQuery.set(null);
    queueMicrotask(() => {
      if (!el) return;
      el.value = next;
      el.focus();
      el.setSelectionRange(before.length, before.length);
    });
  }

  protected async send(): Promise<void> {
    const content = this.text().trim();
    if (!content || this.sending()) return;
    this.sending.set(true);
    try {
      await this.work.post(this.projectId(), content);
      this.text.set('');
      const el = this.inputRef()?.nativeElement;
      if (el) {
        el.value = '';
        el.style.height = 'auto';
      }
    } catch (error) {
      this.toasts.error(error);
    } finally {
      this.sending.set(false);
    }
  }

  protected async stop(): Promise<void> {
    try {
      await this.work.stop(this.projectId());
    } catch (error) {
      this.toasts.error(error);
    }
  }

  protected async apply(p: Proposal, force: boolean): Promise<void> {
    try {
      await this.work.applyProposal(p, force);
      this.conflict.set(null);
      this.toasts.show(`Proposta aplicada em “${p.title}”. A versão anterior está no histórico.`, 'ok');
    } catch (error) {
      const e = ApiError.from(error);
      if (e.code === 'proposal_outdated') {
        this.conflict.set({ id: p.id, base: Number(e.details['baseVersion']), current: Number(e.details['currentVersion']),
          currentContent: String(e.details['currentContent'] ?? '') });
      } else {
        this.toasts.error(e);
      }
    }
  }

  protected async discard(p: Proposal): Promise<void> {
    try {
      await this.work.discardProposal(p);
    } catch (error) {
      this.toasts.error(error);
    }
  }

  protected async undo(p: Proposal): Promise<void> {
    const ok = await this.ui.confirm({ title: 'Desfazer proposta', body: `Restaura “${p.title}” como estava antes da aplicação. O conteúdo aplicado continua no histórico.`, confirm: 'Desfazer' });
    if (!ok) return;
    try {
      await this.work.undoProposal(p);
    } catch (error) {
      this.toasts.error(error);
    }
  }

  protected async newPlan(): Promise<void> {
    try {
      const plan = await this.work.createPlan(this.projectId());
      this.planId.set(plan.id);
    } catch (error) {
      this.toasts.error(error);
    }
  }

  private focusInput(): void {
    queueMicrotask(() => this.inputRef()?.nativeElement.focus());
  }
}

function fold(text: string): string {
  return text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
}
