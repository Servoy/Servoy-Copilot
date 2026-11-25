import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';

import { Part, Session } from '../models/opencode.models';
import { ChildSignals } from '../services/agent-status';
import { ChatMessage, ChatStore } from '../services/chat-store.service';
import { AgentViewComponent } from './agent-view.component';

const msg = (id: string, role: string, parts: Part[], info: Record<string, unknown> = {}): ChatMessage =>
  ({ info: { id, role, ...info }, parts }) as unknown as ChatMessage;
const text = (t: string): Part => ({ type: 'text', text: t }) as Part;
const tool = (status: string, name = 'read'): Part =>
  ({ type: 'tool', tool: name, state: { status } }) as unknown as Part;
const subagentPart = (childId: string): Part =>
  ({
    type: 'tool',
    tool: 'subagent',
    state: { status: 'completed', input: { agent: 'Reviewer' }, metadata: { sessionID: childId } }
  }) as unknown as Part;

describe('AgentViewComponent', () => {
  let fixture: ComponentFixture<AgentViewComponent>;
  let el: HTMLElement;
  let childSignals: ReturnType<typeof signal<ReadonlyMap<string, ChildSignals>>>;

  const render = (inputs: Record<string, unknown>) => {
    for (const [key, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(key, value);
    }
    fixture.detectChanges();
  };
  const q = <T extends HTMLElement>(sel: string) => el.querySelector(sel) as T | null;
  const contentChildren = () =>
    Array.from(q('.content')!.children).map((c) =>
      c.tagName === 'DIV' ? 'div.' + c.className.split(' ')[0] : c.tagName.toLowerCase()
    );

  beforeEach(async () => {
    childSignals = signal<ReadonlyMap<string, ChildSignals>>(new Map());
    await TestBed.configureTestingModule({
      imports: [AgentViewComponent],
      providers: [provideZonelessChangeDetection(), { provide: ChatStore, useValue: { childSignals } }]
    }).compileComponents();
    fixture = TestBed.createComponent(AgentViewComponent);
    el = fixture.nativeElement as HTMLElement;
  });

  const twoRoundMessages = () => [
    msg('u1', 'user', [text('Review the form')]),
    msg('a1', 'assistant', [tool('completed')]),
    msg('u2', 'user', [text('Use option B')]),
    msg('a2', 'assistant', [tool('completed', 'grep'), text('Status: COMPLETED\nFinal report')])
  ];

  it('renders task, round 1, stopped step, divider, round 2 and report in order', () => {
    render({ messages: twoRoundMessages(), session: { id: 'c1' } as Session, child: undefined });
    expect(contentChildren()).toEqual([
      'svy-agent-task',
      'svy-message-item',
      'div.agent-stopped-step',
      'svy-agent-resume-divider',
      'svy-message-item',
      'svy-agent-report'
    ]);
    expect(q('svy-agent-task')!.textContent).toContain('Review the form');
    expect(q('svy-agent-resume-divider')!.textContent).toContain('Use option B');
    expect(q('svy-agent-report')!.textContent).toContain('Final report');
  });

  it('renders no divider and no stopped step for a single round', () => {
    render({
      messages: [msg('u1', 'user', [text('Task')]), msg('a1', 'assistant', [tool('completed')])],
      session: { id: 'c1' } as Session,
      child: { outcome: 'succeeded' }
    });
    expect(q('svy-agent-resume-divider')).toBeNull();
    expect(q('.agent-stopped-step')).toBeNull();
    expect(q('svy-agent-task')).not.toBeNull();
  });

  it('omits the task block when there is no user message', () => {
    render({ messages: [msg('a1', 'assistant', [tool('completed')])], session: { id: 'c1' } as Session });
    expect(q('svy-agent-task')).toBeNull();
  });

  describe('hidden reasoning and the "Thinking..." line', () => {
    const reasoning = (t = 'secret thoughts'): Part => ({ type: 'reasoning', text: t }) as Part;
    const base = (parts: Part[]) => [msg('u1', 'user', [text('Task')]), msg('a1', 'assistant', parts)];
    const session = { id: 'c1' } as Session;

    it('never shows reasoning text or a Reasoning label', () => {
      render({ messages: base([reasoning(), tool('completed'), text('Status: COMPLETED\nDone')]), session, child: undefined });
      expect(el.textContent).not.toContain('secret thoughts');
      expect(el.textContent).not.toContain('Reasoning');
    });

    it('shows "Thinking..." while the child is busy and has only reasoned so far', () => {
      render({ messages: base([reasoning()]), session, child: { busy: true } });
      expect(q('svy-thinking')?.textContent?.trim()).toBe('Thinking...');
      expect(q('.agent-running-step')).toBeNull();
      expect(el.textContent).not.toContain('secret thoughts');
    });

    it('shows "Thinking..." before the child has produced anything', () => {
      render({ messages: [msg('u1', 'user', [text('Task')])], session, child: { busy: true } });
      expect(q('svy-thinking')).not.toBeNull();
    });

    it('does not show next to a running tool (its spinner row already says it works)', () => {
      render({ messages: base([reasoning(), tool('running')]), session, child: { busy: true } });
      expect(q('svy-thinking')).toBeNull();
      expect(q('.agent-running-step')).not.toBeNull();
    });

    it('does not show next to visible text from the child', () => {
      render({ messages: base([tool('completed'), text('partial answer')]), session, child: { busy: true } });
      expect(q('svy-thinking')).toBeNull();
    });

    it('does not show next to a running subagent card', () => {
      childSignals.set(new Map<string, ChildSignals>([['c2', { busy: true }]]));
      render({ messages: base([subagentPart('c2')]), session, child: { busy: true } });
      expect(q('svy-agent-card')).not.toBeNull();
      expect(q('svy-thinking')).toBeNull();
    });

    it('disappears when the child finishes or is not busy', () => {
      render({ messages: base([reasoning()]), session, child: { busy: true } });
      expect(q('svy-thinking')).not.toBeNull();
      render({ child: { busy: false, outcome: 'succeeded' } });
      expect(q('svy-thinking')).toBeNull();
    });

    it('shows only in the last round, not in a stopped earlier round', () => {
      render({
        messages: [
          msg('u1', 'user', [text('Task')]),
          msg('a1', 'assistant', [reasoning()]),
          msg('u2', 'user', [text('Use option B')]),
          msg('a2', 'assistant', [reasoning()])
        ],
        session,
        child: { busy: true }
      });
      expect(el.querySelectorAll('svy-thinking')).toHaveLength(1);
      expect(q('.agent-stopped-step')).not.toBeNull();
    });
  });

  describe('spinner rule', () => {
    const runningMessages = (status: string) => [msg('u1', 'user', [text('Task')]), msg('a1', 'assistant', [tool(status)])];

    it('shows a role=status spinner row only when the child is busy and a tool is running', () => {
      render({ messages: runningMessages('running'), session: { id: 'c1' } as Session, child: { busy: true } });
      const row = q('.agent-running-step')!;
      expect(row).not.toBeNull();
      expect(row.getAttribute('role')).toBe('status');
      expect(row.getAttribute('aria-label')).toMatch(/^Running /);
      expect(row.querySelector('.spinner')).not.toBeNull();
    });

    it('shows no spinner when busy but every tool has completed', () => {
      render({ messages: runningMessages('completed'), session: { id: 'c1' } as Session, child: { busy: true } });
      expect(q('.agent-running-step')).toBeNull();
    });

    it('shows no spinner for a running tool part when the child is not busy', () => {
      render({ messages: runningMessages('running'), session: { id: 'c1' } as Session, child: { busy: false } });
      expect(q('.agent-running-step')).toBeNull();
      render({ child: undefined });
      expect(q('.agent-running-step')).toBeNull();
    });
  });

  it('hands nested agent cards the real childSignals of the store', () => {
    childSignals.set(new Map<string, ChildSignals>([['c2', { busy: true }]]));
    render({
      messages: [msg('u1', 'user', [text('Task')]), msg('a1', 'assistant', [subagentPart('c2')])],
      session: { id: 'c1' } as Session,
      child: { busy: true }
    });
    const card = q('svy-agent-card')!;
    expect(card).not.toBeNull();
    expect(card.querySelector('.agent-status')!.textContent!.trim()).toBe('Running');

    childSignals.set(new Map<string, ChildSignals>([['c2', { outcome: 'failed' }]]));
    fixture.detectChanges();
    expect(q('svy-agent-card .agent-status')!.textContent!.trim()).toBe('Stopped');
  });

  it('bubbles openSession from a nested agent card', () => {
    const spy = vi.fn();
    fixture.componentInstance.openSession.subscribe(spy);
    render({
      messages: [msg('u1', 'user', [text('Task')]), msg('a1', 'assistant', [subagentPart('c2')])],
      session: { id: 'c1' } as Session,
      child: undefined
    });
    q<HTMLButtonElement>('svy-agent-card button.agent-open')!.click();
    expect(spy).toHaveBeenCalledWith('c2');
  });

  describe('file chips', () => {
    const write = (filePath: string, status = 'completed'): Part =>
      ({ type: 'tool', tool: 'write', state: { status, input: { filePath } } }) as unknown as Part;
    const chipNames = () =>
      Array.from(el.querySelectorAll('[data-slot="agent-files"] svy-file-chip button.chip .name')).map((n) =>
        n.textContent!.trim()
      );

    it('shows chips in the task block for files of the own messages (tools and handoff text)', () => {
      render({
        messages: [
          msg('u1', 'user', [text('Task')]),
          msg('a1', 'assistant', [write('src/a.js'), text('Wrote handoff/t/spec.md')])
        ],
        session: { id: 'c1' } as Session
      });
      expect(chipNames()).toEqual(['a.js', 'spec.md']);
      const chips = el.querySelectorAll('[data-slot="agent-files"] button.chip.sm');
      expect(chips).toHaveLength(2);
    });

    it('shows no chip for errored tool calls and no chips without files', () => {
      render({
        messages: [msg('u1', 'user', [text('Task')]), msg('a1', 'assistant', [write('bad.js', 'error')])],
        session: { id: 'c1' } as Session
      });
      expect(chipNames()).toEqual([]);
    });

    it('does not repeat the chips under the individual messages (showFiles=false)', () => {
      render({
        messages: [msg('u1', 'user', [text('Task')]), msg('a1', 'assistant', [write('src/a.js')])],
        session: { id: 'c1' } as Session
      });
      expect(el.querySelectorAll('svy-message-item .files')).toHaveLength(0);
      expect(el.querySelectorAll('svy-file-chip')).toHaveLength(1);
    });

    it('updates the chips reactively when the messages change, without NG0600', () => {
      render({
        messages: [msg('u1', 'user', [text('Task')]), msg('a1', 'assistant', [write('one.js')])],
        session: { id: 'c1' } as Session
      });
      expect(chipNames()).toEqual(['one.js']);
      fixture.componentRef.setInput('messages', [
        msg('u1', 'user', [text('Task')]),
        msg('a1', 'assistant', [write('one.js'), write('two.js')])
      ]);
      expect(() => fixture.detectChanges()).not.toThrow();
      expect(chipNames()).toEqual(['one.js', 'two.js']);
    });
  });

  it('does not throw NG0600 when inputs change', () => {
    render({ messages: twoRoundMessages(), session: { id: 'c1' } as Session, child: { busy: true } });
    fixture.componentRef.setInput('child', { outcome: 'succeeded' });
    fixture.componentRef.setInput('messages', [msg('u1', 'user', [text('Other')])]);
    expect(() => fixture.detectChanges()).not.toThrow();
    expect(q('svy-agent-task')!.textContent).toContain('Other');
  });

  describe('pin to bottom', () => {
    let top: number;

    const setupScroll = () => {
      const scroll = q<HTMLElement>('.scroll')!;
      top = 0;
      Object.defineProperty(scroll, 'scrollTop', { configurable: true, get: () => top, set: (v: number) => (top = v) });
      Object.defineProperty(scroll, 'scrollHeight', { configurable: true, get: () => 1000 });
      Object.defineProperty(scroll, 'clientHeight', { configurable: true, get: () => 100 });
      return scroll;
    };

    const busyMessages = (n: number) => [
      msg('u1', 'user', [text('Task')]),
      ...Array.from({ length: n }, (_, i) => msg('a' + i, 'assistant', [tool('running')]))
    ];

    it('pins to the bottom while busy, stops when the user scrolled up, and re-pins on a session switch', () => {
      render({ messages: busyMessages(1), session: { id: 'c1' } as Session, child: { busy: true } });
      const scroll = setupScroll();

      render({ messages: busyMessages(2) });
      expect(top).toBe(1000);

      top = 0;
      scroll.dispatchEvent(new Event('scroll'));
      render({ messages: busyMessages(3) });
      expect(top).toBe(0);

      render({ session: { id: 'c9' } as Session, messages: busyMessages(1) });
      expect(top).toBe(1000);
    });

    it('does not scroll when the child is not busy', () => {
      render({ messages: busyMessages(1), session: { id: 'c1' } as Session, child: { outcome: 'succeeded' } });
      setupScroll();
      render({ messages: busyMessages(2) });
      expect(top).toBe(0);
    });
  });

  describe('styles use tokens', () => {
    const scss = (name: string) =>
      readFileSync(`src/app/components/${name}.component.scss`, 'utf8');

    it('the orange report block references the accent tokens and no hex colors', () => {
      const css = scss('agent-report');
      expect(css).toContain('--svy-acc-soft2');
      expect(css).toContain('--svy-acc-line');
      expect(css).toContain('--svy-acc-fg');
      expect(css).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    });

    it.each(['agent-view', 'agent-task', 'agent-header', 'agent-footer', 'agent-resume-divider'])(
      '%s has no hard-coded hex colors',
      (name) => {
        expect(scss(name)).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
      }
    );
  });
});
