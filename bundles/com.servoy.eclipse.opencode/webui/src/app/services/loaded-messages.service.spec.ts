import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { Part } from '../models/opencode.models';
import { LoadedMessage, LoadedMessagesService } from './loaded-messages.service';

const msgs = (tag: string): LoadedMessage[] => [{ parts: [{ type: 'text', text: tag } as Part] }];

describe('LoadedMessagesService', () => {
  let service: LoadedMessagesService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(LoadedMessagesService);
  });

  it('starts empty', () => {
    expect(service.bySession().size).toBe(0);
  });

  it('records messages per session and replaces a previous snapshot', () => {
    const first = msgs('a');
    const second = msgs('b');
    service.record('s1', first);
    expect(service.bySession().get('s1')).toBe(first);
    service.record('s1', second);
    expect(service.bySession().get('s1')).toBe(second);
    expect(service.bySession().size).toBe(1);
  });

  it('publishes a new map instance on every record so signals notice', () => {
    const before = service.bySession();
    service.record('s1', msgs('a'));
    expect(service.bySession()).not.toBe(before);
    expect(before.size).toBe(0);
  });

  it('forgets a session', () => {
    service.record('s1', msgs('a'));
    service.record('s2', msgs('b'));
    service.forget('s1');
    expect([...service.bySession().keys()]).toEqual(['s2']);
  });

  it('forgetting an unknown session keeps the same map instance (no spurious notification)', () => {
    service.record('s1', msgs('a'));
    const before = service.bySession();
    service.forget('nope');
    expect(service.bySession()).toBe(before);
  });

  it('keeps at most the 20 most recently recorded sessions, evicting the oldest first', () => {
    for (let i = 0; i < 21; i++) {
      service.record('s' + i, msgs(String(i)));
    }
    const keys = [...service.bySession().keys()];
    expect(keys).toHaveLength(20);
    expect(keys).not.toContain('s0');
    expect(keys[0]).toBe('s1');
    expect(keys[19]).toBe('s20');
  });

  it('re-recording a session makes it the most recent, so it survives eviction', () => {
    for (let i = 0; i < 20; i++) {
      service.record('s' + i, msgs(String(i)));
    }
    service.record('s0', msgs('again'));
    service.record('new', msgs('n'));
    const keys = [...service.bySession().keys()];
    expect(keys).toHaveLength(20);
    expect(keys).toContain('s0');
    expect(keys).not.toContain('s1');
    expect(keys[19]).toBe('new');
  });

  it('exposes a read-only signal', () => {
    expect((service.bySession as unknown as { set?: unknown }).set).toBeUndefined();
    expect((service.bySession as unknown as { update?: unknown }).update).toBeUndefined();
  });
});
