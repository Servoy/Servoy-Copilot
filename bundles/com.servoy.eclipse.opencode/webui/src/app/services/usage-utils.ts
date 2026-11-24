import { MessageInfo, MessageWithParts } from '../models/opencode.models';

export interface TokenUsage {
  input: number;
  output: number;
  reasoning: number;
  cacheRead: number;
  cacheWrite: number;
}

export interface ModelRef {
  id: string;
  providerID: string;
}

function num(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

/** Token counters of an assistant message, or null when it carries none. */
export function messageTokens(info: MessageInfo): TokenUsage | null {
  const tokens = info?.['tokens'];
  if (!isObject(tokens)) {
    return null;
  }
  const cache = isObject(tokens['cache']) ? tokens['cache'] : {};
  return {
    input: num(tokens['input']),
    output: num(tokens['output']),
    reasoning: num(tokens['reasoning']),
    cacheRead: num(cache['read']),
    cacheWrite: num(cache['write'])
  };
}

export function messageModel(info: MessageInfo): ModelRef | null {
  const model = info?.['model'];
  if (!isObject(model)) {
    return null;
  }
  const id = model['id'];
  const providerID = model['providerID'];
  if (typeof id !== 'string' || id.length === 0 || typeof providerID !== 'string' || providerID.length === 0) {
    return null;
  }
  return { id, providerID };
}

export function messageCost(info: MessageInfo): number | null {
  const cost = info?.['cost'];
  return typeof cost === 'number' && Number.isFinite(cost) ? cost : null;
}

export function isCompaction(info: MessageInfo): boolean {
  return info?.['type'] === 'compaction' || info?.role === 'compaction';
}

/** Sum of input + output + reasoning over the assistant messages that carry tokens. */
export function sessionTokens(messages: MessageWithParts[]): number {
  let total = 0;
  for (const m of messages) {
    if (m.info.role !== 'assistant' || isCompaction(m.info)) {
      continue;
    }
    const t = messageTokens(m.info);
    if (t) {
      total += t.input + t.output + t.reasoning;
    }
  }
  return total;
}

/** Whether any assistant message of the session carries tokens. */
export function hasSessionTokens(messages: MessageWithParts[]): boolean {
  return messages.some(
    (m) => m.info.role === 'assistant' && !isCompaction(m.info) && messageTokens(m.info) !== null
  );
}

/** Context fill of the newest non-compaction assistant message with tokens and a model. */
export function contextUsed(messages: MessageWithParts[]): { used: number; model: ModelRef | null } | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    const info = messages[i].info;
    if (info.role !== 'assistant' || isCompaction(info)) {
      continue;
    }
    const t = messageTokens(info);
    const model = messageModel(info);
    if (t && model) {
      return { used: t.input + t.cacheRead + t.cacheWrite + t.output + t.reasoning, model };
    }
  }
  return null;
}

export function contextPercent(used: number, limit: number | null | undefined): number | null {
  if (typeof limit !== 'number' || !Number.isFinite(limit) || limit <= 0) {
    return null;
  }
  return Math.round((used / limit) * 100);
}

export function formatTokens(n: number): string {
  if (n < 1000) {
    return String(Math.round(n));
  }
  if (n < 999_500) {
    return `${Math.round(n / 1000)}k`;
  }
  const m = Math.round((n / 1_000_000) * 10) / 10;
  return `${m}M`;
}
