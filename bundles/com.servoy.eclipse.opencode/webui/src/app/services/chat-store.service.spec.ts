import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Subject, of, throwError } from 'rxjs';

import { ChatStore } from './chat-store.service';
import { EventStreamService, OpencodeEvent } from './event-stream.service';
import { OpencodeApiService } from './opencode-api.service';
import { FormInfo, MessageWithParts, Part, SendPart, Session } from '../models/opencode.models';

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
    updateSessionTitle: vi.fn(),
    deleteSession: vi.fn(),
    listMessages: vi.fn(),
    sendPrompt: vi.fn(),
    interrupt: vi.fn(),
    findFiles: vi.fn(),
    readFile: vi.fn(),
    listPendingForms: vi.fn(),
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
  });

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
    expect(api.listMessages).toHaveBeenCalledWith('new');
  });

  it('bootstrap starts a fresh draft when there are no sessions', () => {
    api.listSessions.mockReturnValue(of<Session[]>([]));

    store.bootstrap();

    expect(store.activeSessionId()).toBeNull();
    expect(store.messages()).toEqual([]);
    expect(api.listMessages).not.toHaveBeenCalled();
  });

  it('bootstrap starts a fresh draft when only archived/child sessions exist', () => {
    const sessions: Session[] = [
      { id: 'child', parentID: 'p', time: { updated: 900 } },
      { id: 'archived', time: { updated: 999, archived: 999 } }
    ];
    api.listSessions.mockReturnValue(of(sessions));

    store.bootstrap();

    expect(store.activeSessionId()).toBeNull();
    expect(api.listMessages).not.toHaveBeenCalled();
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
    api.listMessages.mockReturnValue(of(msgs));

    store.openSession('s1');

    expect(store.messages().map((m) => m.info.id)).toEqual(['u1', 'a1']);
  });

  it('openSession keeps a user message even when it has no parts', () => {
    const msgs: MessageWithParts[] = [{ info: { id: 'u1', role: 'user' }, parts: [] }];
    api.listMessages.mockReturnValue(of(msgs));

    store.openSession('s1');

    expect(store.messages().map((m) => m.info.id)).toEqual(['u1']);
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

  it('backfills a running subagent child id on re-seed from live-captured metadata (link survives the round-trip)', () => {
    // 1. Parent is active; its subagent dispatches and a tool.progress event
    //    carries the child sessionID while it is still RUNNING.
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
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
    api.listMessages.mockReturnValue(
      of<MessageWithParts[]>([
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
      ])
    );
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
    api.listMessages.mockReturnValue(
      of<MessageWithParts[]>([
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
      ])
    );
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
    api.listMessages.mockReturnValue(
      of<MessageWithParts[]>([{ info: { id: 'm1', role: 'user' }, parts: [{ type: 'text', text: 'hi' } as Part] }])
    );

    store.exportSession('s1');

    expect(api.getSession).toHaveBeenCalledWith('s1');
    expect(api.listMessages).toHaveBeenCalledWith('s1');
    expect(clickSpy).toHaveBeenCalled();
    expect(anchor.download).toBe('fix-bug.json');

    createEl.mockRestore();
    createUrl.mockRestore();
    revokeUrl.mockRestore();
  });

  it('exportSession surfaces an error when loading fails', () => {
    api.getSession.mockReturnValue(throwError(() => ({ status: 500 })));
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));

    store.exportSession('s1');

    expect(store.error()).not.toBeNull();
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
});
