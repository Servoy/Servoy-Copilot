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
    archiveSession: vi.fn(),
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
    store.streaming.set(true);

    events.fire({ type: 'session.idle', data: { sessionID: 's1' } });

    expect(store.streaming()).toBe(false);
  });

  it('clears the streaming flag on session.execution.succeeded for the active session', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    store.streaming.set(true);

    events.fire({ type: 'session.execution.succeeded', data: { sessionID: 's1' } });

    expect(store.streaming()).toBe(false);
  });

  it('clears the streaming flag on session.error for the active session', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    store.streaming.set(true);

    events.fire({ type: 'session.error', data: { sessionID: 's1' } });

    expect(store.streaming()).toBe(false);
  });

  it('does not clear streaming on session.idle for a different session', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    store.openSession('s1');
    store.streaming.set(true);

    events.fire({ type: 'session.idle', data: { sessionID: 'other' } });

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

  it('archiveSession archives, refreshes and resets the active session when it was active', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.archiveSession.mockReturnValue(of<Session>({ id: 's1', time: { archived: 1 } }));
    api.listSessions.mockReturnValue(of<Session[]>([]));
    store.openSession('s1');

    store.archiveSession('s1');

    expect(api.archiveSession).toHaveBeenCalledWith('s1');
    expect(store.activeSessionId()).toBeNull();
    expect(store.messages()).toEqual([]);
    expect(api.listSessions).toHaveBeenCalled();
  });

  it('archiveSession keeps the active session when a different one is archived', () => {
    api.listMessages.mockReturnValue(of<MessageWithParts[]>([]));
    api.archiveSession.mockReturnValue(of<Session>({ id: 'other', time: { archived: 1 } }));
    api.listSessions.mockReturnValue(of<Session[]>([{ id: 's1' }]));
    store.openSession('s1');

    store.archiveSession('other');

    expect(store.activeSessionId()).toBe('s1');
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
    api.interrupt.mockReturnValue(of<void>(undefined));
    store.openSession('s1');
    store.streaming.set(true);

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
