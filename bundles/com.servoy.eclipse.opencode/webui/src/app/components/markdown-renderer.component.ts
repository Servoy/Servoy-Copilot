import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  ViewEncapsulation,
  effect,
  input,
  viewChild
} from '@angular/core';
import DOMPurify from 'dompurify';
import hljs from 'highlight.js';
import { marked } from 'marked';

/**
 * Renders assistant text as sanitized HTML with code-block highlighting.
 * <p>
 * Streaming-safe: an {@link effect} re-renders as the {@code content} signal
 * grows. Output is always sanitized with DOMPurify before being written to the
 * DOM.
 */
@Component({
  selector: 'svy-markdown',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  template: `<div class="markdown-body" #host></div>`,
  styleUrl: './markdown-renderer.component.scss'
})
export class MarkdownRendererComponent {
  readonly content = input('');

  readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');

  constructor() {
    effect(() => {
      // Read both signals so the effect re-runs on content change and once the
      // host element is available.
      const content = this.content();
      const hostRef = this.host();
      this.render(hostRef.nativeElement, content);
    });
  }

  private render(el: HTMLDivElement, content: string): void {
    const raw = marked.parse(content ?? '', { async: false, gfm: true, breaks: true }) as string;
    const clean = DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } });
    el.innerHTML = clean;
    el.querySelectorAll('pre code').forEach((block) => {
      try {
        hljs.highlightElement(block as HTMLElement);
      } catch {
        // Highlighting is best-effort; leave the plain code block on failure.
      }
    });
  }
}
