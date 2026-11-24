import { Injectable, signal } from '@angular/core';

import { Part } from '../models/opencode.models';

export interface LoadedMessage {
  readonly parts: Part[];
}

const MAX_SESSIONS = 20;

/**
 * Snapshots of the messages the store has loaded, per session, so file chips
 * can include what a subagent child produced. A child snapshot is only
 * refreshed while that child is the active session; otherwise it is the last
 * state seen. Bounded to the {@link MAX_SESSIONS} most recently recorded.
 */
@Injectable({ providedIn: 'root' })
export class LoadedMessagesService {
  private readonly map = signal<ReadonlyMap<string, readonly LoadedMessage[]>>(new Map());

  readonly bySession = this.map.asReadonly();

  record(sessionId: string, messages: readonly LoadedMessage[]): void {
    this.map.update((current) => {
      const next = new Map(current);
      next.delete(sessionId);
      next.set(sessionId, messages);
      while (next.size > MAX_SESSIONS) {
        const oldest = next.keys().next().value as string;
        next.delete(oldest);
      }
      return next;
    });
  }

  forget(sessionId: string): void {
    if (!this.map().has(sessionId)) {
      return;
    }
    this.map.update((current) => {
      const next = new Map(current);
      next.delete(sessionId);
      return next;
    });
  }
}
