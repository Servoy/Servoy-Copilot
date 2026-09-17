import { Injectable, NgZone, inject } from '@angular/core';
import { Observable, Subject } from 'rxjs';

import { dbg, debugEnabled } from './debug-log';

/**
 * A parsed opencode bus event. opencode emits events as JSON payloads with a
 * {@code type} discriminator (e.g. {@code message.updated},
 * {@code message.part.updated}, {@code session.updated}). The full payload is
 * kept in {@code properties}.
 */
export interface OpencodeEvent {
  type: string;
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
 * poll. Re-entering the Angular zone via {@link NgZone#run} makes the signal
 * writes schedule a tick immediately, so every bus event - title updates,
 * {@code session.idle}, etc. - renders as soon as it arrives.
 */
@Injectable({ providedIn: 'root' })
export class EventStreamService {
  private readonly zone = inject(NgZone);
  private eventSource: EventSource | null = null;
  private readonly events$ = new Subject<OpencodeEvent>();
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private started = false;

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
          // Raw arrival time of every bus event, straight from the EventSource
          // callback (outside Angular). Only computed when tracing is enabled.
          const arrivedAt = Date.now();
          const title =
            parsed.type === 'session.updated'
              ? (parsed.properties?.['info'] as { title?: string } | undefined)?.title
              : undefined;
          dbg(
            'sse',
            `arrive type=${parsed.type} inZone=${NgZone.isInAngularZone()}` +
              (title !== undefined ? ` title=${JSON.stringify(title)}` : '')
          );
          this.zone.run(() => {
            dbg('sse', `+${Date.now() - arrivedAt}ms dispatch type=${parsed.type}`);
            this.events$.next(parsed);
          });
          return;
        }
        // Re-enter Angular so signal writes downstream schedule a render.
        this.zone.run(() => this.events$.next(parsed));
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
