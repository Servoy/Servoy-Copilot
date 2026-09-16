import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { MessageItemComponent } from './message-item.component';
import { Part } from '../models/opencode.models';

describe('MessageItemComponent', () => {
  let fixture: ComponentFixture<MessageItemComponent>;
  let component: MessageItemComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MessageItemComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(MessageItemComponent);
    component = fixture.componentInstance;
  });

  it('renders only renderable parts, filtering out synthetic and unsupported parts', () => {
    fixture.componentRef.setInput('parts', [
      { type: 'text', text: 'visible' } as Part,
      { type: 'text', text: '<system-reminder>hidden' } as Part,
      { type: 'text', text: 'flagged', synthetic: true } as Part,
      { type: 'reasoning', text: 'thinking' } as Part,
      { type: 'tool', tool: 'read' } as Part,
      { type: 'file', filename: 'a.txt' } as Part,
      { type: 'step-start' } as Part
    ]);

    const rendered = component.renderable();
    expect(rendered).toHaveLength(3);
    expect(rendered.map((p) => p.type)).toEqual(['text', 'reasoning', 'tool']);
    expect(rendered[0].text).toBe('visible');
  });

  it('does not render a message with only synthetic parts', () => {
    fixture.componentRef.setInput('parts', [
      { type: 'text', text: '<system-reminder>x' } as Part,
      { type: 'text', text: 'y', synthetic: true } as Part
    ]);
    expect(component.renderable()).toHaveLength(0);
  });

  it('classifies parts with the exposed helpers', () => {
    const text = { type: 'text', text: 'hi' } as Part;
    const reasoning = { type: 'reasoning', text: 'why' } as Part;
    const tool = { type: 'tool', tool: 'read', state: { status: 'running' } } as Part;

    expect(component.isText(text)).toBe(true);
    expect(component.isReasoning(reasoning)).toBe(true);
    expect(component.isTool(tool)).toBe(true);
    expect(component.toolName(tool)).toBe('Read File');
  });

  it('shows a friendly name and an argument subtitle for a tool part', () => {
    const tool = {
      type: 'tool',
      tool: 'bash',
      state: { status: 'completed', input: { command: 'npm run build' } }
    } as Part;
    expect(component.toolName(tool)).toBe('Shell Command');
    expect(component.toolSubtitle(tool)).toBe('npm run build');
  });

  it('only allows expansion when the tool has output', () => {
    const withOutput = { type: 'tool', tool: 'read', id: 't1', state: { output: 'body' } } as Part;
    const withoutOutput = { type: 'tool', tool: 'read', id: 't2' } as Part;
    expect(component.canExpand(withOutput)).toBe(true);
    expect(component.canExpand(withoutOutput)).toBe(false);
  });

  it('toggles tool part expansion by key (only when expandable)', () => {
    const tool = { type: 'tool', tool: 'read', id: 't1', state: { output: 'body' } } as Part;
    expect(component.isExpanded(tool, 0)).toBe(false);
    component.toggle(tool, 0);
    expect(component.isExpanded(tool, 0)).toBe(true);
    component.toggle(tool, 0);
    expect(component.isExpanded(tool, 0)).toBe(false);
  });

  it('does not expand a tool part with no output', () => {
    const tool = { type: 'tool', tool: 'read', id: 't3' } as Part;
    component.toggle(tool, 0);
    expect(component.isExpanded(tool, 0)).toBe(false);
  });

  it('exposes tool output', () => {
    const tool = { type: 'tool', tool: 'read', state: { output: 'file body' } } as Part;
    expect(component.toolOutput(tool)).toBe('file body');
    expect(component.toolOutput({ type: 'tool' } as Part)).toBe('');
  });

  it('renders the visible text part in the DOM and not the synthetic one', () => {
    fixture.componentRef.setInput('parts', [
      { type: 'text', text: 'shown' } as Part,
      { type: 'text', text: '<system-reminder>secret' } as Part
    ]);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('shown');
    expect(text).not.toContain('secret');
  });

  it('surfaces a friendly message for the "No accounts" error', () => {
    fixture.componentRef.setInput('parts', []);
    fixture.componentRef.setInput('error', { name: 'UnknownError', data: { message: 'No accounts' } });
    expect(component.errorText()).toContain('No AI account is connected');
    expect(component.isEmpty()).toBe(false);

    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('No AI account is connected');
  });

  it('falls back to the error detail or name for other errors', () => {
    fixture.componentRef.setInput('error', { name: 'RateLimit', data: { message: 'slow down' } });
    expect(component.errorText()).toBe('slow down');

    fixture.componentRef.setInput('error', { name: 'RateLimit' });
    expect(component.errorText()).toBe('RateLimit');
  });

  it('shows a thinking indicator while an assistant turn is running (not completed, no parts)', () => {
    fixture.componentRef.setInput('role', 'assistant');
    fixture.componentRef.setInput('parts', []);
    fixture.componentRef.setInput('completed', null);
    expect(component.isThinking()).toBe(true);
    expect(component.isEmpty()).toBe(false);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.thinking')).toBeTruthy();
    expect((el.textContent ?? '')).not.toContain('No response was returned');
  });

  it('shows a muted "no response" notice only once the turn completed with no parts/error', () => {
    fixture.componentRef.setInput('role', 'assistant');
    fixture.componentRef.setInput('parts', []);
    fixture.componentRef.setInput('completed', 123);
    expect(component.isThinking()).toBe(false);
    expect(component.isEmpty()).toBe(true);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('No response was returned');
  });

  it('does not treat a user message as thinking', () => {
    fixture.componentRef.setInput('role', 'user');
    fixture.componentRef.setInput('parts', []);
    fixture.componentRef.setInput('completed', null);
    expect(component.isThinking()).toBe(false);
  });
});
