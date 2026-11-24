import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { AgentViewReport } from '../services/agent-view';
import { AgentReportComponent } from './agent-report.component';

function report(overrides: Partial<AgentViewReport> = {}): AgentViewReport {
  return {
    text: 'Status: COMPLETED\nAll done',
    status: 'done',
    label: 'Done',
    stopped: false,
    needsDecision: false,
    reason: '',
    ...overrides
  };
}

describe('AgentReportComponent', () => {
  let fixture: ComponentFixture<AgentReportComponent>;
  let el: HTMLElement;

  const render = (r: AgentViewReport) => {
    fixture.componentRef.setInput('report', r);
    fixture.detectChanges();
  };
  const q = <T extends HTMLElement>(sel: string) => el.querySelector(sel) as T | null;
  const block = () => q('.block')!;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [AgentReportComponent] }).compileComponents();
    fixture = TestBed.createComponent(AgentReportComponent);
    el = fixture.nativeElement as HTMLElement;
  });

  it('normal report: title Report to Orchestrator, no stopped class, region label', async () => {
    render(report());
    await fixture.whenStable();
    expect(q('.title')!.textContent!.trim()).toBe('Report to Orchestrator');
    expect(block().classList.contains('stopped')).toBe(false);
    expect(block().getAttribute('role')).toBe('region');
    expect(block().getAttribute('aria-label')).toBe('Report to Orchestrator');
    expect(el.textContent).toContain('All done');
    expect(q('.reason')).toBeNull();
  });

  it('needs a decision: orange title and stopped class', () => {
    render(report({ stopped: true, needsDecision: true, status: 'stopped', label: 'Stopped — needs a decision' }));
    expect(q('.title')!.textContent!.trim()).toBe('Returned to Orchestrator - needs a decision');
    expect(block().classList.contains('stopped')).toBe(true);
    expect(block().getAttribute('aria-label')).toBe('Returned to Orchestrator - needs a decision');
    expect(q('.reason')).toBeNull();
  });

  it('other stop: title Stopped, stopped class and the reason shown next to the text', () => {
    render(report({ stopped: true, status: 'stopped', label: 'Stopped', text: 'Status: BLOCKED\nNo access', reason: 'No access to DB' }));
    expect(q('.title')!.textContent!.trim()).toBe('Stopped');
    expect(block().classList.contains('stopped')).toBe(true);
    expect(q('.reason')!.textContent!.trim()).toBe('No access to DB');
  });

  it('empty text shows the reason and no markdown body', () => {
    render(report({ text: '', stopped: true, status: 'stopped', label: 'Stopped', reason: 'Stopped by user' }));
    expect(q('.reason')!.textContent!.trim()).toBe('Stopped by user');
    expect(q('svy-markdown')).toBeNull();
  });

  it('does not throw NG0600 when the report changes', () => {
    render(report());
    fixture.componentRef.setInput('report', report({ stopped: true, text: '', reason: 'x' }));
    expect(() => fixture.detectChanges()).not.toThrow();
    expect(block().classList.contains('stopped')).toBe(true);
  });
});
