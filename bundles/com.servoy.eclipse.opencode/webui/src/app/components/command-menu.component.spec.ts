import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { SvyCommand } from '../services/svy-commands';
import { CommandMenuComponent } from './command-menu.component';

describe('CommandMenuComponent', () => {
  let fixture: ComponentFixture<CommandMenuComponent>;
  const items: SvyCommand[] = [
    { name: 'svyFixTicket', description: 'Fix a ticket' },
    { name: 'svyOptimize', description: 'Optimize code' }
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [CommandMenuComponent] }).compileComponents();
    fixture = TestBed.createComponent(CommandMenuComponent);
  });

  function options(): HTMLElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('[role="option"]'));
  }

  function render(activeIndex = 0): void {
    fixture.componentRef.setInput('items', items);
    fixture.componentRef.setInput('activeIndex', activeIndex);
    fixture.detectChanges();
  }

  it('renders one option per item with name and description', () => {
    render();
    const rows = options();
    expect(rows).toHaveLength(2);
    expect(rows[0].querySelector('.name')?.textContent).toBe('/svyFixTicket');
    expect(rows[0].querySelector('.desc')?.textContent).toBe('Fix a ticket');
    expect(rows[1].querySelector('.name')?.textContent).toBe('/svyOptimize');
    expect(fixture.nativeElement.querySelector('[role="listbox"]')?.getAttribute('aria-label')).toBe('Commands');
  });

  it('marks only the active option as selected', () => {
    render(1);
    const rows = options();
    expect(rows.map((r) => r.getAttribute('aria-selected'))).toEqual(['false', 'true']);
    expect(rows[1].classList.contains('active')).toBe(true);
    expect(rows[0].classList.contains('active')).toBe(false);
  });

  it('follows activeIndex changes', () => {
    render(0);
    fixture.componentRef.setInput('activeIndex', 1);
    fixture.detectChanges();
    expect(options().map((r) => r.getAttribute('aria-selected'))).toEqual(['false', 'true']);
  });

  it('uses the default and a custom id prefix', () => {
    render();
    expect(options().map((r) => r.id)).toEqual(['svy-cmd-0', 'svy-cmd-1']);
    fixture.componentRef.setInput('idPrefix', 'x');
    fixture.detectChanges();
    expect(options().map((r) => r.id)).toEqual(['x-0', 'x-1']);
  });

  it('emits pick on mousedown and prevents the default', () => {
    render();
    const picked: SvyCommand[] = [];
    fixture.componentInstance.pick.subscribe((c) => picked.push(c));
    const event = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    options()[1].dispatchEvent(event);
    expect(picked).toEqual([items[1]]);
    expect(event.defaultPrevented).toBe(true);
  });

  it('emits hover with the row index on mouseenter', () => {
    render();
    const hovered: number[] = [];
    fixture.componentInstance.hover.subscribe((i) => hovered.push(i));
    options()[1].dispatchEvent(new MouseEvent('mouseenter'));
    expect(hovered).toEqual([1]);
  });

  it('renders the empty state and no options when there are no items', () => {
    fixture.componentRef.setInput('items', []);
    fixture.detectChanges();
    expect(options()).toHaveLength(0);
    const status = fixture.nativeElement.querySelector('[role="status"]');
    expect(status?.textContent).toContain('No command matches - Enter sends it as text.');
  });

  describe('scrollIntoView', () => {
    const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView');

    afterEach(() => {
      if (original) {
        Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', original);
      } else {
        delete (HTMLElement.prototype as unknown as Record<string, unknown>)['scrollIntoView'];
      }
    });

    it('scrolls the newly active option into view when activeIndex changes', () => {
      render(0);
      const spy = vi.fn();
      Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
        value: spy,
        configurable: true,
        writable: true
      });

      fixture.componentRef.setInput('activeIndex', 1);
      fixture.detectChanges();

      expect(spy).toHaveBeenCalledTimes(1);
      expect(spy).toHaveBeenCalledWith({ block: 'nearest' });
      expect(spy.mock.contexts[0]).toBe(options()[1]);
    });

    it('does not throw when scrollIntoView is unavailable', () => {
      render(0);
      Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
        value: undefined,
        configurable: true,
        writable: true
      });

      fixture.componentRef.setInput('activeIndex', 1);
      expect(() => fixture.detectChanges()).not.toThrow();
    });
  });

  it('does not throw when rendering after state changes', () => {
    render();
    fixture.componentRef.setInput('items', []);
    expect(() => fixture.detectChanges()).not.toThrow();
  });
});
