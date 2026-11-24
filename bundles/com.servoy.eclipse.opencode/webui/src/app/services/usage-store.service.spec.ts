import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { MessageWithParts, ModelLimit, UsageSnapshot } from '../models/opencode.models';
import { ChatStore } from './chat-store.service';
import { OpencodeApiService } from './opencode-api.service';
import { StatusService } from './status.service';
import { UsageStore } from './usage-store.service';

const MODEL = { id: 'm1', providerID: 'kiro' };
const LIMITS: ModelLimit[] = [{ providerID: 'kiro', id: 'm1', context: 100000 }];

const asst = (id: string, tokens: unknown, model: unknown = MODEL): MessageWithParts =>
  ({ info: { id, role: 'assistant', tokens, model }, parts: [] }) as MessageWithParts;
const placeholder = (id: string): MessageWithParts => ({ info: { id, role: 'assistant' }, parts: [] }) as MessageWithParts;

describe('UsageStore', () => {
  let messages: ReturnType<typeof signal<MessageWithParts[]>>;
  let activeSessionId: ReturnType<typeof signal<string | null>>;
  let streaming: ReturnType<typeof signal<boolean>>;
  let models: ReturnType<typeof vi.fn>;
  let usage: ReturnType<typeof vi.fn>;
  let listMessagePage: ReturnType<typeof vi.fn>;
  let store: UsageStore;

  function create(): void {
    store = TestBed.inject(UsageStore);
    TestBed.tick();
  }

  beforeEach(() => {
    messages = signal<MessageWithParts[]>([]);
    activeSessionId = signal<string | null>('s1');
    streaming = signal(false);
    models = vi.fn((): Observable<ModelLimit[]> => of(LIMITS));
    usage = vi.fn((): Observable<UsageSnapshot | null> => of(null));
    listMessagePage = vi.fn(() => of({ messages: [] }));
    TestBed.configureTestingModule({
      providers: [
        { provide: ChatStore, useValue: { messages, activeSessionId, streaming } },
        { provide: StatusService, useValue: { models, usage } },
        { provide: OpencodeApiService, useValue: { listMessagePage } }
      ]
    });
  });

  describe('context', () => {
    it('is null when the session has no assistant message with tokens', () => {
      create();
      expect(store.context()).toEqual({ percent: null, used: 0, limit: null, high: false });
      expect(store.hasSessionTokens()).toBe(false);
    });

    it('computes the percent from the model limit and follows a new streamed message', () => {
      messages.set([asst('a1', { input: 40000, output: 2000 })]);
      create();
      expect(store.context().percent).toBe(42);
      expect(store.context().high).toBe(false);
      messages.update((m) => [...m, asst('a2', { input: 85000, output: 0 })]);
      expect(store.context().percent).toBe(85);
      expect(store.context().high).toBe(true);
      expect(store.context().limit).toBe(100000);
    });

    it('follows a replaced message list (session switch)', () => {
      messages.set([asst('a1', { input: 50000 })]);
      create();
      expect(store.context().percent).toBe(50);
      activeSessionId.set('s2');
      messages.set([]);
      expect(store.context().percent).toBeNull();
      messages.set([asst('b1', { input: 10000 })]);
      expect(store.context().percent).toBe(10);
    });

    it('is high from exactly 80 percent', () => {
      messages.set([asst('a1', { input: 79000 })]);
      create();
      expect(store.context().high).toBe(false);
      messages.set([asst('a1', { input: 80000 })]);
      expect(store.context().high).toBe(true);
    });

    it('is null when the model is missing from the model list', () => {
      models.mockReturnValue(of([{ providerID: 'kiro', id: 'other', context: 5 }]));
      messages.set([asst('a1', { input: 40000 })]);
      create();
      expect(store.context().percent).toBeNull();
    });

    it('is null when the model limit is unknown', () => {
      models.mockReturnValue(of([{ providerID: 'kiro', id: 'm1', context: null }]));
      messages.set([asst('a1', { input: 40000 })]);
      create();
      expect(store.context().percent).toBeNull();
    });

    it('matches by providerID and id', () => {
      models.mockReturnValue(of([{ providerID: 'openai', id: 'm1', context: 1000 }]));
      messages.set([asst('a1', { input: 500 })]);
      create();
      expect(store.context().percent).toBeNull();
    });

    it('does not throw when signals change', () => {
      create();
      expect(() => {
        messages.set([asst('a1', { input: 1 })]);
        TestBed.tick();
      }).not.toThrow();
    });
  });

  describe('session tokens', () => {
    it('sums input + output + reasoning of the viewed messages', () => {
      messages.set([asst('a1', { input: 10, output: 5, reasoning: 1, cache: { read: 1000, write: 1000 } })]);
      create();
      expect(store.sessionTokens()).toBe(16);
      expect(store.hasSessionTokens()).toBe(true);
    });
  });

  describe('model limit loading', () => {
    it('does not fetch models while no message carries a model', () => {
      messages.set([placeholder('a1')]);
      create();
      expect(models).not.toHaveBeenCalled();
    });

    it('fetches once when the model is absent and does not loop', () => {
      models.mockReturnValue(of([]));
      messages.set([asst('a1', { input: 1 })]);
      create();
      TestBed.tick();
      messages.update((m) => [...m, asst('a2', { input: 2 })]);
      TestBed.tick();
      expect(models).toHaveBeenCalledTimes(1);
    });

    it('fetches again for a different session when the model is still absent', () => {
      models.mockReturnValue(of([]));
      messages.set([asst('a1', { input: 1 })]);
      create();
      activeSessionId.set('s2');
      messages.set([asst('b1', { input: 1 })]);
      TestBed.tick();
      expect(models).toHaveBeenCalledTimes(2);
    });

    it('refresh() forces a fetch while the list is empty and does not refetch a populated list within 60 s', () => {
      create();
      store.refresh();
      expect(models).toHaveBeenCalledTimes(1);
      store.refresh();
      expect(models).toHaveBeenCalledTimes(1);
    });

    it('keeps an empty list when the models request yields nothing', () => {
      models.mockReturnValue(of([]));
      create();
      store.refresh();
      expect(store.modelLimits()).toEqual([]);
    });
  });

  describe('credits', () => {
    const NOW = Date.now();
    const snapshot = (extra: Partial<UsageSnapshot> = {}): UsageSnapshot => ({
      available: true,
      used: 381.29,
      limit: 10000,
      pct: 4,
      resetAt: NOW + 5 * 86_400_000,
      updatedAt: NOW - 60_000,
      stale: false,
      ...extra
    });

    it('maps a fresh snapshot', () => {
      usage.mockReturnValue(of(snapshot()));
      create();
      store.refresh();
      expect(store.credits()).toEqual({
        used: 381.29,
        limit: 10000,
        pct: 4,
        resetAt: NOW + 5 * 86_400_000,
        state: 'fresh'
      });
    });

    it('is stale when resetAt is in the past', () => {
      expect(UsageStore.toCredits(snapshot({ resetAt: NOW - 1 }), NOW)?.state).toBe('stale');
    });

    it('is stale when updatedAt is older than 24 hours and fresh just inside', () => {
      expect(UsageStore.toCredits(snapshot({ updatedAt: NOW - 25 * 3_600_000 }), NOW)?.state).toBe('stale');
      expect(UsageStore.toCredits(snapshot({ updatedAt: NOW - 23 * 3_600_000 }), NOW)?.state).toBe('fresh');
    });

    it('is stale when updatedAt is missing or the server says stale', () => {
      expect(UsageStore.toCredits(snapshot({ updatedAt: undefined }), NOW)?.state).toBe('stale');
      expect(UsageStore.toCredits(snapshot({ stale: true }), NOW)?.state).toBe('stale');
    });

    it('is nolimit for a fresh snapshot without a usable limit', () => {
      expect(UsageStore.toCredits(snapshot({ limit: undefined }), NOW)).toMatchObject({ state: 'nolimit', limit: null });
      expect(UsageStore.toCredits(snapshot({ limit: 0 }), NOW)).toMatchObject({ state: 'nolimit', limit: null });
    });

    it('is null when unavailable, missing or without used', () => {
      expect(UsageStore.toCredits({ available: false }, NOW)).toBeNull();
      expect(UsageStore.toCredits(null, NOW)).toBeNull();
      expect(UsageStore.toCredits({ available: true }, NOW)).toBeNull();
    });

    it('is null after an HTTP failure surfaced as a null snapshot', () => {
      usage.mockReturnValue(of(null));
      create();
      store.refresh();
      expect(store.credits()).toBeNull();
      expect(store.creditsVisible()).toBe(false);
    });

    it('is visible with no model message and for kiro, hidden for another provider', () => {
      usage.mockReturnValue(of(snapshot()));
      create();
      store.refresh();
      expect(store.creditsVisible()).toBe(true);
      messages.set([asst('a1', { input: 1 }, { id: 'gpt', providerID: 'openai' })]);
      expect(store.creditsVisible()).toBe(false);
      messages.set([asst('a2', { input: 1 })]);
      expect(store.creditsVisible()).toBe(true);
    });
  });

  describe('idle re-fetch fallback', () => {
    it('overlays tokens and model fetched when the viewed session goes idle', () => {
      messages.set([placeholder('a1')]);
      create();
      expect(store.context().percent).toBeNull();
      streaming.set(true);
      TestBed.tick();
      listMessagePage.mockReturnValue(of({ messages: [asst('a1', { input: 30000, output: 1000 })] }));
      streaming.set(false);
      TestBed.tick();
      expect(listMessagePage).toHaveBeenCalledWith('s1');
      expect(store.context().percent).toBe(31);
      expect(store.sessionTokens()).toBe(31000);
    });

    it('does not overwrite tokens a message already carries', () => {
      messages.set([asst('a1', { input: 10000 })]);
      create();
      streaming.set(true);
      TestBed.tick();
      listMessagePage.mockReturnValue(of({ messages: [asst('a1', { input: 99999 })] }));
      streaming.set(false);
      TestBed.tick();
      expect(store.context().percent).toBe(10);
    });

    it('does not fetch when streaming never started', () => {
      create();
      TestBed.tick();
      expect(listMessagePage).not.toHaveBeenCalled();
    });

    it('ignores a failed re-fetch', () => {
      messages.set([placeholder('a1')]);
      create();
      streaming.set(true);
      TestBed.tick();
      listMessagePage.mockReturnValue(throwError(() => new Error('boom')));
      streaming.set(false);
      expect(() => TestBed.tick()).not.toThrow();
      expect(store.context().percent).toBeNull();
    });

    it('drops the result when the session changed meanwhile', () => {
      messages.set([placeholder('a1')]);
      create();
      streaming.set(true);
      TestBed.tick();
      listMessagePage.mockImplementation(() => {
        activeSessionId.set('s2');
        return of({ messages: [asst('a1', { input: 30000 })] });
      });
      streaming.set(false);
      TestBed.tick();
      expect(store.context().percent).toBeNull();
    });
  });
});
