import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { AgentStatus } from '../services/agent-status';
import { AgentCardComponent } from './agent-card.component';

function agent(overrides: Partial<AgentStatus> = {}): AgentStatus {
  return {
    status: 'done',
    kind: 'new',
    name: 'Developer',
    label: 'Done',
    summary: 'Implemented the thing',
    childSessionId: 'ses_child',
    report: null,
    ...overrides
  };
}

describe('AgentCardComponent', () => {
  let fixture: ComponentFixture<AgentCardComponent>;
  let el: HTMLElement;

  const render = (a: AgentStatus) => {
    fixture.componentRef.setInput('agent', a);
    fixture.detectChanges();
  };
  const q = <T extends HTMLElement>(sel: string) => el.querySelector(sel) as T | null;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [AgentCardComponent] }).compileComponents();
    fixture = TestBed.createComponent(AgentCardComponent);
    el = fixture.nativeElement as HTMLElement;
  });

  describe('status rendering', () => {
    it('renders running with the Running text, running class and a spinner', () => {
      render(agent({ status: 'running', label: 'Running' }));
      const card = q('.agent-card')!;
      expect(q('.agent-status')!.textContent!.trim()).toBe('Running');
      expect(card.classList.contains('running')).toBe(true);
      expect(card.classList.contains('done')).toBe(false);
      expect(card.classList.contains('stopped')).toBe(false);
      expect(q('.spinner')).toBeTruthy();
      expect(q('.dot')).toBeNull();
    });

    it('renders done with the Done text, done class and a dot but no spinner', () => {
      render(agent({ status: 'done', label: 'Done' }));
      const card = q('.agent-card')!;
      expect(q('.agent-status')!.textContent!.trim()).toBe('Done');
      expect(card.classList.contains('done')).toBe(true);
      expect(card.classList.contains('running')).toBe(false);
      expect(card.classList.contains('stopped')).toBe(false);
      expect(q('.spinner')).toBeNull();
      expect(q('.dot')).toBeTruthy();
    });

    it('renders stopped with its label, stopped class and a dot but no spinner', () => {
      render(agent({ status: 'stopped', label: 'Stopped — needs a decision', report: 'INPUT_REQUIRED' }));
      const card = q('.agent-card')!;
      expect(q('.agent-status')!.textContent!.trim()).toBe('Stopped — needs a decision');
      expect(card.classList.contains('stopped')).toBe(true);
      expect(card.classList.contains('running')).toBe(false);
      expect(q('.spinner')).toBeNull();
      expect(q('.dot')).toBeTruthy();
    });

    it('renders the generic Stopped label', () => {
      render(agent({ status: 'stopped', label: 'Stopped' }));
      expect(q('.agent-status')!.textContent!.trim()).toBe('Stopped');
    });

    it('shows no spinner for stopped data that otherwise looks running (summary, no child id)', () => {
      render(agent({ status: 'stopped', label: 'Stopped', summary: 'Reading files', childSessionId: null }));
      expect(q('.spinner')).toBeNull();
    });

    it('marks the spinner and dot aria-hidden', () => {
      render(agent({ status: 'running', label: 'Running' }));
      expect(q('.spinner')!.getAttribute('aria-hidden')).toBe('true');
      render(agent({ status: 'done' }));
      expect(q('.dot')!.getAttribute('aria-hidden')).toBe('true');
    });

    it('swaps the spinner for a dot when the status changes from running to stopped', () => {
      render(agent({ status: 'running', label: 'Running' }));
      expect(q('.spinner')).toBeTruthy();
      render(agent({ status: 'stopped', label: 'Stopped' }));
      expect(q('.spinner')).toBeNull();
      expect(q('.dot')).toBeTruthy();
      expect(q('.agent-card')!.classList.contains('running')).toBe(false);
    });
  });

  describe('content', () => {
    it('shows the agent name', () => {
      render(agent({ name: 'Reviewer' }));
      expect(q('.agent-name')!.textContent!.trim()).toBe('Reviewer');
    });

    it('tags a new agent as "new session"', () => {
      render(agent({ kind: 'new' }));
      expect(q('.agent-tag')!.textContent!.trim()).toBe('new session');
    });

    it('tags a continued agent as "continued"', () => {
      render(agent({ kind: 'continued' }));
      expect(q('.agent-tag')!.textContent!.trim()).toBe('continued');
    });

    it('shows the summary text and holds the full text in the title attribute', () => {
      render(agent({ summary: 'A full one line summary' }));
      const summary = q('.agent-summary')!;
      expect(summary.textContent!.trim()).toBe('A full one line summary');
      expect(summary.getAttribute('title')).toBe('A full one line summary');
    });

    it('puts the summary on its own row below the header so it can wrap to several lines', () => {
      render(agent({ summary: 'A summary' }));
      const head = q('.agent-head')!;
      const summary = q('.agent-summary')!;
      expect(head.contains(summary)).toBe(false);
      expect(head.contains(q('.agent-name'))).toBe(true);
      expect(head.contains(q('.agent-open'))).toBe(true);
      expect(summary.parentElement).toBe(q('.agent-card'));
    });

    it('updates the summary title when the input changes', () => {
      render(agent({ summary: 'first' }));
      render(agent({ summary: 'second' }));
      expect(q('.agent-summary')!.getAttribute('title')).toBe('second');
    });
  });

  describe('accessibility', () => {
    it('is a role=group with an aria-label naming the agent, tag and status label', () => {
      render(agent({ name: 'Developer', kind: 'continued', label: 'Running', status: 'running' }));
      const card = q('.agent-card')!;
      expect(card.getAttribute('role')).toBe('group');
      expect(card.getAttribute('aria-label')).toBe('Developer agent, continued, Running');
    });

    it('uses the new-session tag and the stopped label in the aria-label', () => {
      render(agent({ name: 'Reviewer', kind: 'new', status: 'stopped', label: 'Stopped — needs a decision' }));
      expect(q('.agent-card')!.getAttribute('aria-label')).toBe('Reviewer agent, new session, Stopped — needs a decision');
    });

    it('names the Open button after the agent', () => {
      render(agent({ name: 'Reviewer' }));
      expect(q('.agent-open')!.getAttribute('aria-label')).toBe('Open Reviewer agent session');
    });
  });

  describe('Open button', () => {
    it('is disabled when there is no child id', () => {
      render(agent({ childSessionId: null }));
      expect(q<HTMLButtonElement>('.agent-open')!.disabled).toBe(true);
    });

    it('is enabled when there is a child id', () => {
      render(agent({ childSessionId: 'ses_child' }));
      expect(q<HTMLButtonElement>('.agent-open')!.disabled).toBe(false);
    });

    it('is a non-submitting button labelled Open', () => {
      render(agent());
      const btn = q<HTMLButtonElement>('.agent-open')!;
      expect(btn.type).toBe('button');
      expect(btn.textContent!.trim()).toBe('Open');
    });

    it('emits the child id exactly once per click', () => {
      render(agent({ childSessionId: 'ses_child' }));
      const emitted: string[] = [];
      fixture.componentInstance.open.subscribe((id) => emitted.push(id));
      q<HTMLButtonElement>('.agent-open')!.click();
      expect(emitted).toEqual(['ses_child']);
      q<HTMLButtonElement>('.agent-open')!.click();
      expect(emitted).toEqual(['ses_child', 'ses_child']);
    });

    it('does not emit when disabled', () => {
      render(agent({ childSessionId: null }));
      const emitted: string[] = [];
      fixture.componentInstance.open.subscribe((id) => emitted.push(id));
      q<HTMLButtonElement>('.agent-open')!.click();
      expect(emitted).toEqual([]);
    });

    it('does not emit from onOpen without a child id', () => {
      render(agent({ childSessionId: null }));
      const emitted: string[] = [];
      fixture.componentInstance.open.subscribe((id) => emitted.push(id));
      fixture.componentInstance.onOpen();
      expect(emitted).toEqual([]);
    });

    it('does not emit when the card body is clicked', () => {
      render(agent());
      const emitted: string[] = [];
      fixture.componentInstance.open.subscribe((id) => emitted.push(id));
      q('.agent-card')!.click();
      q('.agent-name')!.click();
      expect(emitted).toEqual([]);
    });

    it('emits the new id after the input changes', () => {
      render(agent({ childSessionId: 'ses_a' }));
      render(agent({ childSessionId: 'ses_b' }));
      const emitted: string[] = [];
      fixture.componentInstance.open.subscribe((id) => emitted.push(id));
      q<HTMLButtonElement>('.agent-open')!.click();
      expect(emitted).toEqual(['ses_b']);
    });

    it('enables once the child id becomes known', () => {
      render(agent({ status: 'running', label: 'Running', childSessionId: null }));
      expect(q<HTMLButtonElement>('.agent-open')!.disabled).toBe(true);
      render(agent({ status: 'running', label: 'Running', childSessionId: 'ses_child' }));
      expect(q<HTMLButtonElement>('.agent-open')!.disabled).toBe(false);
    });
  });

  describe('change detection', () => {
    it('does not throw NG0600 when the input changes between renders', () => {
      render(agent({ status: 'running', label: 'Running' }));
      fixture.componentRef.setInput('agent', agent({ status: 'done' }));
      expect(() => fixture.detectChanges()).not.toThrow();
      fixture.componentRef.setInput('agent', agent({ status: 'stopped', label: 'Stopped', childSessionId: null }));
      expect(() => fixture.detectChanges()).not.toThrow();
    });
  });
});
