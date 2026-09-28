import { Injectable, computed, inject, signal } from '@angular/core';

import { forkJoin } from 'rxjs';

import {
  MessageInfo,
  MessageWithParts,
  Part,
  SendPart,
  Session,
  ToolState
} from '../models/opencode.models';
import { dbg } from './debug-log';
import { EventStreamService, OpencodeEvent } from './event-stream.service';
import { OpencodeApiService } from './opencode-api.service';
import { upsertPart } from './part-utils';
import {
  SessionExportData,
  downloadFile,
  sessionExportBaseName,
  sessionExportToJson
} from './session-export';

/** A message plus its ordered parts, held reactively in the store. */
export interface ChatMessage {
  info: MessageInfo;
  parts: Part[];
}

/**
 * A top-level session with its subagent (child) sessions, for the sidebar tree.
 */
export interface SessionNode {
  session: Session;
  children: Session[];
}

/**
 * Holds the active session id, its ordered messages, and streaming state.
 * Seeded from {@code GET /session/:id/message} on session open, then updated
 * live from the {@code /event} bus stream.
 */
@Injectable({ providedIn: 'root' })
export class ChatStore {
  private readonly api = inject(OpencodeApiService);
  private readonly eventStream = inject(EventStreamService);

  /** All sessions returned by the server, top-level and subagent children. */
  private readonly allSessions = signal<Session[]>([]);

  /** Top-level sessions only (the tree roots). */
  readonly sessions = computed(() => this.allSessions().filter((s) => !s.parentID));

  readonly activeSessionId = signal<string | null>(null);
  readonly messages = signal<ChatMessage[]>([]);
  readonly streaming = signal<boolean>(false);
  readonly error = signal<string | null>(null);

  readonly hasActiveSession = computed(() => this.activeSessionId() !== null);

  /**
   * The sidebar tree: each top-level session with its subagent (child)
   * sessions nested underneath, children newest-first. opencode gives every
   * subagent its own session carrying a {@code parentID} pointing at the
   * session that spawned it (e.g. an "@Reviewer subagent" session); nesting
   * them keeps the list uncluttered while still letting the user drill in.
   */
  readonly sessionTree = computed<SessionNode[]>(() => {
    const all = this.allSessions();
    const childrenByParent = new Map<string, Session[]>();
    for (const s of all) {
      if (!s.parentID) {
        continue;
      }
      const siblings = childrenByParent.get(s.parentID) ?? [];
      siblings.push(s);
      childrenByParent.set(s.parentID, siblings);
    }
    const byRecency = (a: Session, b: Session) =>
      (b.time?.updated ?? b.time?.created ?? 0) - (a.time?.updated ?? a.time?.created ?? 0);
    return all
      .filter((s) => !s.parentID)
      .map((session) => ({
        session,
        children: (childrenByParent.get(session.id) ?? []).sort(byRecency)
      }));
  });

  /**
   * Whether the active session is a subagent (child) session. Chatting is
   * disabled for these: you cannot send a prompt directly to a subagent, you
   * only view its conversation.
   */
  readonly activeIsSubagent = computed(() => {
    const id = this.activeSessionId();
    if (!id) {
      return false;
    }
    return !!this.allSessions().find((s) => s.id === id)?.parentID;
  });

  constructor() {
    this.eventStream.events().subscribe((evt) => this.onEvent(evt));
  }

  refreshSessions(): void {
    this.api.listSessions().subscribe({
      next: (sessions) => this.allSessions.set(sessions ?? []),
      error: (err) => this.error.set(this.describe(err))
    });
  }

  /**
   * One-time startup: load the session list, then open the most recent session
   * for this directory. If there are none, start a fresh draft (no server
   * session created until the first message). Later {@link refreshSessions}
   * calls (after rename/archive/delete) must NOT re-open anything, so the
   * auto-open lives here rather than in {@link refreshSessions}.
   */
  bootstrap(): void {
    this.api.listSessions().subscribe({
      next: (sessions) => {
        const all = sessions ?? [];
        this.allSessions.set(all);
        const mostRecent = this.mostRecentSession(all);
        if (mostRecent) {
          this.openSession(mostRecent.id);
        } else {
          this.newSession();
        }
      },
      error: (err) => this.error.set(this.describe(err))
    });
  }

