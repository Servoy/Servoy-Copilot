import type { Session } from '../models/opencode.models';
import type { SessionNode } from './chat-store.service';

export interface SessionGroup {
  readonly key: string;
  readonly label: string;
  readonly items: SessionNode[];
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DAY_MS = 86_400_000;

function pad(n: number): string {
  return n < 10 ? `0${n}` : `${n}`;
}

function daysAgo(ts: number, now: number): number {
  const a = new Date(now);
  const b = new Date(ts);
  const today = Date.UTC(a.getFullYear(), a.getMonth(), a.getDate());
  const then = Date.UTC(b.getFullYear(), b.getMonth(), b.getDate());
  return Math.round((today - then) / DAY_MS);
}

function dateLabel(ts: number, now: number): string {
  const d = new Date(ts);
  const base = `${MONTHS[d.getMonth()]} ${d.getDate()}`;
  return d.getFullYear() === new Date(now).getFullYear() ? base : `${base}, ${d.getFullYear()}`;
}

export function sessionActivity(s: Session): number | null {
  return s.time?.updated ?? s.time?.created ?? null;
}

export function formatActivity(ts: number | null, now: number): string {
  if (ts === null) {
    return '';
  }
  const d = new Date(ts);
  const clock = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  return daysAgo(ts, now) <= 0 ? clock : `${dateLabel(ts, now)} · ${clock}`;
}

export function groupLabel(ts: number | null, now: number): { key: string; label: string } {
  if (ts === null) {
    return { key: 'earlier', label: 'Earlier' };
  }
  const days = daysAgo(ts, now);
  if (days <= 0) {
    return { key: 'today', label: 'Today' };
  }
  if (days === 1) {
    return { key: 'yesterday', label: 'Yesterday' };
  }
  if (days < 7) {
    return { key: 'week', label: 'This week' };
  }
  const d = new Date(ts);
  return {
    key: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`,
    label: dateLabel(ts, now)
  };
}

function displayTitle(s: Session): string {
  return s.title && s.title.trim().length > 0 ? s.title : 'Untitled session';
}

export function filterNodes(nodes: SessionNode[], term: string): SessionNode[] {
  const needle = term.trim().toLowerCase();
  if (!needle) {
    return nodes;
  }
  return nodes.filter(
    (n) =>
      displayTitle(n.session).toLowerCase().includes(needle) ||
      n.children.some((c) => displayTitle(c).toLowerCase().includes(needle))
  );
}

export function groupSessions(nodes: SessionNode[], now: number): SessionGroup[] {
  const sorted = nodes
    .map((node, index) => ({ node, index, ts: sessionActivity(node.session) }))
    .sort((a, b) => (b.ts ?? -Infinity) - (a.ts ?? -Infinity) || a.index - b.index);
  const groups = new Map<string, { key: string; label: string; rank: number; items: SessionNode[] }>();
  for (const entry of sorted) {
    const { key, label } = groupLabel(entry.ts, now);
    let group = groups.get(key);
    if (!group) {
      const rank =
        key === 'today' ? 0 : key === 'yesterday' ? 1 : key === 'week' ? 2 : key === 'earlier' ? 4 : 3;
      group = { key, label, rank, items: [] };
      groups.set(key, group);
    }
    group.items.push(entry.node);
  }
  return [...groups.values()]
    .map((g, order) => ({ g, order }))
    .sort((a, b) => a.g.rank - b.g.rank || a.order - b.order)
    .map(({ g }) => ({ key: g.key, label: g.label, items: g.items }));
}
