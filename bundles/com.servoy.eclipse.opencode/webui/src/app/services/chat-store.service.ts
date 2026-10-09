import { Injectable, computed, inject, signal } from '@angular/core';

import { forkJoin } from 'rxjs';

import {
  FormAnswer,
  FormInfo,
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
import { isRenderablePart, upsertPart } from './part-utils';
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
 * The unsent composer state of a single session: the text being typed and any
 * attachments picked but not yet sent. Held per-session so switching sessions
 * (or opening a subagent and coming back) preserves each session's own draft.
 */
export interface ComposerDraft {
  text: string;
  attachments: DraftAttachment[];
}

/** A composer attachment (file/image) kept in a draft. Mirrors {@code Attachment}. */
export interface DraftAttachment {
  filename: string;
  mime: string;
  url: string;
}

/** The empty draft used for a session with nothing typed yet. */
const EMPTY_DRAFT: ComposerDraft = { text: '', attachments: [] };

/**
 * The draft key for the fresh, not-yet-created session (when
 * {@code activeSessionId} is {@code null}). Switched to the real session id by
 * {@link ChatStore#send} once opencode creates the session, so a draft typed
 * before the first send is not lost.
 */
const NEW_SESSION_DRAFT_KEY = '__new__';

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
  readonly error = signal<string | null>(null);

  /**
   * The set of sessions with a turn currently streaming on the server, keyed by
   * session id. This is per-session, not a single global flag: a turn dispatched
   * in one session keeps running when you switch to another, so the stop/send
   * button must reflect the session you are *looking at*, not whichever session
   * happened to start a turn last. Entries are added on dispatch / an
   * execution-started event and removed on idle / succeeded / failed / error.
   */
  private readonly streamingSessions = signal<ReadonlySet<string>>(new Set());

  /**
   * Whether the *active* session has a turn streaming. The composer binds its
   * stop/send button to this, so the button belongs to the open session rather
   * than to the app as a whole.
   */
  readonly streaming = computed<boolean>(() => {
    const id = this.activeSessionId();
    return id !== null && this.streamingSessions().has(id);
  });

  /**
   * The unsent composer state per session, keyed by session id (and by
   * {@link NEW_SESSION_DRAFT_KEY} for the not-yet-created session). Preserved
   * across session switches so each session keeps its own half-typed message
   * and attachments. An entry is cleared once its prompt is sent.
   */
  private readonly drafts = signal<ReadonlyMap<string, ComposerDraft>>(new Map());

  /**
   * The draft of the active session (or of the fresh unsaved session). The
   * composer binds its text/attachments to this; {@link setActiveDraft} writes
   * changes back under the active session's key.
   */
  readonly activeDraft = computed<ComposerDraft>(() => {
    const key = this.draftKey();
    return this.drafts().get(key) ?? EMPTY_DRAFT;
  });

  /**
   * The interactive form the active session is currently blocked on, or
   * {@code null}. Set from a {@code form.created} bus event (or recovered on
   * session open via {@link OpencodeApiService#listPendingForms}), cleared when
   * the form is replied to, cancelled, or the session changes. The composer is
   * disabled and a form card is shown while this is non-null. Only one pending
   * form per session is tracked (the server only surfaces one at a time for the
   * question flow).
   */
  private readonly pendingFormSig = signal<FormInfo | null>(null);
  readonly pendingForm = this.pendingFormSig.asReadonly();

  readonly hasActiveSession = computed(() => this.activeSessionId() !== null);

  /** The draft map key for the active session, or the fresh-session key. */
  private draftKey(): string {
    return this.activeSessionId() ?? NEW_SESSION_DRAFT_KEY;
  }

  /**
   * Persist the composer draft of the active session (or the fresh session).
   * An empty draft (no text, no attachments) is removed from the map so a
   * cleared composer leaves nothing behind. The composer calls this on every
   * change so switching sessions keeps each session's own half-typed message.
   */
  setActiveDraft(draft: ComposerDraft): void {
    const key = this.draftKey();
    const isEmpty = draft.text.trim().length === 0 && draft.attachments.length === 0;
    this.drafts.update((map) => {
      const next = new Map(map);
      if (isEmpty) {
        next.delete(key);
      } else {
        next.set(key, { text: draft.text, attachments: draft.attachments.slice() });
      }
      return next;
    });
  }

  /** Drop the draft stored under {@code key} (after its prompt was sent). */
  private clearDraft(key: string): void {
    this.drafts.update((map) => {
      if (!map.has(key)) {
        return map;
      }
      const next = new Map(map);
      next.delete(key);
      return next;
    });
  }

  /** Marks a session as having a turn streaming, or not. */
  private setSessionStreaming(id: string, streaming: boolean): void {
    this.streamingSessions.update((set) => {
      if (streaming === set.has(id)) {
        return set;
      }
      const next = new Set(set);
      if (streaming) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }

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
      // Sort top-level sessions newest-first too, not just children. Without
      // this the sidebar order followed the raw array order, so a session
      // appended live via a session.updated event (see applySessionUpdate)
      // showed up at the bottom until a full reload re-fetched the list in the
      // server's newest-first order.
      .sort(byRecency)
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

  /**
   * Tool metadata captured live from the event bus, keyed by tool-call id. The
   * subagent tool's {@code metadata.sessionID} arrives on a
   * {@code session.tool.progress} event while the subagent still runs, but
   * {@code GET /session/:id/message} does NOT persist it until the tool
   * completes. So when the parent is re-seeded mid-run (e.g. you open a running
   * subagent, then return to the parent via the tree) the re-seeded tool part
   * has no child id and its "Open subagent" link vanishes until completion.
   * Recording metadata here - for every session, active or not, since the SSE
   * stream is global - lets {@link backfillToolMetadata} restore it on re-seed,
   * so the link survives the round-trip.
   */
  private readonly toolMetaByCallId = new Map<string, Record<string, unknown>>();

  constructor() {
    this.eventStream.events().subscribe((evt) => this.onEvent(evt));
  }

  /**
   * Reconcile the session list with the server, MERGING rather than replacing.
   *
   * opencode's {@code GET /session} does not return a freshly created session
   * for a while (its list index lags, though {@code GET /session/:id} finds it),
   * so a blind {@code set(serverList)} would drop sessions we just created and
   * are actively showing - they would appear to vanish and only "come back"
   * after their title event re-added them. Merging keeps any locally known
   * session the server list omits; explicit removals ({@link afterRemoval}) and
   * {@code session.deleted} events are what prune the list.
   */
  refreshSessions(): void {
    this.api.listSessions().subscribe({
      next: (sessions) => {
        const server = sessions ?? [];
        const serverIds = new Set(server.map((s) => s.id));
        this.allSessions.update((local) => [...server, ...local.filter((s) => !serverIds.has(s.id))]);
      },
      error: (err) => this.error.set(this.describe('refreshSessions', err))
    });
  }

  /** Insert or replace a session in the list by id (kept for the sidebar tree). */
  private upsertSession(session: Session): void {
    this.allSessions.update((sessions) => {
      const i = sessions.findIndex((s) => s.id === session.id);
      if (i === -1) {
        return [session, ...sessions];
      }
      const next = sessions.slice();
      next[i] = { ...next[i], ...session };
      return next;
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
      error: (err) => this.error.set(this.describe('bootstrap', err))
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
    this.pendingFormSig.set(null);
    this.api.listMessages(id).subscribe({
      next: (msgs) =>
        this.messages.set(
          (msgs ?? [])
            .filter((m) => this.isRenderableMessage(m))
            .map((m) => this.backfillToolMetadata(this.toChatMessage(m)))
        ),
      error: (err) => this.error.set(this.describe('listMessages', err))
    });
    // Recover a form still pending on the server (e.g. after a reload, when the
    // form.created event was missed). Only apply if this session is still active.
    this.api.listPendingForms(id).subscribe({
      next: (forms) => {
        if (this.activeSessionId() === id && forms.length > 0) {
          this.pendingFormSig.set(forms[0]);
        }
      },
      error: (err) => {
        // Non-fatal: no form recovery, the user can still chat - but still log
        // the cause so a silent failure is visible in the debug overlay.
        dbg('error', `listPendingForms ${this.errorDetail(err)}`);
      }
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
    this.pendingFormSig.set(null);
  }

  /** Rename a session, then refresh the list so the new title shows. */
  renameSession(id: string, title: string): void {
    const trimmed = title.trim();
    if (!trimmed) {
      return;
    }
    this.api.updateSessionTitle(id, trimmed).subscribe({
      next: () => this.refreshSessions(),
      error: (err) => this.error.set(this.describe('renameSession', err))
    });
  }

  /**
   * Archive a session (hidden from the list, recoverable). Refreshes the list;
   * if the archived session was active, drops back to a fresh draft.
   */
  archiveSession(id: string): void {
    this.api.archiveSession(id).subscribe({
      next: () => this.afterRemoval(id),
      error: (err) => this.error.set(this.describe('archiveSession', err))
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
      error: (err) => this.error.set(this.describe('exportSession', err))
    });
  }

  /** Permanently delete a session. Refreshes the list and resets if it was active. */
  deleteSession(id: string): void {
    this.api.deleteSession(id).subscribe({
      next: () => this.afterRemoval(id),
      error: (err) => this.error.set(this.describe('deleteSession', err))
    });
  }

  /** Shared cleanup after a session leaves the list (archive or delete). */
  private afterRemoval(id: string): void {
    if (this.activeSessionId() === id) {
      this.activeSessionId.set(null);
      this.messages.set([]);
      this.pendingFormSig.set(null);
    }
    // The removed session's composer draft and streaming flag are no longer
    // meaningful; drop them so they can't leak onto a reused id.
    this.clearDraft(id);
    this.setSessionStreaming(id, false);
    // Drop it locally first so the merging refreshSessions() can't re-add it
    // (the server list no longer returns it, and merge preserves local-only
    // entries - which is exactly what we do NOT want for a removed session).
    this.allSessions.update((sessions) => sessions.filter((s) => s.id !== id));
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
      // The draft for this session has now been sent; clear it.
      this.clearDraft(id);
      this.dispatch(id, parts);
      return;
    }
    // Sending from the fresh (not-yet-created) session: its draft is keyed under
    // NEW_SESSION_DRAFT_KEY and must be cleared once sent.
    this.clearDraft(NEW_SESSION_DRAFT_KEY);
    this.api.createSession().subscribe({
      next: (session) => {
        this.activeSessionId.set(session.id);
        this.messages.set([]);
        // Add the new session to the list directly. We must NOT refreshSessions()
        // here: the server's list index does not include a just-created session
        // yet, so refreshing would immediately drop it (and any other session
        // created since the last full load). Its title arrives later via a
        // session.updated event, which upserts in place.
        this.upsertSession(session);
        this.dispatch(session.id, parts);
      },
      error: (err) => this.error.set(this.describe('createSession', err))
    });
  }

  abort(): void {
    const id = this.activeSessionId();
    if (!id) {
      return;
    }
    this.api.interrupt(id).subscribe({
      next: () => this.setSessionStreaming(id, false),
      error: (err) => this.error.set(this.describe('interrupt', err))
    });
  }

  /**
   * Answer the pending form and clear it. The reply unblocks the waiting agent
   * turn, which then resumes streaming via the normal {@code session.*} events.
   * A 409 (form already settled elsewhere) is treated as success - the form is
   * gone either way.
   */
  submitForm(answer: FormAnswer): void {
    const form = this.pendingFormSig();
    if (!form) {
      return;
    }
    this.error.set(null);
    this.api.replyToForm(form.sessionID, form.id, answer).subscribe({
      next: () => this.clearForm(form.id),
      error: (err) => {
        if (this.isAlreadySettled(err)) {
          this.clearForm(form.id);
          return;
        }
        this.error.set(this.describe('replyToForm', err));
      }
    });
  }

  /** Cancel the pending form (aborts the waiting turn) and clear it. */
  cancelPendingForm(): void {
    const form = this.pendingFormSig();
    if (!form) {
      return;
    }
    this.api.cancelForm(form.sessionID, form.id).subscribe({
      next: () => this.clearForm(form.id),
      error: (err) => {
        if (this.isAlreadySettled(err)) {
          this.clearForm(form.id);
          return;
        }
        this.error.set(this.describe('cancelForm', err));
      }
    });
  }

  /** Clears the pending form if it still matches {@code formID}. */
  private clearForm(formID: string): void {
    if (this.pendingFormSig()?.id === formID) {
      this.pendingFormSig.set(null);
    }
  }

  /** True when an HTTP error is a 409 (form already replied/cancelled). */
  private isAlreadySettled(err: unknown): boolean {
    return !!err && typeof err === 'object' && (err as { status?: number }).status === 409;
  }

  private dispatch(id: string, parts: SendPart[]): void {
    this.error.set(null);
    this.setSessionStreaming(id, true);
    // DEBUG(upload): record the shape of what we are about to send - the text
    // length and, for each file part, its mime and url scheme (data: vs file:)
    // without dumping the whole base64 payload. Visible in the ?debug_view
    // overlay to compare against what the running opencode build accepts.
    dbg(
      'upload',
      `dispatch files=${JSON.stringify(
        parts
          .filter((p) => p.type === 'file')
          .map((p) => ({ mime: p.mime, filename: p.filename, urlScheme: (p.url ?? '').slice(0, 12) }))
      )}`
    );
    // Optimistically render the user's own message. opencode V2 does not push a
    // bus event for the prompt itself (only the assistant's streamed text /
    // reasoning / tool events follow), so without this the user's message would
    // not appear in the transcript until a reload re-seeds from GET /message.
    this.appendOptimisticUserMessage(id, parts);
    this.api.sendPrompt(id, parts).subscribe({
      error: (err) => {
        this.setSessionStreaming(id, false);
        // describe('prompt', ...) logs the real server response (status +
        // opencode's message) to the debug overlay and console before returning
        // the short banner text, so an attachment rejection is never swallowed.
        this.error.set(this.describe('prompt', err));
      }
    });
  }

  /**
   * A compact, human-readable description of an HTTP error for the debug log:
   * the status plus opencode's own error message/body when present. opencode
   * returns {@code { message, field }} (or {@code { error }}) for a 4xx, so a
   * rejected attachment carries a useful reason here.
   */
  private errorDetail(err: unknown): string {
    if (!err || typeof err !== 'object') {
      return String(err);
    }
    const e = err as { status?: number; error?: unknown; message?: string };
    const status = e.status != null ? `status=${e.status}` : '';
    let body = '';
    if (typeof e.error === 'string') {
      body = e.error;
    } else if (e.error && typeof e.error === 'object') {
      const be = e.error as { message?: string; field?: string; error?: string };
      body = be.message ?? be.error ?? JSON.stringify(e.error);
      if (be.field) {
        body += ` (field=${be.field})`;
      }
    } else if (e.message) {
      body = e.message;
    }
    return `${status} ${body}`.trim();
  }

  /**
   * Appends a locally-rendered user message for a just-sent prompt. It is keyed
   * with a synthetic {@code local-user-*} id so a later reload (which re-seeds
   * from {@code GET /message} with the server's real message) does not produce a
   * duplicate: {@link openSession} replaces the whole list, dropping these
   * optimistic entries.
   */
  private appendOptimisticUserMessage(sessionID: string, parts: SendPart[]): void {
    const text = parts
      .filter((p) => p.type === 'text' && p.text)
      .map((p) => p.text)
      .join('\n');
    const messageID = `local-user-${Date.now()}`;
    const fileParts: Part[] = parts
      .filter((p) => p.type === 'file' && p.url)
      .map((p, i) => ({
        id: `${messageID}#file-${i}`,
        type: 'file',
        filename: p.filename,
        mime: p.mime,
        url: p.url,
        sessionID
      }));
    if (!text && fileParts.length === 0) {
      return;
    }
    const messageParts: Part[] = [];
    if (text) {
      messageParts.push({ id: `${messageID}#text`, type: 'text', text, messageID, sessionID });
    }
    messageParts.push(...fileParts);
    const message: ChatMessage = {
      info: { id: messageID, sessionID, role: 'user' },
      parts: messageParts
    };
    this.messages.update((msgs) => [...msgs, message]);
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

      // --- turn lifecycle (per session, not just the active one) ---
      case 'session.execution.started': {
        const id = this.eventSessionId(props);
        if (id) {
          this.setSessionStreaming(id, true);
        }
        break;
      }
      case 'session.idle':
      case 'session.execution.succeeded':
      case 'session.execution.failed':
      case 'session.error': {
        const id = this.eventSessionId(props);
        if (id) {
          this.setSessionStreaming(id, false);
          if (
            id === this.activeSessionId() &&
            (evt.type === 'session.idle' || evt.type === 'session.execution.succeeded')
          ) {
            this.scheduleTitleRefresh();
          }
        }
        break;
      }

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
      case 'session.tool.progress':
        this.onToolProgress(props);
        break;
      case 'session.tool.success':
        this.onToolResult(props, 'completed');
        break;
      case 'session.tool.error':
        this.onToolResult(props, 'error');
        break;

      // --- interactive forms (V2 Form flow) ---
      case 'form.created':
        this.onFormCreated(props);
        break;
      case 'form.replied':
      case 'form.cancelled':
        this.onFormSettled(props);
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
    this.markStreamingFromEvent(props);
    if (!this.matchesActiveSession(props)) {
      return;
    }
    this.upsertStreamPart(props, 'text', { text: '' });
  }

  private onTextDelta(props: Record<string, unknown>): void {
    this.markStreamingFromEvent(props);
    if (!this.matchesActiveSession(props)) {
      return;
    }
    const delta = typeof props['delta'] === 'string' ? (props['delta'] as string) : '';
    this.appendStreamText(props, 'text', delta);
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
    this.markStreamingFromEvent(props);
    if (!this.matchesActiveSession(props)) {
      return;
    }
    const name = typeof props['name'] === 'string' ? (props['name'] as string) : 'tool';
    this.upsertToolPart(props, { tool: name, state: { status: 'running' } });
  }

  /**
   * A streamed event (text/tool/part) implies its session has a turn running;
   * mark that session streaming regardless of whether it is the active one, so
   * a background session's stop/send state stays correct.
   */
  private markStreamingFromEvent(props: Record<string, unknown>): void {
    const id = this.eventSessionId(props);
    if (id) {
      this.setSessionStreaming(id, true);
    }
  }

  private onToolCalled(props: Record<string, unknown>): void {
    this.recordToolMeta(props);
    if (!this.matchesActiveSession(props)) {
      return;
    }
    const input = props['input'];
    const state: Partial<ToolState> = { status: 'running', input };
    if (props['metadata'] !== undefined) {
      state.metadata = props['metadata'];
    }
    this.upsertToolPart(props, { state: state as ToolState });
  }

  /**
   * A {@code session.tool.progress} event carries the tool's evolving
   * {@code metadata} while it still runs. For a subagent tool this is where the
   * spawned child {@code sessionID} first lands - so handling it is what lets
   * the "Open subagent" link appear during the run, not only once it finishes.
   */
  private onToolProgress(props: Record<string, unknown>): void {
    this.markStreamingFromEvent(props);
    this.recordToolMeta(props);
    if (!this.matchesActiveSession(props)) {
      return;
    }
    if (props['metadata'] === undefined) {
      return;
    }
    this.upsertToolPart(props, { state: { status: 'running', metadata: props['metadata'] } as ToolState });
  }

  private onToolResult(props: Record<string, unknown>, status: 'completed' | 'error'): void {
    this.recordToolMeta(props);
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
    // Carry the final metadata (e.g. a subagent's child sessionID) if present.
    if (props['metadata'] !== undefined) {
      state.metadata = props['metadata'];
    }
    this.upsertToolPart(props, { state: state as ToolState });
  }

  /**
   * Record a tool event's {@code metadata} by its tool-call id, for EVERY
   * session (not just the active one) - the SSE stream is global, so a subagent
   * running in a background session still delivers its progress here. Only a
   * non-empty metadata object is kept, and it is merged, so a later partial
   * event cannot wipe the {@code sessionID} an earlier one supplied. See
   * {@link toolMetaByCallId} and {@link backfillToolMetadata}.
   */
  private recordToolMeta(props: Record<string, unknown>): void {
    const callId = typeof props['id'] === 'string' ? (props['id'] as string) : null;
    const metadata = props['metadata'];
    if (!callId || !metadata || typeof metadata !== 'object' || Object.keys(metadata).length === 0) {
      return;
    }
    const prev = this.toolMetaByCallId.get(callId) ?? {};
    this.toolMetaByCallId.set(callId, { ...prev, ...(metadata as Record<string, unknown>) });
  }

  /**
   * Restore live-captured tool metadata onto a message's tool parts that came
   * back from {@code GET /message} without it. {@code GET /message} omits a
   * running tool's metadata, so a subagent re-seeded mid-run would lose its
   * child {@code sessionID} (and its "Open subagent" link) until it finished;
   * this fills it back in from {@link toolMetaByCallId}.
   */
  private backfillToolMetadata(message: ChatMessage): ChatMessage {
    let changed = false;
    const parts = message.parts.map((p) => {
      if (p.type !== 'tool' || !p.id) {
        return p;
      }
      const existing = (p.state?.metadata as Record<string, unknown> | undefined) ?? undefined;
      if (existing && Object.keys(existing).length > 0) {
        return p;
      }
      const cached = this.toolMetaByCallId.get(p.id);
      if (!cached) {
        return p;
      }
      changed = true;
      return { ...p, state: { ...p.state, metadata: cached } };
    });
    return changed ? { ...message, parts } : message;
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

  /**
   * Creates or merges a tool part identified by its opencode tool-call id.
   *
   * The id must be the RAW tool-call id ({@code toolu_...}), the same id
   * {@link mapV2Message} assigns a seeded tool part ({@code content[].id}). If
   * the live path used a different key (e.g. a {@code tool#} prefix), a tool
   * seeded from {@code GET /message} and then updated by a later live event
   * would not merge: the live update would spawn a second, nameless part that
   * renders as "Tool" with no subtitle/subagent link. That is exactly the
   * "open a running subagent, go back to the parent, watch it finish" case -
   * the parent is re-seeded while the subagent still runs, then its
   * success/progress event must land on the SAME part. Keying both paths by the
   * raw call id keeps them one part.
   */
  private upsertToolPart(props: Record<string, unknown>, patch: Partial<Part>): void {
    const toolCallId = typeof props['id'] === 'string' ? (props['id'] as string) : null;
    if (!toolCallId) {
      return;
    }
    const partId = toolCallId;
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
        this.markStreamingFromEvent(props);
      }
    }
  }

  // -----------------------------------------------------------------------
  // Interactive forms
  // -----------------------------------------------------------------------

  /**
   * A {@code form.created} event: the agent asked a question and the turn is now
   * blocked on the user. The payload carries the full {@link FormInfo} under
   * {@code form} (its own {@code sessionID} identifies which session it belongs
   * to). Only show it if it targets the active session.
   */
  private onFormCreated(props: Record<string, unknown>): void {
    const form = this.extractForm(props);
    if (!form) {
      return;
    }
    const active = this.activeSessionId();
    if (!active || form.sessionID !== active) {
      return;
    }
    dbg('store', `form.created id=${form.id} fields=${form.fields?.length ?? 0}`);
    this.pendingFormSig.set(form);
  }

  /**
   * A {@code form.replied} / {@code form.cancelled} event: the form is settled
   * (possibly by another client). Clear it if it matches the pending one.
   */
  private onFormSettled(props: Record<string, unknown>): void {
    const id =
      (props['id'] as string | undefined) ??
      (this.extractForm(props)?.id as string | undefined);
    if (id) {
      this.clearForm(id);
    }
  }

  /** Extracts a {@link FormInfo} from a form event payload. */
  private extractForm(props: Record<string, unknown>): FormInfo | null {
    const nested = props['form'] as FormInfo | undefined;
    if (nested?.id) {
      return nested;
    }
    // Some builds may put the fields directly on `data`.
    const id = props['id'] as string | undefined;
    const sessionID = props['sessionID'] as string | undefined;
    if (id && sessionID && Array.isArray(props['fields'])) {
      return props as unknown as FormInfo;
    }
    return null;
  }

  private matchesActiveSession(props: Record<string, unknown>): boolean {
    const active = this.activeSessionId();
    if (!active) {
      return false;
    }
    return this.eventSessionId(props) === active;
  }

  /**
   * The session id an event belongs to, regardless of whether it is the active
   * session. Used for per-session streaming state so a turn running in a
   * background session is tracked correctly. Looks at {@code sessionID} first,
   * then the nested message {@code info} / {@code part}.
   */
  private eventSessionId(props: Record<string, unknown>): string | null {
    const info = props['info'] as MessageInfo | undefined;
    const part = props['part'] as Part | undefined;
    return (
      (props['sessionID'] as string | undefined) ??
      info?.sessionID ??
      (part?.sessionID as string | undefined) ??
      null
    );
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

  /**
   * Whether a seeded V2 message should appear in the transcript. opencode emits
   * bookkeeping messages with no user-facing content - notably {@code idle}
   * markers (role {@code idle}) that carry no parts and no completion timestamp.
   * Those must be dropped: otherwise {@code MessageItemComponent} treats them as
   * an in-flight assistant turn and renders the "thinking" dots mid-transcript
   * next to already-answered messages. Keep every user message, and keep any
   * message that has at least one *renderable* part - a message whose only parts
   * are placeholders (e.g. an empty "..." reasoning stub opencode emits) would
   * otherwise seed as a stray "..." row attached to nothing.
   */
  private isRenderableMessage(m: MessageWithParts): boolean {
    if (m.info.role === 'user') {
      return true;
    }
    return (m.parts ?? []).some(isRenderablePart);
  }

  /**
   * Turn a caught error into the user-facing banner message AND record the full
   * detail (operation + status + opencode's own message) so nothing is silently
   * swallowed. This is the single choke point every store error passes through,
   * so {@code describe(context, err)} both logs the real cause to the
   * {@code ?debug_view} overlay (and the browser console) and returns the short
   * message for the banner. {@code context} names the operation that failed
   * (e.g. 'prompt', 'createSession', 'rename') so the log pinpoints it.
   */
  private describe(context: string, err: unknown): string {
    const detail = this.errorDetail(err);
    // Always surface the real cause: the debug overlay (when ?debug_view=true)
    // and the browser console. The banner only ever shows the short message.
    dbg('error', `${context} ${detail}`);
    try {
      console.error(`[servoy-ai] ${context} failed: ${detail}`, err);
    } catch {
      // console may be unavailable in some embedded hosts - never let logging
      // throw and mask the original error.
    }
    if (err && typeof err === 'object' && 'status' in err) {
      const status = (err as { status?: number }).status;
      if (status === 409 || status === 503) {
        return 'Servoy AI is starting or no solution is active. Please wait…';
      }
      // A 4xx carries a specific reason from opencode (e.g. a rejected
      // attachment). Surface it rather than the generic message - it is what
      // the user needs to understand why a prompt/upload failed.
      if (status != null && status >= 400 && status < 500) {
        const message = this.serverMessage(err);
        if (message) {
          return message;
        }
      }
    }
    return 'Something went wrong talking to Servoy AI.';
  }

  /** opencode's own error message from an HTTP error body, or null. */
  private serverMessage(err: unknown): string | null {
    const body = (err as { error?: unknown }).error;
    if (typeof body === 'string' && body.trim().length > 0) {
      return body.trim();
    }
    if (body && typeof body === 'object') {
      const be = body as { message?: string; error?: string };
      return (be.message ?? be.error)?.trim() ?? null;
    }
    return null;
  }
}