  /**
   * The session to reopen on startup: the most recently updated non-archived,
   * top-level session. opencode already returns the list newest-first, but we
   * sort defensively and skip archived sessions and child (sub-agent) sessions.
   */
  private mostRecentSession(sessions: Session[]): Session | null {
    const candidates = sessions.filter((s) => !s.parentID && !s.time?.archived);
    if (candidates.length === 0) {
      return null;
    }
    return candidates.reduce((best, s) => {
      const bestAt = best.time?.updated ?? best.time?.created ?? 0;
      const at = s.time?.updated ?? s.time?.created ?? 0;
      return at > bestAt ? s : best;
    });
  }

  openSession(id: string): void {
    this.activeSessionId.set(id);
    this.messages.set([]);
    this.error.set(null);
    this.api.listMessages(id).subscribe({
      next: (msgs) => this.messages.set((msgs ?? []).map((m) => this.toChatMessage(m))),
      error: (err) => this.error.set(this.describe(err))
    });
  }

  /**
   * Start a fresh, unsaved session. No session is created on the server yet -
   * the empty session would otherwise show in the list as an untitled
   * "New session" placeholder. {@link send} creates it lazily on the first
   * message, at which point opencode generates a descriptive title and the
   * session appears in the list already named.
   */
  newSession(): void {
    this.activeSessionId.set(null);
    this.messages.set([]);
    this.error.set(null);
  }

  /** Rename a session, then refresh the list so the new title shows. */
  renameSession(id: string, title: string): void {
    const trimmed = title.trim();
    if (!trimmed) {
      return;
    }
    this.api.updateSessionTitle(id, trimmed).subscribe({
      next: () => this.refreshSessions(),
      error: (err) => this.error.set(this.describe(err))
    });
  }

  /**
   * Archive a session (hidden from the list, recoverable). Refreshes the list;
   * if the archived session was active, drops back to a fresh draft.
   */
  archiveSession(id: string): void {
    this.api.archiveSession(id).subscribe({
      next: () => this.afterRemoval(id),
      error: (err) => this.error.set(this.describe(err))
    });
  }

  /**
   * Export a session as JSON and trigger a browser download. Fetches the
   * session info and its full message history and emits them verbatim in the
   * {@code { info, messages: [{ info, parts }] }} structure that opencode's own
   * {@code opencode export} produces, so the file is byte-compatible with that
   * format.
   */
  exportSession(id: string): void {
    forkJoin({
      info: this.api.getSession(id),
      messages: this.api.listMessages(id)
    }).subscribe({
      next: ({ info, messages }) => {
        const data: SessionExportData = { info, messages: messages ?? [] };
        downloadFile(`${sessionExportBaseName(info)}.json`, sessionExportToJson(data), 'application/json');
      },
      error: (err) => this.error.set(this.describe(err))
    });
  }

  /** Permanently delete a session. Refreshes the list and resets if it was active. */
  deleteSession(id: string): void {
    this.api.deleteSession(id).subscribe({
      next: () => this.afterRemoval(id),
      error: (err) => this.error.set(this.describe(err))
    });
  }

  /** Shared cleanup after a session leaves the list (archive or delete). */
  private afterRemoval(id: string): void {
    if (this.activeSessionId() === id) {
      this.activeSessionId.set(null);
      this.messages.set([]);
    }
    this.refreshSessions();
  }

  /** Ensures a session exists (creating one if needed), then sends the prompt. */
  send(parts: SendPart[]): void {
    // You cannot chat directly with a subagent; the composer is disabled for
    // these, but guard here too so a stray call can't dispatch to a child.
    if (this.activeIsSubagent()) {
      return;
    }
    const id = this.activeSessionId();
    if (id) {
      this.dispatch(id, parts);
      return;
    }
    this.api.createSession().subscribe({
      next: (session) => {
        this.activeSessionId.set(session.id);
        this.messages.set([]);
        this.refreshSessions();
        this.dispatch(session.id, parts);
      },
      error: (err) => this.error.set(this.describe(err))
    });
  }

