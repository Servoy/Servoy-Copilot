import { describe, expect, it } from 'vitest';

import { MessageInfo, MessageWithParts } from '../models/opencode.models';
import {
  contextPercent,
  contextUsed,
  formatTokens,
  hasSessionTokens,
  isCompaction,
  messageCost,
  messageModel,
  messageTokens,
  sessionTokens
} from './usage-utils';

const info = (extra: Record<string, unknown>): MessageInfo => ({ id: 'x', role: 'assistant', ...extra }) as MessageInfo;
const msg = (extra: Record<string, unknown>): MessageWithParts => ({ info: info(extra), parts: [] });
const MODEL = { id: 'm1', providerID: 'kiro' };

describe('usage-utils', () => {
  describe('messageTokens', () => {
    it('returns null when tokens are absent or not an object', () => {
      expect(messageTokens(info({}))).toBeNull();
      expect(messageTokens(info({ tokens: 5 }))).toBeNull();
      expect(messageTokens(info({ tokens: 'lots' }))).toBeNull();
      expect(messageTokens(info({ tokens: [1, 2] }))).toBeNull();
      expect(messageTokens(info({ tokens: null }))).toBeNull();
    });

    it('reads all counters including cache', () => {
      expect(
        messageTokens(info({ tokens: { input: 1, output: 2, reasoning: 3, cache: { read: 4, write: 5 } } }))
      ).toEqual({ input: 1, output: 2, reasoning: 3, cacheRead: 4, cacheWrite: 5 });
    });

    it('treats missing fields as 0', () => {
      expect(messageTokens(info({ tokens: { input: 7 } }))).toEqual({
        input: 7,
        output: 0,
        reasoning: 0,
        cacheRead: 0,
        cacheWrite: 0
      });
    });

    it('treats non-finite and string numbers as 0', () => {
      expect(
        messageTokens(info({ tokens: { input: NaN, output: Infinity, reasoning: '12', cache: { read: '3', write: -Infinity } } }))
      ).toEqual({ input: 0, output: 0, reasoning: 0, cacheRead: 0, cacheWrite: 0 });
    });
  });

  describe('messageModel / messageCost / isCompaction', () => {
    it('reads id and providerID only when both are non-empty strings', () => {
      expect(messageModel(info({ model: { ...MODEL, variant: 'x' } }))).toEqual(MODEL);
      expect(messageModel(info({ model: { id: 'm1' } }))).toBeNull();
      expect(messageModel(info({ model: { id: '', providerID: 'kiro' } }))).toBeNull();
      expect(messageModel(info({ model: { id: 5, providerID: 'kiro' } }))).toBeNull();
      expect(messageModel(info({}))).toBeNull();
    });

    it('reads a finite numeric cost only', () => {
      expect(messageCost(info({ cost: 0.5 }))).toBe(0.5);
      expect(messageCost(info({ cost: [] }))).toBeNull();
      expect(messageCost(info({ cost: NaN }))).toBeNull();
      expect(messageCost(info({}))).toBeNull();
    });

    it('detects a compaction by type', () => {
      expect(isCompaction(info({ type: 'compaction' }))).toBe(true);
      expect(isCompaction(info({ type: 'assistant' }))).toBe(false);
      expect(isCompaction(info({}))).toBe(false);
    });
  });

  describe('sessionTokens', () => {
    it('sums input + output + reasoning and excludes cache', () => {
      const messages = [
        msg({ tokens: { input: 100, output: 10, reasoning: 1, cache: { read: 99999, write: 99999 } } }),
        msg({ tokens: { input: 200, output: 20, reasoning: 2 } })
      ];
      expect(sessionTokens(messages)).toBe(333);
    });

    it('skips user, compaction and token-less messages', () => {
      const messages = [
        msg({ role: 'user', tokens: { input: 1000 } }),
        msg({ type: 'compaction', tokens: { input: 1000 } }),
        msg({}),
        msg({ tokens: { input: 5 } })
      ];
      expect(sessionTokens(messages)).toBe(5);
    });

    it('is 0 for an empty list and hasSessionTokens is false', () => {
      expect(sessionTokens([])).toBe(0);
      expect(hasSessionTokens([])).toBe(false);
    });

    it('hasSessionTokens is true for a message with zero-valued tokens', () => {
      expect(hasSessionTokens([msg({ tokens: { input: 0 } })])).toBe(true);
      expect(hasSessionTokens([msg({ role: 'user', tokens: { input: 1 } })])).toBe(false);
    });
  });

  describe('contextUsed', () => {
    it('picks the newest assistant message and includes cache, output and reasoning', () => {
      const messages = [
        msg({ model: MODEL, tokens: { input: 1 } }),
        msg({ model: MODEL, tokens: { input: 10, output: 20, reasoning: 30, cache: { read: 40, write: 50 } } })
      ];
      expect(contextUsed(messages)).toEqual({ used: 150, model: MODEL });
    });

    it('skips compaction, user, token-less and model-less messages', () => {
      const messages = [
        msg({ model: MODEL, tokens: { input: 7 } }),
        msg({ type: 'compaction', model: MODEL, tokens: { input: 9999 } }),
        msg({ role: 'user', model: MODEL, tokens: { input: 9999 } }),
        msg({ model: MODEL }),
        msg({ tokens: { input: 9999 } })
      ];
      expect(contextUsed(messages)).toEqual({ used: 7, model: MODEL });
    });

    it('is null when nothing qualifies', () => {
      expect(contextUsed([])).toBeNull();
      expect(contextUsed([msg({ role: 'user' })])).toBeNull();
    });
  });

  describe('contextPercent', () => {
    it('rounds the ratio', () => {
      expect(contextPercent(42000, 100000)).toBe(42);
      expect(contextPercent(1, 3)).toBe(33);
      expect(contextPercent(2, 3)).toBe(67);
    });

    it('is null for an unusable limit', () => {
      expect(contextPercent(10, 0)).toBeNull();
      expect(contextPercent(10, undefined)).toBeNull();
      expect(contextPercent(10, null)).toBeNull();
      expect(contextPercent(10, NaN)).toBeNull();
      expect(contextPercent(10, -5)).toBeNull();
    });

    it('does not clamp values above 100 and returns the boundary values', () => {
      expect(contextPercent(79, 100)).toBe(79);
      expect(contextPercent(80, 100)).toBe(80);
      expect(contextPercent(100, 100)).toBe(100);
      expect(contextPercent(103, 100)).toBe(103);
      expect(contextPercent(0, 100)).toBe(0);
    });
  });

  describe('formatTokens', () => {
    it('formats plain, k and M values', () => {
      expect(formatTokens(0)).toBe('0');
      expect(formatTokens(812)).toBe('812');
      expect(formatTokens(999)).toBe('999');
      expect(formatTokens(1000)).toBe('1k');
      expect(formatTokens(64000)).toBe('64k');
      expect(formatTokens(1_200_000)).toBe('1.2M');
      expect(formatTokens(999_499)).toBe('999k');
      expect(formatTokens(999_999)).toBe('1M');
    });
  });
});
