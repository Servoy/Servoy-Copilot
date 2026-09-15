import { Part } from '../models/opencode.models';

/**
 * Part normalisation / filtering, mirroring the rules in OpenChamber's
 * {@code partUtils.ts} (reimplemented, not copied): decide which opencode
 * {@code Part}s are shown to the user and how they map to rendered blocks.
 */

/** A synthetic/system-reminder part injected by opencode - never rendered. */
export function isSyntheticPart(part: Part): boolean {
  if (part.synthetic === true) {
    return true;
  }
  if (part.type === 'text' && typeof part.text === 'string') {
    const trimmed = part.text.trimStart();
    if (trimmed.startsWith('<system-reminder')) {
      return true;
    }
  }
  return false;
}

/** Text parts that carry visible assistant/user prose. */
export function isTextPart(part: Part): boolean {
  return part.type === 'text' && !isSyntheticPart(part) && !!part.text && part.text.trim().length > 0;
}

/** Reasoning parts - rendered in a muted block. */
export function isReasoningPart(part: Part): boolean {
  return part.type === 'reasoning' && !!part.text && part.text.trim().length > 0;
}

/** Tool-call parts - rendered as a compact collapsed row. */
export function isToolPart(part: Part): boolean {
  return part.type === 'tool';
}

/** Whether the part should be rendered at all in iteration 1. */
export function isRenderablePart(part: Part): boolean {
  if (isSyntheticPart(part)) {
    return false;
  }
  return isTextPart(part) || isReasoningPart(part) || isToolPart(part);
}

/** A short human label for a tool part (name + status). */
export function toolLabel(part: Part): string {
  const name = part.tool || 'tool';
  const status = part.state?.status;
  return status ? `${name} · ${status}` : name;
}

/**
 * Merges an incoming (possibly partial) part into an existing ordered part
 * array, keyed by part id. Returns a new array. Used to apply streamed
 * {@code message.part.updated} deltas.
 */
export function upsertPart(parts: Part[], incoming: Part): Part[] {
  const id = incoming.id;
  if (!id) {
    return [...parts, incoming];
  }
  const idx = parts.findIndex((p) => p.id === id);
  if (idx === -1) {
    return [...parts, incoming];
  }
  const next = parts.slice();
  next[idx] = { ...next[idx], ...incoming };
  return next;
}
