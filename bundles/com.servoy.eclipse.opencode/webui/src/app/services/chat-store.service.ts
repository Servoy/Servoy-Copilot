import { Injectable, computed, inject, signal } from '@angular/core';

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

/** A message plus its ordered parts, held reactively in the store. */
export interface ChatMessage {
  info: MessageInfo;
  parts: Part[];
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

  readonly sessions = signal<Session[]>([]);
  readonly activeSessionId = signal<string | null>(null);
  readonly messages = signal<ChatMessage[]>([]);
  readonly streaming = signal<boolean>(false);
  readonly error = signal<string | null>(null);

  readonly hasActiveSession = computed(() => this.activeSessionId() !== null);

  constructor() {
    this.eventStream.events().subscribe((evt) => this.onEvent(evt));
  }

  refreshSessions(): void {
    this.api.listSessions().subscribe({
      next: (sessions) => this.sessions.set(sessions ?? []),
      error: (err) => this.error.set(this.describe(err))
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

  newSession(): void {
    this.api.createSession().subscribe({
      next: (session) => {
        this.refreshSessions();
        this.openSession(session.id);
      },
      error: (err) => this.error.set(this.describe(err))
    });
  }

  /** Ensures a session exists (creating one if needed), then sends the prompt. */
  send(parts: SendPart[]): void {
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
