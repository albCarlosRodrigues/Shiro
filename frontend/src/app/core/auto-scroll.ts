import { Directive, ElementRef, OnDestroy, OnInit, inject } from '@angular/core';

/**
 * Mantém a rolagem no fim enquanto o conteúdo cresce, a menos que a pessoa tenha subido para ler.
 * Usa MutationObserver, então funciona com texto em streaming sem depender de detecção de mudanças.
 */
@Directive({ selector: '[sdAutoScroll]', exportAs: 'autoScroll' })
export class AutoScroll implements OnInit, OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private observer: MutationObserver | null = null;
  private stick = true;
  private readonly onScroll = () => {
    const el = this.host.nativeElement;
    this.stick = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
  };

  ngOnInit(): void {
    const el = this.host.nativeElement;
    el.addEventListener('scroll', this.onScroll, { passive: true });
    this.observer = new MutationObserver(() => {
      if (this.stick) el.scrollTop = el.scrollHeight;
    });
    this.observer.observe(el, { childList: true, subtree: true, characterData: true });
    queueMicrotask(() => (el.scrollTop = el.scrollHeight));
  }

  get atBottom(): boolean {
    return this.stick;
  }

  toBottom(): void {
    const el = this.host.nativeElement;
    this.stick = true;
    el.scrollTop = el.scrollHeight;
  }

  ngOnDestroy(): void {
    this.observer?.disconnect();
    this.host.nativeElement.removeEventListener('scroll', this.onScroll);
  }
}
