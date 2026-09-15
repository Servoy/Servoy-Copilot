import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';

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
 * The app runs zoneless: parsed events are pushed straight into the signal-based
 * {@code ChatStore}, whose signal writes schedule change detection - so no
 * {@code NgZone} re-entry is required for the UI to reflect streamed parts.
 */
@Injectable({ providedIn: 'root' })
export class EventStreamService {
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
        this.events$.next(parsed);
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
