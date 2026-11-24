import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { Session } from '../models/opencode.models';
import type { SessionNode } from './chat-store.service';
import {
  filterNodes,
  formatActivity,
  groupLabel,
  groupSessions,
  sessionActivity
} from './session-groups';

const at = (y: number, m: number, d: number, h = 0, min = 0): number => new Date(y, m, d, h, min).getTime();

const node = (id: string, title: string, updated: number | undefined, children: Session[] = []): SessionNode => ({
  session: { id, title, ...(updated === undefined ? {} : { time: { updated } }) } as Session,
  children
});

describe('session-groups', () => {
  const now = at(2026, 10, 17, 12, 0);

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 10, 17, 12, 0));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  describe('formatActivity', () => {
    it('shows HH:mm for today', () => {
      expect(formatActivity(at(2026, 10, 17, 9, 42), now)).toBe('09:42');
      expect(formatActivity(at(2026, 10, 17, 0, 5), now)).toBe('00:05');
    });

    it('shows date and time for an earlier day', () => {
      expect(formatActivity(at(2026, 10, 16, 16, 20), now)).toBe('Nov 16 · 16:20');
    });

    it('adds the year for another year', () => {
      expect(formatActivity(at(2025, 10, 16, 16, 20), now)).toBe('Nov 16, 2025 · 16:20');
    });

    it('returns an empty string for null', () => {
      expect(formatActivity(null, now)).toBe('');
    });

    it('shows HH:mm for a future timestamp on a later day, consistent with the Today group', () => {
      const future = at(2026, 10, 18, 3, 7);
      expect(groupLabel(future, now).label).toBe('Today');
      expect(formatActivity(future, now)).toBe('03:07');
    });

    it('contains no relative text', () => {
      expect(formatActivity(at(2026, 10, 17, 11, 55), now)).not.toContain('ago');
    });
  });

  describe('midnight boundary', () => {
    const justAfterMidnight = at(2026, 10, 17, 0, 0) + 30_000;

    it('23:59 yesterday is Yesterday and 00:00 today is Today', () => {
      expect(groupLabel(at(2026, 10, 16, 23, 59), justAfterMidnight).label).toBe('Yesterday');
      expect(groupLabel(at(2026, 10, 17, 0, 0), justAfterMidnight).label).toBe('Today');
    });
  });

  describe('groupLabel', () => {
    it('labels today, yesterday and the rolling week', () => {
      expect(groupLabel(at(2026, 10, 17, 8), now)).toEqual({ key: 'today', label: 'Today' });
      expect(groupLabel(at(2026, 10, 16, 8), now)).toEqual({ key: 'yesterday', label: 'Yesterday' });
      expect(groupLabel(at(2026, 10, 15, 8), now).label).toBe('This week');
      expect(groupLabel(at(2026, 10, 11, 8), now).label).toBe('This week');
    });

    it('uses a dated group from 7 days ago', () => {
      expect(groupLabel(at(2026, 10, 10, 8), now)).toEqual({ key: '2026-11-10', label: 'Nov 10' });
    });

    it('adds the year for dates of another year', () => {
      expect(groupLabel(at(2025, 10, 10, 8), now)).toEqual({ key: '2025-11-10', label: 'Nov 10, 2025' });
    });

    it('treats a future timestamp as Today', () => {
      expect(groupLabel(now + 5 * 60_000, now).label).toBe('Today');
    });

    it('labels a missing timestamp Earlier', () => {
      expect(groupLabel(null, now)).toEqual({ key: 'earlier', label: 'Earlier' });
    });
  });

  describe('sessionActivity', () => {
    it('prefers updated, falls back to created, else null', () => {
      expect(sessionActivity({ id: 'a', time: { updated: 5, created: 1 } } as Session)).toBe(5);
      expect(sessionActivity({ id: 'a', time: { created: 1 } } as Session)).toBe(1);
      expect(sessionActivity({ id: 'a' } as Session)).toBeNull();
    });
  });

  describe('groupSessions', () => {
    it('orders groups Today, Yesterday, This week, dates newest first, Earlier and omits empty ones', () => {
      const input = [
        node('old2', 'o2', at(2026, 10, 10, 9)),
        node('none', 'n', undefined),
        node('week', 'w', at(2026, 10, 14, 9)),
        node('today', 't', at(2026, 10, 17, 9)),
        node('d3', 'd3', at(2026, 10, 3, 9)),
        node('old1', 'o1', at(2026, 10, 10, 15)),
        node('yest', 'y', at(2026, 10, 16, 9))
      ];
      const groups = groupSessions(input, now);
      expect(groups.map((g) => g.label)).toEqual([
        'Today',
        'Yesterday',
        'This week',
        'Nov 10',
        'Nov 3',
        'Earlier'
      ]);
      expect(groups.find((g) => g.label === 'Nov 10')!.items.map((n) => n.session.id)).toEqual(['old1', 'old2']);
    });

    it('omits empty groups', () => {
      const groups = groupSessions([node('a', 'a', at(2026, 10, 17, 9))], now);
      expect(groups.map((g) => g.label)).toEqual(['Today']);
    });

    it('sorts items newest first within a group', () => {
      const groups = groupSessions(
        [node('a', 'a', at(2026, 10, 17, 8)), node('b', 'b', at(2026, 10, 17, 10)), node('c', 'c', at(2026, 10, 17, 9))],
        now
      );
      expect(groups[0].items.map((n) => n.session.id)).toEqual(['b', 'c', 'a']);
    });

    it('keeps input order for equal timestamps', () => {
      const ts = at(2026, 10, 17, 9);
      const groups = groupSessions([node('x', 'x', ts), node('y', 'y', ts), node('z', 'z', ts)], now);
      expect(groups[0].items.map((n) => n.session.id)).toEqual(['x', 'y', 'z']);
    });

    it('puts a session without time in Earlier and uses created when updated is missing', () => {
      const created: SessionNode = { session: { id: 'c', title: 'c', time: { created: at(2026, 10, 17, 7) } } as Session, children: [] };
      const groups = groupSessions([node('none', 'n', undefined), created], now);
      expect(groups.map((g) => g.label)).toEqual(['Today', 'Earlier']);
      expect(groups[1].items[0].session.id).toBe('none');
    });

    it.each([
      [2026, 2, 8],
      [2026, 2, 29],
      [2026, 9, 25]
    ])('treats 23:30 the day before a DST change day (%i-%i-%i) as Yesterday', (y, m, d) => {
      const dstNow = at(y, m, d, 12, 0);
      const prev = at(y, m, d - 1, 23, 30);
      expect(groupLabel(prev, dstNow).label).toBe('Yesterday');
      expect(groupLabel(at(y, m, d, 0, 30), dstNow).label).toBe('Today');
    });
  });

  describe('filterNodes', () => {
    const child = (id: string, title: string): Session => ({ id, title, parentID: 'p' }) as Session;
    const nodes = [
      node('a', 'Fix login bug', 1),
      node('b', 'Add report', 2, [child('c1', 'Reviewer FIX run')]),
      node('c', '', 3)
    ];

    it('matches case-insensitively on a trimmed term', () => {
      expect(filterNodes(nodes, '  FIX ').map((n) => n.session.id)).toEqual(['a', 'b']);
    });

    it('returns the same reference for an empty or blank term', () => {
      expect(filterNodes(nodes, '')).toBe(nodes);
      expect(filterNodes(nodes, '   ')).toBe(nodes);
    });

    it('matches Untitled session for an empty title', () => {
      expect(filterNodes(nodes, 'untitled').map((n) => n.session.id)).toEqual(['c']);
    });

    it('keeps a parent whose child matches, without reducing the children', () => {
      const result = filterNodes(nodes, 'reviewer');
      expect(result.map((n) => n.session.id)).toEqual(['b']);
      expect(result[0].children).toBe(nodes[1].children);
    });

    it('returns an empty array when nothing matches', () => {
      expect(filterNodes(nodes, 'zzz')).toEqual([]);
    });
  });
});
