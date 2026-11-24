import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { WorkspaceFile } from '../models/opencode.models';
import { FileReaderService, statusFromError } from './file-reader.service';
import { OpencodeApiService } from './opencode-api.service';

const file = (content: string, path = 'a.md'): WorkspaceFile => ({
  path,
  name: path.split('/').pop()!,
  size: content.length,
  modified: 1,
  content
});
const httpError = (status: number) => new HttpErrorResponse({ status, url: 'file' });

describe('FileReaderService', () => {
  let service: FileReaderService;
  let api: { readWorkspaceFile: ReturnType<typeof vi.fn> };
  let calls: Subject<WorkspaceFile>[];

  beforeEach(() => {
    calls = [];
    api = {
      readWorkspaceFile: vi.fn(() => {
        const s = new Subject<WorkspaceFile>();
        calls.push(s);
        return s;
      })
    };
    TestBed.configureTestingModule({ providers: [{ provide: OpencodeApiService, useValue: api }] });
    service = TestBed.inject(FileReaderService);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    document.body.innerHTML = '';
  });

  it('starts closed', () => {
    expect(service.state()).toEqual({ open: false, path: null, status: 'loading', file: null });
  });

  it('open() shows loading immediately and requests the path', () => {
    service.open('handoff/t/spec.md');
    expect(api.readWorkspaceFile).toHaveBeenCalledWith('handoff/t/spec.md');
    expect(service.state()).toEqual({ open: true, path: 'handoff/t/spec.md', status: 'loading', file: null });
  });

  it('becomes ready with the file on success', () => {
    service.open('a.md');
    const f = file('hello');
    calls[0].next(f);
    expect(service.state()).toEqual({ open: true, path: 'a.md', status: 'ready', file: f });
  });

  it.each([
    [404, 'not-found'],
    [403, 'forbidden'],
    [415, 'binary'],
    [413, 'too-large'],
    [0, 'error'],
    [500, 'error'],
    [409, 'error'],
    [400, 'error']
  ])('maps HTTP %i to %s', (code, status) => {
    service.open('a.md');
    calls[0].error(httpError(code));
    expect(service.state()).toEqual({ open: true, path: 'a.md', status, file: null });
  });

  it('maps a non-HTTP error to error', () => {
    service.open('a.md');
    calls[0].error(new Error('boom'));
    expect(service.state().status).toBe('error');
  });

  it('statusFromError treats non-HttpErrorResponse values as error', () => {
    expect(statusFromError(null)).toBe('error');
    expect(statusFromError({ status: 404 })).toBe('error');
    expect(statusFromError(httpError(404))).toBe('not-found');
  });

  it('refetches on every open of the same path (no content cache)', () => {
    service.open('a.md');
    calls[0].next(file('old'));
    service.close();
    service.open('a.md');
    expect(api.readWorkspaceFile).toHaveBeenCalledTimes(2);
    expect(service.state().status).toBe('loading');
    expect(service.state().file).toBeNull();
    calls[1].next(file('new'));
    expect(service.state().file?.content).toBe('new');
  });

  it('refetches when opened again while still open', () => {
    service.open('a.md');
    calls[0].next(file('old'));
    service.open('a.md');
    expect(api.readWorkspaceFile).toHaveBeenCalledTimes(2);
    expect(service.state().status).toBe('loading');
  });

  it('ignores a stale success when a newer open was started', () => {
    service.open('first.md');
    service.open('second.md');
    calls[0].next(file('first', 'first.md'));
    expect(service.state()).toMatchObject({ path: 'second.md', status: 'loading', file: null });
    calls[1].next(file('second', 'second.md'));
    expect(service.state()).toMatchObject({ path: 'second.md', status: 'ready' });
    expect(service.state().file?.content).toBe('second');
  });

  it('unsubscribes the previous request on a new open and on close', () => {
    service.open('first.md');
    expect(calls[0].observed).toBe(true);
    service.open('second.md');
    expect(calls[0].observed).toBe(false);
    expect(calls[1].observed).toBe(true);
    service.close();
    expect(calls[1].observed).toBe(false);
  });

  it('ignores an error from a superseded request', () => {
    service.open('first.md');
    service.open('second.md');
    calls[0].error(httpError(404));
    expect(service.state()).toMatchObject({ path: 'second.md', status: 'loading' });
  });

  it('stays closed when a response arrives after close', () => {
    service.open('a.md');
    service.close();
    calls[0].next(file('late'));
    expect(service.state()).toEqual({ open: false, path: null, status: 'loading', file: null });
  });

  it('retry() refetches the current path and returns to loading', () => {
    service.open('a.md');
    calls[0].error(httpError(500));
    expect(service.state().status).toBe('error');
    service.retry();
    expect(api.readWorkspaceFile).toHaveBeenCalledTimes(2);
    expect(api.readWorkspaceFile).toHaveBeenLastCalledWith('a.md');
    expect(service.state()).toEqual({ open: true, path: 'a.md', status: 'loading', file: null });
    calls[1].next(file('ok'));
    expect(service.state().status).toBe('ready');
  });

  it('retry() does nothing when closed', () => {
    service.retry();
    expect(api.readWorkspaceFile).not.toHaveBeenCalled();
    expect(service.state().open).toBe(false);
  });

  describe('focus return', () => {
    const makeButton = (): HTMLButtonElement => {
      const b = document.createElement('button');
      document.body.appendChild(b);
      return b;
    };

    it('returns focus to the trigger on close', () => {
      const trigger = makeButton();
      const other = makeButton();
      other.focus();
      service.open('a.md', trigger);
      expect(document.activeElement).toBe(other);
      service.close();
      expect(document.activeElement).toBe(trigger);
    });

    it('uses the trigger of the latest open', () => {
      const t1 = makeButton();
      const t2 = makeButton();
      service.open('a.md', t1);
      service.open('b.md', t2);
      service.close();
      expect(document.activeElement).toBe(t2);
    });

    it('falls back to document.body when the trigger left the DOM', () => {
      const trigger = makeButton();
      const bodyFocus = vi.spyOn(document.body, 'focus');
      service.open('a.md', trigger);
      trigger.remove();
      service.close();
      expect(bodyFocus).toHaveBeenCalledTimes(1);
    });

    it('falls back to document.body when no trigger was given', () => {
      const bodyFocus = vi.spyOn(document.body, 'focus');
      service.open('a.md');
      service.close();
      expect(bodyFocus).toHaveBeenCalledTimes(1);
    });

    it('does not focus the old trigger again on a second close', () => {
      const trigger = makeButton();
      service.open('a.md', trigger);
      service.close();
      const spy = vi.spyOn(trigger, 'focus');
      service.close();
      expect(spy).not.toHaveBeenCalled();
    });

    it('keeps the trigger across retry', () => {
      const trigger = makeButton();
      service.open('a.md', trigger);
      calls[0].error(httpError(500));
      service.retry();
      service.close();
      expect(document.activeElement).toBe(trigger);
    });
  });
});
