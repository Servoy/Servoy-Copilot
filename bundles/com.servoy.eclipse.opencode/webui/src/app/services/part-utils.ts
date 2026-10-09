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

/**
 * opencode writes a text part whose entire content is the literal
 * {@code "(empty)"} placeholder when a model step produced a tool call but no
 * prose of its own (common right after a subagent/tool dispatch). It carries no
 * information - rendered as-is it is a stray "(empty)" line next to the tool
 * row - so it is treated as empty, the same way the "..." reasoning stub is.
 */
export function isPlaceholderText(text: string | undefined): boolean {
  return (text ?? '').trim() === '(empty)';
}

/** Text parts that carry visible assistant/user prose. */
export function isTextPart(part: Part): boolean {
  return (
    part.type === 'text' &&
    !isSyntheticPart(part) &&
    !!part.text &&
    part.text.trim().length > 0 &&
    !isPlaceholderText(part.text)
  );
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

/**
 * File/image attachment parts - a user's uploaded or pasted file. Rendered as
 * an inline thumbnail (image) or a small chip (other files) at the top of the
 * message, so you can see what was attached. Only shown when it has a URL to
 * point at (a {@code data:} URL or a file reference).
 */
export function isFilePart(part: Part): boolean {
  return (part.type === 'file' || part.type === 'media') && typeof part.url === 'string' && part.url.length > 0;
}

/**
 * Whether a file part is an image (shown as a thumbnail rather than a chip),
 * decided from its mime type or a {@code data:image/...} URL.
 */
export function isImageFilePart(part: Part): boolean {
  if (!isFilePart(part)) {
    return false;
  }
  const mime = typeof part.mime === 'string' ? part.mime : '';
  if (mime.startsWith('image/')) {
    return true;
  }
  const url = part.url ?? '';
  return url.startsWith('data:image/');
}

/** Whether the part should be rendered at all in iteration 1. */
export function isRenderablePart(part: Part): boolean {
  if (isSyntheticPart(part)) {
    return false;
  }
  return isTextPart(part) || isReasoningPart(part) || isToolPart(part) || isFilePart(part);
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
  task: 'Subagent',
  subagent: 'Subagent',
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
 * True for the tool that spawns a subagent (its own child session). opencode
 * names it {@code subagent}; the core variant is {@code task}.
 */
export function isSubagentTool(part: Part): boolean {
  return part.type === 'tool' && (part.tool === 'subagent' || part.tool === 'task');
}

/**
 * The child session id a subagent tool part spawned (or continued), or null.
 * The row for it is a link into that session - the same navigation the sidebar
 * tree offers. The id is found either on the tool input ({@code sessionID},
 * present when the call continued an existing child) or in the tool output,
 * whose first block opens with {@code <subagent sessionID="ses_...">} when a new
 * child was created.
 */
export function subagentSessionId(part: Part): string | null {
  if (!isSubagentTool(part)) {
    return null;
  }
  // 1. The authoritative location: opencode writes the child id to the tool
  //    state metadata, emitted on a `session.tool.progress` event as soon as the
  //    subagent is dispatched - so this is set while the subagent is still
  //    running, which is what lets the "Open" link appear during the run.
  const metadata = (part.state as Record<string, unknown> | undefined)?.['metadata'];
  if (metadata && typeof metadata === 'object') {
    const id = (metadata as Record<string, unknown>)['sessionID'];
    if (typeof id === 'string' && id.length > 0) {
      return id;
    }
  }
  // 2. The input, when the call continued an existing child session.
  const input = part.state?.input;
  if (input && typeof input === 'object') {
    const id = (input as Record<string, unknown>)['sessionID'];
    if (typeof id === 'string' && id.length > 0) {
      return id;
    }
  }
  // 3. Fallback: parse the completed output's opening marker.
  const output = part.state?.output;
  if (typeof output === 'string') {
    const match = /<subagent\s+sessionID="([^"]+)"/.exec(output);
    if (match) {
      return match[1];
    }
  }
  return null;
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
    args['id'] ?? // the skill tool identifies the loaded skill by its id
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
