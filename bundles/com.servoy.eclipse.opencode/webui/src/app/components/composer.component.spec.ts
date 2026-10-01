import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ComposerComponent } from './composer.component';
import { ComposerDraft } from '../services/chat-store.service';
import { Attachment } from './attachment-bar.component';
import { SendPart } from '../models/opencode.models';

describe('ComposerComponent', () => {
  let fixture: ComponentFixture<ComposerComponent>;
  let component: ComposerComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ComposerComponent]
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
});
