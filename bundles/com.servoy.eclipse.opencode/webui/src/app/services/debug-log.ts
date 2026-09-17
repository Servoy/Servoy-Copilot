/**
 * Lightweight, opt-in debug tracing for SSE / store / sidebar timing.
 *
 * Acts as a log level: nothing is buffered, logged, or rendered unless the app
 * URL carries {@code ?debug_view=true}. When enabled, entries are kept in a
 * small ring buffer (read by {@link debugLog}) and mirrored to the console; the
 * {@code svy-debug-overlay} component renders the buffer so timings can be read
 * straight from a DOM snapshot when no console is available.
 *
 * Because the flag is read once from {@code window.location}, calls to
 * {@link dbg} are effectively free (a boolean check) when the param is absent.
 */
export interface DebugEntry {
  t: number;
  tag: string;
  msg: string;
}

const buffer: DebugEntry[] = [];
const MAX = 200;

/** True when {@code ?debug_view=true} (or {@code =1}) is present in the URL. */
export const debugEnabled: boolean = (() => {
  try {
    const value = new URLSearchParams(window.location.search).get('debug_view');
    return value === '' || value === 'true' || value === '1';
  } catch {
    return false;
  }
})();

export function dbg(tag: string, msg: string): void {
  if (!debugEnabled) {
    return;
  }
  const entry: DebugEntry = { t: Date.now(), tag, msg };
  buffer.push(entry);
  if (buffer.length > MAX) {
    buffer.shift();
  }
  // eslint-disable-next-line no-console
  console.log(`[${tag}] ${new Date(entry.t).toISOString()} ${msg}`);
}

export function debugLog(): readonly DebugEntry[] {
  return buffer;
}

export function clearDebugLog(): void {
  buffer.length = 0;
}
