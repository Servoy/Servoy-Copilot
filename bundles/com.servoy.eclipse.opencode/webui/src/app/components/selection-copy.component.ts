import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';

interface CopyAnchor {
  top: number;
  left: number;
  text: string;
}

const BUTTON_WIDTH = 64;
const BUTTON_HEIGHT = 30;
const MARGIN = 4;
const COPIED_MS = 900;

@Component({
  selector: 'svy-selection-copy',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './selection-copy.component.html',
  styleUrl: './selection-copy.component.scss'
})
export class SelectionCopyComponent {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly anchor = signal<CopyAnchor | null>(null);
  readonly copied = signal(false);

  private hideTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    const onContextMenu = (event: MouseEvent): void => {
      if (this.isOwn(event.target)) {
        return;
      }
      this.show(event.clientX, event.clientY);
    };
    const onMouseDown = (event: MouseEvent): void => {
      if (!this.isOwn(event.target)) {
        this.hide();
      }
    };
    const onSelectionChange = (): void => {
      if (this.anchor() && !this.readSelection()) {
        this.hide();
      }
    };
    const onDismiss = (): void => this.hide();

    document.addEventListener('contextmenu', onContextMenu);
    document.addEventListener('mousedown', onMouseDown, true);
    document.addEventListener('selectionchange', onSelectionChange);
    document.addEventListener('scroll', onDismiss, true);
    document.addEventListener('keydown', onDismiss, true);
    window.addEventListener('resize', onDismiss);

    inject(DestroyRef).onDestroy(() => {
      document.removeEventListener('contextmenu', onContextMenu);
      document.removeEventListener('mousedown', onMouseDown, true);
      document.removeEventListener('selectionchange', onSelectionChange);
      document.removeEventListener('scroll', onDismiss, true);
      document.removeEventListener('keydown', onDismiss, true);
      window.removeEventListener('resize', onDismiss);
      this.clearTimer();
    });
  }

  async copy(): Promise<void> {
    const anchor = this.anchor();
    if (!anchor) {
      return;
    }
    const ok = await SelectionCopyComponent.write(anchor.text);
    if (!ok) {
      this.hide();
      return;
    }
    this.copied.set(true);
    this.clearTimer();
    this.hideTimer = setTimeout(() => this.hide(), COPIED_MS);
  }

  private show(x: number, y: number): void {
    const text = this.readSelection();
    if (!text) {
      this.hide();
      return;
    }
    this.clearTimer();
    this.copied.set(false);
    const left = Math.min(Math.max(x, MARGIN), window.innerWidth - BUTTON_WIDTH - MARGIN);
    const top = Math.min(Math.max(y, MARGIN), window.innerHeight - BUTTON_HEIGHT - MARGIN);
    this.anchor.set({ top, left, text });
  }

  private hide(): void {
    this.clearTimer();
    this.copied.set(false);
    if (this.anchor()) {
      this.anchor.set(null);
    }
  }

  private clearTimer(): void {
    if (this.hideTimer !== null) {
      clearTimeout(this.hideTimer);
      this.hideTimer = null;
    }
  }

  private isOwn(target: EventTarget | null): boolean {
    return target instanceof Node && this.host.nativeElement.contains(target);
  }

  private readSelection(): string | null {
    const active = document.activeElement;
    if (active instanceof HTMLTextAreaElement || active instanceof HTMLInputElement) {
      const start = active.selectionStart;
      const end = active.selectionEnd;
      if (start !== null && end !== null && start !== end) {
        const text = active.value.slice(start, end);
        return text.trim() ? text : null;
      }
    }
    const selection = document.getSelection();
    if (!selection || selection.isCollapsed) {
      return null;
    }
    const text = selection.toString();
    return text.trim() ? text : null;
  }

  private static async write(text: string): Promise<boolean> {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      return SelectionCopyComponent.legacyWrite(text);
    }
  }

  private static legacyWrite(text: string): boolean {
    const field = document.createElement('textarea');
    field.value = text;
    field.setAttribute('readonly', '');
    field.style.position = 'fixed';
    field.style.opacity = '0';
    document.body.appendChild(field);
    field.select();
    try {
      return document.execCommand('copy');
    } catch {
      return false;
    } finally {
      document.body.removeChild(field);
    }
  }
}
