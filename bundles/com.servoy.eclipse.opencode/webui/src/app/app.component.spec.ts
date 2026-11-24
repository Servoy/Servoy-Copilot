import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AppComponent } from './app.component';
import { ChatStore, ComposerDraft } from './services/chat-store.service';
import { StatusService } from './services/status.service';

describe('AppComponent', () => {
  let fixture: ComponentFixture<AppComponent>;
  let component: AppComponent;
  let draft: ReturnType<typeof signal<ComposerDraft>>;
  let store: Record<string, unknown>;
  let status: { health: ReturnType<typeof vi.fn>; mcp: ReturnType<typeof vi.fn>; providers: ReturnType<typeof vi.fn> };

  function setQuery(query: string): void {
    history.replaceState(null, '', window.location.pathname + query);
  }

  async function setup(query = ''): Promise<void> {
    setQuery(query);
    document.documentElement.removeAttribute('data-theme');
    draft = signal<ComposerDraft>({ text: '', attachments: [] });
    store = {
      bootstrap: vi.fn(),
      sessionTree: signal([]),
      activeSessionId: signal<string | null>(null),
      messages: signal([]),
      hasMoreMessages: signal(false),
      error: signal<string | null>(null),
      pendingForm: signal(null),
      streaming: signal(false),
      activeDraft: draft,
      activeIsSubagent: signal(false),
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
      providers: vi.fn(() => of({ connected: ['kiro'], default: {} }))
    };
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: ChatStore, useValue: store },
        { provide: StatusService, useValue: status }
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
});