  abort(): void {
    const id = this.activeSessionId();
    if (!id) {
      return;
    }
    this.api.interrupt(id).subscribe({
      next: () => this.streaming.set(false),
      error: (err) => this.error.set(this.describe(err))
    });
  }

  private dispatch(id: string, parts: SendPart[]): void {
    this.error.set(null);
    this.streaming.set(true);
    this.api.sendPrompt(id, parts).subscribe({
      error: (err) => {
        this.streaming.set(false);
        this.error.set(this.describe(err));
      }
    });
  }

  // -----------------------------------------------------------------------
  // Event handling
  // -----------------------------------------------------------------------

  private onEvent(evt: OpencodeEvent): void {
    // V2 puts the payload on `data`; older builds used `properties`.
    const props = evt.data ?? evt.properties ?? {};
    switch (evt.type) {
      // --- session list / lifecycle ---
      case 'session.created':
      case 'session.updated':
        this.applySessionUpdate(props);
        break;
      case 'session.deleted':
        this.applySessionDeleted(props);
        break;

      // --- turn lifecycle ---
      case 'session.execution.started':
        if (this.matchesActiveSession(props)) {
          this.streaming.set(true);
        }
        break;
      case 'session.idle':
      case 'session.execution.succeeded':
      case 'session.execution.failed':
      case 'session.error':
        if (this.matchesActiveSession(props)) {
          this.streaming.set(false);
          if (evt.type === 'session.idle' || evt.type === 'session.execution.succeeded') {
            this.scheduleTitleRefresh();
          }
        }
        break;

      // --- streamed assistant text ---
      case 'session.text.started':
        this.onTextStarted(props);
        break;
      case 'session.text.delta':
        this.onTextDelta(props);
        break;
      case 'session.text.ended':
        this.onTextEnded(props);
        break;

      // --- streamed reasoning ---
      case 'session.reasoning.started':
        this.onReasoningStarted(props);
        break;
      case 'session.reasoning.delta':
        this.onReasoningDelta(props);
        break;
      case 'session.reasoning.ended':
        this.onReasoningEnded(props);
        break;

      // --- tool calls ---
      case 'session.tool.input.started':
        this.onToolStarted(props);
        break;
      case 'session.tool.called':
        this.onToolCalled(props);
        break;
      case 'session.tool.success':
        this.onToolResult(props, 'completed');
        break;
      case 'session.tool.error':
        this.onToolResult(props, 'error');
        break;

      // --- V1-style fallbacks (kept so an older/proxy build still renders) ---
      case 'message.updated':
      case 'message.part.updated':
        this.applyMessageEvent(evt.type, props);
        break;
      default:
        break;
    }
  }

  // -----------------------------------------------------------------------
  // V2 streaming: text
  // -----------------------------------------------------------------------

  private onTextStarted(props: Record<string, unknown>): void {
    if (!this.matchesActiveSession(props)) {
      return;
    }
    this.streaming.set(true);
    this.upsertStreamPart(props, 'text', { text: '' });
  }

  private onTextDelta(props: Record<string, unknown>): void {
    if (!this.matchesActiveSession(props)) {
      return;
    }
    const delta = typeof props['delta'] === 'string' ? (props['delta'] as string) : '';
    this.appendStreamText(props, 'text', delta);
    this.streaming.set(true);
  }

  private onTextEnded(props: Record<string, unknown>): void {
    if (!this.matchesActiveSession(props)) {
      return;
    }
    const finalText = typeof props['text'] === 'string' ? (props['text'] as string) : undefined;
    if (finalText != null) {
      this.upsertStreamPart(props, 'text', { text: finalText });
    }
  }

  // -----------------------------------------------------------------------
  // V2 streaming: reasoning
  // -----------------------------------------------------------------------

  private onReasoningStarted(props: Record<string, unknown>): void {
    if (!this.matchesActiveSession(props)) {
      return;
    }
    this.upsertStreamPart(props, 'reasoning', { text: '' });
  }

  private onReasoningDelta(props: Record<string, unknown>): void {
    if (!this.matchesActiveSession(props)) {
      return;
    }
    const delta = typeof props['delta'] === 'string' ? (props['delta'] as string) : '';
    this.appendStreamText(props, 'reasoning', delta);
  }

