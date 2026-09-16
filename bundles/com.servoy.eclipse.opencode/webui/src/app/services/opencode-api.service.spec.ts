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

  it('listSessions GETs rest_api/session and returns the parsed body', () => {
    const sessions: Session[] = [{ id: 's1', title: 'One' }];
    let result: Session[] | undefined;
    service.listSessions().subscribe((r) => (result = r));

    const req = httpMock.expectOne('rest_api/session');
    expect(req.request.method).toBe('GET');
    req.flush(sessions);

    expect(result).toEqual(sessions);
  });

  it('createSession POSTs an empty body when no title is given', () => {
    service.createSession().subscribe();
    const req = httpMock.expectOne('rest_api/session');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush({ id: 's1' });
  });

  it('createSession POSTs the title when provided', () => {
    let created: Session | undefined;
    service.createSession('My chat').subscribe((s) => (created = s));
    const req = httpMock.expectOne('rest_api/session');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ title: 'My chat' });
    req.flush({ id: 's2', title: 'My chat' });
    expect(created?.id).toBe('s2');
  });

  it('getSession GETs a URL-encoded session id', () => {
    service.getSession('a/b c').subscribe();
    const req = httpMock.expectOne('rest_api/session/a%2Fb%20c');
    expect(req.request.method).toBe('GET');
    req.flush({ id: 'a/b c' });
  });

  it('updateSessionTitle PATCHes the title', () => {
    let updated: Session | undefined;
    service.updateSessionTitle('s1', 'New title').subscribe((s) => (updated = s));
    const req = httpMock.expectOne('rest_api/session/s1');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ title: 'New title' });
    req.flush({ id: 's1', title: 'New title' });
    expect(updated?.title).toBe('New title');
  });

  it('archiveSession PATCHes a time.archived timestamp', () => {
    service.archiveSession('s1', 123).subscribe();
    const req = httpMock.expectOne('rest_api/session/s1');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ time: { archived: 123 } });
    req.flush({ id: 's1', time: { archived: 123 } });
  });

  it('deleteSession DELETEs the session', () => {
    let ok: boolean | undefined;
    service.deleteSession('s1').subscribe((r) => (ok = r));
    const req = httpMock.expectOne('rest_api/session/s1');
    expect(req.request.method).toBe('DELETE');
    req.flush(true);
    expect(ok).toBe(true);
  });

  it('listMessages GETs the message endpoint with no limit param by default', () => {
    const msgs: MessageWithParts[] = [
      { info: { id: 'm1', role: 'assistant' }, parts: [] }
    ];
    let result: MessageWithParts[] | undefined;
    service.listMessages('s1').subscribe((r) => (result = r));

    const req = httpMock.expectOne((r) => r.url === 'rest_api/session/s1/message');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('limit')).toBe(false);
    req.flush(msgs);

    expect(result).toEqual(msgs);
  });

  it('listMessages includes the limit query param when supplied', () => {
    service.listMessages('s1', 25).subscribe();
    const req = httpMock.expectOne(
      (r) => r.url === 'rest_api/session/s1/message' && r.params.get('limit') === '25'
    );
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('sendPromptAsync POSTs the parts to prompt_async', () => {
    const parts: SendPart[] = [{ type: 'text', text: 'hello' }];
    service.sendPromptAsync('s1', parts).subscribe();
    const req = httpMock.expectOne('rest_api/session/s1/prompt_async');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ parts });
    req.flush(null);
  });

  it('abort POSTs an empty body to the abort endpoint', () => {
    service.abort('s1').subscribe();
    const req = httpMock.expectOne('rest_api/session/s1/abort');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush(null);
  });

  it('findFiles GETs find/file with the query param', () => {
    const matches: FileMatch[] = [{ path: 'a/b.ts' }];
    let result: FileMatch[] | undefined;
    service.findFiles('foo').subscribe((r) => (result = r));

    const req = httpMock.expectOne(
      (r) => r.url === 'rest_api/find/file' && r.params.get('query') === 'foo'
    );
    expect(req.request.method).toBe('GET');
    req.flush(matches);

    expect(result).toEqual(matches);
  });

  it('readFile GETs file/content as text with the path param', () => {
    let content: string | undefined;
    service.readFile('src/a.ts').subscribe((c) => (content = c));

    const req = httpMock.expectOne(
      (r) => r.url === 'rest_api/file/content' && r.params.get('path') === 'src/a.ts'
    );
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
