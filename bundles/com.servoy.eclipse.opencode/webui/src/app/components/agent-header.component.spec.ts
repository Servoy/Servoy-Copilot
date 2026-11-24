import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AgentHeaderComponent } from './agent-header.component';

describe('AgentHeaderComponent', () => {
  let fixture: ComponentFixture<AgentHeaderComponent>;
  let el: HTMLElement;

  const render = (inputs: Record<string, unknown>) => {
    for (const [key, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(key, value);
    }
    fixture.detectChanges();
  };
  const q = <T extends HTMLElement>(sel: string) => el.querySelector(sel) as T | null;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [AgentHeaderComponent] }).compileComponents();
    fixture = TestBed.createComponent(AgentHeaderComponent);
    el = fixture.nativeElement as HTMLElement;
  });

  it('shows the parent title, an aria-hidden separator and the agent name', () => {
    render({ parentTitle: 'Main work', parentId: 'p1', agentName: 'reviewer' });
    expect(q('.parent')!.textContent!.trim()).toBe('Main work');
    expect(q('.sep')!.textContent!.trim()).toBe('>');
    expect(q('.sep')!.getAttribute('aria-hidden')).toBe('true');
    expect(q('.agent')!.textContent!.trim()).toBe('reviewer');
  });

  it('shows a read-only pill', () => {
    render({ parentTitle: 'Main work', parentId: 'p1', agentName: 'reviewer' });
    expect(q('.pill')!.textContent!.trim()).toBe('read-only');
  });

  it('puts the parent title in the back button aria-label', () => {
    render({ parentTitle: 'Main work', parentId: 'p1', agentName: 'reviewer' });
    expect(q('button.back')!.getAttribute('aria-label')).toBe('Back to parent session: Main work');
  });

  it('emits back exactly once per click on the back button', () => {
    const spy = vi.fn();
    fixture.componentInstance.back.subscribe(spy);
    render({ parentTitle: 'Main work', parentId: 'p1', agentName: 'reviewer' });
    q<HTMLButtonElement>('button.back')!.click();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('falls back to Parent session when the parent has no title', () => {
    render({ parentTitle: null, parentId: 'p1', agentName: 'reviewer' });
    expect(q('.parent')!.textContent!.trim()).toBe('Parent session');
    expect(q('button.back')!.getAttribute('aria-label')).toBe('Back to parent session');
  });

  it('renders no back button and only the agent name for an orphan (parentId null)', () => {
    render({ parentTitle: null, parentId: null, agentName: 'reviewer' });
    expect(q('button.back')).toBeNull();
    expect(q('.parent')).toBeNull();
    expect(q('.sep')).toBeNull();
    expect(q('.agent')!.textContent!.trim()).toBe('reviewer');
    expect(q('.pill')).not.toBeNull();
  });

  it('does not throw NG0600 when inputs change', () => {
    render({ parentTitle: 'A', parentId: 'p1', agentName: 'x' });
    fixture.componentRef.setInput('agentName', 'y');
    expect(() => fixture.detectChanges()).not.toThrow();
    expect(q('.agent')!.textContent!.trim()).toBe('y');
  });
});