  private onReasoningEnded(props: Record<string, unknown>): void {
    if (!this.matchesActiveSession(props)) {
      return;
    }
    const finalText = typeof props['text'] === 'string' ? (props['text'] as string) : undefined;
    if (finalText != null) {
      this.upsertStreamPart(props, 'reasoning', { text: finalText });
    }
  }

  // -----------------------------------------------------------------------
  // V2 streaming: tools
  // -----------------------------------------------------------------------

  /**
   * A tool part is keyed by its opencode tool-call {@code id} (e.g.
   * {@code toolu_...}), not by {@code ordinal}, because the input/called/result
   * events for one call all carry that same id but no ordinal.
   */
  private onToolStarted(props: Record<string, unknown>): void {
    if (!this.matchesActiveSession(props)) {
      return;
    }
    this.streaming.set(true);
    const name = typeof props['name'] === 'string' ? (props['name'] as string) : 'tool';
    this.upsertToolPart(props, { tool: name, state: { status: 'running' } });
  }

  private onToolCalled(props: Record<string, unknown>): void {
    if (!this.matchesActiveSession(props)) {
      return;
    }
    const input = props['input'];
    this.upsertToolPart(props, { state: { status: 'running', input } });
  }

  private onToolResult(props: Record<string, unknown>, status: 'completed' | 'error'): void {
    if (!this.matchesActiveSession(props)) {
      return;
    }
    const output = this.flattenContent(props['content']);
    // Only carry fields this event actually provides; the tool-state merge in
    // upsertToolPart preserves the input/name captured by the earlier
    // tool.called/input events (a success event has no `input`).
    const state: Partial<ToolState> = { status, output };
    if (props['input'] !== undefined) {
      state.input = props['input'];
    }
    this.upsertToolPart(props, { state: state as ToolState });
  }

  /** Flattens a V2 {@code content: [{ type:'text', text }]} array into a string. */
  private flattenContent(content: unknown): string {
    if (!Array.isArray(content)) {
      return '';
    }
    return content
      .map((b) =>
        b && typeof b === 'object' && typeof (b as Record<string, unknown>)['text'] === 'string'
          ? ((b as Record<string, unknown>)['text'] as string)
          : ''
      )
      .filter((t) => t.length > 0)
      .join('\n');
  }

  /**
   * The assistant message id a streamed event belongs to. V2 uses
   * {@code assistantMessageID} on all delta/tool events.
   */
  private streamMessageId(props: Record<string, unknown>): string | null {
    const id = props['assistantMessageID'];
    return typeof id === 'string' ? id : null;
  }

  /** Ensures an assistant message exists, then applies {@code fn} to its parts. */
  private withAssistantMessage(
    props: Record<string, unknown>,
    fn: (parts: Part[]) => Part[]
  ): void {
    const messageID = this.streamMessageId(props);
    if (!messageID) {
      return;
    }
    const sessionID = props['sessionID'] as string | undefined;
    this.messages.update((msgs) => {
      const idx = msgs.findIndex((m) => m.info.id === messageID);
      if (idx === -1) {
        const info: MessageInfo = { id: messageID, sessionID, role: 'assistant' };
        return [...msgs, { info, parts: fn([]) }];
      }
      const next = msgs.slice();
      next[idx] = { ...next[idx], parts: fn(next[idx].parts) };
      return next;
    });
  }

  /**
   * Creates or replaces a text/reasoning part identified by
   * {@code <assistantMessageID>#<ordinal>#<type>}.
   */
  private upsertStreamPart(
    props: Record<string, unknown>,
    type: 'text' | 'reasoning',
    patch: Partial<Part>
  ): void {
    const messageID = this.streamMessageId(props);
    if (!messageID) {
      return;
    }
    const ordinal = props['ordinal'];
    const partId = `${messageID}#${ordinal ?? 0}#${type}`;
    const sessionID = props['sessionID'] as string | undefined;
    this.withAssistantMessage(props, (parts) =>
      upsertPart(parts, { id: partId, messageID, sessionID, type, ...patch })
    );
  }

