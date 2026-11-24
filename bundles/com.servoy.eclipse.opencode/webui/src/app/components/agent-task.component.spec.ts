import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { FileReaderService } from '../services/file-reader.service';
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

  it('renders one chip button per file that opens the reader', () => {
    render('short', [
      { path: 'a.js', name: 'a.js', caption: 'Changed file' },
      { path: 'src/b.js', name: 'b.js', caption: 'Changed file' },
      { path: 'c.js', name: 'c.js', caption: 'Changed file' }
    ]);
    const chips = Array.from(el.querySelectorAll('[data-slot="agent-files"] button.chip')) as HTMLElement[];
    expect(chips.map((c) => c.querySelector('.name')!.textContent!.trim())).toEqual(['a.js', 'b.js', 'c.js']);
    expect(chips[1].getAttribute('title')).toBe('src/b.js');
    expect(chips.every((c) => c.getAttribute('aria-haspopup') === 'dialog')).toBe(true);
  });

  it('clicking a chip opens the reader with that path and the clicked button as trigger', () => {
    const reader = TestBed.inject(FileReaderService);
    const open = vi.spyOn(reader, 'open').mockImplementation(() => undefined);
    render('short', [
      { path: 'a.js', name: 'a.js', caption: 'Changed file' },
      { path: 'handoff/t/spec.md', name: 'spec.md', caption: 'Spec' }
    ]);
    const chips = Array.from(el.querySelectorAll('[data-slot="agent-files"] button.chip')) as HTMLElement[];
    chips[1].click();
    expect(open).toHaveBeenCalledTimes(1);
    expect(open).toHaveBeenCalledWith('handoff/t/spec.md', chips[1]);
  });

  it('renders the chips small (sm) and with their captions', () => {
    render('short', [{ path: 'handoff/t/review.md', name: 'review.md', caption: 'Review' }]);
    const chip = q('[data-slot="agent-files"] button.chip')!;
    expect(chip.classList.contains('sm')).toBe(true);
    expect(chip.querySelector('.caption')!.textContent!.trim()).toBe('Review');
  });

  it('does not throw NG0600 when the task changes', () => {
    render('short');
    fixture.componentRef.setInput('task', 'x'.repeat(700));
    expect(() => fixture.detectChanges()).not.toThrow();
    expect(q('.toggle')).not.toBeNull();
  });
});
