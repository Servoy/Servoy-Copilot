import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Session } from '../models/opencode.models';
import { ChildSignals } from '../services/agent-status';
import { SessionNode } from '../services/chat-store.service';
import { SessionListComponent } from './session-list.component';

const at = (y: number, m: number, d: number, h = 0, min = 0): number => new Date(y, m, d, h, min).getTime();

const node = (id: string, title: string, updated: number, children: Session[] = []): SessionNode => ({
  session: { id, title, time: { updated } } as Session,
  children
});

const child = (id: string, parentID: string, title = '', extra: Record<string, unknown> = {}): Session =>
  ({ id, parentID, title, ...extra }) as Session;

describe('SessionListComponent', () => {
  let fixture: ComponentFixture<SessionListComponent>;
  let component: SessionListComponent;

  const q = <T extends Element>(sel: string): T | null => fixture.nativeElement.querySelector(sel);
  const qa = <T extends Element>(sel: string): T[] => Array.from(fixture.nativeElement.querySelectorAll(sel));
  const text = (): string => fixture.nativeElement.textContent as string;
  const set = (name: string, value: unknown): void => {
    fixture.componentRef.setInput(name, value);
    fixture.detectChanges();
  };
  const rows = (): HTMLElement[] => qa<HTMLElement>('.session:not(.child)');
  const rowFor = (title: string): HTMLElement =>
    rows().find((r) => r.querySelector('.session-title')?.textContent?.trim() === title)!;
  const meta = (row: HTMLElement): string => row.querySelector('.session-time')?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  const type = async (value: string): Promise<void> => {
    const input = q<HTMLInputElement>('.search-input')!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  };

  const baseTree = (): SessionNode[] => [
    node('t', 'Today work', at(2026, 10, 17, 9, 42)),
    node('y', 'Yesterday fix', at(2026, 10, 16, 16, 20)),
    node('w', 'Week item', at(2026, 10, 14, 10, 0)),
    node('o', 'Old Fix thing', at(2026, 10, 10, 8, 0))
  ];

  beforeEach(async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    vi.setSystemTime(new Date(2026, 10, 17, 12, 0));
    await TestBed.configureTestingModule({
      imports: [SessionListComponent],
      providers: [provideZonelessChangeDetection()]
    }).compileComponents();
    fixture = TestBed.createComponent(SessionListComponent);
    component = fixture.componentInstance;
    document.body.appendChild(fixture.nativeElement);
  });

  afterEach(() => {
    fixture.nativeElement.remove();
    vi.useRealTimers();
  });

  const init = async (tree: SessionNode[] = baseTree()): Promise<void> => {
    fixture.componentRef.setInput('tree', tree);
    await settle();
  };

  describe('grouping and times', () => {
    it('renders groups in order with headers and activity times', async () => {
      await init();
      expect(qa('.group-title').map((e) => e.textContent!.trim())).toEqual([
        'Today',
        'Yesterday',
        'This week',
        'Nov 10'
      ]);
      expect(meta(rowFor('Today work'))).toBe('09:42');
      expect(meta(rowFor('Yesterday fix'))).toBe('Nov 16 · 16:20');
      expect(text()).not.toContain('ago');
    });
  });

  describe('search', () => {
    it('filters live, case-insensitively, and hides empty groups', async () => {
      await init();
      await type('FIX');
      expect(rows().map((r) => r.querySelector('.session-title')!.textContent!.trim())).toEqual([
        'Yesterday fix',
        'Old Fix thing'
      ]);
      expect(qa('.group-title').map((e) => e.textContent!.trim())).toEqual(['Yesterday', 'Nov 10']);
    });

    it('shows No sessions match. with role status when nothing matches', async () => {
      await init();
      await type('zzz');
      const msg = q<HTMLElement>('.no-match')!;
      expect(msg.textContent!.trim()).toBe('No sessions match.');
      expect(msg.getAttribute('role')).toBe('status');
      expect(rows()).toHaveLength(0);
    });

    it('has no clear button with an empty term and shows it once typing', async () => {
      await init();
      expect(q('.search-clear')).toBeNull();
      await type('fix');
      expect(q('.search-clear')).not.toBeNull();
    });

    it('the clear button resets the term and focuses the input', async () => {
      await init();
      await type('fix');
      q<HTMLButtonElement>('button[aria-label="Clear search"]')!.click();
      await settle();
      expect(component.searchTerm()).toBe('');
      expect(rows()).toHaveLength(4);
      expect(document.activeElement).toBe(q('.search-input'));
      expect(q('.search-clear')).toBeNull();
    });

    it('Escape resets the term and focuses the input', async () => {
      await init();
      await type('fix');
      q<HTMLInputElement>('.search-input')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      await settle();
      expect(component.searchTerm()).toBe('');
      expect(rows()).toHaveLength(4);
      expect(document.activeElement).toBe(q('.search-input'));
    });

    it('has no search field for an empty tree', async () => {
      await init([]);
      expect(q('.search-input')).toBeNull();
    });
  });

  describe('empty state', () => {
    it('shows the empty text and no match message', async () => {
      await init([]);
      expect(q('.empty-title')!.textContent!.trim()).toBe('No sessions yet');
      expect(q('.empty-hint')!.textContent!.trim()).toBe(
        'Every conversation you start lands here, with the agents that worked on it.'
      );
      expect(q('.no-match')).toBeNull();
    });
  });

  describe('session status', () => {
    const todayRow = () => rowFor('Today work');

    beforeEach(async () => init());

    it('busy shows an aria-hidden spinner and sr-only running text', () => {
      set('busySessionIds', new Set(['t']));
      const spinner = todayRow().querySelector('.spinner')!;
      expect(spinner.getAttribute('aria-hidden')).toBe('true');
      expect(todayRow().querySelector('.sr-only')!.textContent!.trim()).toBe('running');
      expect(todayRow().textContent).not.toContain('needs your answer');
    });

    it('a pending form shows needs your answer without a spinner', () => {
      set('pendingFormSessions', new Set(['t']));
      expect(meta(todayRow())).toContain('needs your answer');
      expect(todayRow().querySelector('.spinner')).toBeNull();
    });

    it('both shows only needs your answer', () => {
      set('busySessionIds', new Set(['t']));
      set('pendingFormSessions', new Set(['t']));
      expect(meta(todayRow())).toContain('needs your answer');
      expect(todayRow().querySelector('.spinner')).toBeNull();
      expect(todayRow().querySelector('.sr-only')).toBeNull();
    });

    it('neither shows no marker and no done text', () => {
      expect(todayRow().querySelector('.spinner')).toBeNull();
      expect(todayRow().textContent).not.toContain('needs your answer');
      expect(todayRow().textContent).not.toContain('done');
      expect(meta(todayRow())).toBe('09:42');
    });

    it('a parent is running when only its child is busy', async () => {
      await init([node('p', 'Parent', at(2026, 10, 17, 9), [child('c', 'p', 'Agent c')])]);
      set('busySessionIds', new Set(['c']));
      expect(rowFor('Parent').querySelector('.spinner')).not.toBeNull();
    });
  });

  describe('agent rows', () => {
    const tree = () => [
      node('p', 'Parent', at(2026, 10, 17, 9), [
        child('c1', 'p', 'First title', { agent: 'developer' }),
        child('c2', 'p', 'Second title'),
        child('c3', 'p', ''),
        child('c4', 'p', 'Fourth')
      ])
    ];
    const agentRows = () => qa<HTMLElement>('.session.child');
    const expand = async () => {
      q<HTMLButtonElement>('.twisty')!.click();
      await settle();
    };

    it('shows spinner + running, done and stopped, and nothing for unseeded signals', async () => {
      await init(tree());
      set(
        'childSignals',
        new Map<string, ChildSignals>([
          ['c1', { busy: true }],
          ['c2', { busy: false, outcome: 'succeeded' }],
          ['c3', { busy: false, outcome: 'failed' }],
          ['c4', {}]
        ])
      );
      await expand();
      const [r1, r2, r3, r4] = agentRows();
      expect(r1.querySelector('.spinner')).not.toBeNull();
      expect(r1.textContent).toContain('running');
      expect(r2.textContent).toContain('done');
      expect(r2.querySelector('.agent-done')).not.toBeNull();
      expect(r3.textContent).toContain('stopped');
      expect(r3.querySelector('.agent-stopped')).not.toBeNull();
      expect(r4.querySelector('.session-time')).toBeNull();
    });

    it('shows the agent name with title and Agent fallbacks', async () => {
      await init(tree());
      await expand();
      expect(agentRows().map((r) => r.querySelector('.session-title')!.textContent!.trim())).toEqual([
        'developer',
        'Second title',
        'Agent',
        'Fourth'
      ]);
    });

    it('the name carries the child title as tooltip', async () => {
      await init(tree());
      await expand();
      expect(agentRows()[0].querySelector('.session-title')!.getAttribute('title')).toBe('First title');
    });
  });

  describe('live updates', () => {
    it('keeps the row DOM node when status inputs change and does not throw NG0600', async () => {
      await init();
      const row = rowFor('Today work');
      set('busySessionIds', new Set(['t']));
      expect(row.querySelector('.spinner')).not.toBeNull();
      set('pendingFormSessions', new Set(['t']));
      expect(row.querySelector('.spinner')).toBeNull();
      expect(meta(row)).toContain('needs your answer');
      set('pendingFormSessions', new Set());
      set('busySessionIds', new Set());
      expect(row.querySelector('.spinner')).toBeNull();
      expect(row.textContent).not.toContain('needs your answer');
      expect(rowFor('Today work')).toBe(row);
      expect(() => fixture.detectChanges()).not.toThrow();
    });

    it('keeps expansion, search term and the rename input across a new tree', async () => {
      const withChild = (title: string, other: string) => [
        node('p', 'Fix parent', at(2026, 10, 17, 9), [child('c', 'p', 'Agent c')]),
        node('o', other, at(2026, 10, 17, 8)),
        node('r', 'Fix rename me', at(2026, 10, 17, 7))
      ].map((n) => (n.session.id === 'p' ? { ...n, session: { ...n.session, title } } : n));
      await init(withChild('Fix parent', 'Other fix'));
      await type('fix');
      q<HTMLButtonElement>('.twisty')!.click();
      await settle();
      expect(qa('.session.child')).toHaveLength(1);

      component.onRename('r');
      await settle();
      const input = q<HTMLInputElement>('.rename-input')!;
      input.value = 'typed';
      input.dispatchEvent(new Event('input'));
      await settle();
      input.focus();

      set('tree', withChild('Fix parent', 'Other fix renamed'));
      await settle();

      expect(component.searchTerm()).toBe('fix');
      expect(qa('.session.child')).toHaveLength(1);
      expect(q('.rename-input')).toBe(input);
      expect(input.value).toBe('typed');
      expect(document.activeElement).toBe(input);
    });
  });

  describe('search and expansion', () => {
    const tree = () => [node('p', 'Parent', at(2026, 10, 17, 9), [child('c', 'p', 'Needle agent')])];

    it('shows the parent expanded when only a child matches and restores on clear', async () => {
      await init(tree());
      expect(qa('.session.child')).toHaveLength(0);
      await type('needle');
      expect(qa('.session.child')).toHaveLength(1);
      expect(component.expandedIds().has('p')).toBe(false);
      q<HTMLButtonElement>('.search-clear')!.click();
      await settle();
      expect(qa('.session.child')).toHaveLength(0);
    });
  });

  describe('expand output', () => {
    const tree = () => [node('p', 'Parent', at(2026, 10, 17, 9), [child('c', 'p', 'Agent')])];

    it('emits on expanding but not on collapsing', async () => {
      await init(tree());
      const emitted: string[] = [];
      component.expand.subscribe((id) => emitted.push(id));
      q<HTMLButtonElement>('.twisty')!.click();
      await settle();
      expect(emitted).toEqual(['p']);
      q<HTMLButtonElement>('.twisty')!.click();
      await settle();
      expect(emitted).toEqual(['p']);
    });

    it('does not emit from the auto-expand effect of an active child', async () => {
      await init(tree());
      const emitted: string[] = [];
      component.expand.subscribe((id) => emitted.push(id));
      set('activeSessionId', 'c');
      await settle();
      expect(component.expandedIds().has('p')).toBe(true);
      expect(qa('.session.child')).toHaveLength(1);
      expect(emitted).toEqual([]);
    });
  });

  describe('twisty chevron', () => {
    const tree = () => [
      node('p', 'Parent', at(2026, 10, 17, 9), [child('c', 'p', 'Agent')]),
      node('q', 'Leaf', at(2026, 10, 17, 8))
    ];
    const twisty = () => q<HTMLButtonElement>('.twisty')!;

    it('renders the shared svy-chevron (aria-hidden svg) instead of a text glyph', async () => {
      await init(tree());
      expect(twisty().querySelector('svy-chevron')).not.toBeNull();
      const svg = twisty().querySelector('svy-chevron svg');
      expect(svg).not.toBeNull();
      expect(svg!.getAttribute('aria-hidden')).toBe('true');
      expect(svg!.querySelector('path')).not.toBeNull();
      expect(twisty().textContent!.trim()).toBe('');
      expect(twisty().textContent).not.toMatch(/[▸▾]/);
    });

    it('turns the shared chevron a quarter turn when the row is expanded', async () => {
      await init(tree());
      expect(twisty().querySelector('svy-chevron svg')!.classList.contains('open')).toBe(false);
      twisty().click();
      await settle();
      expect(twisty().querySelector('svy-chevron svg')!.classList.contains('open')).toBe(true);
    });

    it('keeps aria-expanded and aria-label in step with the expansion', async () => {
      await init(tree());
      expect(twisty().getAttribute('aria-expanded')).toBe('false');
      expect(twisty().getAttribute('aria-label')).toBe('Expand subagents');
      twisty().click();
      await settle();
      expect(twisty().getAttribute('aria-expanded')).toBe('true');
      expect(twisty().getAttribute('aria-label')).toBe('Collapse subagents');
      expect(qa('.session.child')).toHaveLength(1);
    });

    it('only toggles: clicking the chevron does not select the session', async () => {
      await init(tree());
      const selected: string[] = [];
      component.select.subscribe((id) => selected.push(id));
      twisty().click();
      await settle();
      expect(selected).toEqual([]);
    });

    it('keeps the title column aligned: spacer rows and child rows reserve the twisty width', async () => {
      await init(tree());
      twisty().click();
      await settle();
      expect(qa('.twisty-spacer').length).toBeGreaterThanOrEqual(2);
      const parent = rowFor('Parent');
      const leaf = rowFor('Leaf');
      expect(parent.querySelector(':scope > .twisty')).not.toBeNull();
      expect(leaf.querySelector(':scope > .twisty-spacer')).not.toBeNull();
      expect(q('.session.child > .twisty-spacer')).not.toBeNull();
    });

    it('auto-expands when only a child title matches the search', async () => {
      await init(tree());
      await type('agent');
      expect(twisty().getAttribute('aria-expanded')).toBe('true');
      expect(qa('.session.child')).toHaveLength(1);
    });
  });

  describe('unchanged behaviour', () => {
    beforeEach(async () => init());

    it('emits select, create, export and remove', async () => {
      const out: Record<string, unknown[]> = { select: [], create: [], export: [], remove: [] };
      component.select.subscribe((v) => out['select'].push(v));
      component.create.subscribe(() => out['create'].push(true));
      component.export.subscribe((v) => out['export'].push(v));
      component.remove.subscribe((v) => out['remove'].push(v));

      rowFor('Today work').click();
      q<HTMLButtonElement>('.new-session')!.click();
      rowFor('Today work').querySelector<HTMLButtonElement>('.session-menu-btn')!.click();
      await settle();
      qa<HTMLButtonElement>('.menu-item').find((b) => b.textContent!.trim() === 'Export')!.click();
      await settle();
      rowFor('Today work').querySelector<HTMLButtonElement>('.session-menu-btn')!.click();
      await settle();
      qa<HTMLButtonElement>('.menu-item').find((b) => b.textContent!.trim() === 'Delete')!.click();
      await settle();

      expect(out['select']).toEqual(['t']);
      expect(out['create']).toHaveLength(1);
      expect(out['export']).toEqual(['t']);
      expect(out['remove']).toEqual(['t']);
    });

    it('opens the context menu on right-click and on the dots button', async () => {
      rowFor('Today work').dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, clientX: 5, clientY: 5 }));
      await settle();
      expect(q('.context-menu')).not.toBeNull();
      component.closeMenu();
      await settle();
      expect(q('.context-menu')).toBeNull();
      rowFor('Today work').querySelector<HTMLButtonElement>('.session-menu-btn')!.click();
      await settle();
      expect(q('.context-menu')).not.toBeNull();
    });

    const startRename = async (): Promise<HTMLInputElement> => {
      component.onRename('t');
      await settle();
      const input = q<HTMLInputElement>('.rename-input')!;
      input.value = 'New name';
      input.dispatchEvent(new Event('input'));
      await settle();
      return input;
    };

    it('rename commits on Enter', async () => {
      const renames: unknown[] = [];
      component.rename.subscribe((r) => renames.push(r));
      const input = await startRename();
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      await settle();
      expect(renames).toEqual([{ id: 't', title: 'New name' }]);
      expect(q('.rename-input')).toBeNull();
    });

    it('rename cancels on Escape', async () => {
      const renames: unknown[] = [];
      component.rename.subscribe((r) => renames.push(r));
      const input = await startRename();
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await settle();
      expect(renames).toEqual([]);
      expect(q('.rename-input')).toBeNull();
    });

    it('rename commits on blur', async () => {
      const renames: unknown[] = [];
      component.rename.subscribe((r) => renames.push(r));
      const input = await startRename();
      input.dispatchEvent(new Event('blur'));
      await settle();
      expect(renames).toEqual([{ id: 't', title: 'New name' }]);
    });

    it('does not select a session while it is being renamed', async () => {
      const selected: string[] = [];
      component.select.subscribe((id) => selected.push(id));
      component.onRename('t');
      await settle();
      q<HTMLElement>('.session.renaming')!.click();
      expect(selected).toEqual([]);
    });

    it('keeps a renaming session visible when it stops matching the search', async () => {
      await type('old');
      component.onRename('t');
      await settle();
      expect(rows().some((r) => r.querySelector('.rename-input'))).toBe(true);
    });
  });

  describe('midnight rollover', () => {
    it('moves a session from Today to Yesterday after midnight and clears the interval on destroy', async () => {
      await init([node('t', 'Late', at(2026, 10, 17, 23, 30))]);
      expect(qa('.group-title').map((e) => e.textContent!.trim())).toEqual(['Today']);

      vi.setSystemTime(new Date(2026, 10, 18, 0, 5));
      vi.advanceTimersByTime(60_000);
      await settle();

      expect(qa('.group-title').map((e) => e.textContent!.trim())).toEqual(['Yesterday']);
      expect(meta(rowFor('Late'))).toBe('Nov 17 · 23:30');

      fixture.destroy();
      expect(vi.getTimerCount()).toBe(0);
    });
  });

  describe('a11y', () => {
    it('names the input, labels each group by its heading and gives every marker text', async () => {
      await init([
        node('t', 'Today work', at(2026, 10, 17, 9), [child('c', 't', 'Agent', { agent: 'dev' })]),
        node('y', 'Yesterday', at(2026, 10, 16, 9))
      ]);
      set('busySessionIds', new Set(['t']));
      set('pendingFormSessions', new Set(['y']));
      set('childSignals', new Map<string, ChildSignals>([['c', { busy: false, outcome: 'succeeded' }]]));
      q<HTMLButtonElement>('.twisty')!.click();
      await settle();

      expect(q('.search-input')!.getAttribute('aria-label')).toBe('Search sessions');
      for (const section of qa('section.group')) {
        const heading = fixture.nativeElement.querySelector(`#${section.getAttribute('aria-labelledby')}`);
        expect(heading).not.toBeNull();
        expect(section.contains(heading)).toBe(true);
        expect(heading.textContent.trim().length).toBeGreaterThan(0);
      }
      const markers = [...qa('.needs-answer'), ...qa('.sr-only'), ...qa('.agent-done')];
      expect(markers.length).toBe(3);
      for (const m of markers) {
        expect(m.textContent!.trim().length).toBeGreaterThan(0);
      }
      for (const s of qa('.spinner')) {
        expect(s.getAttribute('aria-hidden')).toBe('true');
      }
    });
  });
});