  /** Appends streamed delta text onto an existing text/reasoning part. */
  private appendStreamText(
    props: Record<string, unknown>,
    type: 'text' | 'reasoning',
    delta: string
  ): void {
    const messageID = this.streamMessageId(props);
    if (!messageID || !delta) {
      return;
    }
    const ordinal = props['ordinal'];
    const partId = `${messageID}#${ordinal ?? 0}#${type}`;
    this.withAssistantMessage(props, (parts) => {
      const idx = parts.findIndex((p) => p.id === partId);
      if (idx === -1) {
        const sessionID = props['sessionID'] as string | undefined;
        return [...parts, { id: partId, messageID, sessionID, type, text: delta }];
      }
      const next = parts.slice();
      next[idx] = { ...next[idx], text: (next[idx].text ?? '') + delta };
      return next;
    });
  }

  /** Creates or merges a tool part identified by its opencode tool-call id. */
  private upsertToolPart(props: Record<string, unknown>, patch: Partial<Part>): void {
    const toolCallId = typeof props['id'] === 'string' ? (props['id'] as string) : null;
    if (!toolCallId) {
      return;
    }
    const partId = `tool#${toolCallId}`;
    const messageID = this.streamMessageId(props) ?? undefined;
    const sessionID = props['sessionID'] as string | undefined;
    this.withAssistantMessage(props, (parts) => {
      const idx = parts.findIndex((p) => p.id === partId);
      if (idx === -1) {
        return [
          ...parts,
          { id: partId, messageID, sessionID, type: 'tool', ...patch } as Part
        ];
      }
      const next = parts.slice();
      const prev = next[idx];
      next[idx] = {
        ...prev,
        ...patch,
        // Merge tool state rather than replacing, so a later result keeps the
        // earlier input/name.
        state: { ...prev.state, ...patch.state }
      };
      return next;
    });
  }

  /**
   * After a turn goes idle, opencode generates the session title with a
   * separate small LLM call and only then publishes a {@code session.updated}
   * carrying it. That publish can land seconds after {@code session.idle},
   * during a quiet period with no other events to ride on - so the sidebar can
   * sit on the "New session - <timestamp>" placeholder until the next unrelated
   * tick. Mirroring OpenChamber's OPE-193 fix, we defer a few short, targeted
   * session fetches after idle and stop as soon as a real (non-placeholder)
   * title is in hand, instead of waiting for the periodic status poll.
   */
  private scheduleTitleRefresh(): void {
    const id = this.activeSessionId();
    if (!id || this.hasRealTitle(id)) {
      return;
    }
    const delays = [800, 1600, 3200, 5000, 9000, 13000];
    delays.forEach((delay) => {
      setTimeout(() => {
        // Session may have changed, been removed, or already got its title.
        if (this.activeSessionId() !== id || this.hasRealTitle(id)) {
          return;
        }
        this.api.getSession(id).subscribe({
          next: (session) => {
            if (session?.id) {
              this.applySessionUpdate({ info: session });
            }
          },
          error: () => {
            // Transient - a later scheduled poll (or the periodic one) retries.
          }
        });
      }, delay);
    });
  }

  /**
   * Whether the session already carries a real title, i.e. not the transient
   * {@code "New session - <ISO timestamp>"} placeholder opencode assigns before
   * it generates one. Used to stop the post-idle title polling early.
   */
  private hasRealTitle(id: string): boolean {
    const title = this.allSessions().find((s) => s.id === id)?.title?.trim();
    if (!title) {
      return false;
    }
    return !/^New session - \d{4}-\d{2}-\d{2}T/.test(title);
  }

  /**
   * Merge a {@code session.updated} bus event into the session list. opencode
   * emits this when a session is created, renamed, or - crucially - when it
   * lazily generates a title after the first message. Without handling it, a
   * freshly created session shows untitled in the sidebar until a manual
   * reload, since {@link send} refreshes the list once before the title exists.
   */
  private applySessionUpdate(props: Record<string, unknown>): void {
    // V2 session.created/updated carry the session fields directly on `data`
    // (with the id under `sessionID`), and lazy-title fetches pass it under
    // `info`. Accept either. A fresh session.created may only carry
    // `sessionID`, so refetch the full record to obtain the title/timestamps.
    const info = this.extractSessionInfo(props);
    if (!info?.id) {
      return;
    }
    // DEBUG(title-timing): when a session.updated actually reaches the store and
    // what title it carries. Compare this timestamp to the [sse] arrival log.
    dbg('store', `applySessionUpdate id=${info.id} title=${JSON.stringify(info.title)}`);
    this.allSessions.update((sessions) => {
      const idx = sessions.findIndex((s) => s.id === info.id);
      if (idx === -1) {
        return [...sessions, info];
      }
      const next = sessions.slice();
      next[idx] = { ...next[idx], ...info };
      return next;
    });
  }

