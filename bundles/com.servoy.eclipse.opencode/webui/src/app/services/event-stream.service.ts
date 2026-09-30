import { ApplicationRef, Injectable, inject } from '@angular/core';
import { Observable, Subject } from 'rxjs';

import { dbg, debugEnabled } from './debug-log';

/**
 * A parsed opencode bus event.
 *
 * opencode V2 emits flat events: a {@code type} discriminator (e.g.
 * {@code message.updated}, {@code message.part.updated}, {@code session.updated},
 * {@code session.idle}, {@code session.error}, {@code session.created},
 * {@code session.deleted}) plus a top-level {@code data} object with the
 * payload, an {@code id}, and an optional {@code location}. This differs from
 * V1, which nested the payload under {@code properties}. The store reads
 * {@code data} (falling back to {@code properties} for safety), so both shapes
 * work.
 */
export interface OpencodeEvent {
  type: string;
  /** V2 payload container. */
  data?: Record<string, unknown>;
  /** V1 payload container (kept as a fallback). */
  properties?: Record<string, unknown>;
  [key: string]: unknown;
}

/**
 * Opens an {@link EventSource} on {@code ./rest_api/event} and dispatches parsed
 * opencode bus events. Reconnects automatically on drop.
 *
 * {@code EventSource} is strictly same-origin and cannot set custom headers,
 * which is exactly why the BFF proxy is required - it forwards to opencode's
 * {@code /event} stream on loopback and injects the project directory.
 *
 * The app runs zoneless. An {@code EventSource} callback fires entirely outside
 * Angular's reactive context, so signal writes made from it do NOT reliably
 * schedule change detection: they only get rendered when some other tick happens
 * to run (an HttpClient poll, a streamed part, a user interaction). That is why
 * a lazily-generated session title could sit invisible until the next 15s status
 * poll. {@link NgZone#run} does nothing here - zoneless apps have no
 * zone-driven change detection - so instead we explicitly run one
 * {@link ApplicationRef#tick} after dispatching, coalesced onto a microtask so a
 * burst of events (e.g. streamed parts) triggers a single render. Every bus
 * event - title updates, {@code session.idle}, a newly created session - now
 * renders as soon as it arrives.
 */
@Injectable({ providedIn: 'root' })
export class EventStreamService {
  private readonly appRef = inject(ApplicationRef);
  private eventSource: EventSource | null = null;
  private readonly events$ = new Subject<OpencodeEvent>();
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private started = false;
  /** Guards against scheduling more than one coalesced tick at a time. */
  private tickScheduled = false;

  /** Stream of parsed opencode bus events for the whole server. */
  events(): Observable<OpencodeEvent> {
    this.ensureConnected();
    return this.events$.asObservable();
  }

  private ensureConnected(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    this.connect();
  }

  private connect(): void {
    this.close();
    // Relative to the app base href (/servoy_ai/), same origin as the servlet.
    const source = new EventSource('rest_api/event');
    this.eventSource = source;

    source.onmessage = (evt) => {
      let parsed: OpencodeEvent | null = null;
      try {
        parsed = JSON.parse(evt.data) as OpencodeEvent;
      } catch {
        // Non-JSON keep-alive / heartbeat comment - ignore.
        return;
      }
      if (parsed) {
        if (debugEnabled) {
          const payload = parsed.data ?? parsed.properties;
          const title =
            parsed.type === 'session.updated'
              ? (payload?.['info'] as { title?: string } | undefined)?.title
              : undefined;
          dbg(
            'sse',
            `arrive type=${parsed.type}` +
              (title !== undefined ? ` title=${JSON.stringify(title)}` : '')
          );
        }
        this.events$.next(parsed);
        // Signal writes made by subscribers ran outside Angular; force a
        // (coalesced) render so the UI reflects them immediately.
        this.scheduleTick();
      }
    };

    source.onerror = () => {
      // The browser retries on its own, but if it moved to CLOSED we force a
      // fresh connection after a short backoff.
      if (source.readyState === EventSource.CLOSED) {
        this.scheduleReconnect();
      }
    };
  }

  /**
   * Run one change-detection pass, coalescing a burst of events into a single
   * render. Deferred to a microtask so it never re-enters an in-progress tick,
   * and guarded so streamed parts arriving back-to-back don't each force a
   * separate synchronous render.
   */
  private scheduleTick(): void {
    if (this.tickScheduled) {
      return;
    }
    this.tickScheduled = true;
    queueMicrotask(() => {
      this.tickScheduled = false;
      try {
        this.appRef.tick();
      } catch {
        // A tick already in progress rendered our changes anyway.
      }
    });
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer != null) {
      return;
    }
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, 2000);
  }

  private close(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }
}
