import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Subject, of, throwError } from 'rxjs';

import { ChatStore } from './chat-store.service';
import { EventStreamService, OpencodeEvent } from './event-stream.service';
import { OpencodeApiService } from './opencode-api.service';
import { MessageWithParts, Part, SendPart, Session } from '../models/opencode.models';

class FakeEventStream {
  readonly subject = new Subject<OpencodeEvent>();
  events() {
    return this.subject.asObservable();
  }
  fire(evt: OpencodeEvent): void {
    this.subject.next(evt);
  }
}

function apiMock() {
  return {
    listSessions: vi.fn(),
    createSession: vi.fn(),
    getSession: vi.fn(),
    listMessages: vi.fn(),
    sendPromptAsync: vi.fn(),
    abort: vi.fn(),
    findFiles: vi.fn(),
    readFile: vi.fn()
  };
}

describe('ChatStore', () => {
  let store: ChatStore;
  let api: ReturnType<typeof apiMock>;
  let events: FakeEventStream;

  beforeEach(() => {
    api = apiMock();
    events = new FakeEventStream();
    TestBed.configureTestingModule({
      providers: [
        ChatStore,
        { provide: OpencodeApiService, useValue: api },
        { provide: EventStreamService, useValue: events }
      ]
    });
    store = TestBed.inject(ChatStore);
  });

  it('refreshSessions populates the session list', () => {
    const sessions: Session[] = [{ id: 's1', title: 'One' }];
    api.listSessions.mockReturnValue(of(sessions));

    store.refreshSessions();

    expect(store.sessions()).toEqual(sessions);
  });

  it('refreshSessions coerces a null response to an empty list', () => {
    api.listSessions.mockReturnValue(of(null));
    store.refreshSessions();
    expect(store.sessions()).toEqual([]);
  });

  it('openSession seeds messages from GET /message', () => {
    const msgs: MessageWithParts[] = [
      { info: { id: 'm1', role: 'user' }, parts: [{ type: 'text', text: 'hi' } as Part] }
    ];
    api.listMessages.mockReturnValue(of(msgs));

    store.openSession('s1');

    expect(api.listMessages).toHaveBeenCalledWith('s1');
    expect(store.activeSessionId()).toBe('s1');
    expect(store.messages()).toHaveLength(1);
    expect(store.messages()[0].info.id).toBe('m1');
    expect(store.messages()[0].parts[0].text).toBe('hi');
  });

  it('openSession clears any prior error and messages before loading', () => {
    api.listMessages.mockReturnValue(of([]));
    store.openSession('s1');
    expect(store.error()).toBeNull();
    expect(store.messages()).toEqual([]);
  });

  it('a streamed message.part.updated upserts a part into the active message', () => {
    api.listMessages.mockReturnValue(
      of<MessageWithParts[]>([{ info: { id: 'm1', role: 'assistant' }, parts: [] }])
    );
    store.openSession('s1');

    events.fire({
      type: 'message.part.updated',
      properties: {
        sessionID: 's1',
        part: { id: 'p1', messageID: 'm1', sessionID: 's1', type: 'text', text: 'hello' } as Part
      }
    });

    const msg = store.messages().find((m) => m.info.id === 'm1');
    expect(msg?.parts).toHaveLength(1);
    expect(msg?.parts[0].text).toBe('hello');
    expect(store.streaming()).toBe(true);
  });

  it('creates a placeholder message when a part arrives before its message.updated', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');

    events.fire({
      type: 'message.part.updated',
      properties: {
        part: { id: 'p1', messageID: 'mX', sessionID: 's1', type: 'text', text: 'first' } as Part
      }
    });

    const msg = store.messages().find((m) => m.info.id === 'mX');
    expect(msg).toBeTruthy();
    expect(msg?.info.role).toBe('assistant');
    expect(msg?.parts[0].text).toBe('first');
  });

  it('ignores events for a session that is not active', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');

    events.fire({
      type: 'message.part.updated',
      properties: {
        sessionID: 'OTHER',
        part: { id: 'p1', messageID: 'm1', sessionID: 'OTHER', type: 'text', text: 'no' } as Part
      }
    });

    expect(store.messages()).toHaveLength(0);
  });

  it('clears the streaming flag on session.idle for the active session', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    store.streaming.set(true);

    events.fire({ type: 'session.idle', properties: { sessionID: 's1' } });

    expect(store.streaming()).toBe(false);
  });

  it('clears the streaming flag on session.error for the active session', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    store.streaming.set(true);

    events.fire({ type: 'session.error', properties: { sessionID: 's1' } });

    expect(store.streaming()).toBe(false);
  });

  it('does not clear streaming on session.idle for a different session', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    store.streaming.set(true);

    events.fire({ type: 'session.idle', properties: { sessionID: 'other' } });

    expect(store.streaming()).toBe(true);
  });

  it('newSession creates a session, refreshes the list and opens it', () => {
    api.createSession.mockReturnValue(of<Session>({ id: 'new1' }));
    api.listSessions.mockReturnValue(of<Session[]>([{ id: 'new1' }]));
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));

    store.newSession();

    expect(api.createSession).toHaveBeenCalled();
    expect(api.listSessions).toHaveBeenCalled();
    expect(store.activeSessionId()).toBe('new1');
  });

  it('send dispatches to the active session and sets streaming', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.sendPromptAsync.mockReturnValue(of<void>(undefined));
    store.openSession('s1');

    const parts: SendPart[] = [{ type: 'text', text: 'hello' }];
    store.send(parts);

    expect(api.sendPromptAsync).toHaveBeenCalledWith('s1', parts);
    expect(store.streaming()).toBe(true);
    expect(store.error()).toBeNull();
  });

  it('send creates a session first when there is no active session', () => {
    api.createSession.mockReturnValue(of<Session>({ id: 'created' }));
    api.listSessions.mockReturnValue(of<Session[]>([]));
    api.sendPromptAsync.mockReturnValue(of<void>(undefined));

    store.send([{ type: 'text', text: 'hi' }]);

    expect(api.createSession).toHaveBeenCalled();
    expect(store.activeSessionId()).toBe('created');
    expect(api.sendPromptAsync).toHaveBeenCalledWith('created', [{ type: 'text', text: 'hi' }]);
  });

  it('send clears streaming and records a friendly error on dispatch failure', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.sendPromptAsync.mockReturnValue(throwError(() => ({ status: 503 })));
    store.openSession('s1');

    store.send([{ type: 'text', text: 'hi' }]);

    expect(store.streaming()).toBe(false);
    expect(store.error()).toContain('starting');
  });

  it('abort calls the api and clears streaming', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.abort.mockReturnValue(of<void>(undefined));
    store.openSession('s1');
    store.streaming.set(true);

    store.abort();

    expect(api.abort).toHaveBeenCalledWith('s1');
    expect(store.streaming()).toBe(false);
  });

  it('abort is a no-op when there is no active session', () => {
    store.abort();
    expect(api.abort).not.toHaveBeenCalled();
  });

  it('describes a generic error when the api fails with an unknown error', () => {
    api.listSessions.mockReturnValue(throwError(() => new Error('boom')));
    store.refreshSessions();
    expect(store.error()).toContain('Something went wrong');
  });

  it('hasActiveSession reflects the active session id', () => {
    expect(store.hasActiveSession()).toBe(false);
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    expect(store.hasActiveSession()).toBe(true);
  });
});