  /**
   * Extracts a {@link Session} from a session event payload. Handles the V2
   * shapes (fields on {@code data} with the id in {@code sessionID}, or a full
   * session under {@code info}) and the plain {@code info} object used by the
   * lazy-title refetch.
   */
  private extractSessionInfo(props: Record<string, unknown>): Session | null {
    const nested = props['info'] as Session | undefined;
    if (nested?.id) {
      return nested;
    }
    const id = (props['sessionID'] as string | undefined) ?? (props['id'] as string | undefined);
    if (!id) {
      return null;
    }
    // The rest of `data` (title, time, location, ...) belongs to the session.
    const { sessionID, ...rest } = props as Record<string, unknown> & { sessionID?: string };
    return { ...(rest as object), id } as Session;
  }

  /** Drop a session removed on the server (from a {@code session.deleted} event). */
  private applySessionDeleted(props: Record<string, unknown>): void {
    const info = props['info'] as Session | undefined;
    const id = (props['sessionID'] as string | undefined) ?? info?.id;
    if (!id) {
      return;
    }
    this.allSessions.update((sessions) => sessions.filter((s) => s.id !== id));
    if (this.activeSessionId() === id) {
      this.activeSessionId.set(null);
      this.messages.set([]);
    }
  }

  private applyMessageEvent(type: string, props: Record<string, unknown>): void {
    if (!this.matchesActiveSession(props)) {
      return;
    }
    if (type === 'message.updated') {
      const info = props['info'] as MessageInfo | undefined;
      if (info?.id) {
        this.ensureMessage(info);
      }
    } else {
      const part = props['part'] as Part | undefined;
      if (part?.messageID) {
        this.applyPart(part);
        this.streaming.set(true);
      }
    }
  }

  private matchesActiveSession(props: Record<string, unknown>): boolean {
    const active = this.activeSessionId();
    if (!active) {
      return false;
    }
    const info = props['info'] as MessageInfo | undefined;
    const part = props['part'] as Part | undefined;
    const sessionID =
      (props['sessionID'] as string | undefined) ??
      info?.sessionID ??
      (part?.sessionID as string | undefined);
    return sessionID === active;
  }

  private ensureMessage(info: MessageInfo): void {
    this.messages.update((msgs) => {
      const idx = msgs.findIndex((m) => m.info.id === info.id);
      if (idx === -1) {
        return [...msgs, { info, parts: [] }];
      }
      const next = msgs.slice();
      next[idx] = { ...next[idx], info: { ...next[idx].info, ...info } };
      return next;
    });
  }

  private applyPart(part: Part): void {
    this.messages.update((msgs) => {
      const idx = msgs.findIndex((m) => m.info.id === part.messageID);
      if (idx === -1) {
        // Part arrived before its message.updated - create a placeholder.
        const info: MessageInfo = {
          id: part.messageID as string,
          sessionID: part.sessionID,
          role: 'assistant'
        };
        return [...msgs, { info, parts: [part] }];
      }
      const next = msgs.slice();
      next[idx] = { ...next[idx], parts: upsertPart(next[idx].parts, part) };
      return next;
    });
  }

  private toChatMessage(m: MessageWithParts): ChatMessage {
    return { info: m.info, parts: m.parts ?? [] };
  }

  private describe(err: unknown): string {
    if (err && typeof err === 'object' && 'status' in err) {
      const status = (err as { status?: number }).status;
      if (status === 409 || status === 503) {
        return 'Servoy AI is starting or no solution is active. Please wait…';
      }
    }
    return 'Something went wrong talking to Servoy AI.';
  }
}
