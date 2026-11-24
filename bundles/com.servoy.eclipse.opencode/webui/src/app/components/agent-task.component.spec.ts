import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { AgentFile, AgentTaskComponent, isLongTask } from './agent-task.component';

describe('isLongTask', () => {
  it('is false for a short 3-line text', () => {
    expect(isLongTask('one\ntwo\nthree')).toBe(false);
  });

  it('is false for exactly 8 lines and true for 9', () => {
    expect(isLongTask(Array(8).fill('x').join('\n'))).toBe(false);
    expect(isLongTask(Array(9).fill('x').join('\n'))).toBe(true);
  });

  it('is false for exactly 600 chars and true for 601', () => {
    expect(isLongTask('x'.repeat(600))).toBe(false);
    expect(isLongTask('x'.repeat(601))).toBe(true);
  });
});

describe('AgentTaskComponent', () => {
  let fixture: ComponentFixture<AgentTaskComponent>;
  let el: HTMLElement;

  const render = (task: string, files?: AgentFile[]) => {
    fixture.componentRef.setInput('task', task);
    if (files) {
      fixture.componentRef.setInput('files', files);
    }
    fixture.detectChanges();
  };
  const q = <T extends HTMLElement>(sel: string) => el.querySelector(sel) as T | null;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [AgentTaskComponent] }).compileComponents();
    fixture = TestBed.createComponent(AgentTaskComponent);
    el = fixture.nativeElement as HTMLElement;
  });

  it('renders the title and the task text', async () => {
    render('Please review the form');
    await fixture.whenStable();
    expect(q('.title')!.textContent!.trim()).toBe('Task from Orchestrator');
    expect(q('svy-markdown')).not.toBeNull();
    expect(el.textContent).toContain('Please review the form');
  });

  it('shows no toggle and no clamp for a short task', () => {
    render('short');
    expect(q('.toggle')).toBeNull();
    expect(q('.body')!.classList.contains('clamped')).toBe(false);
  });

  it('shows a collapsed Show more toggle for a long task and flips to Show less', () => {
    render('x'.repeat(700));
    const toggle = q<HTMLButtonElement>('.toggle')!;
    expect(toggle.textContent!.trim()).toBe('Show more');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(q('.body')!.classList.contains('clamped')).toBe(true);

    toggle.click();
    fixture.detectChanges();

    expect(q('.toggle')!.textContent!.trim()).toBe('Show less');
    expect(q('.toggle')!.getAttribute('aria-expanded')).toBe('true');
    expect(q('.body')!.classList.contains('clamped')).toBe(false);

    q<HTMLButtonElement>('.toggle')!.click();
    fixture.detectChanges();
    expect(q('.toggle')!.textContent!.trim()).toBe('Show more');
    expect(q('.body')!.classList.contains('clamped')).toBe(true);
  });

  it('always has an empty agent-files slot by default', () => {
    render('short');
    const slot = q('[data-slot="agent-files"]')!;
    expect(slot).not.toBeNull();
    expect(slot.children).toHaveLength(0);
  });

  it('renders one non-interactive chip per file', () => {
    render('short', [{ name: 'a.js' }, { name: 'b.js', path: 'src/b.js' }, { name: 'c.js' }]);
    const chips = Array.from(el.querySelectorAll('[data-slot="agent-files"] .chip')) as HTMLElement[];
    expect(chips.map((c) => c.textContent!.trim())).toEqual(['a.js', 'b.js', 'c.js']);
    expect(chips[1].getAttribute('title')).toBe('src/b.js');
    expect(chips.every((c) => c.tagName === 'SPAN')).toBe(true);
    expect(el.querySelectorAll('[data-slot="agent-files"] button, [data-slot="agent-files"] a')).toHaveLength(0);
  });

  it('does not throw NG0600 when the task changes', () => {
    render('short');
    fixture.componentRef.setInput('task', 'x'.repeat(700));
    expect(() => fixture.detectChanges()).not.toThrow();
    expect(q('.toggle')).not.toBeNull();
  });
});
