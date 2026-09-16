import { MessageWithParts, Session } from '../models/opencode.models';

/**
 * Session export matching opencode's own {@code opencode export} output: the
 * raw session info plus every message with its ordered parts, in the exact
 * {@code { info, messages: [{ info, parts }] }} shape, serialized verbatim.
 * <p>
 * Nothing is transformed or filtered - the {@code GET /session/:id} and
 * {@code GET /session/:id/message} payloads are emitted unchanged so a
 * re-import (or any tool that consumes {@code opencode export} files) sees an
 * identical structure, including {@code step-start} parts, full tool state,
 * token/cost metadata, etc.
 */
export interface SessionExportData {
  info: Session;
  messages: MessageWithParts[];
}

/**
 * A filesystem-safe base name for an exported session, derived from its title
 * (falling back to the slug, then the id). Mirrors opencode's
 * {@code sessionExportFilename}.
 */
export function sessionExportBaseName(session: Session): string {
  const slug = typeof session['slug'] === 'string' ? (session['slug'] as string) : '';
  const name = (session.title && session.title.trim()) || slug || session.id;
  const clean = name
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/gi, '-')
    .replace(/^-+|-+$/g, '');
  return clean || session.id;
}

/** The raw JSON export, pretty-printed, matching the CLI structure exactly. */
export function sessionExportToJson(data: SessionExportData): string {
  return JSON.stringify(data, null, 2);
}

/** Trigger a browser download of {@code content} as {@code filename}. */
export function downloadFile(filename: string, content: string, mime: string): void {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
