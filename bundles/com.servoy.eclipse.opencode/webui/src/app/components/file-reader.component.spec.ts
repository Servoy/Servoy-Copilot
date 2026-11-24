import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { WorkspaceFile } from '../models/opencode.models';
import { FileReaderService, FileReaderState } from '../services/file-reader.service';
import { FileReaderComponent } from './file-reader.component';

const CLOSED: FileReaderState = { open: false, path: null, status: 'loading', file: null };
const wf = (path: string, content: string, name = path.split('/').pop()!): WorkspaceFile => ({
  path,
  name,
  size: content.length,
  modified: 1,
  content
});

describe('FileReaderComponent', () => {
  let fixture: ComponentFixture<FileReaderComponent>;
  let el: HTMLElement;
  let state: ReturnType<typeof signal<FileReaderState>>;
  let reader: { state: ReturnType<typeof signal<FileReaderState>>; close: ReturnType<typeof vi.fn>; retry: ReturnType<typeof vi.fn> };

  const q = <T extends HTMLElement>(sel: string) => el.querySelector(sel) as T | null;
  const show = async (s: FileReaderState) => {
    state.set(s);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  const ready = (file: WorkspaceFile, path = file.path): FileReaderState => ({ open: true, path, status: 'ready', file });
  const key = (k: string, init: KeyboardEventInit = {}) =>
    new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true, ...init });

  beforeEach(async () => {
    state = signal<FileReaderState>(CLOSED);
    reader = { state, close: vi.fn(() => state.set(CLOSED)), retry: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [FileReaderComponent],
      providers: [provideZonelessChangeDetection(), { provide: FileReaderService, useValue: reader }]
    }).compileComponents();
    fixture = TestBed.createComponent(FileReaderComponent);
    el = fixture.nativeElement as HTMLElement;
    document.body.appendChild(el);
    fixture.detectChanges();
  });

  afterEach(() => {
    el.remove();
  });

  it('renders nothing while closed', () => {
    expect(q('.panel')).toBeNull();
    expect(q('.backdrop')).toBeNull();
    expect(el.textContent!.trim()).toBe('');
  });

  describe('header', () => {
    it('shows name, server path, read-only pill and a labelled 40x40-class close button with dialog aria', async () => {
      await show(ready(wf('handoff/t/spec.md', '# Hi')));
      const panel = q('.panel')!;
      expect(panel.getAttribute('role')).toBe('dialog');
      expect(panel.getAttribute('aria-modal')).toBe('true');
      const name = q('#file-reader-name')!;
      expect(panel.getAttribute('aria-labelledby')).toBe('file-reader-name');
      expect(name.textContent!.trim()).toBe('spec.md');
      expect(q('.path')!.textContent!.trim()).toBe('handoff/t/spec.md');
      expect(q('.path')!.getAttribute('title')).toBe('handoff/t/spec.md');
      expect(q('.pill')!.textContent!.trim()).toBe('read-only');
      const close = q<HTMLButtonElement>('button.close')!;
      expect(close.getAttribute('aria-label')).toBe('Close file');
      expect(close.getAttribute('type')).toBe('button');
    });

    it('prefers the server returned path over the requested one', async () => {
      await show(ready(wf('real/path/x.md', 'x'), 'requested.md'));
      expect(q('.path')!.textContent!.trim()).toBe('real/path/x.md');
    });

    it.each(['loading', 'not-found', 'forbidden', 'binary', 'too-large', 'error'] as const)(
      'keeps header name, path and read-only visible in the %s state, derived from the requested path',
      async (status) => {
        await show({ open: true, path: 'dir\\sub\\note.md', status, file: null });
        expect(q('#file-reader-name')!.textContent!.trim()).toBe('note.md');
        expect(q('.path')!.textContent!.trim()).toBe('dir\\sub\\note.md');
        expect(q('.pill')!.textContent!.trim()).toBe('read-only');
        expect(q('button.close')).not.toBeNull();
      }
    );
  });

  describe('closing', () => {
    beforeEach(async () => show(ready(wf('a.md', 'x'))));

    it('closes with the close button', () => {
      q<HTMLButtonElement>('button.close')!.click();
      expect(reader.close).toHaveBeenCalledTimes(1);
    });

    it('closes with a click on the backdrop', () => {
      q('.backdrop')!.click();
      expect(reader.close).toHaveBeenCalledTimes(1);
    });

    it('does not close when clicking inside the panel', () => {
      q('.panel .body')!.click();
      q('.panel .header')!.click();
      expect(reader.close).not.toHaveBeenCalled();
    });

    it('closes on Escape and prevents the default', () => {
      const e = key('Escape');
      document.dispatchEvent(e);
      expect(reader.close).toHaveBeenCalledTimes(1);
      expect(e.defaultPrevented).toBe(true);
    });

    it('ignores other keys', () => {
      document.dispatchEvent(key('Enter'));
      document.dispatchEvent(key('a'));
      expect(reader.close).not.toHaveBeenCalled();
    });

    it('ignores Escape during IME composition', () => {
      const e = key('Escape', { isComposing: true });
      document.dispatchEvent(e);
      expect(reader.close).not.toHaveBeenCalled();
      expect(e.defaultPrevented).toBe(false);
    });

    it('stops Escape in the capture phase so no other handler (document or element level) sees it', () => {
      const bubbleDoc = vi.fn();
      const target = document.createElement('textarea');
      document.body.appendChild(target);
      const onTarget = vi.fn();
      target.addEventListener('keydown', onTarget);
      document.addEventListener('keydown', bubbleDoc);
      try {
        target.dispatchEvent(key('Escape'));
        expect(reader.close).toHaveBeenCalledTimes(1);
        expect(onTarget).not.toHaveBeenCalled();
        expect(bubbleDoc).not.toHaveBeenCalled();
      } finally {
        document.removeEventListener('keydown', bubbleDoc);
        target.remove();
      }
    });

    it('lets Escape through to other handlers when composing', () => {
      const onTarget = vi.fn();
      const target = document.createElement('textarea');
      document.body.appendChild(target);
      target.addEventListener('keydown', onTarget);
      target.dispatchEvent(key('Escape', { isComposing: true }));
      expect(onTarget).toHaveBeenCalledTimes(1);
      target.remove();
    });

    it('removes the document Escape listener once closed, so Escape reaches other handlers again', async () => {
      await show(CLOSED);
      const onTarget = vi.fn();
      const target = document.createElement('textarea');
      document.body.appendChild(target);
      target.addEventListener('keydown', onTarget);
      const e = key('Escape');
      target.dispatchEvent(e);
      expect(onTarget).toHaveBeenCalledTimes(1);
      expect(e.defaultPrevented).toBe(false);
      expect(reader.close).not.toHaveBeenCalled();
      target.remove();
    });

    it('does not accumulate listeners across reopen', async () => {
      await show(CLOSED);
      await show(ready(wf('b.md', 'y')));
      document.dispatchEvent(key('Escape'));
      expect(reader.close).toHaveBeenCalledTimes(1);
    });
  });

  describe('focus', () => {
    it('moves initial focus to the close button', async () => {
      await show(ready(wf('a.md', 'x')));
      expect(document.activeElement).toBe(q('button.close'));
    });

    it('traps Tab on the last focusable, wrapping to the first', async () => {
      await show(ready(wf('a.txt', 'plain')));
      const items = Array.from(el.querySelectorAll<HTMLElement>('.panel button, .panel [tabindex="0"]'));
      const first = items[0];
      const last = items[items.length - 1];
      expect(first).toBe(q('button.close'));
      expect(last).toBe(q('pre.code'));
      last.focus();
      const e = key('Tab');
      last.dispatchEvent(e);
      expect(e.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(first);
    });

    it('traps Shift+Tab on the first focusable, wrapping to the last', async () => {
      await show(ready(wf('a.txt', 'plain')));
      const close = q<HTMLElement>('button.close')!;
      close.focus();
      const e = key('Tab', { shiftKey: true });
      close.dispatchEvent(e);
      expect(e.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(q('pre.code'));
    });

    it('does not interfere with Tab in the middle', async () => {
      await show({ open: true, path: 'a.md', status: 'error', file: null });
      const items = Array.from(el.querySelectorAll<HTMLElement>('.panel button'));
      expect(items.length).toBe(2);
      items[0].focus();
      const e = key('Tab');
      items[0].dispatchEvent(e);
      expect(e.defaultPrevented).toBe(false);
    });

    it('pulls focus back into the panel when it is outside and Tab is pressed', async () => {
      await show(ready(wf('a.txt', 'plain')));
      const outside = document.createElement('button');
      document.body.appendChild(outside);
      outside.focus();
      const e = key('Tab');
      q('.panel')!.dispatchEvent(e);
      expect(e.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(q('button.close'));
      outside.remove();
    });

    it('ignores non-Tab keys on the panel', async () => {
      await show(ready(wf('a.txt', 'plain')));
      const e = key('ArrowDown');
      q('.panel')!.dispatchEvent(e);
      expect(e.defaultPrevented).toBe(false);
    });
  });

  describe('body per state', () => {
    it('shows Loading… with role=status and aria-busy', async () => {
      await show({ open: true, path: 'a.md', status: 'loading', file: null });
      const n = q('.notice')!;
      expect(n.textContent!.trim()).toBe('Loading…');
      expect(n.getAttribute('role')).toBe('status');
      expect(n.getAttribute('aria-busy')).toBe('true');
    });

    it.each([
      ['not-found', 'This file is no longer in the workspace.'],
      ['forbidden', 'This file cannot be opened.'],
      ['binary', 'This file is not a text file and cannot be shown.'],
      ['too-large', 'This file is too large to show here (limit 2 MB).']
    ] as const)('shows the exact %s text in a role=status element without Retry', async (status, message) => {
      await show({ open: true, path: 'a.md', status, file: null });
      const n = q('.notice')!;
      expect(n.textContent!.trim()).toBe(message);
      expect(n.getAttribute('role')).toBe('status');
      expect(n.hasAttribute('aria-busy')).toBe(false);
      expect(q('button.retry')).toBeNull();
    });

    it('shows the error text in a role=alert element with a Retry button that retries', async () => {
      await show({ open: true, path: 'a.md', status: 'error', file: null });
      const n = q('.notice')!;
      expect(n.getAttribute('role')).toBe('alert');
      expect(n.textContent).toContain('The file could not be read.');
      const retry = q<HTMLButtonElement>('button.retry')!;
      expect(retry.textContent!.trim()).toBe('Retry');
      retry.click();
      expect(reader.retry).toHaveBeenCalledTimes(1);
    });

    it('is never empty: every open state renders a notice or content', async () => {
      for (const status of ['loading', 'not-found', 'forbidden', 'binary', 'too-large', 'error'] as const) {
        await show({ open: true, path: 'a.md', status, file: null });
        expect(q('.body')!.textContent!.trim().length, status).toBeGreaterThan(0);
      }
      await show(ready(wf('a.md', 'content')));
      expect(q('.body')!.textContent!.trim().length).toBeGreaterThan(0);
    });
  });

  describe('content', () => {
    it('renders .md through svy-markdown as markdown', async () => {
      await show(ready(wf('a.md', '# Title\n\n**bold**')));
      expect(q('svy-markdown')).not.toBeNull();
      expect(q('pre.code')).toBeNull();
      expect(q('svy-markdown h1')!.textContent).toBe('Title');
      expect(q('svy-markdown strong')!.textContent).toBe('bold');
    });

    it('treats .markdown and upper-case .MD as markdown too', async () => {
      await show(ready(wf('a.markdown', '# T')));
      expect(q('svy-markdown')).not.toBeNull();
      await show(ready(wf('README.MD', '# T')));
      expect(q('svy-markdown')).not.toBeNull();
    });

    it('strips img from markdown and drops href from links (text stays)', async () => {
      await show(ready(wf('a.md', '![x](http://evil/p.png)\n\n[link text](http://evil/)\n\n<img src="http://evil/q.png">')));
      const md = q('svy-markdown')!;
      expect(md.querySelector('img')).toBeNull();
      expect(md.querySelector('a')!.hasAttribute('href')).toBe(false);
      expect(md.textContent).toContain('link text');
    });

    it('renders other files as text in pre > code with tabindex 0', async () => {
      await show(ready(wf('src/a.js', 'const x = 1;\nreturn x;')));
      const pre = q('pre.code')!;
      expect(pre.getAttribute('tabindex')).toBe('0');
      expect(pre.querySelector('code')!.textContent).toBe('const x = 1;\nreturn x;');
      expect(q('svy-markdown')).toBeNull();
    });

    it('never interprets code content as HTML', async () => {
      await show(ready(wf('a.txt', '<img src=x onerror="alert(1)"><script>alert(2)</script><b>bold</b>')));
      const pre = q('pre.code')!;
      expect(pre.querySelector('img')).toBeNull();
      expect(pre.querySelector('script')).toBeNull();
      expect(pre.querySelector('b')).toBeNull();
      expect(pre.querySelector('code')!.textContent).toBe(
        '<img src=x onerror="alert(1)"><script>alert(2)</script><b>bold</b>'
      );
    });

    it('shows an empty text file as an empty pre while the header stays', async () => {
      await show(ready(wf('empty.txt', '')));
      expect(q('pre.code code')!.textContent).toBe('');
      expect(q('#file-reader-name')!.textContent!.trim()).toBe('empty.txt');
    });

    it('updates the content when the state changes, without NG0600', async () => {
      await show(ready(wf('a.txt', 'one')));
      state.set(ready(wf('a.txt', 'two')));
      expect(() => fixture.detectChanges()).not.toThrow();
      expect(q('pre.code code')!.textContent).toBe('two');
    });

    it('removes the whole reader when the state closes', async () => {
      await show(ready(wf('a.txt', 'one')));
      await show(CLOSED);
      expect(q('.panel')).toBeNull();
      expect(q('.backdrop')).toBeNull();
    });
  });
});
