import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Subject, of, throwError } from 'rxjs';

import { deriveChildStatus } from './agent-status';
import { ChatStore } from './chat-store.service';
import { LoadedMessagesService } from './loaded-messages.service';
import { EventStreamService, OpencodeEvent } from './event-stream.service';
import { OpencodeApiService } from './opencode-api.service';
import { FormInfo, MessageWithParts, Part, SendPart, Session } from '../models/opencode.models';

class FakeEventStream {
  readonly subject = new Subject<OpencodeEvent>();
  events() {
    return this.subject.asObservable();
  }
  readonly reconnect$ = new Subject<void>();
  reconnected() {
    return this.reconnect$.asObservable();
  }
  fire(evt: OpencodeEvent): void {
    this.subject.next(evt);
  }
  fireReconnect(): void {
    this.reconnect$.next();
  }
}

function apiMock() {
  return {
    listSessions: vi.fn(),
    createSession: vi.fn(),
    getSession: vi.fn(),
    updateSessionTitle: vi.fn(),
    deleteSession: vi.fn(),
    listMessages: vi.fn(),
    listMessagePage: vi.fn(),
    listAllMessages: vi.fn(),
    sendPrompt: vi.fn(),
    interrupt: vi.fn(),
    readWorkspaceFile: vi.fn(),
    listPendingForms: vi.fn(),
    listAllPendingForms: vi.fn(),
    getActiveSessions: vi.fn(),
    replyToForm: vi.fn(),
    cancelForm: vi.fn()
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
    // openSession/bootstrap now recover pending forms; default to none so the
    // existing session-focused tests are unaffected.
    api.listPendingForms.mockReturnValue(of<FormInfo[]>([]));
    api.listAllPendingForms.mockReturnValue(of<FormInfo[]>([]));
    // openSession pages the transcript (listMessagePage) and export walks the
    // whole history (listAllMessages); default both to empty so the many
    // session-focused tests that only need an empty transcript keep working.
    // Tests that seed real messages or exercise pagination override these.
    api.listMessagePage.mockReturnValue(of({ messages: [] as MessageWithParts[], olderCursor: null }));
    api.listAllMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.getActiveSessions.mockReturnValue(of(new Set<string>() as ReadonlySet<string>));
  });

  /** Seed the newest transcript page for an openSession in a test. */
  const seedPage = (messages: MessageWithParts[], olderCursor: string | null = null) =>
    api.listMessagePage.mockReturnValue(of({ messages, olderCursor }));

  it('refreshSessions populates the session list', () => {
    const sessions: Session[] = [{ id: 's1', title: 'One' }];
    api.listSessions.mockReturnValue(of(sessions));

    store.refreshSessions();

    expect(store.sessions()).toEqual(sessions);
  });

  it('refreshSessions hides subagent (child) sessions from the list', () => {
    const sessions: Session[] = [
      { id: 's1', title: 'Parent' },
      { id: 'sub', title: 'Reviewer subagent', parentID: 's1' }
    ];
    api.listSessions.mockReturnValue(of(sessions));

    store.refreshSessions();

    expect(store.sessions().map((s) => s.id)).toEqual(['s1']);
  });

  it('bootstrap keeps subagent sessions out of the top-level list but auto-opens the parent', () => {
    const sessions: Session[] = [
      { id: 's1', title: 'Parent', time: { updated: 200 } },
      { id: 'sub', title: 'subagent', parentID: 's1', time: { updated: 999 } }
    ];
    api.listSessions.mockReturnValue(of(sessions));
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));

    store.bootstrap();

    expect(store.sessions().map((s) => s.id)).toEqual(['s1']);
    // The newer child must not be auto-opened; the parent is.
    expect(store.activeSessionId()).toBe('s1');
  });

  it('sessionTree nests subagent sessions under their parent, newest child first', () => {
    const sessions: Session[] = [
      { id: 's1', title: 'Parent', time: { updated: 100 } },
      { id: 'subA', title: 'A', parentID: 's1', time: { updated: 300 } },
      { id: 'subB', title: 'B', parentID: 's1', time: { updated: 900 } },
      { id: 's2', title: 'Other', time: { updated: 50 } }
    ];
    api.listSessions.mockReturnValue(of(sessions));

    store.refreshSessions();

    const tree = store.sessionTree();
    expect(tree.map((n) => n.session.id)).toEqual(['s1', 's2']);
    const parent = tree.find((n) => n.session.id === 's1')!;
    expect(parent.children.map((c) => c.id)).toEqual(['subB', 'subA']);
    expect(tree.find((n) => n.session.id === 's2')!.children).toEqual([]);
  });

  it('activeIsSubagent is true only when the active session has a parentID', () => {
    const sessions: Session[] = [
      { id: 's1', title: 'Parent' },
      { id: 'sub', title: 'subagent', parentID: 's1' }
    ];
    api.listSessions.mockReturnValue(of(sessions));
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.refreshSessions();

    store.openSession('s1');
    expect(store.activeIsSubagent()).toBe(false);

    store.openSession('sub');
    expect(store.activeIsSubagent()).toBe(true);
  });

  it('activeTitle follows the active session and is empty without one or without a title', () => {
    const sessions: Session[] = [
      { id: 's1', title: '  Main work  ' },
      { id: 'sub', title: 'Reviewer run', parentID: 's1' },
      { id: 'blank' } as Session
    ];
    api.listSessions.mockReturnValue(of(sessions));
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.refreshSessions();

    expect(store.activeTitle()).toBe('');
    store.openSession('s1');
    expect(store.activeTitle()).toBe('Main work');
    store.openSession('sub');
    expect(store.activeTitle()).toBe('Reviewer run');
    store.openSession('blank');
    expect(store.activeTitle()).toBe('');
  });

  it('activeParent resolves the parent id and title only for a subagent session', () => {
    const sessions: Session[] = [
      { id: 's1', title: 'Parent' },
      { id: 'sub', title: 'subagent', parentID: 's1' },
      { id: 'orphan', title: 'orphan', parentID: 'gone' }
    ];
    api.listSessions.mockReturnValue(of(sessions));
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.refreshSessions();

    store.openSession('s1');
    expect(store.activeParent()).toBeNull();

    store.openSession('sub');
    expect(store.activeParent()).toEqual({ id: 's1', title: 'Parent' });

    store.openSession('orphan');
    expect(store.activeParent()).toEqual({ id: 'gone', title: '' });
  });

  describe('agent session view state (SVY-21850)', () => {
    const sessions: Session[] = [
      { id: 's1', title: 'Parent' },
      { id: 'c1', title: 'Child (@reviewer subagent)', parentID: 's1' }
    ];

    beforeEach(() => {
      api.listSessions.mockReturnValue(of(sessions));
      api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
      store.refreshSessions();
    });

    it('activeSession is undefined without an active session and follows the active id', () => {
      expect(store.activeSession()).toBeUndefined();
      store.openSession('c1');
      expect(store.activeSession()?.id).toBe('c1');
      expect(store.activeSession()?.parentID).toBe('s1');
      store.openSession('s1');
      expect(store.activeSession()?.id).toBe('s1');
    });

    it('activeChildSignals returns the childSignals entry of the active session only', () => {
      store.openSession('c1');
      expect(store.activeChildSignals()?.busy).not.toBe(true);
      events.fire({ type: 'session.execution.started', data: { sessionID: 'c1' } });
      expect(store.activeChildSignals()).toBe(store.childSignals().get('c1'));
      expect(store.activeChildSignals()?.busy).toBe(true);
      store.openSession('s1');
      expect(store.activeChildSignals()).toBeUndefined();
    });

    it('saveScroll then takeScroll returns the saved position once', () => {
      store.saveScroll('s1', { top: 420, pinned: false });
      expect(store.takeScroll('s1')).toEqual({ top: 420, pinned: false });
      expect(store.takeScroll('s1')).toBeUndefined();
    });

    it('keeps saved scroll positions per session and overwrites on a second save', () => {
      store.saveScroll('s1', { top: 1, pinned: false });
      store.saveScroll('other', { top: 2, pinned: true });
      store.saveScroll('s1', { top: 3, pinned: true });
      expect(store.takeScroll('s1')).toEqual({ top: 3, pinned: true });
      expect(store.takeScroll('other')).toEqual({ top: 2, pinned: true });
    });

    it('takeScroll is undefined for a session that was never saved', () => {
      expect(store.takeScroll('nope')).toBeUndefined();
    });

    it('sessionParentId returns the parent of a child and undefined otherwise', () => {
      expect(store.sessionParentId('c1')).toBe('s1');
      expect(store.sessionParentId('s1')).toBeUndefined();
      expect(store.sessionParentId('missing')).toBeUndefined();
    });
  });

  it('send is a no-op when the active session is a subagent', () => {
    const sessions: Session[] = [
      { id: 's1', title: 'Parent' },
      { id: 'sub', title: 'subagent', parentID: 's1' }
    ];
    api.listSessions.mockReturnValue(of(sessions));
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.refreshSessions();
    store.openSession('sub');

    store.send([{ type: 'text', text: 'hi' }]);

    expect(api.sendPrompt).not.toHaveBeenCalled();
    expect(api.createSession).not.toHaveBeenCalled();
  });

  it('bootstrap opens the most recent non-archived top-level session', () => {
    const sessions: Session[] = [
      { id: 'old', time: { updated: 100 } },
      { id: 'new', time: { updated: 500 } },
      { id: 'child', parentID: 'new', time: { updated: 900 } },
      { id: 'archived', time: { updated: 999, archived: 999 } }
    ];
    api.listSessions.mockReturnValue(of(sessions));
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));

    store.bootstrap();

    // Child (subagent) sessions are filtered out of the list; archived stay
    // in the list but are never auto-opened.
    expect(store.sessions().map((s) => s.id)).toEqual(['old', 'new', 'archived']);
    expect(store.activeSessionId()).toBe('new');
    expect(api.listMessagePage).toHaveBeenCalledWith('new');
  });

  it('bootstrap starts a fresh draft when there are no sessions', () => {
    api.listSessions.mockReturnValue(of<Session[]>([]));

    store.bootstrap();

    expect(store.activeSessionId()).toBeNull();
    expect(store.messages()).toEqual([]);
    expect(api.listMessagePage).not.toHaveBeenCalled();
  });

  it('bootstrap starts a fresh draft when only archived/child sessions exist', () => {
    const sessions: Session[] = [
      { id: 'child', parentID: 'p', time: { updated: 900 } },
      { id: 'archived', time: { updated: 999, archived: 999 } }
    ];
    api.listSessions.mockReturnValue(of(sessions));

    store.bootstrap();

    expect(store.activeSessionId()).toBeNull();
    expect(api.listMessagePage).not.toHaveBeenCalled();
  });

  it('refreshSessions coerces a null response to an empty list', () => {
    api.listSessions.mockReturnValue(of(null));
    store.refreshSessions();
    expect(store.sessions()).toEqual([]);
  });

  it('openSession drops empty idle/bookkeeping messages so no stray "thinking" dots render', () => {
    const msgs: MessageWithParts[] = [
      { info: { id: 'u1', role: 'user' }, parts: [{ type: 'text', text: 'hi' } as Part] },
      { info: { id: 'a1', role: 'assistant' }, parts: [{ type: 'text', text: 'hello' } as Part] },
      // opencode idle marker: no parts, not a user message -> must be filtered.
      { info: { id: 'idle1', role: 'idle' }, parts: [] }
    ];
    seedPage(msgs);

    store.openSession('s1');

    expect(store.messages().map((m) => m.info.id)).toEqual(['u1', 'a1']);
  });

  it('openSession keeps a running assistant turn that only has reasoning so far (for the "Thinking..." line) and drops a finished reasoning-only one', () => {
    const msgs: MessageWithParts[] = [
      { info: { id: 'u1', role: 'user' }, parts: [{ type: 'text', text: 'hi' } as Part] },
      { info: { id: 'a1', role: 'assistant', time: { completed: 5 } }, parts: [{ type: 'reasoning', text: 'x' } as Part] },
      { info: { id: 'a2', role: 'assistant' }, parts: [{ type: 'reasoning', text: 'y' } as Part] }
    ];
    seedPage(msgs);

    store.openSession('s1');

    expect(store.messages().map((m) => m.info.id)).toEqual(['u1', 'a2']);
    expect(store.messages()[1].parts[0].type).toBe('reasoning');
  });

  it('openSession keeps a user message even when it has no parts', () => {
    const msgs: MessageWithParts[] = [{ info: { id: 'u1', role: 'user' }, parts: [] }];
    seedPage(msgs);

    store.openSession('s1');

    expect(store.messages().map((m) => m.info.id)).toEqual(['u1']);
  });

  it('openSession seeds messages from GET /message', () => {
    const msgs: MessageWithParts[] = [
      { info: { id: 'm1', role: 'user' }, parts: [{ type: 'text', text: 'hi' } as Part] }
    ];
    seedPage(msgs);

    store.openSession('s1');

    expect(api.listMessagePage).toHaveBeenCalledWith('s1');
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

  it('accumulates V2 session.text.delta events into a streamed assistant text part', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');

    events.fire({
      type: 'session.text.started',
      data: { sessionID: 's1', assistantMessageID: 'mA', ordinal: 0 }
    });
    events.fire({
      type: 'session.text.delta',
      data: { sessionID: 's1', assistantMessageID: 'mA', ordinal: 0, delta: 'Hel' }
    });
    events.fire({
      type: 'session.text.delta',
      data: { sessionID: 's1', assistantMessageID: 'mA', ordinal: 0, delta: 'lo' }
    });

    const msg = store.messages().find((m) => m.info.id === 'mA');
    expect(msg).toBeTruthy();
    expect(msg?.info.role).toBe('assistant');
    expect(msg?.parts).toHaveLength(1);
    expect(msg?.parts[0].type).toBe('text');
    expect(msg?.parts[0].text).toBe('Hello');
    expect(store.streaming()).toBe(true);
  });

  it('session.text.ended replaces the streamed text with the final value', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');

    events.fire({
      type: 'session.text.delta',
      data: { sessionID: 's1', assistantMessageID: 'mA', ordinal: 0, delta: 'partial' }
    });
    events.fire({
      type: 'session.text.ended',
      data: { sessionID: 's1', assistantMessageID: 'mA', ordinal: 0, text: 'the full answer' }
    });

    const msg = store.messages().find((m) => m.info.id === 'mA');
    expect(msg?.parts[0].text).toBe('the full answer');
  });

  it('builds a tool part from V2 tool.input/called/success events keyed by tool-call id', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');

    events.fire({
      type: 'session.tool.input.started',
      data: { sessionID: 's1', assistantMessageID: 'mA', id: 'toolu_1', name: 'read' }
    });
    events.fire({
      type: 'session.tool.called',
      data: { sessionID: 's1', assistantMessageID: 'mA', id: 'toolu_1', input: { path: 'a.js' } }
    });
    events.fire({
      type: 'session.tool.success',
      data: {
        sessionID: 's1',
        assistantMessageID: 'mA',
        id: 'toolu_1',
        content: [{ type: 'text', text: 'file body' }]
      }
    });

    const msg = store.messages().find((m) => m.info.id === 'mA');
    expect(msg?.parts).toHaveLength(1);
    const tool = msg?.parts[0];
    expect(tool?.type).toBe('tool');
    expect(tool?.tool).toBe('read');
    expect(tool?.state?.status).toBe('completed');
    expect(tool?.state?.input).toEqual({ path: 'a.js' });
    expect(tool?.state?.output).toBe('file body');
  });

  it.each(['session.tool.failed', 'session.tool.error'])(
    'moves a running tool part to status error on %s',
    (eventType) => {
      api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
      store.openSession('s1');

      events.fire({
        type: 'session.tool.input.started',
        data: { sessionID: 's1', assistantMessageID: 'mA', id: 'toolu_f', name: 'read' }
      });
      events.fire({
        type: 'session.tool.called',
        data: { sessionID: 's1', assistantMessageID: 'mA', id: 'toolu_f', input: { path: 'a.js' } }
      });
      events.fire({
        type: eventType,
        data: {
          sessionID: 's1',
          assistantMessageID: 'mA',
          id: 'toolu_f',
          error: { type: 'aborted', message: 'Tool execution interrupted' }
        }
      });

      const tool = store.messages().find((m) => m.info.id === 'mA')?.parts[0];
      expect(tool?.state?.status).toBe('error');
      expect(tool?.state?.input).toEqual({ path: 'a.js' });
    }
  );

  it('backfills a running subagent child id on re-seed from live-captured metadata (link survives the round-trip)', () => {
    // 1. Parent is active; its subagent dispatches and a tool.progress event
    //    carries the child sessionID while it is still RUNNING.
    seedPage([]);
    store.openSession('parent');
    events.fire({
      type: 'session.tool.input.started',
      data: { sessionID: 'parent', assistantMessageID: 'mA', id: 'toolu_sa', name: 'subagent' }
    });
    events.fire({
      type: 'session.tool.progress',
      data: { sessionID: 'parent', assistantMessageID: 'mA', id: 'toolu_sa', metadata: { sessionID: 'ses_child', status: 'running' } }
    });

    // 2. User opens the child, then returns to the parent WHILE the subagent is
    //    still running. GET /message for a running subagent has NO metadata.
    seedPage([
      {
        info: { id: 'mA', sessionID: 'parent', role: 'assistant' },
        parts: [
          {
            id: 'toolu_sa',
            type: 'tool',
            tool: 'subagent',
            messageID: 'mA',
            sessionID: 'parent',
            state: { status: 'running', input: { description: 'Do it' } } // no metadata!
          }
        ]
      }
    ]);
    store.openSession('child');
    store.openSession('parent');

    // The re-seeded running subagent part regained its child id from the live
    // cache, so the "Open subagent" link is present again (not only at the end).
    const tool = store.messages().find((m) => m.info.id === 'mA')?.parts[0];
    expect((tool?.state as { metadata?: { sessionID?: string } })?.metadata?.sessionID).toBe('ses_child');
  });

  it('merges a live tool event into a seeded tool part by its raw call id (no phantom "Tool" duplicate)', () => {
    // Seed the parent session as if returning to it via GET /message: a subagent
    // tool part keyed by its raw opencode call id (what mapV2Message assigns).
    seedPage([
      {
        info: { id: 'mA', sessionID: 's1', role: 'assistant' },
        parts: [
          {
            id: 'toolu_sa',
            type: 'tool',
            tool: 'subagent',
            messageID: 'mA',
            sessionID: 's1',
            state: { status: 'running', input: { description: 'Do it' }, metadata: { sessionID: 'ses_child' } }
          }
        ]
      }
    ]);
    store.openSession('s1');

    // The subagent, still running, now finishes - a late live success event for
    // the SAME tool call id must merge, not spawn a second nameless part.
    events.fire({
      type: 'session.tool.success',
      data: {
        sessionID: 's1',
        assistantMessageID: 'mA',
        id: 'toolu_sa',
        content: [{ type: 'text', text: 'done' }],
        metadata: { sessionID: 'ses_child', status: 'completed' }
      }
    });

    const parts = store.messages().find((m) => m.info.id === 'mA')?.parts ?? [];
    // Exactly one tool part, and it kept its name + child id (not "Tool").
    expect(parts).toHaveLength(1);
    expect(parts[0].tool).toBe('subagent');
    expect(parts[0].state?.status).toBe('completed');
    expect(parts[0].state?.output).toBe('done');
    expect((parts[0].state as { metadata?: { sessionID?: string } })?.metadata?.sessionID).toBe('ses_child');
  });

  it('captures a subagent child sessionID from a tool.progress event while it is still running', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');

    events.fire({
      type: 'session.tool.input.started',
      data: { sessionID: 's1', assistantMessageID: 'mA', id: 'toolu_sa', name: 'subagent' }
    });
    events.fire({
      type: 'session.tool.called',
      data: { sessionID: 's1', assistantMessageID: 'mA', id: 'toolu_sa', input: { description: 'Do it' } }
    });
    // Progress arrives WHILE the subagent runs, carrying the spawned child id.
    events.fire({
      type: 'session.tool.progress',
      data: {
        sessionID: 's1',
        assistantMessageID: 'mA',
        id: 'toolu_sa',
        metadata: { sessionID: 'ses_child', status: 'running' }
      }
    });

    const tool = store.messages().find((m) => m.info.id === 'mA')?.parts[0];
    // The child id is already on the part before any success/output - so the
    // "Open subagent" link can show during the run.
    expect((tool?.state as { metadata?: { sessionID?: string } })?.metadata?.sessionID).toBe('ses_child');
    expect(tool?.state?.status).toBe('running');
    expect(tool?.state?.input).toEqual({ description: 'Do it' });
  });

  it('ignores streamed events for a session that is not active', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');

    events.fire({
      type: 'session.text.delta',
      data: { sessionID: 'OTHER', assistantMessageID: 'm1', ordinal: 0, delta: 'no' }
    });

    expect(store.messages()).toHaveLength(0);
  });

  it('clears the streaming flag on session.idle for the active session', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    events.fire({ type: 'session.execution.started', data: { sessionID: 's1' } });
    expect(store.streaming()).toBe(true);

    events.fire({ type: 'session.idle', data: { sessionID: 's1' } });

    expect(store.streaming()).toBe(false);
  });

  it('clears the streaming flag on session.execution.succeeded for the active session', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    events.fire({ type: 'session.execution.started', data: { sessionID: 's1' } });

    events.fire({ type: 'session.execution.succeeded', data: { sessionID: 's1' } });

    expect(store.streaming()).toBe(false);
  });

  it('clears the streaming flag on session.error for the active session', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    events.fire({ type: 'session.execution.started', data: { sessionID: 's1' } });

    events.fire({ type: 'session.error', data: { sessionID: 's1' } });

    expect(store.streaming()).toBe(false);
  });

  it('the active streaming flag ignores a turn running in a different session', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    // A background session starts streaming while s1 is active.
    events.fire({ type: 'session.execution.started', data: { sessionID: 'other' } });

    // store.streaming() reflects the ACTIVE session only.
    expect(store.streaming()).toBe(false);
  });

  it('tracks streaming per session: switching to a still-streaming session shows streaming', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    // A turn runs in a background session 's2'.
    events.fire({ type: 'session.execution.started', data: { sessionID: 's2' } });
    expect(store.streaming()).toBe(false);

    // Switch to s2: its turn is still running, so the button is a stop button.
    store.openSession('s2');
    expect(store.streaming()).toBe(true);

    // s2's turn ends; the active flag clears even though s2 stayed open.
    events.fire({ type: 'session.idle', data: { sessionID: 's2' } });
    expect(store.streaming()).toBe(false);
  });

  it('a session.idle for a background session does not clear the active session streaming', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    events.fire({ type: 'session.execution.started', data: { sessionID: 's1' } });
    events.fire({ type: 'session.execution.started', data: { sessionID: 's2' } });

    // s2 (background) goes idle - s1 (active) is still streaming.
    events.fire({ type: 'session.idle', data: { sessionID: 's2' } });

    expect(store.streaming()).toBe(true);
  });

  it('adds a new session from a V2 session.created event (id under sessionID)', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    events.fire({
      type: 'session.created',
      data: { sessionID: 'sNew', slug: 'happy-otter', title: 'New session - 2026-01-01T00:00:00' }
    });
    expect(store.sessions().map((s) => s.id)).toContain('sNew');
  });

  it('newSession starts a draft without creating a session on the server', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');

    store.newSession();

    expect(api.createSession).not.toHaveBeenCalled();
    expect(store.activeSessionId()).toBeNull();
    expect(store.messages()).toEqual([]);
    expect(store.error()).toBeNull();
  });

  it('renameSession patches the title and refreshes the list', () => {
    api.updateSessionTitle.mockReturnValue(of<Session>({ id: 's1', title: 'Renamed' }));
    api.listSessions.mockReturnValue(of<Session[]>([{ id: 's1', title: 'Renamed' }]));

    store.renameSession('s1', '  Renamed  ');

    expect(api.updateSessionTitle).toHaveBeenCalledWith('s1', 'Renamed');
    expect(api.listSessions).toHaveBeenCalled();
  });

  it('renameSession ignores a blank title', () => {
    store.renameSession('s1', '   ');
    expect(api.updateSessionTitle).not.toHaveBeenCalled();
  });

  it('exportSession fetches the session and messages and downloads a json file', () => {
    const clickSpy = vi.fn();
    const anchor = { href: '', download: '', click: clickSpy } as unknown as HTMLAnchorElement;
    const createEl = vi.spyOn(document, 'createElement').mockReturnValue(anchor);
    vi.spyOn(document.body, 'appendChild').mockImplementation((n) => n as Node);
    vi.spyOn(document.body, 'removeChild').mockImplementation((n) => n as Node);
    const createUrl = vi
      .spyOn(URL, 'createObjectURL')
      .mockReturnValue('blob:mock');
    const revokeUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);

    api.getSession.mockReturnValue(of<Session>({ id: 's1', title: 'Fix Bug' }));
    // Export walks the WHOLE history via listAllMessages, not just the newest page.
    api.listAllMessages.mockReturnValue(
      of<MessageWithParts[]>([{ info: { id: 'm1', role: 'user' }, parts: [{ type: 'text', text: 'hi' } as Part] }])
    );

    store.exportSession('s1');

    expect(api.getSession).toHaveBeenCalledWith('s1');
    expect(api.listAllMessages).toHaveBeenCalledWith('s1');
    expect(clickSpy).toHaveBeenCalled();
    expect(anchor.download).toBe('fix-bug.json');

    createEl.mockRestore();
    createUrl.mockRestore();
    revokeUrl.mockRestore();
  });

  it('exportSession surfaces an error when loading fails', () => {
    api.getSession.mockReturnValue(throwError(() => ({ status: 500 })));
    api.listAllMessages.mockReturnValue(of<MessageWithParts[]>([]));

    store.exportSession('s1');

    expect(store.error()).not.toBeNull();
  });

  // -------------------------------------------------------------------------
  // Transcript pagination: seed the newest page, load older history on demand.
  // -------------------------------------------------------------------------

  describe('transcript pagination', () => {
    const msg = (id: string): MessageWithParts => ({
      info: { id, role: 'user' },
      parts: [{ type: 'text', text: id } as Part]
    });

    it('openSession seeds the newest page and flags that older history exists', () => {
      api.listMessagePage.mockReturnValue(of({ messages: [msg('m50')], olderCursor: 'CUR_OLDER' }));

      store.openSession('s1');

      expect(store.messages().map((m) => m.info.id)).toEqual(['m50']);
      // A non-null older cursor means "load older" is offered.
      expect(store.hasMoreMessages()).toBe(true);
      expect(store.loadingOlder()).toBe(false);
    });

    it('openSession with no older cursor reports no more history', () => {
      api.listMessagePage.mockReturnValue(of({ messages: [msg('m1')], olderCursor: null }));
      store.openSession('s1');
      expect(store.hasMoreMessages()).toBe(false);
    });

    it('loadOlderMessages prepends the older page and advances the cursor', () => {
      api.listMessagePage.mockReturnValueOnce(of({ messages: [msg('newer')], olderCursor: 'CUR1' }));
      store.openSession('s1');
      expect(store.messages().map((m) => m.info.id)).toEqual(['newer']);

      // The next page (older) returns an even-older cursor.
      api.listMessagePage.mockReturnValueOnce(of({ messages: [msg('older')], olderCursor: 'CUR2' }));
      store.loadOlderMessages();

      // Older messages are prepended (chronological), newest stays last.
      expect(store.messages().map((m) => m.info.id)).toEqual(['older', 'newer']);
      expect(api.listMessagePage).toHaveBeenLastCalledWith('s1', { cursor: 'CUR1' });
      expect(store.hasMoreMessages()).toBe(true);
    });

    it('loadOlderMessages stops offering more once the start is reached', () => {
      api.listMessagePage.mockReturnValueOnce(of({ messages: [msg('b')], olderCursor: 'CUR1' }));
      store.openSession('s1');
      api.listMessagePage.mockReturnValueOnce(of({ messages: [msg('a')], olderCursor: null }));

      store.loadOlderMessages();

      expect(store.messages().map((m) => m.info.id)).toEqual(['a', 'b']);
      expect(store.hasMoreMessages()).toBe(false);
    });

    it('loadOlderMessages is a no-op when there is no older cursor', () => {
      api.listMessagePage.mockReturnValue(of({ messages: [msg('only')], olderCursor: null }));
      store.openSession('s1');
      api.listMessagePage.mockClear();

      store.loadOlderMessages();

      expect(api.listMessagePage).not.toHaveBeenCalled();
    });

    it('loadOlderMessages does not fetch twice while a page is still in flight', () => {
      api.listMessagePage.mockReturnValueOnce(of({ messages: [msg('x')], olderCursor: 'CUR1' }));
      store.openSession('s1');
      // A page that never emits simulates a request still in flight.
      api.listMessagePage.mockReturnValueOnce(new Subject());
      api.listMessagePage.mockClear();

      store.loadOlderMessages();
      store.loadOlderMessages();

      // Only the first request fired; the second was guarded by loadingOlder.
      expect(api.listMessagePage).toHaveBeenCalledTimes(1);
      expect(store.loadingOlder()).toBe(true);
    });

    it('loadOlderMessages skips a message id already present at the page boundary', () => {
      api.listMessagePage.mockReturnValueOnce(of({ messages: [msg('dup'), msg('newer')], olderCursor: 'CUR1' }));
      store.openSession('s1');
      // The older page overlaps on 'dup' - it must not be duplicated.
      api.listMessagePage.mockReturnValueOnce(of({ messages: [msg('older'), msg('dup')], olderCursor: null }));

      store.loadOlderMessages();

      expect(store.messages().map((m) => m.info.id)).toEqual(['older', 'dup', 'newer']);
    });
  });

  it('deleteSession deletes, refreshes and resets the active session when it was active', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.deleteSession.mockReturnValue(of<boolean>(true));
    api.listSessions.mockReturnValue(of<Session[]>([]));
    store.openSession('s1');

    store.deleteSession('s1');

    expect(api.deleteSession).toHaveBeenCalledWith('s1');
    expect(store.activeSessionId()).toBeNull();
    expect(store.messages()).toEqual([]);
    expect(api.listSessions).toHaveBeenCalled();
  });

  it('send dispatches to the active session and sets streaming', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.sendPrompt.mockReturnValue(of<void>(undefined));
    store.openSession('s1');

    const parts: SendPart[] = [{ type: 'text', text: 'hello' }];
    store.send(parts);

    expect(api.sendPrompt).toHaveBeenCalledWith('s1', parts);
    expect(store.streaming()).toBe(true);
    expect(store.error()).toBeNull();
  });

  it('send optimistically renders the user message (opencode pushes no prompt event)', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.sendPrompt.mockReturnValue(of<void>(undefined));
    store.openSession('s1');

    store.send([{ type: 'text', text: 'zeg even test' }]);

    const userMsgs = store.messages().filter((m) => m.info.role === 'user');
    expect(userMsgs).toHaveLength(1);
    expect(userMsgs[0].parts[0].type).toBe('text');
    expect(userMsgs[0].parts[0].text).toBe('zeg even test');
  });

  it('send renders file attachments in the optimistic user message', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.sendPrompt.mockReturnValue(of<void>(undefined));
    store.openSession('s1');

    store.send([
      { type: 'text', text: 'see file' },
      { type: 'file', filename: 'a.txt', mime: 'text/plain', url: 'a.txt' }
    ]);

    const userMsg = store.messages().find((m) => m.info.role === 'user');
    expect(userMsg?.parts.map((p) => p.type)).toEqual(['text', 'file']);
    expect(userMsg?.parts[1].filename).toBe('a.txt');
  });
  describe('welcome readiness', () => {
    it('sessionsLoaded and welcomeReady are false before bootstrap', () => {
      expect(store.sessionsLoaded()).toBe(false);
      expect(store.welcomeReady()).toBe(false);
    });

    it('sessionsLoaded stays false and the error is set when listSessions fails', () => {
      api.listSessions.mockReturnValue(throwError(() => ({ status: 503 })));

      store.bootstrap();

      expect(store.sessionsLoaded()).toBe(false);
      expect(store.welcomeReady()).toBe(false);
      expect(store.error()).not.toBeNull();
    });

    it('bootstrap without sessions: sessionsLoaded and welcomeReady are true', () => {
      api.listSessions.mockReturnValue(of<Session[]>([]));

      store.bootstrap();

      expect(store.sessionsLoaded()).toBe(true);
      expect(store.activeSessionId()).toBeNull();
      expect(store.welcomeReady()).toBe(true);
    });

    it('bootstrap with sessions: sessionsLoaded true, welcomeReady true once the page has arrived', () => {
      api.listSessions.mockReturnValue(of<Session[]>([{ id: 's1', title: 'One', time: { updated: 1 } }]));

      store.bootstrap();

      expect(store.sessionsLoaded()).toBe(true);
      expect(store.activeSessionId()).toBe('s1');
      expect(store.welcomeReady()).toBe(true);
    });

    it('openSession keeps welcomeReady false until listMessagePage answers', () => {
      const page = new Subject<{ messages: MessageWithParts[]; olderCursor: string | null }>();
      api.listSessions.mockReturnValue(of<Session[]>([{ id: 's1', title: 'One' }]));
      api.listMessagePage.mockReturnValue(page);

      store.bootstrap();

      expect(store.sessionsLoaded()).toBe(true);
      expect(store.activeSessionId()).toBe('s1');
      expect(store.welcomeReady()).toBe(false);

      page.next({ messages: [], olderCursor: null });

      expect(store.welcomeReady()).toBe(true);
    });

    it('switching to another session resets welcomeReady to false until its page arrives', () => {
      api.listSessions.mockReturnValue(of<Session[]>([{ id: 's1', title: 'One' }]));
      store.bootstrap();
      expect(store.welcomeReady()).toBe(true);
      const page = new Subject<{ messages: MessageWithParts[]; olderCursor: string | null }>();
      api.listMessagePage.mockReturnValue(page);

      store.openSession('s2');

      expect(store.welcomeReady()).toBe(false);
      page.next({ messages: [], olderCursor: null });
      expect(store.welcomeReady()).toBe(true);
    });

    it('a late answer for a session the user left does not flip welcomeReady', () => {
      api.listSessions.mockReturnValue(of<Session[]>([{ id: 's1', title: 'One' }]));
      store.bootstrap();
      const first = new Subject<{ messages: MessageWithParts[]; olderCursor: string | null }>();
      const second = new Subject<{ messages: MessageWithParts[]; olderCursor: string | null }>();
      api.listMessagePage.mockReturnValueOnce(first).mockReturnValueOnce(second);

      store.openSession('a');
      store.openSession('b');
      first.next({ messages: [], olderCursor: null });

      expect(store.activeSessionId()).toBe('b');
      expect(store.welcomeReady()).toBe(false);
      second.next({ messages: [], olderCursor: null });
      expect(store.welcomeReady()).toBe(true);
    });

    it('a listMessagePage error leaves welcomeReady false', () => {
      api.listSessions.mockReturnValue(of<Session[]>([{ id: 's1', title: 'One' }]));
      api.listMessagePage.mockReturnValue(throwError(() => ({ status: 500 })));

      store.bootstrap();

      expect(store.sessionsLoaded()).toBe(true);
      expect(store.error()).not.toBeNull();
      expect(store.welcomeReady()).toBe(false);
    });

    it('newSession after bootstrap gives welcomeReady true', () => {
      api.listSessions.mockReturnValue(of<Session[]>([{ id: 's1', title: 'One' }]));
      store.bootstrap();

      store.newSession();

      expect(store.activeSessionId()).toBeNull();
      expect(store.welcomeReady()).toBe(true);
    });

    it('send from a fresh session keeps welcomeReady true and adds the optimistic message', () => {
      api.listSessions.mockReturnValue(of<Session[]>([]));
      api.createSession.mockReturnValue(of<Session>({ id: 'created' }));
      api.sendPrompt.mockReturnValue(of<void>(undefined));
      store.bootstrap();

      store.send([{ type: 'text', text: 'hi' }]);

      expect(store.activeSessionId()).toBe('created');
      expect(store.welcomeReady()).toBe(true);
      expect(store.messages()).toHaveLength(1);
      expect(store.messages()[0].info.role).toBe('user');
      expect(store.sessions().map((s) => s.id)).toEqual(['created']);
    });

    it('refreshSessions does not set sessionsLoaded', () => {
      api.listSessions.mockReturnValue(of<Session[]>([]));

      store.refreshSessions();

      expect(store.sessionsLoaded()).toBe(false);
    });
  });


  it('send creates a session first when there is no active session', () => {
    api.createSession.mockReturnValue(of<Session>({ id: 'created' }));
    api.listSessions.mockReturnValue(of<Session[]>([]));
    api.sendPrompt.mockReturnValue(of<void>(undefined));

    store.send([{ type: 'text', text: 'hi' }]);

    expect(api.createSession).toHaveBeenCalled();
    expect(store.activeSessionId()).toBe('created');
    expect(api.sendPrompt).toHaveBeenCalledWith('created', [{ type: 'text', text: 'hi' }]);
  });

  it('send clears streaming and records a friendly error on dispatch failure', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.sendPrompt.mockReturnValue(throwError(() => ({ status: 503 })));
    store.openSession('s1');

    store.send([{ type: 'text', text: 'hi' }]);

    expect(store.streaming()).toBe(false);
    expect(store.error()).toContain('starting');
  });

  it('abort calls interrupt and clears streaming', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.sendPrompt.mockReturnValue(of<void>(undefined));
    api.interrupt.mockReturnValue(of<void>(undefined));
    store.openSession('s1');
    // Start a turn so the active session is marked streaming, then abort it.
    store.send([{ type: 'text', text: 'hi' }]);
    expect(store.streaming()).toBe(true);

    store.abort();

    expect(api.interrupt).toHaveBeenCalledWith('s1');
    expect(store.streaming()).toBe(false);
  });

  it('abort is a no-op when there is no active session', () => {
    store.abort();
    expect(api.interrupt).not.toHaveBeenCalled();
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

  // -------------------------------------------------------------------------
  // Per-session composer state: the typed draft (text + attachments) and the
  // stop/send button belong to a session, not to the app. These exercise the
  // full switch flow with faked sessions and events - no opencode needed.
  // -------------------------------------------------------------------------

  describe('per-session composer state', () => {
    const imageAttachment = {
      filename: 'shot.png',
      mime: 'image/png',
      url: 'data:image/png;base64,AAAA'
    };

    beforeEach(() => {
      // Two top-level sessions the user can switch between.
      api.listSessions.mockReturnValue(
        of<Session[]>([
          { id: 's1', title: 'One', time: { updated: 200 } },
          { id: 's2', title: 'Two', time: { updated: 100 } }
        ])
      );
      api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
      store.refreshSessions();
    });

    it('keeps each session its own draft text + attachments across switches', () => {
      // s1: text WITH an image attachment.
      store.openSession('s1');
      store.setActiveDraft({ text: 'look at this', attachments: [imageAttachment] });
      expect(store.activeDraft()).toEqual({ text: 'look at this', attachments: [imageAttachment] });

      // Switch to s2: its draft is independent and starts empty.
      store.openSession('s2');
      expect(store.activeDraft()).toEqual({ text: '', attachments: [] });

      // s2: text ONLY, no attachment.
      store.setActiveDraft({ text: 'just text', attachments: [] });
      expect(store.activeDraft()).toEqual({ text: 'just text', attachments: [] });

      // Back to s1: its text and image are still there, untouched by s2.
      store.openSession('s1');
      expect(store.activeDraft()).toEqual({ text: 'look at this', attachments: [imageAttachment] });

      // Back to s2: its text-only draft is still there, with no attachment.
      store.openSession('s2');
      expect(store.activeDraft()).toEqual({ text: 'just text', attachments: [] });
    });

    it('an empty draft is dropped, not stored, so a cleared composer leaves nothing behind', () => {
      store.openSession('s1');
      store.setActiveDraft({ text: 'typing', attachments: [] });
      store.setActiveDraft({ text: '   ', attachments: [] });
      expect(store.activeDraft()).toEqual({ text: '', attachments: [] });
    });

    it('the stop/send button follows the ACTIVE session while a background turn runs', () => {
      // A turn is streaming in s1 only.
      store.openSession('s1');
      events.fire({ type: 'session.execution.started', data: { sessionID: 's1' } });
      store.openSession('s2');

      // Viewing s2 (idle): send button, not stop.
      expect(store.streaming()).toBe(false);

      // Switch to the still-streaming s1: now it's a stop button.
      store.openSession('s1');
      expect(store.streaming()).toBe(true);
    });

    it('sending clears only the sending session draft, leaving the other intact', () => {
      api.sendPrompt.mockReturnValue(of<void>(undefined));

      store.openSession('s1');
      store.setActiveDraft({ text: 'draft one', attachments: [imageAttachment] });
      store.openSession('s2');
      store.setActiveDraft({ text: 'draft two', attachments: [] });

      // Send from s2.
      store.send([{ type: 'text', text: 'draft two' }]);

      // s2's draft is cleared...
      expect(store.activeDraft()).toEqual({ text: '', attachments: [] });
      // ...s1's draft (text + image) survives.
      store.openSession('s1');
      expect(store.activeDraft()).toEqual({ text: 'draft one', attachments: [imageAttachment] });
    });

    it('deleting a session drops its draft and streaming flag', () => {
      api.deleteSession.mockReturnValue(of<void>(undefined));

      store.openSession('s1');
      store.setActiveDraft({ text: 'leftover', attachments: [imageAttachment] });
      events.fire({ type: 'session.execution.started', data: { sessionID: 's1' } });
      expect(store.streaming()).toBe(true);

      store.deleteSession('s1');

      // Active session reset; a fresh draft is empty and the button is a send.
      expect(store.activeDraft()).toEqual({ text: '', attachments: [] });
      expect(store.streaming()).toBe(false);
    });
  });

  // -------------------------------------------------------------------------
  // Interactive forms (V2 Form exchange)
  // -------------------------------------------------------------------------

  const sampleForm = (sessionID = 's1', id = 'frm_1'): FormInfo => ({
    id,
    sessionID,
    title: 'Questions',
    fields: [
      {
        key: 'q0',
        type: 'string',
        options: [{ value: 'Apple', label: 'Apple' }],
        custom: true
      }
    ]
  });

  it('form.created sets the pending form for the active session', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');

    events.fire({ type: 'form.created', data: { form: sampleForm('s1') } });

    expect(store.pendingForm()?.id).toBe('frm_1');
  });

  it('form.created for a non-active session is ignored', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');

    events.fire({ type: 'form.created', data: { form: sampleForm('OTHER') } });

    expect(store.pendingForm()).toBeNull();
  });

  it('form.replied clears the matching pending form', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    events.fire({ type: 'form.created', data: { form: sampleForm('s1') } });

    events.fire({ type: 'form.replied', data: { id: 'frm_1', sessionID: 's1' } });

    expect(store.pendingForm()).toBeNull();
  });

  it('form.cancelled clears the matching pending form', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    events.fire({ type: 'form.created', data: { form: sampleForm('s1') } });

    events.fire({ type: 'form.cancelled', data: { id: 'frm_1', sessionID: 's1' } });

    expect(store.pendingForm()).toBeNull();
  });

  it('a settle event for a different form id does not clear the pending form', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    events.fire({ type: 'form.created', data: { form: sampleForm('s1', 'frm_1') } });

    events.fire({ type: 'form.replied', data: { id: 'frm_OTHER', sessionID: 's1' } });

    expect(store.pendingForm()?.id).toBe('frm_1');
  });

  it('openSession recovers a pending form via listPendingForms', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.listPendingForms.mockReturnValue(of<FormInfo[]>([sampleForm('s1')]));

    store.openSession('s1');

    expect(api.listPendingForms).toHaveBeenCalledWith('s1');
    expect(store.pendingForm()?.id).toBe('frm_1');
  });

  it('openSession clears a stale pending form when the session has none', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    events.fire({ type: 'form.created', data: { form: sampleForm('s1') } });
    expect(store.pendingForm()).not.toBeNull();

    // Switching to a session with no pending form clears it.
    api.listPendingForms.mockReturnValue(of<FormInfo[]>([]));
    store.openSession('s2');

    expect(store.pendingForm()).toBeNull();
  });

  it('newSession clears any pending form', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    events.fire({ type: 'form.created', data: { form: sampleForm('s1') } });

    store.newSession();

    expect(store.pendingForm()).toBeNull();
  });

  it('submitForm replies with the answer and clears the form', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.replyToForm.mockReturnValue(of<void>(undefined));
    store.openSession('s1');
    events.fire({ type: 'form.created', data: { form: sampleForm('s1') } });

    store.submitForm({ q0: 'Apple' });

    expect(api.replyToForm).toHaveBeenCalledWith('s1', 'frm_1', { q0: 'Apple' });
    expect(store.pendingForm()).toBeNull();
  });

  it('submitForm treats a 409 (already settled) as success and clears the form', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.replyToForm.mockReturnValue(throwError(() => ({ status: 409 })));
    store.openSession('s1');
    events.fire({ type: 'form.created', data: { form: sampleForm('s1') } });

    store.submitForm({ q0: 'Apple' });

    expect(store.pendingForm()).toBeNull();
    expect(store.error()).toBeNull();
  });

  it('submitForm surfaces a non-409 error and keeps the form pending', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.replyToForm.mockReturnValue(throwError(() => ({ status: 500 })));
    store.openSession('s1');
    events.fire({ type: 'form.created', data: { form: sampleForm('s1') } });

    store.submitForm({ q0: 'Apple' });

    expect(store.error()).not.toBeNull();
    expect(store.pendingForm()?.id).toBe('frm_1');
  });

  it('submitForm is a no-op when no form is pending', () => {
    store.submitForm({ q0: 'Apple' });
    expect(api.replyToForm).not.toHaveBeenCalled();
  });

  it('cancelPendingForm cancels via the api and clears the form', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.cancelForm.mockReturnValue(of<void>(undefined));
    store.openSession('s1');
    events.fire({ type: 'form.created', data: { form: sampleForm('s1') } });

    store.cancelPendingForm();

    expect(api.cancelForm).toHaveBeenCalledWith('s1', 'frm_1');
    expect(store.pendingForm()).toBeNull();
  });

  it('cancelPendingForm treats a 409 (already settled) as success and clears', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.cancelForm.mockReturnValue(throwError(() => ({ status: 409 })));
    store.openSession('s1');
    events.fire({ type: 'form.created', data: { form: sampleForm('s1') } });

    store.cancelPendingForm();

    expect(store.pendingForm()).toBeNull();
    expect(store.error()).toBeNull();
  });

  describe('sidebar pending forms and report loading (SVY-21846)', () => {
    const fire = (type: string, data: Record<string, unknown>) => events.fire({ type, data });
    const sessionsOf = (...s: Session[]) => {
      api.listSessions.mockReturnValue(of<Session[]>(s));
      store.refreshSessions();
    };
    const busy = (...ids: string[]) =>
      api.getActiveSessions.mockReturnValue(of(new Set(ids) as ReadonlySet<string>));
    const pending = () => [...store.pendingFormSessions()].sort();

    describe('live events', () => {
      it('form.created for a non-active session adds it to pendingFormSessions and leaves pendingForm null', () => {
        store.openSession('s1');
        fire('form.created', { form: sampleForm('s2', 'f2') });

        expect(pending()).toEqual(['s2']);
        expect(store.pendingForm()).toBeNull();
      });

      it('form.created for the active session is in the set and sets pendingForm', () => {
        store.openSession('s1');
        fire('form.created', { form: sampleForm('s1', 'f1') });

        expect(pending()).toEqual(['s1']);
        expect(store.pendingForm()?.id).toBe('f1');
      });

      it.each([
        ['form.replied', { id: 'f2', sessionID: 's2' }],
        ['form.cancelled', { id: 'f2', sessionID: 's2' }],
        ['form.replied', { id: 'f2' }],
        ['form.cancelled', { id: 'f2' }]
      ])('%s %j removes the session', (type, payload) => {
        fire('form.created', { form: sampleForm('s2', 'f2') });
        fire(type, payload);

        expect(pending()).toEqual([]);
      });

      it('a settle event with only a sessionID removes every form of that session', () => {
        fire('form.created', { form: sampleForm('s2', 'f1') });
        fire('form.created', { form: sampleForm('s2', 'f2') });
        fire('form.created', { form: sampleForm('s3', 'f3') });

        fire('form.cancelled', { sessionID: 's2' });

        expect(pending()).toEqual(['s3']);
      });

      it('keeps the session in the set until both of its forms are settled', () => {
        fire('form.created', { form: sampleForm('s2', 'f1') });
        fire('form.created', { form: sampleForm('s2', 'f2') });

        fire('form.replied', { id: 'f1', sessionID: 's2' });
        expect(pending()).toEqual(['s2']);

        fire('form.replied', { id: 'f2', sessionID: 's2' });
        expect(pending()).toEqual([]);
      });

      it('does not emit a new set instance when nothing changed', () => {
        fire('form.created', { form: sampleForm('s2', 'f1') });
        const before = store.pendingFormSessions();
        fire('form.created', { form: sampleForm('s2', 'f1') });
        fire('form.replied', { id: 'unknown' });

        expect(store.pendingFormSessions()).toBe(before);
      });

      it.each([
        ['session.idle', { sessionID: 's2' }],
        ['session.status', { sessionID: 's2', status: { type: 'idle' } }],
        ['session.deleted', { sessionID: 's2' }]
      ])('%s drops the entries of that session only', (type, payload) => {
        fire('form.created', { form: sampleForm('s2', 'f2') });
        fire('form.created', { form: sampleForm('s3', 'f3') });

        fire(type, payload);

        expect(pending()).toEqual(['s3']);
      });

      it('idle does not touch the active pendingForm', () => {
        store.openSession('s1');
        fire('form.created', { form: sampleForm('s1', 'f1') });

        fire('session.idle', { sessionID: 's1' });

        expect(pending()).toEqual([]);
        expect(store.pendingForm()?.id).toBe('f1');
      });

      it('submitForm and cancelPendingForm remove the form from pendingFormSessions', () => {
        api.replyToForm.mockReturnValue(of<void>(undefined));
        api.cancelForm.mockReturnValue(of<void>(undefined));
        store.openSession('s1');
        fire('form.created', { form: sampleForm('s1', 'f1') });
        store.submitForm({ q0: 'Apple' });
        expect(pending()).toEqual([]);

        fire('form.created', { form: sampleForm('s1', 'f2') });
        expect(pending()).toEqual(['s1']);
        store.cancelPendingForm();
        expect(pending()).toEqual([]);
      });

      it('a 409 on submitForm also removes the form from pendingFormSessions', () => {
        api.replyToForm.mockReturnValue(throwError(() => ({ status: 409 })));
        store.openSession('s1');
        fire('form.created', { form: sampleForm('s1', 'f1') });

        store.submitForm({ q0: 'Apple' });

        expect(pending()).toEqual([]);
      });
    });

    describe('seeding', () => {
      it('bootstrap seeds /session/active once before any openSession', () => {
        busy('a');
        api.listSessions.mockReturnValue(of<Session[]>([]));

        store.bootstrap();

        expect(api.getActiveSessions).toHaveBeenCalledTimes(1);
        expect(store.busySessionIds().has('a')).toBe(true);
      });

      it('primary path: forms of two sessions are seeded and listPendingForms is not called', () => {
        api.listSessions.mockReturnValue(of<Session[]>([]));
        api.listAllPendingForms.mockReturnValue(of([sampleForm('a', 'fa'), sampleForm('b', 'fb')]));

        store.bootstrap();

        expect(pending()).toEqual(['a', 'b']);
        expect(api.listPendingForms).not.toHaveBeenCalled();
      });

      it('fallback path queries only busy top-level sessions', () => {
        sessionsOf({ id: 'a' }, { id: 'b' }, { id: 'c', parentID: 'a' });
        busy('a', 'c');
        api.listPendingForms.mockImplementation((id: string) =>
          of(id === 'a' ? [sampleForm('a', 'fa')] : [])
        );

        events.fireReconnect();

        expect(api.listPendingForms).toHaveBeenCalledTimes(1);
        expect(api.listPendingForms).toHaveBeenCalledWith('a');
        expect(pending()).toEqual(['a']);
      });

      it('fallback path survives a per-session failure', () => {
        sessionsOf({ id: 'a' }, { id: 'b' });
        busy('a', 'b');
        api.listPendingForms.mockImplementation((id: string) =>
          id === 'a' ? throwError(() => ({ status: 500 })) : of([sampleForm('b', 'fb')])
        );

        events.fireReconnect();

        expect(api.listPendingForms).toHaveBeenCalledTimes(2);
        expect(pending()).toEqual(['b']);
        expect(store.error()).toBeNull();
      });

      it('makes no per-session requests when nothing is busy', () => {
        sessionsOf({ id: 'a' }, { id: 'b' });
        busy();

        events.fireReconnect();

        expect(api.listPendingForms).not.toHaveBeenCalled();
        expect(pending()).toEqual([]);
      });

      it('a form.created arriving while the snapshot is pending survives it', () => {
        const snapshot = new Subject<FormInfo[]>();
        api.listAllPendingForms.mockReturnValue(snapshot.asObservable());
        events.fireReconnect();

        fire('form.created', { form: sampleForm('s9', 'f9') });
        snapshot.next([sampleForm('s1', 'f1')]);

        expect(pending()).toEqual(['s1', 's9']);
      });

      it('a form.replied arriving while the snapshot is pending removes a form the snapshot lists', () => {
        const snapshot = new Subject<FormInfo[]>();
        api.listAllPendingForms.mockReturnValue(snapshot.asObservable());
        events.fireReconnect();

        fire('form.replied', { id: 'f1', sessionID: 's1' });
        snapshot.next([sampleForm('s1', 'f1'), sampleForm('s2', 'f2')]);

        expect(pending()).toEqual(['s2']);
      });

      it('reconnect re-seeds and clears stale entries', () => {
        fire('form.created', { form: sampleForm('stale', 'fs') });
        expect(pending()).toEqual(['stale']);
        api.listAllPendingForms.mockReturnValue(of([sampleForm('fresh', 'ff')]));

        events.fireReconnect();

        expect(pending()).toEqual(['fresh']);
      });
    });

    describe('loadSessionReports', () => {
      const bgResult = (childId: string, state: string, body: string) =>
        `<background-result kind="subagent" sessionID="${childId}" state="${state}" description="d">\n${body}\n</background-result>`;
      const synthetic = (id: string, text: string): MessageWithParts => ({
        info: { id, role: 'synthetic', text } as MessageWithParts['info'],
        parts: []
      });
      const tree = () => sessionsOf({ id: 's1' }, { id: 'c1', parentID: 's1' }, { id: 's2' });
      const pageWith = (...messages: MessageWithParts[]) =>
        api.listMessagePage.mockReturnValue(of({ messages, olderCursor: null }));

      it('records an INPUT_REQUIRED hand-back of a cold session as stopped', () => {
        tree();
        pageWith(synthetic('m1', bgResult('c1', 'completed', 'Status: INPUT_REQUIRED\nneed a decision')));

        store.loadSessionReports('s2');

        const signals = store.childSignals().get('c1');
        expect(signals?.result?.body).toContain('INPUT_REQUIRED');
        expect(deriveChildStatus(signals).status).toBe('stopped');
      });

      it('requests once per session', () => {
        tree();
        store.loadSessionReports('s2');
        store.loadSessionReports('s2');

        expect(api.listMessagePage).toHaveBeenCalledTimes(1);
        expect(api.listMessagePage).toHaveBeenCalledWith('s2');
      });

      it('makes no request for the active session', () => {
        tree();
        store.openSession('s2');
        api.listMessagePage.mockClear();

        store.loadSessionReports('s2');

        expect(api.listMessagePage).not.toHaveBeenCalled();
      });

      it('does not overwrite a live-recorded result', () => {
        tree();
        fire('session.synthetic', { sessionID: 's1', text: bgResult('c1', 'completed', 'Status: COMPLETED\nlive') });
        pageWith(synthetic('m1', bgResult('c1', 'completed', 'Status: INPUT_REQUIRED\nold')));

        store.loadSessionReports('s2');

        expect(store.childSignals().get('c1')?.result?.body).toBe('Status: COMPLETED\nlive');
      });

      it('a failure raises no error and a later call retries', () => {
        tree();
        api.listMessagePage.mockReturnValue(throwError(() => ({ status: 500 })));

        store.loadSessionReports('s2');
        expect(store.error()).toBeNull();

        pageWith(synthetic('m1', bgResult('c1', 'completed', 'Status: INPUT_REQUIRED')));
        store.loadSessionReports('s2');

        expect(api.listMessagePage).toHaveBeenCalledTimes(2);
        expect(store.childSignals().get('c1')?.result).toBeDefined();
      });

      it('leaves messages and the active session untouched', () => {
        tree();
        api.listMessagePage.mockReturnValue(
          of({ messages: [{ info: { id: 'u1', role: 'user' }, parts: [] }] as MessageWithParts[], olderCursor: null })
        );
        store.openSession('s1');
        const before = store.messages();

        pageWith(
          { info: { id: 'u9', role: 'user' }, parts: [] } as MessageWithParts,
          synthetic('m1', bgResult('c1', 'completed', 'Status: COMPLETED'))
        );
        store.loadSessionReports('s2');

        expect(store.activeSessionId()).toBe('s1');
        expect(store.messages()).toBe(before);
        expect(store.messages().map((m) => m.info.id)).toEqual(['u1']);
      });
    });
  });

  describe('agent card child signals (SVY-21849)', () => {
    const child = (id = 'c1', extra: Partial<Session> = {}): Session => ({ id, parentID: 's1', title: id, ...extra });

    const bgResult = (childId: string, state: string, body: string) =>
      `<background-result kind="subagent" sessionID="${childId}" state="${state}" description="d">\n${body}\n</background-result>`;

    const synthetic = (id: string, text: string): MessageWithParts => ({
      info: { id, role: 'synthetic', text } as MessageWithParts['info'],
      parts: []
    });

    const withChildren = (...children: Session[]) => {
      api.listSessions.mockReturnValue(of<Session[]>([{ id: 's1', title: 'Parent' }, ...children]));
      store.refreshSessions();
    };

    const fire = (type: string, data: Record<string, unknown>) => events.fire({ type, data });

    const activeIds = () =>
      (store as unknown as { activeSessionIds: () => ReadonlySet<string> }).activeSessionIds();

    describe('busy tracking', () => {
      it('adds a session to busySessionIds on session.execution.started and exposes busy: true in childSignals', () => {
        withChildren(child());
        expect(store.busySessionIds().has('c1')).toBe(false);

        fire('session.execution.started', { sessionID: 'c1' });

        expect(store.busySessionIds().has('c1')).toBe(true);
        expect(store.childSignals().get('c1')?.busy).toBe(true);
      });

      it.each([
        ['session.idle', {}],
        ['session.execution.succeeded', {}],
        ['session.execution.failed', { error: 'x' }],
        ['session.execution.interrupted', { reason: 'user' }],
        ['session.status', { status: { type: 'idle' } }]
      ])('removes the session from busySessionIds after %s', (type, extra) => {
        withChildren(child());
        fire('session.execution.started', { sessionID: 'c1' });
        expect(store.busySessionIds().has('c1')).toBe(true);

        fire(type, { sessionID: 'c1', ...extra });

        expect(store.busySessionIds().has('c1')).toBe(false);
        expect(store.childSignals().get('c1')?.busy).not.toBe(true);
      });

      it('session.status busy marks the session busy and idle clears it', () => {
        withChildren(child());

        fire('session.status', { sessionID: 'c1', status: { type: 'busy' } });
        expect(store.busySessionIds().has('c1')).toBe(true);

        fire('session.status', { sessionID: 'c1', status: { type: 'idle' } });
        expect(store.busySessionIds().has('c1')).toBe(false);
      });

      it('session.status retry leaves the busy state as it is', () => {
        withChildren(child());
        fire('session.execution.started', { sessionID: 'c1' });
        fire('session.status', { sessionID: 'c1', status: { type: 'retry', attempt: 1 } });
        expect(store.busySessionIds().has('c1')).toBe(true);
      });

      it('session.status busy for the active session turns streaming on, idle turns it off', () => {
        store.openSession('s1');
        fire('session.status', { sessionID: 's1', status: { type: 'busy' } });
        expect(store.streaming()).toBe(true);
        fire('session.status', { sessionID: 's1', status: { type: 'idle' } });
        expect(store.streaming()).toBe(false);
      });

      it('childSignals busy is undefined before the active endpoint was seeded and false after', () => {
        withChildren(child());
        expect(store.childSignals().get('c1')?.busy).toBeUndefined();

        store.openSession('s1');

        expect(store.childSignals().get('c1')?.busy).toBe(false);
      });

      it('childSignals only contains child sessions of the list, not top-level sessions', () => {
        withChildren(child());
        expect(Array.from(store.childSignals().keys())).toEqual(['c1']);
      });
    });

    describe('outcomes and interrupts', () => {
      it('records the outcome from the session list', () => {
        withChildren(child('c1', { outcome: 'succeeded' }));
        expect(store.childSignals().get('c1')?.outcome).toBe('succeeded');
      });

      it('live outcomes override the list outcome', () => {
        withChildren(child('c1', { outcome: 'succeeded' }));
        fire('session.execution.failed', { sessionID: 'c1', error: 'x' });
        expect(store.childSignals().get('c1')?.outcome).toBe('failed');
      });

      it('execution.succeeded records a succeeded outcome', () => {
        withChildren(child());
        fire('session.execution.started', { sessionID: 'c1' });
        fire('session.execution.succeeded', { sessionID: 'c1' });
        expect(store.childSignals().get('c1')?.outcome).toBe('succeeded');
      });

      it('session.idle alone writes no outcome', () => {
        withChildren(child());
        fire('session.execution.started', { sessionID: 'c1' });
        fire('session.idle', { sessionID: 'c1' });
        expect(store.childSignals().get('c1')?.outcome).toBeUndefined();
      });

      it('execution.interrupted records the reason and an interrupted outcome', () => {
        withChildren(child());
        fire('session.execution.started', { sessionID: 'c1' });

        fire('session.execution.interrupted', { sessionID: 'c1', reason: 'shutdown' });

        expect(store.childSignals().get('c1')).toMatchObject({
          outcome: 'interrupted',
          interruptedReason: 'shutdown'
        });
      });

      it.each(['user', 'shutdown', 'superseded', 'inactivity'])('keeps the %s interrupt reason', (reason) => {
        withChildren(child());
        fire('session.execution.interrupted', { sessionID: 'c1', reason });
        expect(store.childSignals().get('c1')?.interruptedReason).toBe(reason);
      });

      it('ignores an unknown interrupt reason but still records the interrupted outcome', () => {
        withChildren(child());
        fire('session.execution.interrupted', { sessionID: 'c1', reason: 'cosmic-ray' });
        expect(store.childSignals().get('c1')?.interruptedReason).toBeUndefined();
        expect(store.childSignals().get('c1')?.outcome).toBe('interrupted');
      });

      it('execution.started clears the previous live outcome and interrupt reason', () => {
        withChildren(child('c1', { outcome: 'failed' }));
        fire('session.execution.interrupted', { sessionID: 'c1', reason: 'user' });
        expect(store.childSignals().get('c1')?.interruptedReason).toBe('user');

        fire('session.execution.started', { sessionID: 'c1' });

        const signals = store.childSignals().get('c1');
        expect(signals?.interruptedReason).toBeUndefined();
        expect(signals?.outcome).toBe('failed');
        expect(signals?.busy).toBe(true);
      });

      it('execution.interrupted clears the active session streaming flag', () => {
        store.openSession('s1');
        fire('session.execution.started', { sessionID: 's1' });
        expect(store.streaming()).toBe(true);

        fire('session.execution.interrupted', { sessionID: 's1', reason: 'user' });

        expect(store.streaming()).toBe(false);
      });

      it('execution.interrupted does not schedule a title refresh, while session.idle does', () => {
        vi.useFakeTimers();
        try {
          api.getSession.mockReturnValue(of<Session>({ id: 's1', title: 'T' }));
          store.openSession('s1');

          fire('session.execution.interrupted', { sessionID: 's1', reason: 'user' });
          vi.advanceTimersByTime(20000);
          expect(api.getSession).not.toHaveBeenCalled();

          fire('session.idle', { sessionID: 's1' });
          vi.advanceTimersByTime(20000);
          expect(api.getSession).toHaveBeenCalled();
        } finally {
          vi.useRealTimers();
        }
      });
    });

    describe('background results', () => {
      it('seeds the result of a synthetic message and never renders it in the transcript', () => {
        withChildren(child());
        seedPage([
          { info: { id: 'u1', role: 'user' }, parts: [{ type: 'text', text: 'go' } as Part] },
          synthetic('syn1', bgResult('c1', 'completed', 'Done it\nStatus: COMPLETED'))
        ]);

        store.openSession('s1');

        expect(store.childSignals().get('c1')?.result).toEqual({
          state: 'completed',
          body: 'Done it\nStatus: COMPLETED'
        });
        expect(store.messages().map((m) => m.info.id)).toEqual(['u1']);
      });

      it('the latest seeded result of a continued child wins', () => {
        withChildren(child());
        seedPage([
          synthetic('syn1', bgResult('c1', 'completed', 'Status: INPUT_REQUIRED\nQuestion')),
          synthetic('syn2', bgResult('c1', 'completed', 'Status: COMPLETED\nAll done'))
        ]);

        store.openSession('s1');

        expect(store.childSignals().get('c1')?.result?.body).toBe('Status: COMPLETED\nAll done');
      });

      it('tracks results for several children independently', () => {
        withChildren(child('c1'), child('c2'));
        seedPage([
          synthetic('syn1', bgResult('c1', 'completed', 'one')),
          synthetic('syn2', bgResult('c2', 'error', 'two'))
        ]);

        store.openSession('s1');

        expect(store.childSignals().get('c1')?.result).toEqual({ state: 'completed', body: 'one' });
        expect(store.childSignals().get('c2')?.result).toEqual({ state: 'error', body: 'two' });
      });

      it('ignores synthetic messages that are not a subagent background-result', () => {
        withChildren(child());
        seedPage([synthetic('syn1', 'just some text'), synthetic('syn2', '<background-result kind="shell" sessionID="c1" state="completed">x</background-result>')]);

        store.openSession('s1');

        expect(store.childSignals().get('c1')?.result).toBeUndefined();
      });

      it('creates a childSignals entry for a result whose child is not in the session list', () => {
        seedPage([synthetic('syn1', bgResult('ghost', 'completed', 'x'))]);
        store.openSession('s1');
        expect(store.childSignals().get('ghost')?.result?.state).toBe('completed');
      });

      it('captures a live session.synthetic event, latest wins, and renders nothing', () => {
        withChildren(child());
        store.openSession('s1');

        fire('session.synthetic', { sessionID: 's1', text: bgResult('c1', 'completed', 'Status: INPUT_REQUIRED\nq') });
        expect(store.childSignals().get('c1')?.result?.body).toBe('Status: INPUT_REQUIRED\nq');

        fire('session.synthetic', { sessionID: 's1', text: bgResult('c1', 'completed', 'Status: COMPLETED\nok') });
        expect(store.childSignals().get('c1')?.result?.body).toBe('Status: COMPLETED\nok');
        expect(store.messages()).toEqual([]);
      });

      it('captures the report from a live session.inbox.enqueued synthetic item, which is what 2.4.1 emits', () => {
        withChildren(child());
        store.openSession('s1');

        fire('session.inbox.enqueued', {
          sessionID: 's1',
          inboxID: 'msg1',
          item: { type: 'synthetic', delivery: 'steer', payload: { text: bgResult('c1', 'completed', 'Status: COMPLETED\nAnswer: one form') } }
        });

        expect(store.childSignals().get('c1')?.result?.state).toBe('completed');
        expect(store.childSignals().get('c1')?.result?.body).toBe('Status: COMPLETED\nAnswer: one form');
        expect(store.messages()).toEqual([]);
      });

      it('a live inbox item overwrites an older seeded result', () => {
        withChildren(child());
        seedPage([synthetic('syn1', bgResult('c1', 'completed', 'Status: INPUT_REQUIRED\nold'))]);
        store.openSession('s1');

        fire('session.inbox.enqueued', {
          sessionID: 's1',
          inboxID: 'msg2',
          item: { type: 'synthetic', delivery: 'steer', payload: { text: bgResult('c1', 'completed', 'Status: COMPLETED\nnew') } }
        });

        expect(store.childSignals().get('c1')?.result?.body).toBe('Status: COMPLETED\nnew');
      });

      it('ignores inbox items that are not a background result', () => {
        withChildren(child());
        store.openSession('s1');

        fire('session.inbox.enqueued', { sessionID: 's1', inboxID: 'm', item: { type: 'user', payload: { text: bgResult('c1', 'completed', 'x') } } });
        fire('session.inbox.enqueued', { sessionID: 's1', inboxID: 'm', item: { type: 'synthetic', payload: { text: 'plain note' } } });
        fire('session.inbox.enqueued', { sessionID: 's1', inboxID: 'm' });

        expect(store.childSignals().get('c1')?.result).toBeUndefined();
      });

      it('ignores a live session.synthetic without usable text', () => {
        withChildren(child());
        fire('session.synthetic', { sessionID: 's1' });
        fire('session.synthetic', { sessionID: 's1', text: 'plain' });
        expect(store.childSignals().get('c1')?.result).toBeUndefined();
      });

      it('a live session.synthetic always wins over a seeded result', () => {
        withChildren(child());
        seedPage([synthetic('syn1', bgResult('c1', 'completed', 'Status: INPUT_REQUIRED\nold'))]);
        store.openSession('s1');

        fire('session.synthetic', { sessionID: 's1', text: bgResult('c1', 'completed', 'Status: COMPLETED\nnew') });

        expect(store.childSignals().get('c1')?.result?.body).toBe('Status: COMPLETED\nnew');
      });

      describe('loading older messages (B1 regression)', () => {
        const openWithNewestPage = (newest: MessageWithParts[]) => {
          withChildren(child('c1'));
          api.listMessagePage.mockReturnValueOnce(of({ messages: newest, olderCursor: 'cursor-1' }));
          store.openSession('s1');
        };

        it('does not overwrite a newer result with an older page (INPUT_REQUIRED then COMPLETED stays done)', () => {
          openWithNewestPage([synthetic('syn2', bgResult('c1', 'completed', 'Status: COMPLETED\nround two'))]);
          api.listMessagePage.mockReturnValueOnce(
            of({ messages: [synthetic('syn1', bgResult('c1', 'completed', 'Status: INPUT_REQUIRED\nround one'))], olderCursor: null })
          );

          store.loadOlderMessages();

          expect(store.childSignals().get('c1')?.result?.body).toBe('Status: COMPLETED\nround two');
        });

        it('does not overwrite a live result with an older page', () => {
          openWithNewestPage([]);
          fire('session.synthetic', { sessionID: 's1', text: bgResult('c1', 'completed', 'Status: COMPLETED\nlive') });
          api.listMessagePage.mockReturnValueOnce(
            of({ messages: [synthetic('syn1', bgResult('c1', 'completed', 'Status: INPUT_REQUIRED\nold'))], olderCursor: null })
          );

          store.loadOlderMessages();

          expect(store.childSignals().get('c1')?.result?.body).toBe('Status: COMPLETED\nlive');
        });

        it('fills a child that has no result yet', () => {
          withChildren(child('c1'), child('c2'));
          api.listMessagePage.mockReturnValueOnce(
            of({ messages: [synthetic('syn2', bgResult('c1', 'completed', 'newest c1'))], olderCursor: 'cursor-1' })
          );
          store.openSession('s1');
          api.listMessagePage.mockReturnValueOnce(
            of({ messages: [synthetic('syn1', bgResult('c2', 'error', 'older c2'))], olderCursor: null })
          );

          store.loadOlderMessages();

          expect(store.childSignals().get('c2')?.result).toEqual({ state: 'error', body: 'older c2' });
          expect(store.childSignals().get('c1')?.result?.body).toBe('newest c1');
        });

        it('within one older page the newest message wins', () => {
          openWithNewestPage([]);
          api.listMessagePage.mockReturnValueOnce(
            of({
              messages: [
                synthetic('syn1', bgResult('c1', 'completed', 'Status: INPUT_REQUIRED\nfirst')),
                synthetic('syn2', bgResult('c1', 'completed', 'Status: COMPLETED\nsecond'))
              ],
              olderCursor: null
            })
          );

          store.loadOlderMessages();

          expect(store.childSignals().get('c1')?.result?.body).toBe('Status: COMPLETED\nsecond');
        });

        it('keeps the older synthetic message out of the transcript', () => {
          openWithNewestPage([{ info: { id: 'u2', role: 'user' }, parts: [{ type: 'text', text: 'later' } as Part] }]);
          api.listMessagePage.mockReturnValueOnce(
            of({
              messages: [
                { info: { id: 'u1', role: 'user' }, parts: [{ type: 'text', text: 'earlier' } as Part] },
                synthetic('syn1', bgResult('c1', 'completed', 'x'))
              ],
              olderCursor: null
            })
          );

          store.loadOlderMessages();

          expect(store.messages().map((m) => m.info.id)).toEqual(['u1', 'u2']);
        });
      });
    });

    describe('active session seeding', () => {
      it('requests /session/active when a session is opened', () => {
        store.openSession('s1');
        expect(api.getActiveSessions).toHaveBeenCalledTimes(1);
      });

      it('seeds the busy set from the server answer', () => {
        withChildren(child());
        api.getActiveSessions.mockReturnValue(of(new Set(['c1']) as ReadonlySet<string>));

        store.openSession('s1');

        expect(store.busySessionIds().has('c1')).toBe(true);
        expect(store.childSignals().get('c1')?.busy).toBe(true);
      });

      it('filters an id that ended while the request was in flight', () => {
        const response = new Subject<ReadonlySet<string>>();
        api.getActiveSessions.mockReturnValue(response.asObservable());
        withChildren(child('c1'), child('c2'));
        store.openSession('s1');

        fire('session.idle', { sessionID: 'c1' });
        response.next(new Set(['c1', 'c2']));

        expect(store.busySessionIds().has('c1')).toBe(false);
        expect(store.busySessionIds().has('c2')).toBe(true);
      });

      it('un-filters an id that started again while the request was in flight', () => {
        const response = new Subject<ReadonlySet<string>>();
        api.getActiveSessions.mockReturnValue(response.asObservable());
        withChildren(child('c1'));
        store.openSession('s1');

        fire('session.idle', { sessionID: 'c1' });
        fire('session.execution.started', { sessionID: 'c1' });
        response.next(new Set(['c1']));

        expect(activeIds().has('c1')).toBe(true);
      });

      it('replaces the previous seed and does not stay stuck busy after the child ends', () => {
        withChildren(child('c1'));
        api.getActiveSessions.mockReturnValue(of(new Set(['c1']) as ReadonlySet<string>));
        store.openSession('s1');
        expect(store.busySessionIds().has('c1')).toBe(true);

        fire('session.idle', { sessionID: 'c1' });

        expect(store.busySessionIds().has('c1')).toBe(false);
        expect(store.childSignals().get('c1')?.busy).toBe(false);
      });

      it('an empty answer (the api maps a failed request to an empty set) leaves nothing stuck busy', () => {
        withChildren(child('c1'));
        api.getActiveSessions.mockReturnValue(of(new Set<string>() as ReadonlySet<string>));

        store.openSession('s1');

        expect(store.busySessionIds().size).toBe(0);
        expect(store.childSignals().get('c1')?.busy).toBe(false);
      });

      it('a completed seed no longer filters ids in a later seed', () => {
        withChildren(child('c1'));
        store.openSession('s1');
        fire('session.idle', { sessionID: 'c1' });
        api.getActiveSessions.mockReturnValue(of(new Set(['c1']) as ReadonlySet<string>));

        events.fireReconnect();

        expect(store.busySessionIds().has('c1')).toBe(true);
      });

      it('re-seeds when the event stream reconnects', () => {
        store.openSession('s1');
        expect(api.getActiveSessions).toHaveBeenCalledTimes(1);
        api.getActiveSessions.mockReturnValue(of(new Set(['c9']) as ReadonlySet<string>));

        events.fireReconnect();

        expect(api.getActiveSessions).toHaveBeenCalledTimes(2);
        expect(store.busySessionIds().has('c9')).toBe(true);
      });
    });

    describe('session.deleted pruning', () => {
      it('prunes every per-session map and set of the deleted session', () => {
        withChildren(child('c1'));
        api.getActiveSessions.mockReturnValue(of(new Set(['c1']) as ReadonlySet<string>));
        store.openSession('s1');
        fire('session.execution.started', { sessionID: 'c1' });
        fire('session.execution.interrupted', { sessionID: 'c1', reason: 'user' });
        fire('session.execution.started', { sessionID: 'c1' });
        fire('session.execution.interrupted', { sessionID: 'c1', reason: 'user' });
        fire('session.synthetic', { sessionID: 's1', text: bgResult('c1', 'completed', 'x') });
        expect(store.childSignals().get('c1')?.result).toBeDefined();

        fire('session.deleted', { sessionID: 'c1' });
        expect(store.childSignals().has('c1')).toBe(false);
        expect(store.busySessionIds().has('c1')).toBe(false);
        expect(activeIds().has('c1')).toBe(false);

        fire('session.updated', { info: { id: 'c1', parentID: 's1' } });
        expect(store.childSignals().get('c1')).toMatchObject({
          outcome: undefined,
          interruptedReason: undefined,
          result: undefined
        });
      });

      it('prunes a busy streaming flag', () => {
        withChildren(child('c1'));
        fire('session.execution.started', { sessionID: 'c1' });
        fire('session.deleted', { sessionID: 'c1' });
        expect(store.busySessionIds().has('c1')).toBe(false);
      });
    });
  });

  describe('loaded messages snapshot (SVY-21851)', () => {
    const wmsg = (id: string, sessionID: string | undefined, filePath: string): MessageWithParts =>
      ({
        info: { id, role: 'assistant', ...(sessionID ? { sessionID } : {}) },
        parts: [{ type: 'tool', tool: 'write', state: { status: 'completed', input: { filePath } } } as unknown as Part]
      }) as MessageWithParts;

    let loaded: LoadedMessagesService;

    beforeEach(() => {
      loaded = TestBed.inject(LoadedMessagesService);
      api.listSessions.mockReturnValue(of<Session[]>([]));
    });

    it('records the active session messages so file chips can see them', () => {
      seedPage([wmsg('m1', 's1', 'a.js')]);
      store.openSession('s1');
      TestBed.tick();
      expect(loaded.bySession().get('s1')?.map((m) => (m as MessageWithParts).info.id)).toEqual(['m1']);
    });

    it('does not record an empty transcript', () => {
      store.openSession('s1');
      TestBed.tick();
      expect(loaded.bySession().has('s1')).toBe(false);
    });

    it('keeps the snapshot of a child after another session becomes active', () => {
      seedPage([wmsg('m1', 'c1', 'child.js')]);
      store.openSession('c1');
      TestBed.tick();
      seedPage([wmsg('m2', 's1', 'parent.js')]);
      store.openSession('s1');
      TestBed.tick();
      expect([...loaded.bySession().keys()].sort()).toEqual(['c1', 's1']);
    });

    it('does not record messages that belong to another session than the active one', () => {
      seedPage([wmsg('m1', 'other', 'x.js')]);
      store.openSession('s1');
      TestBed.tick();
      expect(loaded.bySession().has('s1')).toBe(false);
    });

    it('forgets the snapshot when a session is deleted through the API', () => {
      loaded.record('s9', [{ parts: [] }]);
      api.deleteSession.mockReturnValue(of(null));
      store.deleteSession('s9');
      expect(loaded.bySession().has('s9')).toBe(false);
    });

    it('forgets the snapshot when the session.deleted event arrives', () => {
      loaded.record('c1', [{ parts: [] }]);
      loaded.record('keep', [{ parts: [] }]);
      events.fire({ type: 'session.deleted', data: { sessionID: 'c1' } });
      expect([...loaded.bySession().keys()]).toEqual(['keep']);
    });

    it('does not forget the snapshot when the delete request fails', () => {
      loaded.record('s9', [{ parts: [] }]);
      api.deleteSession.mockReturnValue(throwError(() => new Error('x')));
      store.deleteSession('s9');
      expect(loaded.bySession().has('s9')).toBe(true);
    });
  });
});
