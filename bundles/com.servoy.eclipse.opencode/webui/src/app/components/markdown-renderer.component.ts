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
  readonly inert = input(false);

  readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');

  constructor() {
    effect(() => {
      // Read all signals so the effect re-runs on content change and once the
      // host element is available.
      const content = this.content();
      const inert = this.inert();
      const hostRef = this.host();
      this.render(hostRef.nativeElement, content, inert);
    });
  }

  private render(el: HTMLDivElement, content: string, inert: boolean): void {
    const raw = marked.parse(content ?? '', { async: false, gfm: true, breaks: true }) as string;
    const clean = DOMPurify.sanitize(
      raw,
      inert
        ? {
            USE_PROFILES: { html: true },
            FORBID_TAGS: [
              'img',
              'picture',
              'source',
              'iframe',
              'form',
              'style',
              'video',
              'audio',
              'object',
              'embed',
              'link',
              'base',
              'meta'
            ],
            FORBID_ATTR: ['style', 'src', 'srcset', 'poster', 'formaction', 'xlink:href']
          }
        : { USE_PROFILES: { html: true } }
    );
    el.innerHTML = clean;
    if (inert) {
      el.querySelectorAll('a').forEach((a) => a.removeAttribute('href'));
    }
    el.querySelectorAll('pre code').forEach((block) => {
      try {
        hljs.highlightElement(block as HTMLElement);
      } catch {
        // Highlighting is best-effort; leave the plain code block on failure.
      }
    });
    this.addCopyButtons(el);
  }

  /**
   * Adds a copy button to each {@code <pre>} code block. Idempotent across
   * streaming re-renders: because {@code innerHTML} rebuilds the whole subtree
   * on every render, each call starts from button-less markup and attaches
   * exactly one fresh button per block.
   */
  private addCopyButtons(el: HTMLElement): void {
    el.querySelectorAll('pre').forEach((pre) => {
      // Capture the raw source BEFORE appending the button so the button's own
      // label can never leak into the copied text.
      const code = (pre.querySelector('code')?.textContent ?? pre.textContent ?? '');

      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'svy-copy-btn';
      button.setAttribute('aria-label', 'Copy code');
      button.textContent = 'Copy';

      button.addEventListener('click', () => {
        void this.copyText(code, button);
      });

      pre.appendChild(button);
    });
  }

  /**
   * Copies {@code code} to the clipboard, preferring the async Clipboard API
   * and falling back to a hidden textarea + {@code execCommand('copy')} for the
   * embedded browser. Any failure is swallowed so it never breaks rendering.
   */
  private async copyText(code: string, button: HTMLButtonElement): Promise<void> {
    let copied = false;
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(code);
        copied = true;
      }
    } catch {
      // Fall through to the execCommand fallback.
    }

    if (!copied) {
      copied = this.copyViaTextarea(code);
    }

    if (copied) {
      this.showCopiedFeedback(button);
    }
  }

  private copyViaTextarea(code: string): boolean {
    try {
      const textarea = document.createElement('textarea');
      textarea.value = code;
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      textarea.style.pointerEvents = 'none';
      document.body.appendChild(textarea);
      textarea.select();
      const ok = document.execCommand('copy');
      document.body.removeChild(textarea);
      return ok;
    } catch {
      return false;
    }
  }

  private showCopiedFeedback(button: HTMLButtonElement): void {
    button.textContent = 'Copied!';
    button.setAttribute('data-copied', '');
    button.classList.add('copied');
    setTimeout(() => {
      // Guard: a re-render may have detached this button already.
      if (!button.isConnected) {
        return;
      }
      button.textContent = 'Copy';
      button.removeAttribute('data-copied');
      button.classList.remove('copied');
    }, 1500);
  }
}
