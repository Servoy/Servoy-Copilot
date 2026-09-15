import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { EventStreamService, OpencodeEvent } from './event-stream.service';

/** A controllable fake EventSource that records instances and lets tests fire events. */
class FakeEventSource {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;

  static instances: FakeEventSource[] = [];

  readonly url: string;
  readyState = FakeEventSource.OPEN;
  onmessage: ((evt: { data: string }) => void) | null = null;
  onerror: ((evt: unknown) => void) | null = null;
  closed = false;

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  emit(data: string): void {
    this.onmessage?.({ data });
  }

  fail(): void {
    this.readyState = FakeEventSource.CLOSED;
    this.onerror?.({});
  }

  close(): void {
    this.closed = true;
    this.readyState = FakeEventSource.CLOSED;
  }
}

describe('EventStreamService', () => {
  let service: EventStreamService;

  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal('EventSource', FakeEventSource as unknown as typeof EventSource);
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [EventStreamService]
    });
    service = TestBed.inject(EventStreamService);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('connects to rest_api/event on first subscription', () => {
    service.events().subscribe();
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(FakeEventSource.instances[0].url).toBe('rest_api/event');
  });

  it('does not open a second connection for a second subscriber', () => {
    service.events().subscribe();
    service.events().subscribe();
    expect(FakeEventSource.instances).toHaveLength(1);
  });

  it('parses a JSON message and dispatches the typed event', () => {
    const received: OpencodeEvent[] = [];
    service.events().subscribe((e) => received.push(e));

    const payload = {
      type: 'message.part.updated',
      properties: { part: { id: 'p1', type: 'text', text: 'hi' } }
    };
    FakeEventSource.instances[0].emit(JSON.stringify(payload));

    expect(received).toHaveLength(1);
    expect(received[0].type).toBe('message.part.updated');
    expect((received[0].properties as Record<string, unknown>)['part']).toEqual({
      id: 'p1',
      type: 'text',
      text: 'hi'
    });
  });

  it('ignores non-JSON keep-alive/heartbeat lines without emitting or throwing', () => {
    const received: OpencodeEvent[] = [];
    service.events().subscribe((e) => received.push(e));

    expect(() => FakeEventSource.instances[0].emit(': keep-alive')).not.toThrow();
    expect(received).toHaveLength(0);
  });

  it('reconnects after a backoff when the source moves to CLOSED', () => {
    service.events().subscribe();
    expect(FakeEventSource.instances).toHaveLength(1);

    FakeEventSource.instances[0].fail();
    // No immediate reconnect; the backoff timer must fire first.
    expect(FakeEventSource.instances).toHaveLength(1);

    vi.advanceTimersByTime(2000);
    expect(FakeEventSource.instances).toHaveLength(2);
    expect(FakeEventSource.instances[1].url).toBe('rest_api/event');
  });

  it('closes the previous source when it reconnects', () => {
    service.events().subscribe();
    const first = FakeEventSource.instances[0];
    first.fail();
    vi.advanceTimersByTime(2000);
    expect(first.closed).toBe(true);
  });

  it('does not schedule multiple reconnects for repeated errors before the timer fires', () => {
    service.events().subscribe();
    const first = FakeEventSource.instances[0];
    first.fail();
    first.fail();
    vi.advanceTimersByTime(2000);
    expect(FakeEventSource.instances).toHaveLength(2);
  });

  it('does not reconnect on a transient error that stays open', () => {
    service.events().subscribe();
    const src = FakeEventSource.instances[0];
    src.readyState = FakeEventSource.OPEN;
    src.onerror?.({});
    vi.advanceTimersByTime(5000);
    expect(FakeEventSource.instances).toHaveLength(1);
  });
});
