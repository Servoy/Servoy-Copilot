import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MarkdownRendererComponent } from './markdown-renderer.component';

describe('MarkdownRendererComponent', () => {
  let fixture: ComponentFixture<MarkdownRendererComponent>;

  function hostHtml(): string {
    return (fixture.nativeElement as HTMLElement).querySelector('.markdown-body')!.innerHTML;
  }

  function hostEl(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function copyButtons(): HTMLButtonElement[] {
    return Array.from(hostEl().querySelectorAll<HTMLButtonElement>('.svy-copy-btn'));
  }

  function setContent(content: string): void {
    fixture.componentRef.setInput('content', content);
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MarkdownRendererComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(MarkdownRendererComponent);
    fixture.detectChanges();
  });

  it('renders bold, headings and lists as the expected tags', () => {
    setContent('# Title\n\n**bold** text\n\n- one\n- two');
    const html = hostHtml();
    expect(html).toContain('<h1');
    expect(html).toContain('<strong>bold</strong>');
    expect(html).toContain('<ul>');
    expect(html).toContain('<li>one</li>');
  });

  it('strips <script> tags (sanitization)', () => {
    setContent('before <script>alert(1)</script> after');
    const html = hostHtml().toLowerCase();
    expect(html).not.toContain('<script');
    expect(html).not.toContain('alert(1)');
  });

  it('strips dangerous onerror handlers from injected HTML', () => {
    setContent('<img src="x" onerror="alert(1)">');
    const html = hostHtml().toLowerCase();
    expect(html).not.toContain('onerror');
  });

  it('renders a fenced code block as a highlighted <code> element', () => {
    setContent('```js\nconst x = 1;\n```');
    const el = fixture.nativeElement as HTMLElement;
    const code = el.querySelector('pre code');
    expect(code).toBeTruthy();
    expect(code!.textContent).toContain('const x = 1;');
    // highlight.js marks up processed blocks with the hljs class.
    expect(code!.className).toContain('hljs');
  });

  it('re-renders when content changes (streaming-safe)', () => {
    setContent('first');
    expect(hostHtml()).toContain('first');

    setContent('first and second');
    expect(hostHtml()).toContain('first and second');
  });

  it('handles empty content without throwing', () => {
    expect(() => setContent('')).not.toThrow();
  });

  describe('inert mode', () => {
    const rich =
      '![alt](http://evil/p.png)\n\n[link text](http://evil/)\n\n<img src="http://evil/q.png">\n\n' +
      '<picture><source srcset="http://evil/s.png"><img src="x"></picture>\n\n' +
      '<iframe src="http://evil/"></iframe>\n\n<form action="http://evil/"><input></form>\n\n' +
      '<style>body{display:none}</style>\n\n<video src="http://evil/v.mp4"></video>\n\n' +
      '<p style="color:red">styled</p>';

    function setInert(content: string, inert: boolean): void {
      fixture.componentRef.setInput('inert', inert);
      setContent(content);
    }

    it('defaults to not inert: images and links keep working', () => {
      setContent('![alt](http://x/p.png)\n\n[t](http://x/)');
      expect(hostEl().querySelector('img')?.getAttribute('src')).toBe('http://x/p.png');
      expect(hostEl().querySelector('a')?.getAttribute('href')).toBe('http://x/');
    });

    it('removes img, picture, source, iframe, form, style and video when inert', () => {
      setInert(rich, true);
      for (const tag of ['img', 'picture', 'source', 'iframe', 'form', 'style', 'video']) {
        expect(hostEl().querySelector(tag), tag).toBeNull();
      }
      expect(hostHtml()).not.toContain('evil');
      expect(hostHtml()).not.toContain('display:none');
    });

    it('drops href on links but keeps the link text when inert', () => {
      setInert('[link text](http://evil/)', true);
      const a = hostEl().querySelector('a')!;
      expect(a).not.toBeNull();
      expect(a.hasAttribute('href')).toBe(false);
      expect(a.textContent).toBe('link text');
    });

    it('drops inline style attributes when inert', () => {
      setInert('<p style="color:red">styled</p>', true);
      const p = hostEl().querySelector('p')!;
      expect(p.textContent).toBe('styled');
      expect(p.hasAttribute('style')).toBe(false);
    });

    it('still renders normal markdown and code blocks when inert', () => {
      setInert('# Title\n\n- a\n- b\n\n```js\nconst x = 1;\n```', true);
      expect(hostEl().querySelector('h1')?.textContent).toBe('Title');
      expect(hostEl().querySelectorAll('li')).toHaveLength(2);
      expect(hostEl().querySelector('pre code')?.textContent).toContain('const x = 1;');
    });

    it('is still script-safe when inert', () => {
      setInert('<script>alert(1)</script><a href="javascript:alert(1)">x</a>', true);
      expect(hostHtml().toLowerCase()).not.toContain('<script');
      expect(hostHtml().toLowerCase()).not.toContain('javascript:');
    });

    it('switching inert off again restores images and links on the next render', () => {
      setInert('[t](http://x/) ![a](http://x/p.png)', true);
      expect(hostEl().querySelector('a')!.hasAttribute('href')).toBe(false);
      fixture.componentRef.setInput('inert', false);
      fixture.detectChanges();
      expect(hostEl().querySelector('a')!.getAttribute('href')).toBe('http://x/');
      expect(hostEl().querySelector('img')).not.toBeNull();
    });
  });

  describe('copy button', () => {
    // Keep a handle on whatever navigator.clipboard was before each test so the
    // stubbing (which replaces it, sometimes deletes it) is fully reverted.
    const originalClipboard = (navigator as unknown as { clipboard?: unknown }).clipboard;
    const originalExecCommand = (document as unknown as { execCommand?: unknown }).execCommand;

    function setClipboard(clipboard: unknown): void {
      Object.defineProperty(navigator, 'clipboard', {
        value: clipboard,
        configurable: true,
        writable: true
      });
    }

    afterEach(() => {
      vi.useRealTimers();
      setClipboard(originalClipboard);
      Object.defineProperty(document, 'execCommand', {
        value: originalExecCommand,
        configurable: true,
        writable: true
      });
      vi.restoreAllMocks();
    });

    // AC: every rendered <pre> gets exactly one keyboard-operable button.
    it('adds exactly one <button> copy affordance inside a fenced code block', () => {
      setContent('```js\nconst x = 1;\n```');

      const buttons = copyButtons();
      expect(buttons).toHaveLength(1);

      const button = buttons[0];
      expect(button.tagName).toBe('BUTTON');
      // Positioned inside the <pre>, not loose in the markdown body.
      expect(button.closest('pre')).toBeTruthy();
    });

    // AC: a real, keyboard-operable button with an accessible name.
    it('renders a type="button" with an accessible name', () => {
      setContent('```\nplain\n```');

      const button = copyButtons()[0];
      expect(button.getAttribute('type')).toBe('button');
      // Accessible name via aria-label and/or visible text.
      const accessibleName = button.getAttribute('aria-label') ?? button.textContent ?? '';
      expect(accessibleName.trim().length).toBeGreaterThan(0);
      expect(button.getAttribute('aria-label')).toBe('Copy code');
    });

    // AC: clicking copies the block's code; copied text excludes the button label.
    it('copies the block source on click, never the button label', async () => {
      const writeText = vi.fn().mockResolvedValue(undefined);
      setClipboard({ writeText });

      setContent('```sql\nSELECT * FROM orders;\n```');
      const button = copyButtons()[0];
      button.click();
      await Promise.resolve();

      expect(writeText).toHaveBeenCalledTimes(1);
      const copied = writeText.mock.calls[0][0] as string;
      expect(copied).toContain('SELECT * FROM orders;');
      expect(copied).not.toContain('Copy');
      expect(copied).not.toContain('Copied!');
    });

    // AC: idempotency / correctness across multiple blocks — each copies its own text.
    it('gives each of several blocks its own button that copies only that block', async () => {
      const writeText = vi.fn().mockResolvedValue(undefined);
      setClipboard({ writeText });

      setContent('```js\nfirst();\n```\n\ntext\n\n```js\nsecond();\n```');
      const buttons = copyButtons();
      expect(buttons).toHaveLength(2);

      buttons[0].click();
      await Promise.resolve();
      buttons[1].click();
      await Promise.resolve();

      const firstCopied = writeText.mock.calls[0][0] as string;
      const secondCopied = writeText.mock.calls[1][0] as string;
      expect(firstCopied).toContain('first();');
      expect(firstCopied).not.toContain('second();');
      expect(secondCopied).toContain('second();');
      expect(secondCopied).not.toContain('first();');
    });

    // AC: exactly one button per block, no accumulation as the streamed answer grows.
    it('stays at one button per block across streaming re-renders', () => {
      setContent('```js\nconst x = 1');
      expect(copyButtons()).toHaveLength(1);

      // Simulate the stream growing (same block, more text).
      setContent('```js\nconst x = 1;\nconst y = 2;\n```');
      expect(copyButtons()).toHaveLength(1);

      // A second block arrives.
      setContent('```js\nconst x = 1;\n```\n\n```sql\nSELECT 1;\n```');
      expect(copyButtons()).toHaveLength(2);
    });

    // AC: embedded-browser fallback — execCommand('copy') via hidden textarea.
    it('falls back to document.execCommand(\'copy\') when the Clipboard API is absent', () => {
      setClipboard(undefined);
      const execCommand = vi.fn().mockReturnValue(true);
      Object.defineProperty(document, 'execCommand', {
        value: execCommand,
        configurable: true,
        writable: true
      });

      setContent('```\nfallback-code\n```');
      const button = copyButtons()[0];
      expect(() => button.click()).not.toThrow();

      expect(execCommand).toHaveBeenCalledWith('copy');
      // The block is still rendered — a copy attempt never tears down the render.
      expect(hostEl().querySelector('pre code')?.textContent).toContain('fallback-code');
    });

    // AC: a rejecting Clipboard API still falls back, and never throws into render.
    it('falls back to execCommand when the Clipboard API rejects, without throwing', async () => {
      const writeText = vi.fn().mockRejectedValue(new Error('denied'));
      setClipboard({ writeText });
      const execCommand = vi.fn().mockReturnValue(true);
      Object.defineProperty(document, 'execCommand', {
        value: execCommand,
        configurable: true,
        writable: true
      });

      setContent('```\nrejecting\n```');
      const button = copyButtons()[0];
      button.click();
      // Let the rejected writeText promise settle and the fallback run.
      await Promise.resolve();
      await Promise.resolve();

      expect(writeText).toHaveBeenCalledTimes(1);
      expect(execCommand).toHaveBeenCalledWith('copy');
      expect(hostEl().querySelector('pre code')?.textContent).toContain('rejecting');
    });

    // AC: a copy failure never breaks rendering even if the fallback also fails.
    it('does not throw when both the Clipboard API and execCommand fail', async () => {
      const writeText = vi.fn().mockRejectedValue(new Error('denied'));
      setClipboard({ writeText });
      const execCommand = vi.fn().mockImplementation(() => {
        throw new Error('execCommand blocked');
      });
      Object.defineProperty(document, 'execCommand', {
        value: execCommand,
        configurable: true,
        writable: true
      });

      setContent('```\nboth-fail\n```');
      const button = copyButtons()[0];
      expect(() => button.click()).not.toThrow();
      await Promise.resolve();
      await Promise.resolve();

      expect(hostEl().querySelector('pre code')?.textContent).toContain('both-fail');
    });

    // AC: brief "Copied!" feedback on success, reverting after ~1.5s.
    it('shows "Copied!" feedback on success and reverts after ~1.5s', async () => {
      vi.useFakeTimers();
      const writeText = vi.fn().mockResolvedValue(undefined);
      setClipboard({ writeText });

      setContent('```\nfeedback\n```');
      const button = copyButtons()[0];
      button.click();
      // Drain the click handler's promise chain (await writeText ->
      // showCopiedFeedback) so the "Copied!" state is set, WITHOUT yet running
      // the ~1.5s revert timer.
      await Promise.resolve();
      await Promise.resolve();

      expect(button.textContent).toContain('Copied!');
      expect(button.hasAttribute('data-copied')).toBe(true);

      vi.advanceTimersByTime(1500);
      expect(button.textContent).toContain('Copy');
      expect(button.textContent).not.toContain('Copied!');
      expect(button.hasAttribute('data-copied')).toBe(false);
    });

    // AC: the revert timer must not error if the button was detached (re-render) first.
    it('does not throw when the feedback timer fires after a re-render detached the button', async () => {
      vi.useFakeTimers();
      const writeText = vi.fn().mockResolvedValue(undefined);
      setClipboard({ writeText });

      setContent('```\ndetach\n```');
      const button = copyButtons()[0];
      button.click();
      // Let the feedback run (set "Copied!") but not the pending revert timer.
      await Promise.resolve();
      await Promise.resolve();
      expect(button.textContent).toContain('Copied!');

      // A streaming re-render rebuilds the subtree, detaching the old button.
      setContent('```\ndetach\nmore\n```');
      expect(button.isConnected).toBe(false);

      // The pending ~1.5s revert must be a no-op, not a crash.
      expect(() => vi.advanceTimersByTime(1500)).not.toThrow();
    });

    // AC (no regression): markdown parse + sanitize + highlight still work alongside the button.
    it('still parses, sanitizes and highlights with the copy button present', () => {
      setContent('# Title\n\n```js\nconst x = 1;\n```\n\n<script>alert(1)</script>');

      const el = hostEl();
      expect(el.querySelector('h1')).toBeTruthy();
      const code = el.querySelector('pre code');
      expect(code?.textContent).toContain('const x = 1;');
      expect(code?.className).toContain('hljs');
      expect(hostHtml().toLowerCase()).not.toContain('<script');
      expect(copyButtons()).toHaveLength(1);
    });
  });
});
