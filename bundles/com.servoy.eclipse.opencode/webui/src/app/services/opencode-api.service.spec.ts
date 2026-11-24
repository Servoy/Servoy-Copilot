import { HttpErrorResponse } from '@angular/common/http';
import { provideHttpClient, withFetch } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, afterEach, describe, expect, it } from 'vitest';

import { OpencodeApiService } from './opencode-api.service';
import { CommandInfo, FormInfo, MessageWithParts, SendPart, Session } from '../models/opencode.models';

/**
 * Tests for the V2 ({@code @opencode/cli} 2.x) API wrappers: the front-end keeps
 * calling the stable {@code rest_api/**} paths (the BFF rewrites them to
 * {@code /api/**}), so these still assert {@code rest_api/session} etc. What
 * changed for V2 is response unwrapping ({@code { data }} envelopes), the
 * prompt/interrupt shapes, and the file endpoints.
 */
describe('OpencodeApiService', () => {
  let service: OpencodeApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        OpencodeApiService,
        provideHttpClient(withFetch()),
        provideHttpClientTesting()
      ]
    });
    service = TestBed.inject(OpencodeApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('listSessions GETs rest_api/session and unwraps the { data } envelope', () => {
    const sessions: Session[] = [{ id: 's1', title: 'One' }];
    let result: Session[] | undefined;
    service.listSessions().subscribe((r) => (result = r));

    const req = httpMock.expectOne('rest_api/session');
    expect(req.request.method).toBe('GET');
    req.flush({ data: sessions });

    expect(result).toEqual(sessions);
  });

  it('listSessions tolerates a missing data field (returns [])', () => {
    let result: Session[] | undefined;
    service.listSessions().subscribe((r) => (result = r));
    const req = httpMock.expectOne('rest_api/session');
    req.flush({});
    expect(result).toEqual([]);
  });

  it('getActiveSessions GETs rest_api/session/active and returns the ids of the data keys', () => {
    let result: ReadonlySet<string> | undefined;
    service.getActiveSessions().subscribe((r) => (result = r));

    const req = httpMock.expectOne('rest_api/session/active');
    expect(req.request.method).toBe('GET');
    req.flush({ data: { ses_a: { type: 'running' }, ses_b: { type: 'running' } } });

    expect(result).toBeInstanceOf(Set);
    expect([...result!].sort()).toEqual(['ses_a', 'ses_b']);
  });

  it('getActiveSessions returns an empty set for a missing or empty data field', () => {
    const results: ReadonlySet<string>[] = [];
    service.getActiveSessions().subscribe((r) => results.push(r));
    httpMock.expectOne('rest_api/session/active').flush({});
    service.getActiveSessions().subscribe((r) => results.push(r));
    httpMock.expectOne('rest_api/session/active').flush({ data: {} });
    expect(results.map((r) => r.size)).toEqual([0, 0]);
  });

  it('getActiveSessions maps an HTTP error to an empty set instead of failing', () => {
    let result: ReadonlySet<string> | undefined;
    let failed = false;
    service.getActiveSessions().subscribe({ next: (r) => (result = r), error: () => (failed = true) });
    httpMock.expectOne('rest_api/session/active').flush('boom', { status: 500, statusText: 'Server Error' });
    expect(failed).toBe(false);
    expect(result?.size).toBe(0);
  });

  it('createSession POSTs an empty body when no title is given and unwraps data', () => {
    let created: Session | undefined;
    service.createSession().subscribe((s) => (created = s));
    const req = httpMock.expectOne('rest_api/session');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush({ data: { id: 's1' } });
    expect(created?.id).toBe('s1');
  });

  it('createSession POSTs the title when provided', () => {
    let created: Session | undefined;
    service.createSession('My chat').subscribe((s) => (created = s));
    const req = httpMock.expectOne('rest_api/session');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ title: 'My chat' });
    req.flush({ data: { id: 's2', title: 'My chat' } });
    expect(created?.id).toBe('s2');
  });

  it('getSession GETs a URL-encoded session id and unwraps data', () => {
    let got: Session | undefined;
    service.getSession('a/b c').subscribe((s) => (got = s));
    const req = httpMock.expectOne('rest_api/session/a%2Fb%20c');
    expect(req.request.method).toBe('GET');
    req.flush({ data: { id: 'a/b c' } });
    expect(got?.id).toBe('a/b c');
  });

  it('updateSessionTitle PATCHes the title (V2 returns 204)', () => {
    service.updateSessionTitle('s1', 'New title').subscribe();
    const req = httpMock.expectOne('rest_api/session/s1');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ title: 'New title' });
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('deleteSession DELETEs the session (V2 returns 204)', () => {
    service.deleteSession('s1').subscribe();
    const req = httpMock.expectOne('rest_api/session/s1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('listMessages GETs the message endpoint and maps + reverses V2 messages', () => {
    // V2 returns newest-first inside { data }; each item is a flat message.
    const v2Response = {
      data: [
        { id: 'm2', type: 'assistant', content: [{ type: 'text', text: 'hi' }] },
        { id: 'm1', type: 'user', text: 'hello' }
      ]
    };
    let result: MessageWithParts[] | undefined;
    service.listMessages('s1').subscribe((r) => (result = r));

    const req = httpMock.expectOne((r) => r.url === 'rest_api/session/s1/message');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('limit')).toBe(false);
    req.flush(v2Response);

    // Reversed to chronological order: user m1 first, assistant m2 second.
    expect(result?.map((m) => m.info.id)).toEqual(['m1', 'm2']);
    expect(result?.[0].info.role).toBe('user');
    expect(result?.[0].parts[0]).toMatchObject({ type: 'text', text: 'hello' });
    expect(result?.[1].info.role).toBe('assistant');
    expect(result?.[1].parts[0]).toMatchObject({ type: 'text', text: 'hi' });
  });

  it('listMessages turns a user message attachment (files[]) into a renderable image file part', () => {
    const v2Response = {
      data: [
        {
          id: 'm1',
          type: 'user',
          text: 'wat zie je?',
          files: [{ data: 'AAAABBBB', mime: 'image/png', name: 'image.png', source: { type: 'inline' } }]
        }
      ]
    };
    let result: MessageWithParts[] | undefined;
    service.listMessages('s1').subscribe((r) => (result = r));
    const req = httpMock.expectOne((r) => r.url === 'rest_api/session/s1/message');
    req.flush(v2Response);

    const parts = result?.[0].parts ?? [];
    // The prompt text plus the attachment, as a data: URL image file part.
    expect(parts[0]).toMatchObject({ type: 'text', text: 'wat zie je?' });
    expect(parts[1]).toMatchObject({
      type: 'file',
      mime: 'image/png',
      filename: 'image.png',
      url: 'data:image/png;base64,AAAABBBB'
    });
  });

  it('listMessages includes the limit query param when supplied', () => {
    service.listMessages('s1', 25).subscribe();
    const req = httpMock.expectOne(
      (r) => r.url === 'rest_api/session/s1/message' && r.params.get('limit') === '25'
    );
    expect(req.request.method).toBe('GET');
    req.flush({ data: [] });
  });

  it('listMessagePage surfaces the server cursor.next as olderCursor', () => {
    let page: { messages: MessageWithParts[]; olderCursor: string | null } | undefined;
    service.listMessagePage('s1').subscribe((p) => (page = p));
    const req = httpMock.expectOne((r) => r.url === 'rest_api/session/s1/message');
    req.flush({
      data: [{ id: 'm2', type: 'assistant', content: [{ type: 'text', text: 'b' }] }, { id: 'm1', type: 'user', text: 'a' }],
      cursor: { next: 'OLDER', previous: 'NEWER' }
    });
    // Reversed to chronological, and the older-cursor is cursor.next.
    expect(page?.messages.map((m) => m.info.id)).toEqual(['m1', 'm2']);
    expect(page?.olderCursor).toBe('OLDER');
  });

  it('listMessagePage sends the cursor param (and no limit) for an older page', () => {
    service.listMessagePage('s1', { cursor: 'CUR' }).subscribe();
    const req = httpMock.expectOne(
      (r) => r.url === 'rest_api/session/s1/message' && r.params.get('cursor') === 'CUR'
    );
    expect(req.request.params.has('limit')).toBe(false);
    req.flush({ data: [], cursor: {} });
  });

  it('listMessagePage reports a null olderCursor at the start of the conversation', () => {
    let page: { olderCursor: string | null } | undefined;
    service.listMessagePage('s1', { cursor: 'CUR' }).subscribe((p) => (page = p));
    const req = httpMock.expectOne((r) => r.url === 'rest_api/session/s1/message');
    req.flush({ data: [], cursor: { next: null } });
    expect(page?.olderCursor).toBeNull();
  });

  it('listAllMessages follows the older cursor across pages and returns the whole history chronologically', () => {
    let all: MessageWithParts[] | undefined;
    service.listAllMessages('s1').subscribe((r) => (all = r));

    // Page 1: newest page, has an older cursor.
    const p1 = httpMock.expectOne((r) => r.url === 'rest_api/session/s1/message' && !r.params.has('cursor'));
    p1.flush({
      data: [{ id: 'm4', type: 'assistant', content: [] }, { id: 'm3', type: 'user', text: 'c' }],
      cursor: { next: 'CUR1' }
    });

    // Page 2 (older): fetched with CUR1, has a further older cursor.
    const p2 = httpMock.expectOne((r) => r.url === 'rest_api/session/s1/message' && r.params.get('cursor') === 'CUR1');
    p2.flush({
      data: [{ id: 'm2', type: 'assistant', content: [] }, { id: 'm1', type: 'user', text: 'a' }],
      cursor: { next: 'CUR2' }
    });

    // Page 3 (oldest): no further cursor -> paging stops.
    const p3 = httpMock.expectOne((r) => r.url === 'rest_api/session/s1/message' && r.params.get('cursor') === 'CUR2');
    p3.flush({ data: [{ id: 'm0', type: 'user', text: 'start' }], cursor: { next: null } });

    // Full history, oldest first.
    expect(all?.map((m) => m.info.id)).toEqual(['m0', 'm1', 'm2', 'm3', 'm4']);
  });

  it('sendPrompt POSTs a { text } body to the V2 prompt endpoint', () => {
    const parts: SendPart[] = [
      { type: 'text', text: 'hello' },
      { type: 'text', text: 'world' }
    ];
    service.sendPrompt('s1', parts).subscribe();
    const req = httpMock.expectOne('rest_api/session/s1/prompt');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ text: 'hello\nworld' });
    req.flush(null);
  });

  it('sendPrompt sends file attachments as PromptInput.FileAttachment { uri, name }', () => {
    const parts: SendPart[] = [
      { type: 'text', text: 'look' },
      { type: 'file', filename: 'a.txt', mime: 'text/plain', url: 'data:...' }
    ];
    service.sendPrompt('s1', parts).subscribe();
    const req = httpMock.expectOne('rest_api/session/s1/prompt');
    // opencode 2.0.26 takes the content from `uri` and detects the mime itself;
    // the filename maps to `name`. There is no `url`/`filename`/`mime` field.
    expect(req.request.body).toEqual({
      text: 'look',
      files: [{ uri: 'data:...', name: 'a.txt' }]
    });
    req.flush(null);
  });

  it('sendPrompt omits name when the attachment has no filename', () => {
    const parts: SendPart[] = [{ type: 'file', mime: 'image/png', url: 'data:image/png;base64,AAA' }];
    service.sendPrompt('s1', parts).subscribe();
    const req = httpMock.expectOne('rest_api/session/s1/prompt');
    expect(req.request.body).toEqual({
      text: '',
      files: [{ uri: 'data:image/png;base64,AAA' }]
    });
    req.flush(null);
  });

  it('interrupt POSTs an empty body to the V2 interrupt endpoint', () => {
    service.interrupt('s1').subscribe();
    const req = httpMock.expectOne('rest_api/session/s1/interrupt');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush(null);
  });

  it('readWorkspaceFile GETs file?path= with the path encoded', () => {
    const file = { path: 'a b/c+d.md', name: 'c+d.md', size: 1, modified: 2, content: 'x' };
    let result: unknown;
    service.readWorkspaceFile('a b/c+d.md').subscribe((r) => (result = r));

    const req = httpMock.expectOne('file?path=a%20b%2Fc%2Bd.md');
    expect(req.request.method).toBe('GET');
    req.flush(file);

    expect(result).toEqual(file);
  });

  it.each([
    ['plus', 'a+b.md', 'a%2Bb.md'],
    ['space', 'a b.md', 'a%20b.md'],
    ['hash', 'a#b.md', 'a%23b.md'],
    ['ampersand', 'a&b=c.md', 'a%26b%3Dc.md'],
    ['percent', '100%.md', '100%25.md'],
    ['backslash', 'a\\b.md', 'a%5Cb.md'],
    ['non-ASCII', 'dossier/é☃.md', 'dossier%2F%C3%A9%E2%98%83.md']
  ])('readWorkspaceFile encodes %s in the query with encodeURIComponent', (_label, path, encoded) => {
    service.readWorkspaceFile(path).subscribe();
    const req = httpMock.expectOne(`file?path=${encoded}`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys()).toEqual([]);
    req.flush({});
  });

  it('readWorkspaceFile propagates the HTTP status so the reader can map it', () => {
    let error: HttpErrorResponse | undefined;
    service.readWorkspaceFile('a.md').subscribe({ error: (e: HttpErrorResponse) => (error = e) });
    httpMock.expectOne('file?path=a.md').flush({ error: 'forbidden' }, { status: 403, statusText: 'Forbidden' });
    expect(error?.status).toBe(403);
  });

  it('no longer exposes the unsafe proxy file helpers readFile/findFiles', () => {
    const api = service as unknown as Record<string, unknown>;
    expect(api['readFile']).toBeUndefined();
    expect(api['findFiles']).toBeUndefined();
  });

  it('listPendingForms GETs the form endpoint and unwraps the { data } envelope', () => {
    const forms: FormInfo[] = [
      { id: 'frm_1', sessionID: 's1', title: 'Q', fields: [{ key: 'q0', type: 'string' }] }
    ];
    let result: FormInfo[] | undefined;
    service.listPendingForms('s1').subscribe((r) => (result = r));

    const req = httpMock.expectOne('rest_api/session/s1/form');
    expect(req.request.method).toBe('GET');
    req.flush({ data: forms });

    expect(result).toEqual(forms);
  });

  it('listPendingForms tolerates a missing data field (returns [])', () => {
    let result: FormInfo[] | undefined;
    service.listPendingForms('s1').subscribe((r) => (result = r));
    const req = httpMock.expectOne('rest_api/session/s1/form');
    req.flush({});
    expect(result).toEqual([]);
  });

  it('replyToForm POSTs { answer } to the reply endpoint (V2 returns 204)', () => {
    service.replyToForm('s1', 'frm_1', { q0: 'Banana' }).subscribe();
    const req = httpMock.expectOne('rest_api/session/s1/form/frm_1/reply');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ answer: { q0: 'Banana' } });
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('replyToForm URL-encodes the session and form ids', () => {
    service.replyToForm('a/b', 'f/c', { q0: 'x' }).subscribe();
    const req = httpMock.expectOne('rest_api/session/a%2Fb/form/f%2Fc/reply');
    expect(req.request.method).toBe('POST');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('cancelForm DELETEs the form endpoint (V2 returns 204)', () => {
    service.cancelForm('s1', 'frm_1').subscribe();
    const req = httpMock.expectOne('rest_api/session/s1/form/frm_1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('propagates a 503 error to the subscriber (upstream restarting)', () => {
    let error: HttpErrorResponse | undefined;
    service.listSessions().subscribe({
      next: () => {
        throw new Error('should not succeed');
      },
      error: (e: HttpErrorResponse) => (error = e)
    });

    const req = httpMock.expectOne('rest_api/session');
    req.flush('restarting', { status: 503, statusText: 'Service Unavailable' });

    expect(error?.status).toBe(503);
  });

  describe('listCommands', () => {
    function run(body: object | null): CommandInfo[] {
      let result: CommandInfo[] | undefined;
      service.listCommands().subscribe((r) => (result = r));
      const req = httpMock.expectOne('rest_api/command');
      expect(req.request.method).toBe('GET');
      req.flush(body);
      return result as CommandInfo[];
    }

    it('unwraps the { data } envelope', () => {
      const data = [{ name: 'svyFixTicket', description: 'Fix' }];
      expect(run({ data })).toEqual(data);
    });

    it('unwraps the { location, data } envelope', () => {
      const data = [{ name: 'svyImplement', description: 'Impl' }];
      expect(run({ location: { directory: 'x' }, data })).toEqual(data);
    });

    it('accepts a bare array', () => {
      const data = [{ name: 'svyOptimize' }];
      expect(run(data)).toEqual(data);
    });

    it('returns [] for {}, null and non-array data', () => {
      expect(run({})).toEqual([]);
      expect(run(null)).toEqual([]);
      expect(run({ data: 'nope' })).toEqual([]);
      expect(run({ data: { name: 'svyX' } })).toEqual([]);
    });

    it('drops entries without a string name', () => {
      const result = run({
        data: [{ name: 'svyA' }, { description: 'no name' }, { name: 5 }, null, { name: 'svyB' }]
      });
      expect(result.map((c) => c.name)).toEqual(['svyA', 'svyB']);
    });

    it('propagates HTTP errors', () => {
      let error: HttpErrorResponse | undefined;
      service.listCommands().subscribe({ error: (e: HttpErrorResponse) => (error = e) });
      httpMock.expectOne('rest_api/command').flush('x', { status: 503, statusText: 'Unavailable' });
      expect(error?.status).toBe(503);
    });
  });
});
