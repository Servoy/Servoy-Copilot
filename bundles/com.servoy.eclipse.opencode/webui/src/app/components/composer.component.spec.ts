import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ComposerComponent } from './composer.component';
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

  function keydown(key: string, shiftKey = false): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key, shiftKey });
    vi.spyOn(event, 'preventDefault');
    component.onKeydown(event);
    return event;
  }

  it('Enter (no shift) sends the trimmed text and clears the input', () => {
    const sent: SendPart[][] = [];
    component.sendMessage.subscribe((p) => sent.push(p));
    component.text.set('  hello world  ');

    const event = keydown('Enter');

    expect(event.preventDefault).toHaveBeenCalled();
    expect(sent).toHaveLength(1);
    expect(sent[0]).toEqual([{ type: 'text', text: 'hello world' }]);
    expect(component.text()).toBe('');
  });

  it('Shift+Enter does NOT send and does not prevent default', () => {
    const sent: SendPart[][] = [];
    component.sendMessage.subscribe((p) => sent.push(p));
    component.text.set('line one');

    const event = keydown('Enter', true);

    expect(event.preventDefault).not.toHaveBeenCalled();
    expect(sent).toHaveLength(0);
    expect(component.text()).toBe('line one');
  });

  it('does not send empty or whitespace-only input', () => {
    const sent: SendPart[][] = [];
    component.sendMessage.subscribe((p) => sent.push(p));

    component.text.set('   ');
    component.submit();

    expect(sent).toHaveLength(0);
    expect(component.text()).toBe('   ');
  });

  it('does not send while streaming', () => {
    const sent: SendPart[][] = [];
    component.sendMessage.subscribe((p) => sent.push(p));
    fixture.componentRef.setInput('streaming', true);
    component.text.set('ready');

    component.submit();

    expect(sent).toHaveLength(0);
  });

  it('sends attachments alongside text as file parts', () => {
    const sent: SendPart[][] = [];
    component.sendMessage.subscribe((p) => sent.push(p));
    component.text.set('see file');
    component.attachments.set([
      { filename: 'a.txt', mime: 'text/plain', url: 'a.txt' }
    ]);

    component.submit();

    expect(sent[0]).toEqual([
      { type: 'text', text: 'see file' },
      { type: 'file', filename: 'a.txt', mime: 'text/plain', url: 'a.txt' }
    ]);
    expect(component.attachments()).toEqual([]);
  });

  it('sends an attachment with no text', () => {
    const sent: SendPart[][] = [];
    component.sendMessage.subscribe((p) => sent.push(p));
    component.text.set('');
    component.attachments.set([{ filename: 'a.txt', mime: 'text/plain', url: 'a.txt' }]);

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

  it('removeAttachment removes the chip at the given index', () => {
    component.attachments.set([
      { filename: 'a.txt', mime: 'text/plain', url: 'a.txt' },
      { filename: 'b.txt', mime: 'text/plain', url: 'b.txt' }
    ]);
    component.removeAttachment(0);
    expect(component.attachments().map((a) => a.filename)).toEqual(['b.txt']);
  });

  it('auto-grows the textarea height based on scrollHeight, capped at the max', () => {
    const el = component.textarea().nativeElement;
    Object.defineProperty(el, 'scrollHeight', { value: 120, configurable: true });

    component.onInput();

    expect(el.style.height).toBe('120px');
    expect(el.style.overflowY).toBe('hidden');
  });

  it('caps the textarea height and enables scrolling past the max', () => {
    const el = component.textarea().nativeElement;
    Object.defineProperty(el, 'scrollHeight', { value: 500, configurable: true });

    component.onInput();

    expect(el.style.height).toBe('200px');
    expect(el.style.overflowY).toBe('auto');
  });

  it('resets the height after a successful send', () => {
    const sent: SendPart[][] = [];
    component.sendMessage.subscribe((p) => sent.push(p));
    const el = component.textarea().nativeElement;
    component.text.set('hi');

    component.submit();

    expect(el.style.height).toBe('auto');
    expect(el.style.overflowY).toBe('hidden');
  });
});
