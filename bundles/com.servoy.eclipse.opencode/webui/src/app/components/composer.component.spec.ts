import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ComposerComponent } from './composer.component';
import { ComposerDraft } from '../services/chat-store.service';
import { Attachment } from './attachment-bar.component';
import { SendPart } from '../models/opencode.models';
import { CommandCatalogService } from '../services/command-catalog.service';
import { SvyCommand } from '../services/svy-commands';

describe('ComposerComponent', () => {
  let fixture: ComponentFixture<ComposerComponent>;
  let component: ComposerComponent;
  const commands = signal<SvyCommand[]>([]);
  const load = vi.fn();

  beforeEach(async () => {
    commands.set([
      { name: 'svyFixTicket', description: 'Fix a ticket' },
      { name: 'svyFixStacktrace', description: 'Fix a stacktrace' },
      { name: 'svyOptimize', description: 'Optimize code' }
    ]);
    load.mockClear();
    await TestBed.configureTestingModule({
      imports: [ComposerComponent],
      providers: [{ provide: CommandCatalogService, useValue: { commands, load } }]
    }).compileComponents();

    fixture = TestBed.createComponent(ComposerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  /** Set the controlled draft input and apply the change. */
  function setDraft(text: string, attachments: Attachment[] = []): void {
    fixture.componentRef.setInput('draft', { text, attachments } satisfies ComposerDraft);
    fixture.detectChanges();
  }

  function keydown(key: string, shiftKey = false): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key, shiftKey });
    vi.spyOn(event, 'preventDefault');
    component.onKeydown(event);
    return event;
  }

  it('Enter (no shift) sends the trimmed text and clears the draft', () => {
    const sent: SendPart[][] = [];
    const drafts: ComposerDraft[] = [];
    component.sendMessage.subscribe((p) => sent.push(p));
    component.draftChange.subscribe((d) => drafts.push(d));
    setDraft('  hello world  ');

    const event = keydown('Enter');

    expect(event.preventDefault).toHaveBeenCalled();
    expect(sent).toHaveLength(1);
    expect(sent[0]).toEqual([{ type: 'text', text: 'hello world' }]);
    // The composer owns no state; it asks the store to clear the draft.
    expect(drafts.at(-1)).toEqual({ text: '', attachments: [] });
  });

  it('Shift+Enter does NOT send and does not prevent default', () => {
    const sent: SendPart[][] = [];
    component.sendMessage.subscribe((p) => sent.push(p));
    setDraft('line one');

    const event = keydown('Enter', true);

    expect(event.preventDefault).not.toHaveBeenCalled();
    expect(sent).toHaveLength(0);
    // The draft is untouched.
    expect(component.text()).toBe('line one');
  });

  it('does not send empty or whitespace-only input', () => {
    const sent: SendPart[][] = [];
    component.sendMessage.subscribe((p) => sent.push(p));

    setDraft('   ');
    component.submit();

    expect(sent).toHaveLength(0);
    expect(component.text()).toBe('   ');
  });

  it('does not send while streaming', () => {
    const sent: SendPart[][] = [];
    component.sendMessage.subscribe((p) => sent.push(p));
    fixture.componentRef.setInput('streaming', true);
    setDraft('ready');

    component.submit();

    expect(sent).toHaveLength(0);
  });

  it('sends attachments alongside text as file parts', () => {
    const sent: SendPart[][] = [];
    const drafts: ComposerDraft[] = [];
    component.sendMessage.subscribe((p) => sent.push(p));
    component.draftChange.subscribe((d) => drafts.push(d));
    setDraft('see file', [{ filename: 'a.txt', mime: 'text/plain', url: 'a.txt' }]);

    component.submit();

    expect(sent[0]).toEqual([
      { type: 'text', text: 'see file' },
      { type: 'file', filename: 'a.txt', mime: 'text/plain', url: 'a.txt' }
    ]);
    expect(drafts.at(-1)).toEqual({ text: '', attachments: [] });
  });

  it('sends an attachment with no text', () => {
    const sent: SendPart[][] = [];
    component.sendMessage.subscribe((p) => sent.push(p));
    setDraft('', [{ filename: 'a.txt', mime: 'text/plain', url: 'a.txt' }]);

    component.submit();

    expect(sent).toHaveLength(1);
    expect(sent[0]).toEqual([
      { type: 'file', filename: 'a.txt', mime: 'text/plain', url: 'a.txt' }
    ]);
  });

  it('the stop button emits stop', () => {
    let stopped = false;
    component.stop.subscribe(() => (stopped = true));
    component.onStop();
    expect(stopped).toBe(true);
  });

  it('canSend is false for an empty or whitespace-only draft', () => {
    setDraft('');
    expect(component.canSend()).toBe(false);
    setDraft('   ');
    expect(component.canSend()).toBe(false);
  });

  it('canSend is true as soon as there is non-blank text', () => {
    setDraft('hi');
    expect(component.canSend()).toBe(true);
  });

  it('canSend is true when there is an attachment even with no text', () => {
    setDraft('', [{ filename: 'a.txt', mime: 'text/plain', url: 'a.txt' }]);
    expect(component.canSend()).toBe(true);
  });

  it('removeAttachment emits a draft with the chip at the given index gone', () => {
    const drafts: ComposerDraft[] = [];
    component.draftChange.subscribe((d) => drafts.push(d));
    setDraft('note', [
      { filename: 'a.txt', mime: 'text/plain', url: 'a.txt' },
      { filename: 'b.txt', mime: 'text/plain', url: 'b.txt' }
    ]);

    component.removeAttachment(0);

    expect(drafts.at(-1)).toEqual({
      text: 'note',
      attachments: [{ filename: 'b.txt', mime: 'text/plain', url: 'b.txt' }]
    });
  });

  it('onInput emits the typed text as a draft change', () => {
    const drafts: ComposerDraft[] = [];
    component.draftChange.subscribe((d) => drafts.push(d));
    setDraft('', [{ filename: 'a.txt', mime: 'text/plain', url: 'a.txt' }]);

    component.onInput('typing…');

    // Keeps the existing attachments, updates the text.
    expect(drafts.at(-1)).toEqual({
      text: 'typing…',
      attachments: [{ filename: 'a.txt', mime: 'text/plain', url: 'a.txt' }]
    });
  });

  it('renders the controlled draft text and attachments', () => {
    setDraft('hello', [{ filename: 'a.txt', mime: 'text/plain', url: 'a.txt' }]);
    expect(component.text()).toBe('hello');
    expect(component.attachments()).toEqual([
      { filename: 'a.txt', mime: 'text/plain', url: 'a.txt' }
    ]);
  });

  it('auto-grows the textarea height based on scrollHeight, capped at the max', () => {
    const el = component.textarea().nativeElement;
    Object.defineProperty(el, 'scrollHeight', { value: 120, configurable: true });

    component.onInput('x');

    expect(el.style.height).toBe('120px');
    expect(el.style.overflowY).toBe('hidden');
  });

  it('caps the textarea height and enables scrolling past the max', () => {
    const el = component.textarea().nativeElement;
    Object.defineProperty(el, 'scrollHeight', { value: 500, configurable: true });

    component.onInput('x');

    expect(el.style.height).toBe('200px');
    expect(el.style.overflowY).toBe('auto');
  });

  it('resets the height after a successful send', () => {
    const sent: SendPart[][] = [];
    component.sendMessage.subscribe((p) => sent.push(p));
    const el = component.textarea().nativeElement;
    setDraft('hi');

    component.submit();

    expect(el.style.height).toBe('auto');
    expect(el.style.overflowY).toBe('hidden');
  });

  describe('command menu', () => {
    const root = (): HTMLElement => fixture.nativeElement;
    const menu = (): HTMLElement | null => root().querySelector('svy-command-menu');
    const optionNames = (): string[] =>
      Array.from(root().querySelectorAll('[role="option"] .name')).map((e) => e.textContent ?? '');
    const textarea = (): HTMLTextAreaElement => component.textarea().nativeElement;

    it('opens for "/" and lists all svy commands', () => {
      setDraft('/');
      expect(menu()).not.toBeNull();
      expect(optionNames()).toEqual(['/svyFixTicket', '/svyFixStacktrace', '/svyOptimize']);
    });

    it('filters by case-insensitive substring', () => {
      setDraft('/FIX');
      expect(optionNames()).toEqual(['/svyFixTicket', '/svyFixStacktrace']);
    });

    it('closes once a space is typed', () => {
      setDraft('/svyFixTicket ');
      expect(menu()).toBeNull();
    });

    it('does not open for a slash that is not the first character', () => {
      setDraft('a /x');
      expect(menu()).toBeNull();
      setDraft('hello/');
      expect(menu()).toBeNull();
    });

    it('never opens when disabled', () => {
      fixture.componentRef.setInput('disabled', true);
      setDraft('/');
      expect(menu()).toBeNull();
    });

    it('ArrowDown and ArrowUp move the active item with wrap-around and prevent default', () => {
      setDraft('/');
      expect(keydown('ArrowDown').preventDefault).toHaveBeenCalled();
      expect(component.activeIndex()).toBe(1);
      keydown('ArrowDown');
      keydown('ArrowDown');
      expect(component.activeIndex()).toBe(0);
      const up = keydown('ArrowUp');
      expect(up.preventDefault).toHaveBeenCalled();
      expect(component.activeIndex()).toBe(2);
    });

    it('aria-activedescendant tracks the active item', () => {
      setDraft('/');
      expect(textarea().getAttribute('aria-activedescendant')).toBe('svy-cmd-0');
      keydown('ArrowDown');
      fixture.detectChanges();
      expect(textarea().getAttribute('aria-activedescendant')).toBe('svy-cmd-1');
      expect(textarea().getAttribute('aria-expanded')).toBe('true');
      expect(textarea().getAttribute('aria-controls')).toBe('svy-cmd-menu');
      expect(textarea().getAttribute('role')).toBe('combobox');
    });

    it('has no aria-activedescendant or aria-controls when closed', () => {
      setDraft('hello');
      expect(textarea().getAttribute('aria-activedescendant')).toBeNull();
      expect(textarea().getAttribute('aria-controls')).toBeNull();
      expect(textarea().getAttribute('aria-expanded')).toBe('false');
    });

    it('Enter picks the active item and does not send', () => {
      const sent: SendPart[][] = [];
      const drafts: ComposerDraft[] = [];
      component.sendMessage.subscribe((p) => sent.push(p));
      component.draftChange.subscribe((d) => drafts.push(d));
      setDraft('/');
      keydown('ArrowDown');

      const event = keydown('Enter');

      expect(event.preventDefault).toHaveBeenCalled();
      expect(sent).toHaveLength(0);
      expect(drafts.at(-1)).toEqual({ text: '/svyFixStacktrace ', attachments: [] });
    });

    it('Tab picks the active item', () => {
      const drafts: ComposerDraft[] = [];
      component.draftChange.subscribe((d) => drafts.push(d));
      setDraft('/opt');

      const event = keydown('Tab');

      expect(event.preventDefault).toHaveBeenCalled();
      expect(drafts.at(-1)).toEqual({ text: '/svyOptimize ', attachments: [] });
    });

    it('mousedown on an item picks it', () => {
      const sent: SendPart[][] = [];
      const drafts: ComposerDraft[] = [];
      component.sendMessage.subscribe((p) => sent.push(p));
      component.draftChange.subscribe((d) => drafts.push(d));
      setDraft('/');

      const rows = root().querySelectorAll('[role="option"]');
      const event = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
      rows[2].dispatchEvent(event);

      expect(event.defaultPrevented).toBe(true);
      expect(drafts.at(-1)).toEqual({ text: '/svyOptimize ', attachments: [] });
      expect(sent).toHaveLength(0);
    });

    it('hovering an item makes it active', () => {
      setDraft('/');
      root().querySelectorAll('[role="option"]')[2].dispatchEvent(new MouseEvent('mouseenter'));
      fixture.detectChanges();
      expect(component.activeIndex()).toBe(2);
    });

    it('picking keeps the existing attachments', () => {
      const drafts: ComposerDraft[] = [];
      component.draftChange.subscribe((d) => drafts.push(d));
      const att = { filename: 'a.txt', mime: 'text/plain', url: 'a.txt' };
      setDraft('/', [att]);
      keydown('Enter');
      expect(drafts.at(-1)).toEqual({ text: '/svyFixTicket ', attachments: [att] });
    });

    it('Escape closes the menu without changing the text', () => {
      const drafts: ComposerDraft[] = [];
      component.draftChange.subscribe((d) => drafts.push(d));
      setDraft('/');

      const event = keydown('Escape');
      fixture.detectChanges();

      expect(event.preventDefault).toHaveBeenCalled();
      expect(menu()).toBeNull();
      expect(drafts).toHaveLength(0);
      expect(component.text()).toBe('/');
    });

    it('typing again after Escape reopens the menu', () => {
      setDraft('/');
      keydown('Escape');
      component.onInput('/s');
      setDraft('/s');
      expect(menu()).not.toBeNull();
    });

    it('shows the empty state and Enter sends the text as typed', () => {
      const sent: SendPart[][] = [];
      component.sendMessage.subscribe((p) => sent.push(p));
      setDraft('/zzz');

      expect(optionNames()).toEqual([]);
      expect(root().querySelector('[role="status"]')?.textContent).toContain(
        'No command matches - Enter sends it as text.'
      );
      expect(textarea().getAttribute('aria-activedescendant')).toBeNull();

      const down = keydown('ArrowDown');
      expect(down.preventDefault).not.toHaveBeenCalled();
      expect(component.activeIndex()).toBe(0);

      keydown('Enter');
      expect(sent).toEqual([[{ type: 'text', text: '/zzz' }]]);
    });

    it('Escape closes the empty-state menu', () => {
      setDraft('/zzz');
      keydown('Escape');
      fixture.detectChanges();
      expect(menu()).toBeNull();
    });

    it('with the menu closed Enter sends and Shift+Enter does not', () => {
      const sent: SendPart[][] = [];
      component.sendMessage.subscribe((p) => sent.push(p));
      setDraft('hello');

      const shift = keydown('Enter', true);
      expect(shift.preventDefault).not.toHaveBeenCalled();
      expect(sent).toHaveLength(0);

      const enter = keydown('Enter');
      expect(enter.preventDefault).toHaveBeenCalled();
      expect(sent).toHaveLength(1);
    });

    it('Tab is left alone when the menu is closed', () => {
      setDraft('hello');
      const event = keydown('Tab');
      expect(event.preventDefault).not.toHaveBeenCalled();
    });

    it('has no Commands button and shows the slash hint in the placeholder', () => {
      expect(root().querySelector('button[aria-label="Commands"]')).toBeNull();
      expect(textarea().placeholder).toContain('Type "/" for commands');
      fixture.componentRef.setInput('disabled', true);
      fixture.detectChanges();
      expect(textarea().placeholder).toBe('Chatting is disabled for subagent sessions');
    });

    it('loads the catalog on init when a restored draft starts with a slash', () => {
      load.mockClear();
      const f = TestBed.createComponent(ComposerComponent);
      f.componentRef.setInput('draft', { text: '/fix', attachments: [] });
      f.detectChanges();
      expect(load).toHaveBeenCalled();
    });

    it('loads the catalog when the user types a slash, but not for ordinary text', () => {
      component.onInput('hello');
      expect(load).not.toHaveBeenCalled();
      component.onInput('/');
      expect(load).toHaveBeenCalledTimes(1);
    });

    it('closes a typed menu when the draft is swapped to plain text', () => {
      setDraft('/fix');
      expect(menu()).not.toBeNull();
      setDraft('plain');
      expect(menu()).toBeNull();
    });

    it('resets dismissed and activeIndex when the draft is swapped from outside', () => {
      setDraft('/');
      keydown('ArrowDown');
      keydown('Escape');
      expect(component.dismissed()).toBe(true);

      setDraft('/s');

      expect(component.dismissed()).toBe(false);
      expect(component.activeIndex()).toBe(0);
      expect(menu()).not.toBeNull();
    });

    it('closes a typed menu when disabled flips to true', () => {
      setDraft('/');
      expect(menu()).not.toBeNull();
      fixture.componentRef.setInput('disabled', true);
      fixture.detectChanges();
      expect(menu()).toBeNull();
    });

    it('clamps the active index when the list shrinks before Enter', () => {
      const drafts: ComposerDraft[] = [];
      component.draftChange.subscribe((d) => drafts.push(d));
      setDraft('/');
      keydown('ArrowDown');
      keydown('ArrowDown');
      expect(component.activeIndex()).toBe(2);

      commands.set([{ name: 'svyOnly', description: 'only' }]);
      keydown('Enter');

      expect(drafts.at(-1)).toEqual({ text: '/svyOnly ', attachments: [] });
    });

    it('Tab and ArrowUp fall through in the empty-state menu', () => {
      const drafts: ComposerDraft[] = [];
      component.draftChange.subscribe((d) => drafts.push(d));
      setDraft('/zzz');

      const tab = keydown('Tab');
      const up = keydown('ArrowUp');

      expect(tab.preventDefault).not.toHaveBeenCalled();
      expect(up.preventDefault).not.toHaveBeenCalled();
      expect(drafts).toHaveLength(0);
      expect(component.activeIndex()).toBe(0);
    });

    it('does not throw when the catalog arrives after the menu opened', () => {
      commands.set([]);
      setDraft('/');
      expect(optionNames()).toEqual([]);
      commands.set([{ name: 'svyA', description: 'a' }]);
      expect(() => fixture.detectChanges()).not.toThrow();
      expect(optionNames()).toEqual(['/svyA']);
    });
  });

  describe('drag and drop', () => {
    const composerEl = (): HTMLElement => fixture.nativeElement.querySelector('.composer');
    const overlay = (): HTMLElement | null => fixture.nativeElement.querySelector('.drop-overlay');

    function dragEvent(type: string, types: string[], files: File[] = []): Event {
      const event = new Event(type, { bubbles: true, cancelable: true });
      Object.defineProperty(event, 'dataTransfer', { value: { types, files } });
      return event;
    }

    function fire(type: string, types: string[] = ['Files']): void {
      composerEl().dispatchEvent(dragEvent(type, types));
      fixture.detectChanges();
    }

    it('shows the overlay for a Files drag', () => {
      fire('dragenter');
      expect(overlay()).not.toBeNull();
      expect(overlay()?.textContent).toContain('Drop images to attach');
      expect(overlay()?.getAttribute('aria-hidden')).toBe('true');
    });

    it('does not show the overlay for a text drag', () => {
      fire('dragenter', ['text/plain']);
      fire('dragover', ['text/plain']);
      expect(overlay()).toBeNull();
    });

    it('keeps the overlay across nested enter/leave until the last leave', () => {
      fire('dragenter');
      fire('dragenter');
      fire('dragleave');
      expect(overlay()).not.toBeNull();
      fire('dragleave');
      expect(overlay()).toBeNull();
    });

    it('dragover alone shows the overlay for files and prevents default', () => {
      const event = dragEvent('dragover', ['Files']);
      composerEl().dispatchEvent(event);
      fixture.detectChanges();
      expect(event.defaultPrevented).toBe(true);
      expect(overlay()).not.toBeNull();
    });

    it('extra leave events do not drive the depth negative', () => {
      fire('dragleave');
      fire('dragleave');
      fire('dragenter');
      expect(overlay()).not.toBeNull();
      fire('dragleave');
      expect(overlay()).toBeNull();
    });

    it('drop hides the overlay and attaches a non-image file by name', () => {
      const drafts: ComposerDraft[] = [];
      component.draftChange.subscribe((d) => drafts.push(d));
      fire('dragenter');
      const file = new File(['x'], 'notes.txt', { type: 'text/plain' });

      composerEl().dispatchEvent(dragEvent('drop', ['Files'], [file]));
      fixture.detectChanges();

      expect(overlay()).toBeNull();
      expect(drafts.at(-1)?.attachments).toEqual([
        { filename: 'notes.txt', mime: 'text/plain', url: 'notes.txt' }
      ]);
    });

    it('drop attaches an image as a data URL', async () => {
      const drafts: ComposerDraft[] = [];
      component.draftChange.subscribe((d) => drafts.push(d));
      const file = new File(['abc'], 'pic.png', { type: 'image/png' });

      composerEl().dispatchEvent(dragEvent('drop', ['Files'], [file]));

      await vi.waitFor(() => expect(drafts.length).toBeGreaterThan(0));
      const attachment = drafts.at(-1)!.attachments[0];
      expect(attachment.filename).toBe('pic.png');
      expect(attachment.mime).toBe('image/png');
      expect(attachment.url.startsWith('data:image/png')).toBe(true);
    });

    it('resets the drag state when disabled flips to true mid-drag', () => {
      fire('dragenter');
      expect(overlay()).not.toBeNull();

      fixture.componentRef.setInput('disabled', true);
      fixture.detectChanges();
      expect(overlay()).toBeNull();
      expect(component.dragging()).toBe(false);

      fixture.componentRef.setInput('disabled', false);
      fixture.detectChanges();
      fire('dragenter');
      fire('dragleave');
      expect(overlay()).toBeNull();
    });

    it('drop while disabled prevents default but attaches nothing', () => {
      const drafts: ComposerDraft[] = [];
      component.draftChange.subscribe((d) => drafts.push(d));
      fixture.componentRef.setInput('disabled', true);
      fixture.detectChanges();
      const file = new File(['x'], 'notes.txt', { type: 'text/plain' });
      const event = dragEvent('drop', ['Files'], [file]);

      composerEl().dispatchEvent(event);

      expect(event.defaultPrevented).toBe(true);
      expect(drafts).toHaveLength(0);
    });

    it('paste of an image attaches it as a data URL', async () => {
      const drafts: ComposerDraft[] = [];
      component.draftChange.subscribe((d) => drafts.push(d));
      const file = new File(['abc'], 'shot.png', { type: 'image/png' });
      const event = {
        clipboardData: { items: [{ kind: 'file', type: 'image/png', getAsFile: () => file }] }
      } as unknown as ClipboardEvent;

      component.onPaste(event);

      await vi.waitFor(() => expect(drafts.length).toBeGreaterThan(0));
      const attachment = drafts.at(-1)!.attachments[0];
      expect(attachment.filename).toBe('shot.png');
      expect(attachment.url.startsWith('data:image/png')).toBe(true);
    });

    it('paste of plain text attaches nothing', () => {
      const drafts: ComposerDraft[] = [];
      component.draftChange.subscribe((d) => drafts.push(d));
      const event = {
        clipboardData: { items: [{ kind: 'string', type: 'text/plain', getAsFile: () => null }] }
      } as unknown as ClipboardEvent;

      component.onPaste(event);

      expect(drafts).toHaveLength(0);
    });

    it('shows no overlay when disabled', () => {
      fixture.componentRef.setInput('disabled', true);
      fixture.detectChanges();
      fire('dragenter');
      fire('dragover');
      expect(overlay()).toBeNull();
    });

    it('does not throw while dragging', () => {
      fire('dragenter');
      expect(() => fixture.detectChanges()).not.toThrow();
    });
  });

  describe('focusEnd', () => {
    const textarea = () => component.textarea().nativeElement;

    beforeEach(() => {
      document.body.appendChild(fixture.nativeElement);
    });

    afterEach(() => {
      fixture.nativeElement.remove();
    });

    it('focuses the textarea with the caret after the last character of the draft', async () => {
      setDraft('/svyImplement ');
      await fixture.whenStable();
      textarea().setSelectionRange(0, 0);
      expect(document.activeElement).not.toBe(textarea());
      expect(textarea().selectionStart).toBe(0);

      component.focusEnd();
      await fixture.whenStable();

      expect(textarea().value).toBe('/svyImplement ');
      expect(document.activeElement).toBe(textarea());
      expect(textarea().selectionStart).toBe(textarea().value.length);
      expect(textarea().selectionEnd).toBe(textarea().value.length);
    });

    it('puts the caret at the end when the draft is replaced in the same tick as the call', async () => {
      setDraft('old text that is long');
      await fixture.whenStable();
      textarea().setSelectionRange(0, 0);
      const spy = vi.spyOn(textarea(), 'setSelectionRange');

      fixture.componentRef.setInput('draft', { text: '/svyFixTicket ', attachments: [] } satisfies ComposerDraft);
      component.focusEnd();
      fixture.detectChanges();
      await fixture.whenStable();

      expect(textarea().value).toBe('/svyFixTicket ');
      expect(document.activeElement).toBe(textarea());
      expect(textarea().selectionStart).toBe(textarea().value.length);
      expect(textarea().selectionEnd).toBe(textarea().value.length);
      expect(spy).toHaveBeenLastCalledWith('/svyFixTicket '.length, '/svyFixTicket '.length);
    });

    it('recomputes the textarea height for a long multi-line draft, capped at the maximum', async () => {
      Object.defineProperty(textarea(), 'scrollHeight', { configurable: true, get: () => 500 });
      setDraft('a\nb\nc\nd\ne\nf\ng\nh');

      component.focusEnd();
      await fixture.whenStable();

      expect(textarea().style.height).toBe('200px');
      expect(textarea().style.overflowY).toBe('auto');
    });

    it('sizes the textarea to its content below the maximum', async () => {
      Object.defineProperty(textarea(), 'scrollHeight', { configurable: true, get: () => 90 });
      setDraft('a\nb');

      component.focusEnd();
      await fixture.whenStable();

      expect(textarea().style.height).toBe('90px');
      expect(textarea().style.overflowY).toBe('hidden');
    });

    it('emits neither sendMessage nor draftChange', async () => {
      const sent: SendPart[][] = [];
      const drafts: ComposerDraft[] = [];
      component.sendMessage.subscribe((p) => sent.push(p));
      component.draftChange.subscribe((d) => drafts.push(d));
      setDraft('/svyImplement ');

      component.focusEnd();
      await fixture.whenStable();

      expect(sent).toHaveLength(0);
      expect(drafts).toHaveLength(0);
    });
  });

  describe('context ring', () => {
    const ring = (): HTMLButtonElement | null => fixture.nativeElement.querySelector('button.ctx-ring');
    const sendBtn = (): HTMLButtonElement => fixture.nativeElement.querySelector('button.send-btn');

    function setPercent(value: number | null): void {
      fixture.componentRef.setInput('contextPercent', value);
      fixture.detectChanges();
    }

    it('is absent when the percent is null and does not throw', () => {
      expect(() => setPercent(null)).not.toThrow();
      expect(ring()).toBeNull();
      expect(sendBtn()).not.toBeNull();
    });

    it('is present with an accessible name and title when the percent is known', () => {
      expect(() => setPercent(42)).not.toThrow();
      expect(ring()!.getAttribute('aria-label')).toBe('Context 42% used. Open status');
      expect(ring()!.getAttribute('title')).toBe('Context 42% used');
      expect(ring()!.getAttribute('type')).toBe('button');
      expect(ring()!.classList.contains('high')).toBe(false);
    });

    it('hints at a new session or compact from 80 percent, not at 79', () => {
      setPercent(79);
      expect(ring()!.getAttribute('title')).not.toContain('start a new session or compact');
      expect(ring()!.getAttribute('aria-label')).not.toContain('start a new session or compact');
      setPercent(80);
      expect(ring()!.getAttribute('title')).toBe('Context 80% used - start a new session or compact');
      expect(ring()!.getAttribute('aria-label')).toContain('start a new session or compact');
      expect(ring()!.classList.contains('high')).toBe(true);
    });

    it('shows percentages over 100 and caps the ring fill', () => {
      setPercent(103);
      expect(ring()!.getAttribute('title')).toContain('103%');
      expect(component.ringFill()).toBe(100);
      const arc = ring()!.querySelector('.arc')!;
      expect(arc.getAttribute('stroke-dasharray')).toBe('100 100');
    });

    it('emits contextClick when clicked', () => {
      setPercent(10);
      let clicks = 0;
      component.contextClick.subscribe(() => clicks++);
      ring()!.click();
      expect(clicks).toBe(1);
    });

    it('keeps the send button unaffected by the ring', () => {
      const before = sendBtn().outerHTML;
      setPercent(50);
      expect(sendBtn().outerHTML).toBe(before);
      expect(ring()!.nextElementSibling).toBe(sendBtn());
    });
  });
});
