import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Part } from '../models/opencode.models';
import { ChatMessage } from '../services/chat-store.service';
import { CommandCatalogService } from '../services/command-catalog.service';
import { SvyCommand } from '../services/svy-commands';
import { MessageListComponent } from './message-list.component';
import { WelcomeComponent } from './welcome.component';

const msg = (id: string, t = id): ChatMessage =>
  ({ info: { id, role: 'assistant' }, parts: [{ type: 'text', text: t } as Part] }) as unknown as ChatMessage;

describe('MessageListComponent scroll state and restore', () => {
  let fixture: ComponentFixture<MessageListComponent>;
  let component: MessageListComponent;
  let top: number;
  let height: number;
  let scrollEl: HTMLElement;

  const render = (inputs: Record<string, unknown>) => {
    for (const [key, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(key, value);
    }
    fixture.detectChanges();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MessageListComponent],
      providers: [provideZonelessChangeDetection()]
    }).compileComponents();
    fixture = TestBed.createComponent(MessageListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    scrollEl = component.scroll().nativeElement;
    top = 0;
    height = 1500;
    Object.defineProperty(scrollEl, 'scrollTop', { configurable: true, get: () => top, set: (v: number) => (top = v) });
    Object.defineProperty(scrollEl, 'scrollHeight', { configurable: true, get: () => height });
    Object.defineProperty(scrollEl, 'clientHeight', { configurable: true, get: () => 500 });
  });

  describe('getScrollState', () => {
    it('is pinned at the initial position and reports the element scrollTop', () => {
      top = 77;
      expect(component.getScrollState()).toEqual({ top: 77, pinned: true });
    });

    it('reports unpinned after the user scrolled away from the bottom', () => {
      top = 123;
      scrollEl.dispatchEvent(new Event('scroll'));
      expect(component.getScrollState()).toEqual({ top: 123, pinned: false });
    });

    it('reports pinned when within the bottom threshold', () => {
      top = 980;
      scrollEl.dispatchEvent(new Event('scroll'));
      expect(component.getScrollState()).toEqual({ top: 980, pinned: true });
    });
  });

  describe('restoreScroll', () => {
    it('restores an unpinned position', () => {
      render({ messages: [msg('m1')], restoreScroll: { top: 400, pinned: false } });
      expect(top).toBe(400);
      expect(component.getScrollState().pinned).toBe(false);
    });

    it('clamps a too large top to scrollHeight - clientHeight', () => {
      render({ messages: [msg('m1')], restoreScroll: { top: 5000, pinned: false } });
      expect(top).toBe(1000);
    });

    it('never goes negative', () => {
      render({ messages: [msg('m1')], restoreScroll: { top: -50, pinned: false } });
      expect(top).toBe(0);
    });

    it('ends at the bottom when pinned', () => {
      render({ messages: [msg('m1')], restoreScroll: { top: 400, pinned: true } });
      expect(top).toBe(1500);
      expect(component.getScrollState().pinned).toBe(true);
    });

    it('stays pending until messages exist, then applies', () => {
      render({ restoreScroll: { top: 400, pinned: false } });
      expect(top).not.toBe(400);
      render({ messages: [msg('m1')] });
      expect(top).toBe(400);
    });

    it('is applied once: later content changes do not reset the position', () => {
      render({ messages: [msg('m1')], restoreScroll: { top: 400, pinned: false } });
      top = 250;
      render({ messages: [msg('m1'), msg('m2')] });
      expect(top).toBe(250);
    });

    it('applies a new restore object again', () => {
      render({ messages: [msg('m1')], restoreScroll: { top: 400, pinned: false } });
      render({ restoreScroll: { top: 600, pinned: false } });
      expect(top).toBe(600);
    });

    it('does not let the prepend anchor alter the restored value in the same pass', () => {
      render({ messages: [msg('m2')] });
      top = 0;
      height = 2000;
      render({ messages: [msg('m1'), msg('m2')], restoreScroll: { top: 400, pinned: false } });
      expect(top).toBe(400);
    });
  });

  describe('without restoreScroll', () => {
    it('keeps pinning to the bottom as content grows', () => {
      render({ messages: [msg('m1')] });
      expect(top).toBe(1500);
      height = 1800;
      render({ messages: [msg('m1'), msg('m2')] });
      expect(top).toBe(1800);
    });

    it('anchors the reading position on a prepend when not pinned', () => {
      render({ messages: [msg('m2')] });
      top = 100;
      scrollEl.dispatchEvent(new Event('scroll'));
      height = 2000;
      render({ messages: [msg('m1'), msg('m2')] });
      expect(top).toBe(600);
    });
  });
});

describe('MessageListComponent welcome', () => {
  let fixture: ComponentFixture<MessageListComponent>;
  const commands = signal<SvyCommand[]>([{ name: 'svyImplement', description: 'Implement a feature' }]);
  const load = vi.fn();

  const el = (): HTMLElement => fixture.nativeElement;
  const welcome = () => el().querySelector('svy-welcome');

  const render = (inputs: Record<string, unknown>) => {
    for (const [key, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(key, value);
    }
    fixture.detectChanges();
  };

  beforeEach(async () => {
    load.mockClear();
    await TestBed.configureTestingModule({
      imports: [MessageListComponent],
      providers: [provideZonelessChangeDetection(), { provide: CommandCatalogService, useValue: { commands, load } }]
    }).compileComponents();
    fixture = TestBed.createComponent(MessageListComponent);
    fixture.detectChanges();
  });

  it('renders no welcome and no text when showWelcome is false and there are no messages', () => {
    render({ messages: [] });
    expect(welcome()).toBeNull();
    expect(el().textContent).not.toContain('What should we build in Servoy today?');
    expect(el().textContent).not.toContain('Welcome to Servoy');
    expect(load).not.toHaveBeenCalled();
  });

  it('renders the intro welcome when showWelcome is true and hasSessions is false', () => {
    render({ messages: [], showWelcome: true, hasSessions: false });
    expect(welcome()).not.toBeNull();
    expect(el().textContent).toContain('Welcome to Servoy');
    expect(el().querySelector('ol.steps')).not.toBeNull();
  });

  it('forwards hasSessions: renders the hero line and pills when true', () => {
    render({ messages: [], showWelcome: true, hasSessions: true });
    expect(el().querySelector('h1.title')!.textContent).toBe('What should we build in Servoy today?');
    expect(el().querySelector('button.pill')).not.toBeNull();
    expect(el().querySelector('ol.steps')).toBeNull();
  });

  it('re-emits a pick from the welcome on starter', () => {
    const picked: SvyCommand[] = [];
    fixture.componentInstance.starter.subscribe((c) => picked.push(c));
    render({ messages: [], showWelcome: true, hasSessions: true });
    (el().querySelector('button.pill') as HTMLButtonElement).click();
    expect(picked).toEqual([{ name: 'svyImplement', description: 'Implement a feature' }]);
  });

  it('renders no welcome when messages are present even with showWelcome true', () => {
    render({ messages: [msg('m1')], showWelcome: true, hasSessions: true });
    expect(welcome()).toBeNull();
    expect(el().textContent).not.toContain('What should we build');
  });

  it('removes the welcome once messages arrive', () => {
    render({ messages: [], showWelcome: true });
    expect(welcome()).not.toBeNull();
    render({ messages: [msg('m1')] });
    expect(welcome()).toBeNull();
    expect(fixture.debugElement.query(By.directive(WelcomeComponent))).toBeNull();
  });
});

