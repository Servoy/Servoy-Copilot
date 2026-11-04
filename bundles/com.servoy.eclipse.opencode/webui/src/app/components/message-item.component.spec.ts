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

  it('renders an image attachment part as a thumbnail in the DOM', () => {
    fixture.componentRef.setInput('role', 'user');
    fixture.componentRef.setInput('parts', [
      { type: 'file', mime: 'image/png', filename: 'shot.png', url: 'data:image/png;base64,AAA' } as Part,
      { type: 'text', text: 'what is this?' } as Part
    ]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const img = el.querySelector('img.attachment-image') as HTMLImageElement | null;
    expect(img).toBeTruthy();
    expect(img?.getAttribute('src')).toBe('data:image/png;base64,AAA');
    expect(img?.getAttribute('alt')).toBe('shot.png');
    expect(el.textContent).toContain('what is this?');
  });

  it('renders a non-image attachment part as a named chip', () => {
    fixture.componentRef.setInput('role', 'user');
    fixture.componentRef.setInput('parts', [
      { type: 'file', mime: 'application/pdf', filename: 'spec.pdf', url: 'file:///spec.pdf' } as Part
    ]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('img.attachment-image')).toBeNull();
    expect(el.querySelector('.attachment-chip')?.textContent).toContain('spec.pdf');
  });

  it('keeps a subagent row expandable (its output) AND exposes an open link that emits the child id', () => {
    const sub = {
      type: 'tool',
      tool: 'subagent',
      id: 'sa1',
      state: {
        status: 'completed',
        input: { description: 'Build it', agent: 'Developer' },
        output: '<subagent sessionID="ses_child_1" state="completed">\n## Summary'
      }
    } as Part;

    expect(component.isSubagentLink(sub)).toBe(true);
    // The row still expands/collapses to show the subagent output.
    expect(component.canExpand(sub)).toBe(true);
    expect(component.toolName(sub)).toBe('Subagent');
    expect(component.toolSubtitle(sub)).toBe('Build it');
    expect(component.subagentLabel(sub)).toBe('Build it subagent');

    // The separate "Open subagent" link navigates into the child session.
    let opened: string | null = null;
    component.openSession.subscribe((id) => (opened = id));
    component.openSubagent(sub);
    expect(opened).toBe('ses_child_1');
  });

  it('resolves the subagent child id from input.sessionID when the call continued a child', () => {
    const sub = {
      type: 'tool',
      tool: 'subagent',
      state: { status: 'completed', input: { description: 'Re-review', sessionID: 'ses_child_2' } }
    } as Part;
    expect(component.isSubagentLink(sub)).toBe(true);
    let opened: string | null = null;
    component.openSession.subscribe((id) => (opened = id));
    component.openSubagent(sub);
    expect(opened).toBe('ses_child_2');
  });

  it('a subagent part with no resolvable child id is not treated as a link', () => {
    const sub = {
      type: 'tool',
      tool: 'subagent',
      state: { status: 'running', input: { description: 'pending' } }
    } as Part;
    expect(component.isSubagentLink(sub)).toBe(false);
  });

  it('shows the loaded skill id as the subtitle of a skill tool part', () => {
    const skill = {
      type: 'tool',
      tool: 'skill',
      state: { status: 'completed', input: { id: 'servoy-orchestrator' } }
    } as Part;
    expect(component.toolName(skill)).toBe('Load Skill');
    expect(component.toolSubtitle(skill)).toBe('servoy-orchestrator');
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

  describe('reasoning collapse (SVY-21707)', () => {
    const reasoningPart = (over: Partial<Part> = {}): Part =>
      ({ type: 'reasoning', id: 'r1', text: 'because the model thought hard', ...over } as Part);

    it('renders a non-blank reasoning part collapsed by default: header present, body absent', () => {
      fixture.componentRef.setInput('parts', [reasoningPart()]);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;

      // The one-line "Reasoning" header row is present...
      const row = el.querySelector('.reasoning-row');
      expect(row).toBeTruthy();
      expect(row?.textContent).toContain('Reasoning');
      // ...but the reasoning markdown body is NOT in the DOM while collapsed.
      expect(el.querySelector('.reasoning')).toBeNull();
      expect(el.querySelector('.reasoning svy-markdown')).toBeNull();
      // Caret shows the collapsed glyph.
      expect(el.querySelector('.reasoning-caret')?.textContent?.trim()).toBe('›');
    });

    it('isReasoningExpanded defaults to false for a reasoning part', () => {
      const part = reasoningPart();
      expect(component.isReasoningExpanded(part, 0)).toBe(false);
    });

    it('expands the reasoning body when the header is clicked (caret flips › -> ▾)', () => {
      fixture.componentRef.setInput('parts', [reasoningPart()]);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;

      const row = el.querySelector('.reasoning-row') as HTMLElement;
      expect(row).toBeTruthy();
      row.click();
      fixture.detectChanges();

      // The reasoning markdown body is now rendered.
      const body = el.querySelector('.reasoning');
      expect(body).toBeTruthy();
      expect(body?.querySelector('svy-markdown')).toBeTruthy();
      // Caret flipped to the open glyph.
      expect(el.querySelector('.reasoning-caret')?.textContent?.trim()).toBe('▾');
    });

    it('collapses the reasoning body again on a second header click (body removed)', () => {
      fixture.componentRef.setInput('parts', [reasoningPart()]);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      const row = el.querySelector('.reasoning-row') as HTMLElement;

      row.click();
      fixture.detectChanges();
      expect(el.querySelector('.reasoning')).toBeTruthy();

      row.click();
      fixture.detectChanges();
      expect(el.querySelector('.reasoning')).toBeNull();
      expect(el.querySelector('.reasoning-caret')?.textContent?.trim()).toBe('›');
    });

    it('toggleReasoning flips isReasoningExpanded and is not gated by canExpand', () => {
      const part = reasoningPart();
      expect(component.isReasoningExpanded(part, 0)).toBe(false);
      component.toggleReasoning(part, 0);
      expect(component.isReasoningExpanded(part, 0)).toBe(true);
      component.toggleReasoning(part, 0);
      expect(component.isReasoningExpanded(part, 0)).toBe(false);
    });

    it('drops blank/placeholder reasoning ("...") entirely: no row, not renderable', () => {
      fixture.componentRef.setInput('parts', [
        reasoningPart({ id: 'r-blank', text: '...' }),
        reasoningPart({ id: 'r-ellipsis', text: '…' }),
        reasoningPart({ id: 'r-ws', text: '   ' })
      ]);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;

      expect(component.renderable()).toHaveLength(0);
      expect(el.querySelector('.reasoning-row')).toBeNull();
      expect(el.querySelector('.reasoning')).toBeNull();
    });

    it('keeps the reasoning part collapsed independently of turn completion', () => {
      // A completed turn still shows the reasoning collapsed (no auto-expand).
      fixture.componentRef.setInput('parts', [reasoningPart()]);
      fixture.componentRef.setInput('completed', 123);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('.reasoning-row')).toBeTruthy();
      expect(el.querySelector('.reasoning')).toBeNull();
      expect(el.querySelector('.reasoning svy-markdown')).toBeNull();
    });

    it('renders a still-streaming reasoning part (completed=null) collapsed by default', () => {
      // A reasoning part that has arrived but whose turn is still running
      // (message-level completed is null). The spec's §3.2 decision is that
      // reasoning is collapsed by default throughout streaming, with no
      // auto-expand while it streams.
      fixture.componentRef.setInput('role', 'assistant');
      fixture.componentRef.setInput('parts', [reasoningPart({ text: 'starting to think' })]);
      fixture.componentRef.setInput('completed', null);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;

      // The "Reasoning" header row is present while it streams...
      const row = el.querySelector('.reasoning-row');
      expect(row).toBeTruthy();
      expect(row?.textContent).toContain('Reasoning');
      // ...but the markdown body is NOT auto-expanded while streaming.
      expect(el.querySelector('.reasoning')).toBeNull();
      expect(el.querySelector('.reasoning svy-markdown')).toBeNull();
      expect(el.querySelector('.reasoning-caret')?.textContent?.trim()).toBe('›');
    });

    it('keeps a user-expanded reasoning part expanded as a streaming delta appends (completed=null)', () => {
      // User expands reasoning mid-stream, then more reasoning text streams in.
      // Because expand state is keyed by the stable part id, the body stays
      // expanded across the delta - no auto-collapse on new text.
      fixture.componentRef.setInput('role', 'assistant');
      fixture.componentRef.setInput('parts', [reasoningPart({ text: 'step one' })]);
      fixture.componentRef.setInput('completed', null);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;

      // User expands it mid-stream.
      const row = el.querySelector('.reasoning-row') as HTMLElement;
      row.click();
      fixture.detectChanges();
      expect(el.querySelector('.reasoning')).toBeTruthy();
      expect(el.querySelector('.reasoning svy-markdown')).toBeTruthy();

      // A delta arrives: the SAME part id, longer text, still streaming.
      fixture.componentRef.setInput('parts', [reasoningPart({ text: 'step one, then step two' })]);
      fixture.detectChanges();

      // The body is STILL expanded after the delta (expand survives deltas).
      expect(el.querySelector('.reasoning')).toBeTruthy();
      expect(el.querySelector('.reasoning svy-markdown')).toBeTruthy();
      expect(el.querySelector('.reasoning-caret')?.textContent?.trim()).toBe('▾');
    });
  });
});
