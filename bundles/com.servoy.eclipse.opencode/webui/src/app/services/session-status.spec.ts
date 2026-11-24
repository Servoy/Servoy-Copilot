import { describe, expect, it } from 'vitest';

import type { Session } from '../models/opencode.models';
import { ChildSignals, deriveChildStatus } from './agent-status';
import type { SessionNode } from './chat-store.service';
import { agentName, agentRowStatus, sessionStatus } from './session-status';

const node = (id: string, childIds: string[] = []): SessionNode => ({
  session: { id } as Session,
  children: childIds.map((c) => ({ id: c, parentID: id }) as Session)
});

const set = (...ids: string[]): ReadonlySet<string> => new Set(ids);

describe('sessionStatus', () => {
  it('prefers needs-answer over running', () => {
    expect(sessionStatus(node('s'), set('s'), set('s'))).toBe('needs-answer');
  });

  it('is running when only busy', () => {
    expect(sessionStatus(node('s'), set('s'), set())).toBe('running');
  });

  it('is idle when neither busy nor pending', () => {
    expect(sessionStatus(node('s'), set('other'), set('other'))).toBe('idle');
  });

  it('is running when only a child is busy', () => {
    expect(sessionStatus(node('s', ['c']), set('c'), set())).toBe('running');
  });

  it('is needs-answer when a child has a pending form', () => {
    expect(sessionStatus(node('s', ['c']), set(), set('c'))).toBe('needs-answer');
  });
});

describe('agentRowStatus', () => {
  it('returns null for undefined signals', () => {
    expect(agentRowStatus(undefined)).toBeNull();
  });

  it('returns null for unseeded signals without information', () => {
    expect(agentRowStatus({})).toBeNull();
    expect(agentRowStatus({ busy: undefined, outcome: undefined })).toBeNull();
  });

  it.each<[string, ChildSignals, string]>([
    ['busy', { busy: true }, 'running'],
    ['succeeded', { busy: false, outcome: 'succeeded' }, 'done'],
    [
      'input required report',
      { busy: false, outcome: 'succeeded', result: { state: 'completed', body: 'Status: INPUT_REQUIRED\nneed a choice' } },
      'stopped'
    ],
    ['failed', { outcome: 'failed' }, 'stopped'],
    ['interrupted', { outcome: 'interrupted' }, 'stopped']
  ])('maps %s to %s and equals deriveChildStatus', (_name, signals, expected) => {
    expect(agentRowStatus(signals)).toBe(expected);
    expect(agentRowStatus(signals)).toBe(deriveChildStatus(signals).status);
  });
});

describe('agentName', () => {
  it('uses the agent field', () => {
    expect(agentName({ id: 'c', title: 'T', agent: ' reviewer ' } as unknown as Session)).toBe('reviewer');
  });

  it('falls back to the trimmed title', () => {
    expect(agentName({ id: 'c', title: '  My title ', agent: '  ' } as unknown as Session)).toBe('My title');
  });

  it('falls back to Agent', () => {
    expect(agentName({ id: 'c', title: '   ' } as Session)).toBe('Agent');
    expect(agentName({ id: 'c' } as Session)).toBe('Agent');
  });
});
