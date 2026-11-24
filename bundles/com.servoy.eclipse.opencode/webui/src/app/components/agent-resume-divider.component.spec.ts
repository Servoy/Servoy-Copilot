import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { AgentResumeDividerComponent } from './agent-resume-divider.component';

describe('AgentResumeDividerComponent', () => {
  let fixture: ComponentFixture<AgentResumeDividerComponent>;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [AgentResumeDividerComponent] }).compileComponents();
    fixture = TestBed.createComponent(AgentResumeDividerComponent);
    el = fixture.nativeElement as HTMLElement;
    fixture.componentRef.setInput('answer', 'Use option B');
    fixture.detectChanges();
  });

  it('renders exactly the resumed text with the answer', () => {
    expect(el.querySelector('.text')!.textContent!.trim()).toBe(
      'Resumed by the Orchestrator with your answer: Use option B'
    );
  });

  it('has role separator', () => {
    expect(el.querySelector('.divider')!.getAttribute('role')).toBe('separator');
  });

  it('carries the full answer in the title attribute and a rule on both sides', () => {
    expect(el.querySelector('.text')!.getAttribute('title')).toBe('Use option B');
    expect(el.querySelectorAll('.rule')).toHaveLength(2);
  });

  it('updates when the answer changes', () => {
    fixture.componentRef.setInput('answer', 'Other');
    expect(() => fixture.detectChanges()).not.toThrow();
    expect(el.querySelector('.text')!.textContent!.trim()).toBe(
      'Resumed by the Orchestrator with your answer: Other'
    );
  });
});
