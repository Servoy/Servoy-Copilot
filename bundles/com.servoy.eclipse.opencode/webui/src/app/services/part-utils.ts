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
    const trimmed = part.text.trim();
    if (trimmed.startsWith('<system-reminder')) {
      return true;
    }
    // opencode injects a bare "[system: ...]" continuation marker between tool
    // calls (e.g. "[system: tool calling continues]"). It is plumbing, not
    // assistant prose, so it must never surface in the transcript.
    if (/^\[system:[^\]]*\]$/.test(trimmed)) {
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

/** Map of raw opencode tool ids to a friendlier display name. */
const TOOL_DISPLAY_NAMES: Record<string, string> = {
  read: 'Read File',
  edit: 'Edit File',
  write: 'Write File',
  bash: 'Shell Command',
  glob: 'Find Files',
  grep: 'Search',
  webfetch: 'Fetch Web Page',
  list: 'List Directory',
  todowrite: 'Update Todos',
  task: 'Task',
  skill: 'Load Skill'
};

/** A human-friendly display name for a tool part (e.g. "read" -> "Read File"). */
export function toolDisplayName(part: Part): string {
  const raw = part.tool || 'tool';
  if (TOOL_DISPLAY_NAMES[raw]) {
    return TOOL_DISPLAY_NAMES[raw];
  }
  // Fall back to a title-cased version of the raw id (mcp tools etc.).
  return raw
    .replace(/[_-]+/g, ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

/**
 * A short, muted subtitle for a tool part: the most meaningful single argument
 * from the tool input (a file path, a command, a query). Returns '' when there
 * is nothing worth showing inline. Mirrors OpenChamber's inline tool summary.
 */
export function toolSubtitle(part: Part): string {
  const input = part.state?.input;
  if (!input || typeof input !== 'object') {
    return '';
  }
  const args = input as Record<string, unknown>;
  const candidate =
    args['filePath'] ??
    args['path'] ??
    args['command'] ??
    args['pattern'] ??
    args['query'] ??
    args['url'] ??
    args['name'] ?? // skill / subagent name, etc.
    args['description'];
  if (typeof candidate !== 'string') {
    return '';
  }
  return candidate.trim();
}

/**
 * Tools whose output is never worth showing the user - it's internal plumbing.
 * A loaded skill dumps its whole instruction file as "output"; nobody needs to
 * expand that, so the row stays non-expandable (name + subtitle only).
 */
const NON_EXPANDABLE_TOOLS = new Set(['skill']);

/** Whether a tool part has any output worth expanding to see. */
export function hasToolOutput(part: Part): boolean {
  if (part.tool && NON_EXPANDABLE_TOOLS.has(part.tool)) {
    return false;
  }
  return !!part.state?.output && part.state.output.trim().length > 0;
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
