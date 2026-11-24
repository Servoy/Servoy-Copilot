import { computed } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Part } from '../models/opencode.models';
import { ChildSignals } from './agent-status';
import { FileRefsService } from './file-refs.service';
import { LoadedMessagesService } from './loaded-messages.service';
import { OpencodeApiService } from './opencode-api.service';

const write = (filePath: string): Part =>
  ({ type: 'tool', tool: 'write', state: { status: 'completed', input: { filePath } } }) as unknown as Part;
const text = (t: string): Part => ({ type: 'text', text: t }) as Part;
const subagent = (childId: string): Part =>
  ({
    type: 'tool',
    tool: 'subagent',
    state: { status: 'completed', input: { agent: 'x' }, metadata: { sessionID: childId } }
  }) as unknown as Part;
const signals = (entries: Record<string, unknown> = {}) =>
  new Map(Object.entries(entries)) as unknown as ReadonlyMap<string, ChildSignals>;
const paths = (refs: { path: string }[]) => refs.map((r) => r.path);

describe('FileRefsService', () => {
  let service: FileRefsService;
  let loaded: LoadedMessagesService;
  let api: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(() => {
    api = new Proxy({} as Record<string, ReturnType<typeof vi.fn>>, {
      get: (target, name: string) => (target[name] ??= vi.fn())
    });
    TestBed.configureTestingModule({ providers: [{ provide: OpencodeApiService, useValue: api }] });
    service = TestBed.inject(FileRefsService);
    loaded = TestBed.inject(LoadedMessagesService);
  });

  it('returns the own tool and text refs of the parts', () => {
    const refs = service.refsForParts([write('src/a.js'), text('see handoff/t/spec.md')], signals());
    expect(paths(refs)).toEqual(['src/a.js', 'handoff/t/spec.md']);
  });

  it('is empty without refs', () => {
    expect(service.refsForParts([text('nothing here')], signals())).toEqual([]);
  });

  it('adds refs from already loaded child messages of a subagent part', () => {
    loaded.record('c1', [{ parts: [write('child/out.js'), text('handoff/t/review.md')] }]);
    const refs = service.refsForParts([write('own.js'), subagent('c1')], signals());
    expect(paths(refs)).toEqual(['own.js', 'child/out.js', 'handoff/t/review.md']);
  });

  it('does not include loaded sessions that no subagent part points to', () => {
    loaded.record('other', [{ parts: [write('other.js')] }]);
    expect(service.refsForParts([subagent('c1')], signals())).toEqual([]);
  });

  it('does not fetch anything when the child is not loaded', () => {
    const refs = service.refsForParts([subagent('c1')], signals());
    expect(refs).toEqual([]);
    expect(Object.values(api).every((fn) => fn.mock.calls.length === 0)).toBe(true);
  });

  it('adds refs from the child result body of the child signals', () => {
    const refs = service.refsForParts(
      [subagent('c1')],
      signals({ c1: { result: { body: 'Wrote handoff/t/spec.md for you' } } })
    );
    expect(paths(refs)).toEqual(['handoff/t/spec.md']);
  });

  it('ignores a subagent part without a resolvable child id', () => {
    const noId = { type: 'tool', tool: 'subagent', state: { status: 'running', input: {} } } as unknown as Part;
    loaded.record('c1', [{ parts: [write('x.js')] }]);
    expect(service.refsForParts([noId], signals())).toEqual([]);
  });

  it('dedupes across own parts, child messages and result body', () => {
    loaded.record('c1', [{ parts: [write('A.js')] }]);
    const refs = service.refsForParts(
      [write('a.js'), subagent('c1')],
      signals({ c1: { result: { body: 'handoff/t/spec.md handoff/T/spec.md' } } })
    );
    expect(paths(refs)).toEqual(['a.js', 'handoff/t/spec.md']);
  });

  it('is reactive: a computed over refsForParts updates when the child messages are loaded or forgotten, without NG0600', () => {
    const parts = [subagent('c1')];
    const refs = TestBed.runInInjectionContext(() => computed(() => service.refsForParts(parts, signals())));
    expect(refs()).toEqual([]);
    expect(() => loaded.record('c1', [{ parts: [write('late.js')] }])).not.toThrow();
    expect(paths(refs())).toEqual(['late.js']);
    loaded.forget('c1');
    expect(refs()).toEqual([]);
  });
});
