import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { BehaviorSubject, Subject, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AppComponent } from './app.component';
import { MessageListComponent } from './components/message-list.component';
import { SessionListComponent } from './components/session-list.component';
import { Part, Session } from './models/opencode.models';
import { ChildSignals } from './services/agent-status';
import { ChatMessage, ChatStore, ComposerDraft } from './services/chat-store.service';
import { CommandCatalogService } from './services/command-catalog.service';
import { StatusService } from './services/status.service';
import { SvyCommand } from './services/svy-commands';

describe('AppComponent', () => {
  let fixture: ComponentFixture<AppComponent>;
  let component: AppComponent;
  let draft: ReturnType<typeof signal<ComposerDraft>>;
  let store: Record<string, unknown>;
  const catalogCommands = signal<SvyCommand[]>([]);
  let status: {
    health: ReturnType<typeof vi.fn>;
    mcp: ReturnType<typeof vi.fn>;
    providers: ReturnType<typeof vi.fn>;
    solution: ReturnType<typeof vi.fn>;
    models: ReturnType<typeof vi.fn>;
    usage: ReturnType<typeof vi.fn>;
  };

  function setQuery(query: string): void {
    history.replaceState(null, '', window.location.pathname + query);
  }

  async function setup(query = '', configure?: (s: typeof status) => void): Promise<void> {
    setQuery(query);
    document.documentElement.removeAttribute('data-theme');
    draft = signal<ComposerDraft>({ text: '', attachments: [] });
    catalogCommands.set([
      { name: 'svyImplement', description: 'Implement a feature' },
      { name: 'svyFixTicket', description: 'Fix a ticket' }
    ]);
    store = {
      bootstrap: vi.fn(),
      sessionTree: signal([]),
      activeSessionId: signal<string | null>(null),
      messages: signal([]),
      sessions: signal<Session[]>([]),
      welcomeReady: signal(false),
      hasMoreMessages: signal(false),
      childSignals: signal(new Map()),
      busySessionIds: signal(new Set<string>()),
      pendingFormSessions: signal(new Set<string>()),
      loadSessionReports: vi.fn(),
      error: signal<string | null>(null),
      pendingForm: signal(null),
      streaming: signal(false),
      activeDraft: draft,
      activeIsSubagent: signal(false),
      activeParent: signal<{ id: string; title: string } | null>(null),
      activeTitle: signal(''),
      activeSession: signal<Session | undefined>(undefined),
      activeChildSignals: signal<ChildSignals | undefined>(undefined),
      sessionParentId: vi.fn(() => undefined),
      saveScroll: vi.fn(),
      takeScroll: vi.fn(() => undefined),
      setActiveDraft: vi.fn((d: ComposerDraft) => draft.set(d)),
      openSession: vi.fn(),
      newSession: vi.fn(),
      renameSession: vi.fn(),
      exportSession: vi.fn(),
      deleteSession: vi.fn(),
      send: vi.fn(),
      abort: vi.fn(),
      loadOlderMessages: vi.fn(),
      submitForm: vi.fn(),
      cancelPendingForm: vi.fn()
    };
    status = {
      health: vi.fn(() => of({ healthy: true, version: '2.0.0' })),
      mcp: vi.fn(() => of({})),
      providers: vi.fn(() => of({ connected: ['kiro'], default: {} })),
      solution: vi.fn(() => of('crm_app')),
      models: vi.fn(() => of([{ providerID: 'kiro', id: 'm1', context: 100000 }])),
      usage: vi.fn(() => of({ available: false }))
    };
    configure?.(status);
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: ChatStore, useValue: store },
        { provide: StatusService, useValue: status },
        { provide: CommandCatalogService, useValue: { commands: catalogCommands, load: vi.fn() } }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(AppComponent);
    component = fixture.componentInstance;
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const q = <T extends Element>(sel: string): T | null => fixture.nativeElement.querySelector(sel);
  const themeBtn = () => q<HTMLButtonElement>('.theme-btn')!;
  const statusBtn = () => q<HTMLButtonElement>('.status-btn')!;
  const panel = () => q<HTMLElement>('#status-panel')!;

  async function click(el: HTMLElement): Promise<void> {
    el.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => {
    fixture?.nativeElement.remove();
    document.documentElement.removeAttribute('data-theme');
    history.replaceState(null, '', '/');
  });

  describe('layout', () => {
    beforeEach(async () => setup());

    it('bootstraps the store once', () => {
      expect(store['bootstrap']).toHaveBeenCalledTimes(1);
    });

    it('renders sidebar, chat main and status area by default', () => {
      expect(q('aside.sidebar')).not.toBeNull();
      expect(q('main.chat')).not.toBeNull();
      expect(panel()).not.toBeNull();
      expect(panel().tagName).toBe('ASIDE');
      expect(panel().hidden).toBe(false);
      expect(q('#status-panel svy-status-panel')).not.toBeNull();
    });

    it('has no legacy .topbar', () => {
      expect(q('.topbar')).toBeNull();
    });

    it('has the header slots in main.chat', () => {
      expect(q('main.chat > header.chat-header .header-leading')).not.toBeNull();
      expect(q('main.chat > header.chat-header .header-title')).not.toBeNull();
      expect(q('main.chat > header.chat-header .header-actions')).not.toBeNull();
    });

    it('orders the actions theme button then status button', () => {
      const buttons = Array.from(fixture.nativeElement.querySelectorAll('.header-actions > button')) as HTMLElement[];
      expect(buttons.map((b) => b.className.includes('theme-btn') ? 'theme' : b.className.includes('status-btn') ? 'status' : '?')).toEqual([
        'theme',
        'status'
      ]);
    });

    it('does not throw NG0600 on change detection', () => {
      expect(() => fixture.detectChanges()).not.toThrow();
    });
  });

  describe('status panel toggle', () => {
    beforeEach(async () => setup());

    it('status button is wired to the panel and expanded by default', () => {
      expect(statusBtn().getAttribute('aria-expanded')).toBe('true');
      expect(statusBtn().getAttribute('aria-controls')).toBe('status-panel');
      expect(statusBtn().getAttribute('type')).toBe('button');
      expect(statusBtn().getAttribute('aria-label')).toBe('Status panel');
    });

    it('clicking hides the panel and flips aria-expanded', async () => {
      await click(statusBtn());
      expect(component.statusOpen()).toBe(false);
      expect(panel().hidden).toBe(true);
      expect(statusBtn().getAttribute('aria-expanded')).toBe('false');
    });

    it('keeps the status panel mounted while closed', async () => {
      await click(statusBtn());
      expect(q('#status-panel svy-status-panel')).not.toBeNull();
    });

    it('clicking again re-shows it and refreshes status', async () => {
      const callsBefore = status.health.mock.calls.length;
      await click(statusBtn());
      expect(status.health.mock.calls.length).toBe(callsBefore);
      await click(statusBtn());
      expect(panel().hidden).toBe(false);
      expect(statusBtn().getAttribute('aria-expanded')).toBe('true');
      expect(status.health.mock.calls.length).toBe(callsBefore + 1);
      expect(status.mcp.mock.calls.length).toBe(callsBefore + 1);
      expect(status.providers.mock.calls.length).toBe(callsBefore + 1);
      expect(status.solution.mock.calls.length).toBe(callsBefore + 1);
    });

    it('the panel close button closes it and returns focus to the status button', async () => {
      await click(q<HTMLButtonElement>('#status-panel button.close')!);
      expect(component.statusOpen()).toBe(false);
      expect(panel().hidden).toBe(true);
      expect(statusBtn().getAttribute('aria-expanded')).toBe('false');
      expect(document.activeElement).toBe(statusBtn());
    });

    it('header dot reflects the overall status even while closed', async () => {
      const dot = () => q<HTMLElement>('.status-dot')!;
      expect(dot().classList.contains('ok')).toBe(true);
      await click(statusBtn());
      expect(dot().classList.contains('ok')).toBe(true);
    });

    it('header dot is error-coloured when the server is unhealthy', async () => {
      fixture.nativeElement.remove();
      TestBed.resetTestingModule();
      await setup();
      status.health.mockReturnValue(of({ healthy: false, version: '2.0.0' }));
      component.toggleStatus();
      component.toggleStatus();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      expect(q('.status-dot')!.classList.contains('error')).toBe(true);
      expect(q('.status-dot')!.classList.contains('ok')).toBe(false);
    });
  });

  describe('header status dot states', () => {
    const dot = () => q<HTMLElement>('.status-dot')!;
    const states = () => ['ok', 'warn', 'error'].filter((c) => dot().classList.contains(c));
    const failedMcp = { bad: { status: 'failed' } };

    it('has no state class while health has not resolved (idle)', async () => {
      await setup('', (s) => s.health.mockReturnValue(new Subject()));
      expect(states()).toEqual([]);
    });

    it('is ok when ready', async () => {
      await setup();
      expect(states()).toEqual(['ok']);
    });

    it('is warn when an enabled MCP server is offline', async () => {
      await setup('', (s) => s.mcp.mockReturnValue(of({ good: { status: 'connected' }, ...failedMcp })));
      expect(states()).toEqual(['warn']);
    });

    it('stays ok when the only non-connected MCP server is disabled', async () => {
      await setup('', (s) => s.mcp.mockReturnValue(of({ d: { status: 'disabled' } })));
      expect(states()).toEqual(['ok']);
    });

    it('is error when unhealthy', async () => {
      await setup('', (s) => s.health.mockReturnValue(of({ healthy: false })));
      expect(states()).toEqual(['error']);
    });

    it('is error when there is no provider', async () => {
      await setup('', (s) => s.providers.mockReturnValue(of({ connected: [], default: {} })));
      expect(states()).toEqual(['error']);
    });

    it('is live while the panel is closed, including a state change after closing', async () => {
      const mcp$ = new BehaviorSubject<Record<string, { status: string }>>({});
      await setup('', (s) => s.mcp.mockReturnValue(mcp$));
      expect(states()).toEqual(['ok']);
      await click(statusBtn());
      expect(panel().hidden).toBe(true);
      expect(states()).toEqual(['ok']);
      mcp$.next(failedMcp);
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      expect(panel().hidden).toBe(true);
      expect(states()).toEqual(['warn']);
    });

    it('does not throw NG0600 when the dot state changes', async () => {
      const mcp$ = new BehaviorSubject<Record<string, { status: string }>>({});
      await setup('', (s) => s.mcp.mockReturnValue(mcp$));
      mcp$.next(failedMcp);
      expect(() => fixture.detectChanges()).not.toThrow();
    });
  });

  describe('viewport-dependent default of the status panel', () => {
    const original = Object.getOwnPropertyDescriptor(window, 'matchMedia');

    function mockMatchMedia(matches: boolean): ReturnType<typeof vi.fn> {
      const fn = vi.fn((query: string) => ({ matches, media: query }));
      Object.defineProperty(window, 'matchMedia', { configurable: true, writable: true, value: fn });
      return fn;
    }

    afterEach(() => {
      if (original) {
        Object.defineProperty(window, 'matchMedia', original);
      } else {
        delete (window as unknown as Record<string, unknown>)['matchMedia'];
      }
    });

    it('starts closed on a narrow viewport and keeps the panel mounted but hidden', async () => {
      const mm = mockMatchMedia(false);
      await setup();
      expect(mm).toHaveBeenCalledWith('(min-width: 1080px)');
      expect(component.statusOpen()).toBe(false);
      expect(panel().hidden).toBe(true);
      expect(q('#status-panel svy-status-panel')).not.toBeNull();
      expect(statusBtn().getAttribute('aria-expanded')).toBe('false');
    });

    it('opens the panel from the header button on a narrow viewport and refreshes it', async () => {
      mockMatchMedia(false);
      await setup();
      const callsBefore = status.health.mock.calls.length;
      await click(statusBtn());
      expect(component.statusOpen()).toBe(true);
      expect(panel().hidden).toBe(false);
      expect(statusBtn().getAttribute('aria-expanded')).toBe('true');
      expect(status.health.mock.calls.length).toBe(callsBefore + 1);
    });

    it('starts open on a wide viewport', async () => {
      mockMatchMedia(true);
      await setup();
      expect(component.statusOpen()).toBe(true);
      expect(panel().hidden).toBe(false);
    });
  });

  describe('sessions area', () => {
    const original = Object.getOwnPropertyDescriptor(window, 'matchMedia');
    const sessions = () => q<HTMLElement>('#sessions-panel')!;
    const sessionsBtn = () => q<HTMLButtonElement>('.sessions-btn')!;

    function mockWidth(width: number): void {
      const fn = vi.fn((query: string) => {
        const min = /min-width:\s*(\d+)px/.exec(query);
        return { matches: min ? width >= Number(min[1]) : false, media: query };
      });
      Object.defineProperty(window, 'matchMedia', { configurable: true, writable: true, value: fn });
    }

    afterEach(() => {
      if (original) {
        Object.defineProperty(window, 'matchMedia', original);
      } else {
        delete (window as unknown as Record<string, unknown>)['matchMedia'];
      }
    });

    it('is docked and open from 800px and wired to its header button', async () => {
      mockWidth(1024);
      await setup();
      expect(component.sessionsOpen()).toBe(true);
      expect(sessions().hidden).toBe(false);
      expect(sessionsBtn().getAttribute('aria-controls')).toBe('sessions-panel');
      expect(sessionsBtn().getAttribute('aria-expanded')).toBe('true');
      expect(sessionsBtn().getAttribute('type')).toBe('button');
    });

    it('can be collapsed and reopened with the header button without destroying the session list', async () => {
      mockWidth(1024);
      await setup();
      const list = q('svy-session-list');
      await click(sessionsBtn());
      expect(sessions().hidden).toBe(true);
      expect(sessionsBtn().getAttribute('aria-expanded')).toBe('false');
      expect(q('svy-session-list')).toBe(list);
      await click(sessionsBtn());
      expect(sessions().hidden).toBe(false);
      expect(sessionsBtn().getAttribute('aria-expanded')).toBe('true');
    });

    it('binds the sidebar status inputs from the store and forwards (expand) to loadSessionReports', async () => {
      mockWidth(1024);
      await setup();
      const busy = new Set(['s1']);
      const pendingSet = new Set(['s2']);
      const signals = new Map<string, ChildSignals>([['c1', { busy: true }]]);
      (store['busySessionIds'] as ReturnType<typeof signal<Set<string>>>).set(busy);
      (store['pendingFormSessions'] as ReturnType<typeof signal<Set<string>>>).set(pendingSet);
      (store['childSignals'] as ReturnType<typeof signal<Map<string, ChildSignals>>>).set(signals);
      expect(() => fixture.detectChanges()).not.toThrow();
      await fixture.whenStable();
      fixture.detectChanges();

      const list = fixture.debugElement.query(By.directive(SessionListComponent)).componentInstance as SessionListComponent;
      expect(list.busySessionIds()).toBe(busy);
      expect(list.pendingFormSessions()).toBe(pendingSet);
      expect(list.childSignals()).toBe(signals);

      list.expand.emit('s1');
      expect(store['loadSessionReports']).toHaveBeenCalledWith('s1');
    });

    it('starts closed below 800px, and the status panel is independent of it', async () => {
      mockWidth(600);
      await setup();
      expect(component.sessionsOpen()).toBe(false);
      expect(sessions().hidden).toBe(true);
      expect(component.statusOpen()).toBe(false);
      await click(sessionsBtn());
      expect(sessions().hidden).toBe(false);
      expect(panel().hidden).toBe(true);
    });

    it('closes the overlay by clicking the scrim below 800px', async () => {
      mockWidth(600);
      await setup();
      await click(sessionsBtn());
      expect(q('.sessions-scrim')).not.toBeNull();
      await click(q<HTMLElement>('.sessions-scrim')!);
      expect(sessions().hidden).toBe(true);
      expect(q('.sessions-scrim')).toBeNull();
    });

    it('closes the overlay after selecting a session below 800px but keeps it docked at 1024px', async () => {
      mockWidth(600);
      await setup();
      await click(sessionsBtn());
      component.onSelectSession('s1');
      fixture.detectChanges();
      expect(store['openSession']).toHaveBeenCalledWith('s1');
      expect(component.sessionsOpen()).toBe(false);

      fixture.nativeElement.remove();
      TestBed.resetTestingModule();
      mockWidth(1024);
      await setup();
      component.onSelectSession('s2');
      expect(component.sessionsOpen()).toBe(true);
    });
  });

  describe('header title', () => {
    beforeEach(async () => setup());

    const titleEl = () => q<HTMLElement>('.title-text')!;
    const setTitle = async (t: string) => {
      (store['activeTitle'] as ReturnType<typeof signal<string>>).set(t);
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };

    it('falls back to "Servoy AI" when the active session has no title', () => {
      expect(titleEl().textContent!.trim()).toBe('Servoy AI');
      expect(titleEl().hasAttribute('title')).toBe(false);
    });

    it('shows the active session title and exposes it as hover text', async () => {
      await setTitle('Warnings in TestSol ophalen');
      expect(titleEl().textContent!.trim()).toBe('Warnings in TestSol ophalen');
      expect(titleEl().getAttribute('title')).toBe('Warnings in TestSol ophalen');
    });

    it('returns to the fallback when the title becomes empty', async () => {
      await setTitle('Some title');
      await setTitle('');
      expect(titleEl().textContent!.trim()).toBe('Servoy AI');
    });
  });

  describe('agent header back button (replaces the parent link)', () => {
    beforeEach(async () => setup());

    const backButton = () => q<HTMLButtonElement>('svy-agent-header button.back');
    const setSubagent = async (parent: { id: string; title: string } | null, isSubagent = true) => {
      (store['activeIsSubagent'] as ReturnType<typeof signal<boolean>>).set(isSubagent);
      (store['activeParent'] as ReturnType<typeof signal<{ id: string; title: string } | null>>).set(parent);
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };

    it('is absent when the active session is not a subagent', () => {
      expect(q('svy-agent-header')).toBeNull();
      expect(backButton()).toBeNull();
      expect(q('.parent-link')).toBeNull();
    });

    it('shows the parent title in the accessible name and tooltip when viewing a subagent', async () => {
      await setSubagent({ id: 's1', title: 'Main work' });
      expect(q('svy-agent-header .parent')!.textContent!.trim()).toBe('Main work');
      expect(backButton()!.getAttribute('aria-label')).toBe('Back to parent session: Main work');
      expect(backButton()!.getAttribute('title')).toBe('Main work');
    });

    it('shows the agent name resolved from the active session title', async () => {
      (store['activeSession'] as ReturnType<typeof signal<Session | undefined>>).set({
        id: 'c1',
        title: 'Check (@reviewer subagent)',
        parentID: 's1'
      });
      await setSubagent({ id: 's1', title: 'Main work' });
      expect(q('svy-agent-header .agent')!.textContent!.trim()).toBe('reviewer');
    });

    it('falls back to a generic label when the parent has no title', async () => {
      await setSubagent({ id: 's1', title: '' });
      expect(backButton()!.getAttribute('aria-label')).toBe('Back to parent session');
      expect(backButton()!.getAttribute('title')).toBe('Parent session');
    });

    it('opens the parent session when clicked', async () => {
      await setSubagent({ id: 's1', title: 'Main work' });
      await click(backButton()!);
      expect(store['openSession']).toHaveBeenCalledWith('s1');
    });

    it('disappears again when the active session is no longer a subagent', async () => {
      await setSubagent({ id: 's1', title: 'Main work' });
      await setSubagent(null, false);
      expect(q('svy-agent-header')).toBeNull();
      expect(backButton()).toBeNull();
    });

    it('orphan child: header has no back button and the footer has no Open parent session', async () => {
      await setSubagent(null);
      expect(q('svy-agent-header')).not.toBeNull();
      expect(backButton()).toBeNull();
      expect(q('svy-agent-footer')).not.toBeNull();
      expect(q('svy-agent-footer button.open')).toBeNull();
    });
  });

  describe('agent view wiring', () => {
    beforeEach(async () => setup());

    const setSubagent = async (isSubagent: boolean, parent: { id: string; title: string } | null = null) => {
      (store['activeIsSubagent'] as ReturnType<typeof signal<boolean>>).set(isSubagent);
      (store['activeParent'] as ReturnType<typeof signal<{ id: string; title: string } | null>>).set(parent);
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };

    it('regular session: composer and message list present, agent components absent', () => {
      expect(q('svy-composer')).not.toBeNull();
      expect(q('svy-message-list')).not.toBeNull();
      expect(q('svy-agent-view')).toBeNull();
      expect(q('svy-agent-footer')).toBeNull();
      expect(q('svy-agent-header')).toBeNull();
    });

    it('subagent session: agent view, header and footer present; composer and message list absent', async () => {
      await setSubagent(true, { id: 's1', title: 'Main work' });
      expect(q('svy-agent-view')).not.toBeNull();
      expect(q('svy-agent-footer')).not.toBeNull();
      expect(q('svy-agent-header')).not.toBeNull();
      expect(q('svy-composer')).toBeNull();
      expect(q('svy-message-list')).toBeNull();
    });

    it('the footer Open parent session button opens the parent', async () => {
      await setSubagent(true, { id: 's1', title: 'Main work' });
      await click(q<HTMLButtonElement>('svy-agent-footer button.open')!);
      expect(store['openSession']).toHaveBeenCalledWith('s1');
    });

    it('feeds the agent view the store messages', async () => {
      (store['messages'] as ReturnType<typeof signal<ChatMessage[]>>).set([
        { info: { id: 'u1', role: 'user' }, parts: [{ type: 'text', text: 'Do the review' } as Part] }
      ] as unknown as ChatMessage[]);
      await setSubagent(true, { id: 's1', title: 'Main work' });
      expect(q('svy-agent-task')!.textContent).toContain('Do the review');
    });

    it('hosts exactly one svy-file-reader, closed by default (renders nothing)', () => {
      expect(fixture.nativeElement.querySelectorAll('svy-file-reader')).toHaveLength(1);
      expect(q('svy-file-reader .panel')).toBeNull();
    });

    it('does not throw NG0600 when switching between regular and agent view', async () => {
      await setSubagent(true, { id: 's1', title: 'Main work' });
      expect(() => fixture.detectChanges()).not.toThrow();
      await setSubagent(false);
      expect(() => fixture.detectChanges()).not.toThrow();
      expect(q('svy-composer')).not.toBeNull();
    });
  });

  describe('scroll save and restore on parent/child navigation', () => {
    beforeEach(async () => setup());

    const set = <T>(key: string, value: T) => (store[key] as ReturnType<typeof signal<T>>).set(value);

    it('opening a child of the active parent saves the list scroll state under the parent id', () => {
      const spy = vi.spyOn(MessageListComponent.prototype, 'getScrollState').mockReturnValue({ top: 321, pinned: false });
      set('activeSessionId', 'p1');
      (store['sessionParentId'] as ReturnType<typeof vi.fn>).mockImplementation((id: string) =>
        id === 'c1' ? 'p1' : undefined
      );
      component.onSelectSession('c1');
      expect(spy).toHaveBeenCalled();
      expect(store['saveScroll']).toHaveBeenCalledWith('p1', { top: 321, pinned: false });
      expect(store['openSession']).toHaveBeenCalledWith('c1');
      expect(component.restoreScroll()).toBeNull();
      spy.mockRestore();
    });

    it('opening an unrelated session saves nothing', () => {
      set('activeSessionId', 'p1');
      component.onSelectSession('other');
      expect(store['saveScroll']).not.toHaveBeenCalled();
      expect(store['openSession']).toHaveBeenCalledWith('other');
    });

    it('re-selecting the active session saves nothing', () => {
      set('activeSessionId', 'p1');
      (store['sessionParentId'] as ReturnType<typeof vi.fn>).mockReturnValue('p1');
      component.onSelectSession('p1');
      expect(store['saveScroll']).not.toHaveBeenCalled();
    });

    it('going back to the parent from the agent view passes the saved state to the list', () => {
      set('activeSessionId', 'c1');
      set('activeIsSubagent', true);
      set('activeParent', { id: 'p1', title: 'Main' });
      (store['takeScroll'] as ReturnType<typeof vi.fn>).mockReturnValue({ top: 321, pinned: false });
      component.onBackToParent();
      expect(store['takeScroll']).toHaveBeenCalledWith('p1');
      expect(component.restoreScroll()).toEqual({ top: 321, pinned: false });
      expect(store['openSession']).toHaveBeenCalledWith('p1');
    });

    it('binds the saved scroll state to the message list input after going back to the parent', async () => {
      set('activeSessionId', 'c1');
      set('activeIsSubagent', true);
      set('activeParent', { id: 'p1', title: 'Main' });
      (store['takeScroll'] as ReturnType<typeof vi.fn>).mockReturnValue({ top: 321, pinned: false });
      component.onBackToParent();
      set('activeSessionId', 'p1');
      set('activeIsSubagent', false);
      set('activeParent', null);
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      const list = fixture.debugElement.query(By.directive(MessageListComponent));
      expect(list).not.toBeNull();
      expect((list.componentInstance as MessageListComponent).restoreScroll()).toEqual({ top: 321, pinned: false });
    });

    it('selecting the parent from the sidebar while in the agent view restores its saved scroll', () => {
      set('activeSessionId', 'c1');
      set('activeIsSubagent', true);
      set('activeParent', { id: 'p1', title: 'Main' });
      (store['takeScroll'] as ReturnType<typeof vi.fn>).mockReturnValue({ top: 321, pinned: false });
      component.onSelectSession('p1');
      expect(store['takeScroll']).toHaveBeenCalledWith('p1');
      expect(component.restoreScroll()).toEqual({ top: 321, pinned: false });
      expect(store['openSession']).toHaveBeenCalledWith('p1');
    });

    it('going from the agent view to an unrelated session restores nothing', () => {
      set('activeSessionId', 'c1');
      set('activeIsSubagent', true);
      set('activeParent', { id: 'p1', title: 'Main' });
      component.restoreScroll.set({ top: 5, pinned: false });
      component.onSelectSession('other');
      expect(store['takeScroll']).not.toHaveBeenCalled();
      expect(component.restoreScroll()).toBeNull();
    });

    it('going back with nothing saved leaves restoreScroll null', () => {
      set('activeSessionId', 'c1');
      set('activeIsSubagent', true);
      set('activeParent', { id: 'p1', title: 'Main' });
      component.onBackToParent();
      expect(component.restoreScroll()).toBeNull();
      expect(store['openSession']).toHaveBeenCalledWith('p1');
    });

    it('onBackToParent does nothing for an orphan', () => {
      set('activeSessionId', 'c1');
      set('activeIsSubagent', true);
      component.onBackToParent();
      expect(store['openSession']).not.toHaveBeenCalled();
    });
  });

  describe('welcome and starters', () => {
    beforeEach(async () => setup());

    const set = <T>(key: string, value: T) => (store[key] as ReturnType<typeof signal<T>>).set(value);
    const settle = async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    const textarea = () => q<HTMLTextAreaElement>('svy-composer textarea')!;

    it('renders no welcome while welcomeReady is false', () => {
      expect(q('svy-welcome')).toBeNull();
    });

    it('renders no welcome next to the banner when the store has an error and is not ready', async () => {
      set('error', 'Servoy AI is starting or no solution is active. Please wait.');
      await settle();
      expect(q('.banner')!.textContent).toContain('Servoy AI is starting or no solution is active. Please wait.');
      expect(q('svy-welcome')).toBeNull();
    });

    it('renders the full welcome when ready, whether or not there are sessions', async () => {
      set('welcomeReady', true);
      await settle();
      expect(q('svy-welcome')).not.toBeNull();
      expect(q('svy-welcome ol.steps')).not.toBeNull();
      expect(q('svy-welcome button.card')).not.toBeNull();
      set('sessions', [{ id: 's1', title: 'One' }]);
      await settle();
      expect(q('svy-welcome ol.steps')).not.toBeNull();
      expect(q('svy-welcome button.card')).not.toBeNull();
    });

    it('removes the welcome once the store has messages', async () => {
      set('welcomeReady', true);
      await settle();
      expect(q('svy-welcome')).not.toBeNull();
      set('messages', [
        { info: { id: 'u1', role: 'user' }, parts: [{ type: 'text', text: 'hi' } as Part] }
      ] as unknown as ChatMessage[]);
      await settle();
      expect(q('svy-welcome')).toBeNull();
    });

    it('clicking a starter fills the draft with "/name ", sends nothing, focuses the textarea with the caret at the end', async () => {
      draft.set({ text: 'previous words that are long', attachments: [] });
      set('welcomeReady', true);
      await settle();
      textarea().setSelectionRange(0, 0);
      expect(textarea().selectionStart).toBe(0);

      await click(q<HTMLButtonElement>('svy-welcome button.card')!);
      await settle();

      expect(draft().text).toBe('/svyImplement ');
      expect(store['setActiveDraft']).toHaveBeenCalledTimes(1);
      expect(store['send']).not.toHaveBeenCalled();
      expect(textarea().value).toBe('/svyImplement ');
      expect(document.activeElement).toBe(textarea());
      expect(textarea().selectionStart).toBe(textarea().value.length);
      expect(textarea().selectionEnd).toBe(textarea().value.length);
    });

    it('replaces existing draft text and keeps existing attachments', async () => {
      const attachments = [{ filename: 'a.txt', mime: 'text/plain', url: 'a.txt' }];
      draft.set({ text: 'previous words', attachments });
      set('welcomeReady', true);
      await settle();

      await click(q<HTMLButtonElement>('svy-welcome button.card')!);
      await settle();

      expect(draft().text).toBe('/svyImplement ');
      expect(draft().attachments).toEqual(attachments);
      expect(store['send']).not.toHaveBeenCalled();
    });

    it('onStarter does not throw when no composer is rendered (subagent view)', async () => {
      set('activeIsSubagent', true);
      set('activeParent', { id: 's1', title: 'Main' });
      await settle();
      expect(q('svy-composer')).toBeNull();
      expect(() => component.onStarter({ name: 'svyFixTicket', description: 'Fix a ticket' })).not.toThrow();
      expect(draft().text).toBe('/svyFixTicket ');
      expect(store['send']).not.toHaveBeenCalled();
    });

    it('does not throw NG0600 when welcomeReady and sessions change', async () => {
      set('welcomeReady', true);
      expect(() => fixture.detectChanges()).not.toThrow();
      set('sessions', [{ id: 's1', title: 'One' }]);
      expect(() => fixture.detectChanges()).not.toThrow();
    });
  });

  describe('theme toggle', () => {
    it('labels "Switch to dark mode" in light and flips data-theme on click', async () => {
      await setup('?darkmode=false');
      expect(themeBtn().getAttribute('aria-label')).toBe('Switch to dark mode');
      expect(themeBtn().getAttribute('title')).toBe('Switch to dark mode');
      expect(themeBtn().getAttribute('type')).toBe('button');
      await click(themeBtn());
      expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
      expect(themeBtn().getAttribute('aria-label')).toBe('Switch to light mode');
      expect(themeBtn().getAttribute('title')).toBe('Switch to light mode');
    });

    it('labels "Switch to light mode" in dark and flips back on click', async () => {
      await setup('?darkmode=true');
      expect(themeBtn().getAttribute('aria-label')).toBe('Switch to light mode');
      await click(themeBtn());
      expect(document.documentElement.getAttribute('data-theme')).toBe('light');
      expect(themeBtn().getAttribute('aria-label')).toBe('Switch to dark mode');
    });

    it('swaps the icon with the theme', async () => {
      await setup('?darkmode=true');
      const darkIcon = themeBtn().innerHTML;
      expect(themeBtn().querySelector('circle')).not.toBeNull();
      await click(themeBtn());
      expect(themeBtn().querySelector('circle')).toBeNull();
      expect(themeBtn().innerHTML).not.toBe(darkIcon);
      expect(themeBtn().querySelector('svg')!.getAttribute('aria-hidden')).toBe('true');
    });

    it('does not change the URL or reload', async () => {
      await setup('?darkmode=true');
      const before = window.location.href;
      await click(themeBtn());
      expect(window.location.href).toBe(before);
    });

    it('preserves composer text and the message list / composer DOM instances', async () => {
      await setup();
      draft.set({ text: 'half typed', attachments: [] });
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      const textarea = q<HTMLTextAreaElement>('svy-composer textarea')!;
      const list = q('svy-message-list');
      const composer = q('svy-composer');
      expect(textarea.value).toBe('half typed');

      await click(themeBtn());

      expect(q('svy-composer textarea')).toBe(textarea);
      expect(q('svy-message-list')).toBe(list);
      expect(q('svy-composer')).toBe(composer);
      expect(textarea.value).toBe('half typed');
      expect(draft().text).toBe('half typed');
    });
  });

  describe('context ring', () => {
    const ring = () => q<HTMLButtonElement>('button.ctx-ring');
    const msgs = () => store['messages'] as ReturnType<typeof signal<unknown[]>>;
    const asst = (id: string, input: number) => ({
      info: { id, role: 'assistant', tokens: { input, output: 0 }, model: { id: 'm1', providerID: 'kiro' } },
      parts: []
    });

    beforeEach(async () => setup());

    it('is absent while the session has no usage', () => {
      expect(ring()).toBeNull();
    });

    it('appears and updates with a larger assistant message without a reload', async () => {
      msgs().set([asst('a1', 40000)]);
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      expect(ring()!.getAttribute('aria-label')).toBe('Context 40% used. Open status');
      msgs().update((m) => [...m, asst('a2', 85000)]);
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      expect(ring()!.getAttribute('aria-label')).toContain('Context 85% used');
      expect(ring()!.classList.contains('high')).toBe(true);
    });

    it('toggles the status panel when clicked', async () => {
      msgs().set([asst('a1', 40000)]);
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      expect(component.statusOpen()).toBe(true);
      await click(ring()!);
      expect(component.statusOpen()).toBe(false);
      await click(ring()!);
      expect(component.statusOpen()).toBe(true);
    });

    it('is not rendered in a subagent view', async () => {
      msgs().set([asst('a1', 40000)]);
      (store['activeIsSubagent'] as ReturnType<typeof signal<boolean>>).set(true);
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      expect(ring()).toBeNull();
    });
  });
});
