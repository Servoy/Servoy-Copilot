import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { Part } from '../models/opencode.models';
import { ChatMessage } from '../services/chat-store.service';
import { MessageListComponent } from './message-list.component';

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
