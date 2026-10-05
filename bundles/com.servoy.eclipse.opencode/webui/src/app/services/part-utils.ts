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

/**
 * Whether a reasoning part's text is empty for rendering purposes. opencode
 * frequently emits a placeholder reasoning block whose entire text is just an
 * ellipsis ("..." or the single "…" glyph, sometimes padded with whitespace) -
 * a stub for a thinking block the model never filled in. Rendered as-is it
 * shows up as a stray muted "..." row with a left guide bar, attached to
 * nothing. Treat such a part as empty so it is dropped.
 */
export function isBlankReasoningText(text: string | undefined): boolean {
  if (!text) {
    return true;
  }
  const trimmed = text.trim();
  if (trimmed.length === 0) {
    return true;
  }
  // Only dots / unicode ellipsis (and whitespace) -> nothing meaningful.
  return /^[.\u2026\s]+$/.test(trimmed);
}

/** Reasoning parts - rendered in a muted block. Placeholder "..." is dropped. */
export function isReasoningPart(part: Part): boolean {
  return part.type === 'reasoning' && !isBlankReasoningText(part.text);
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
  skill: 'Load Skill',
  question: 'Question',
  execute: 'Script'
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
 * The source code of a Code Mode {@code execute} ("Script") tool part, taken
 * from its {@code input.code}. Empty string for any other tool or when no code
 * was captured yet.
 */
export function toolScript(part: Part): string {
  const input = part.state?.input;
  if (!input || typeof input !== 'object') {
    return '';
  }
  const code = (input as Record<string, unknown>)['code'];
  return typeof code === 'string' ? code : '';
}

/** True for the Code Mode {@code execute} tool, which runs a script. */
export function isScriptTool(part: Part): boolean {
  return part.type === 'tool' && part.tool === 'execute';
}

/**
 * A compact summary of the MCP tool calls a Code Mode script makes, e.g.
 * {@code "eclipse-ide.getCompilationErrors"} - mirroring OpenChamber's inline
 * Script summary. Scans the script for {@code tools["server"].method(} and
 * {@code tools.server.method(} call sites, de-duplicates them in order, drops a
 * {@code _codemode} suffix from the server name, and caps the list so the row
 * stays short. Returns '' when the script makes no recognizable tool call.
 */
export function scriptToolSummary(code: string): string {
  if (!code) {
    return '';
  }
  const re = /tools(?:\[\s*["']([^"']+)["']\s*\]|\.([A-Za-z0-9_$]+))\.([A-Za-z0-9_$]+)\s*\(/g;
  const seen: string[] = [];
  let match: RegExpExecArray | null;
  while ((match = re.exec(code)) !== null) {
    const server = (match[1] ?? match[2] ?? '').replace(/_codemode$/, '');
    const method = match[3] ?? '';
    if (!server || !method) {
      continue;
    }
    const label = `${server}.${method}`;
    if (!seen.includes(label)) {
      seen.push(label);
    }
  }
  if (seen.length === 0) {
    return '';
  }
  const MAX = 2;
  if (seen.length <= MAX) {
    return seen.join(', ');
  }
  return `${seen.slice(0, MAX).join(', ')} +${seen.length - MAX}`;
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
  // A Code Mode script has no single meaningful argument; summarize the tool
  // calls it makes instead (like OpenChamber's "Script eclipse-ide.foo").
  if (isScriptTool(part)) {
    return scriptToolSummary(toolScript(part));
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
 * Whether a tool row can be expanded to reveal more. True when there is output
 * worth showing, or - for a Code Mode script - when there is script source to
 * reveal even before any output exists.
 */
export function isToolExpandable(part: Part): boolean {
  return hasToolOutput(part) || (isScriptTool(part) && toolScript(part).trim().length > 0);
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
