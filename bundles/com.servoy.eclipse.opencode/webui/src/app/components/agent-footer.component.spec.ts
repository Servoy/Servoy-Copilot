import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AgentFooterComponent } from './agent-footer.component';

describe('AgentFooterComponent', () => {
  let fixture: ComponentFixture<AgentFooterComponent>;
  let el: HTMLElement;

  const button = () => el.querySelector('button.open') as HTMLButtonElement | null;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [AgentFooterComponent] }).compileComponents();
    fixture = TestBed.createComponent(AgentFooterComponent);
    el = fixture.nativeElement as HTMLElement;
  });

  it('shows the read-only explanation and has no input control', () => {
    fixture.componentRef.setInput('canOpenParent', true);
    fixture.detectChanges();
    expect(el.textContent).toContain(
      "You're viewing an agent session. Questions and answers go through the Orchestrator."
    );
    expect(el.querySelector('textarea')).toBeNull();
    expect(el.querySelector('input')).toBeNull();
  });

  it('emits openParent when Open parent session is clicked', () => {
    const spy = vi.fn();
    fixture.componentInstance.openParent.subscribe(spy);
    fixture.componentRef.setInput('canOpenParent', true);
    fixture.detectChanges();
    expect(button()!.textContent!.trim()).toBe('Open parent session');
    button()!.click();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('hides the button when the parent cannot be opened (default and explicit false)', () => {
    fixture.detectChanges();
    expect(button()).toBeNull();
    fixture.componentRef.setInput('canOpenParent', false);
    fixture.detectChanges();
    expect(button()).toBeNull();
    expect(el.textContent).toContain("You're viewing an agent session.");
  });

  it('does not throw NG0600 when canOpenParent toggles', () => {
    fixture.componentRef.setInput('canOpenParent', true);
    fixture.detectChanges();
    fixture.componentRef.setInput('canOpenParent', false);
    expect(() => fixture.detectChanges()).not.toThrow();
    expect(button()).toBeNull();
  });
});
