import { Injectable, computed, inject, signal } from '@angular/core';

import { forkJoin } from 'rxjs';

import {
  MessageInfo,
  MessageWithParts,
  Part,
  SendPart,
  Session
} from '../models/opencode.models';
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
    this.api.abort(id).subscribe({
      next: () => this.streaming.set(false),
      error: (err) => this.error.set(this.describe(err))
    });
  }

  private dispatch(id: string, parts: SendPart[]): void {
    this.error.set(null);
    this.streaming.set(true);
    this.api.sendPromptAsync(id, parts).subscribe({
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
    const props = evt.properties ?? {};
    switch (evt.type) {
      case 'message.updated':
      case 'message.part.updated':
        this.applyMessageEvent(evt.type, props);
        break;
      case 'session.updated':
        this.applySessionUpdate(props);
        break;
      case 'session.deleted':
        this.applySessionDeleted(props);
        break;
      case 'session.idle':
      case 'session.error':
        if (this.matchesActiveSession(props)) {
          this.streaming.set(false);
        }
        break;
      default:
        break;
    }
  }

  /**
   * Merge a {@code session.updated} bus event into the session list. opencode
   * emits this when a session is created, renamed, or - crucially - when it
   * lazily generates a title after the first message. Without handling it, a
   * freshly created session shows untitled in the sidebar until a manual
   * reload, since {@link send} refreshes the list once before the title exists.
   */
  private applySessionUpdate(props: Record<string, unknown>): void {
    const info = props['info'] as Session | undefined;
    if (!info?.id) {
      return;
    }
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
