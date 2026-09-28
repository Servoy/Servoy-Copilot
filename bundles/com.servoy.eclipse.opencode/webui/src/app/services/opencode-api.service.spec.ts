import { HttpErrorResponse } from '@angular/common/http';
import { provideHttpClient, withFetch } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, afterEach, describe, expect, it } from 'vitest';

import { OpencodeApiService } from './opencode-api.service';
import { FileMatch, MessageWithParts, SendPart, Session } from '../models/opencode.models';

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

  it('archiveSession PATCHes a time.archived timestamp', () => {
    service.archiveSession('s1', 123).subscribe();
    const req = httpMock.expectOne('rest_api/session/s1');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ time: { archived: 123 } });
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

  it('listMessages includes the limit query param when supplied', () => {
    service.listMessages('s1', 25).subscribe();
    const req = httpMock.expectOne(
      (r) => r.url === 'rest_api/session/s1/message' && r.params.get('limit') === '25'
    );
    expect(req.request.method).toBe('GET');
    req.flush({ data: [] });
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

  it('sendPrompt includes file attachments when present', () => {
    const parts: SendPart[] = [
      { type: 'text', text: 'look' },
      { type: 'file', filename: 'a.txt', mime: 'text/plain', url: 'data:...' }
    ];
    service.sendPrompt('s1', parts).subscribe();
    const req = httpMock.expectOne('rest_api/session/s1/prompt');
    expect(req.request.body).toEqual({
      text: 'look',
      files: [{ filename: 'a.txt', mime: 'text/plain', url: 'data:...' }]
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

  it('findFiles GETs fs/find with the query param and unwraps data', () => {
    const matches: FileMatch[] = [{ path: 'a/b.ts', type: 'file' }];
    let result: FileMatch[] | undefined;
    service.findFiles('foo').subscribe((r) => (result = r));

    const req = httpMock.expectOne(
      (r) => r.url === 'rest_api/fs/find' && r.params.get('query') === 'foo'
    );
    expect(req.request.method).toBe('GET');
    req.flush({ location: { directory: '/proj' }, data: matches });

    expect(result).toEqual(matches);
  });

  it('readFile GETs fs/read/<path> as text with the path in the URL', () => {
    let content: string | undefined;
    service.readFile('src/a.ts').subscribe((c) => (content = c));

    const req = httpMock.expectOne((r) => r.url === 'rest_api/fs/read/src/a.ts');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('text');
    req.flush('file body');

    expect(content).toBe('file body');
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
});
