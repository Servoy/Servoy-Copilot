import { describe, expect, it } from 'vitest';

import { Part } from '../models/opencode.models';
import {
  AgentReportStatus,
  BackgroundResultState,
  ChildOutcome,
  ChildSignals,
  InterruptReason,
  deriveAgentStatus,
  parseBackgroundResult,
  parseReportStatus
} from './agent-status';

type Status = Record<string, unknown>;

function sub(state: Status = {}, tool = 'subagent'): Part {
  return {
    type: 'tool',
    tool,
    id: 'p1',
    state: {
      status: 'completed',
      input: { agent: 'Developer', description: 'Build it' },
      metadata: { sessionID: 'ses_child' },
      ...state
    }
  } as unknown as Part;
}

function result(state: BackgroundResultState, body: string): ChildSignals {
  return { result: { state, body } };
}

describe('parseReportStatus', () => {
  it('reads a plain Status line', () => {
    expect(parseReportStatus('Did it.\nStatus: COMPLETED')).toBe('COMPLETED');
  });

  it.each([
    ['**Status:** INPUT_REQUIRED', 'INPUT_REQUIRED'],
    ['**Status: BLOCKED**', 'BLOCKED'],
    ['- Status: INCOMPLETE', 'INCOMPLETE'],
    ['> Status: FAILED', 'FAILED'],
    ['## Status: REJECTED', 'REJECTED'],
    ['  `Status: COMPLETED`', 'COMPLETED']
  ])('tolerates markdown variant %s', (line, expected) => {
    expect(parseReportStatus(`intro\n${line}`)).toBe(expected);
  });

  it('is case-insensitive for the key and the value', () => {
    expect(parseReportStatus('status: blocked')).toBe('BLOCKED');
    expect(parseReportStatus('STATUS: input_required')).toBe('INPUT_REQUIRED');
  });

  it('the last Status line wins', () => {
    expect(parseReportStatus('Status: COMPLETED\nmore\nStatus: BLOCKED')).toBe('BLOCKED');
  });

  it('skips a trailing unknown Status value and falls back to the earlier known one', () => {
    expect(parseReportStatus('Status: BLOCKED\nStatus: WEIRD')).toBe('BLOCKED');
  });

  it('returns null for an unknown value only', () => {
    expect(parseReportStatus('Status: WEIRD')).toBeNull();
  });

  it('returns null for no Status line', () => {
    expect(parseReportStatus('just prose, the status is fine')).toBeNull();
  });

  it('returns null for empty and undefined', () => {
    expect(parseReportStatus('')).toBeNull();
    expect(parseReportStatus(undefined)).toBeNull();
  });

  it('strips the background-result envelope so attributes are never read', () => {
    const text =
      '<background-result kind="subagent" sessionID="ses_1" state="completed" description="Status: BLOCKED">\nAll fine\n</background-result>';
    expect(parseReportStatus(text)).toBeNull();
  });

  it('strips the legacy subagent envelope lines', () => {
    expect(parseReportStatus('<subagent sessionID="ses_1" state="completed">\nStatus: INPUT_REQUIRED\n</subagent>')).toBe(
      'INPUT_REQUIRED'
    );
  });

  it('ignores a Status line inside the trailing "Background work still running" block', () => {
    const text = 'Done.\nStatus: COMPLETED\n\nBackground work still running:\n- Status: BLOCKED worker';
    expect(parseReportStatus(text)).toBe('COMPLETED');
  });

  it('handles CRLF line endings', () => {
    expect(parseReportStatus('x\r\nStatus: REJECTED\r\n')).toBe('REJECTED');
  });
});

describe('parseBackgroundResult', () => {
  const tag = (attrs: string) => `<background-result ${attrs}>\nbody\n</background-result>`;

  it.each(['completed', 'error', 'cancelled', 'running'] as BackgroundResultState[])('parses state %s', (state) => {
    const r = parseBackgroundResult(tag(`kind="subagent" sessionID="ses_1" state="${state}" description="d"`));
    expect(r).toEqual({ childId: 'ses_1', state, body: 'body' });
  });

  it('returns null for a different kind', () => {
    expect(parseBackgroundResult(tag('kind="shell" sessionID="ses_1" state="completed"'))).toBeNull();
  });

  it('returns null for an unknown state', () => {
    expect(parseBackgroundResult(tag('kind="subagent" sessionID="ses_1" state="weird"'))).toBeNull();
  });

  it('returns null for a missing sessionID', () => {
    expect(parseBackgroundResult(tag('kind="subagent" state="completed"'))).toBeNull();
  });

  it('returns null for a missing state', () => {
    expect(parseBackgroundResult(tag('kind="subagent" sessionID="ses_1"'))).toBeNull();
  });

  it('returns null for a missing kind', () => {
    expect(parseBackgroundResult(tag('sessionID="ses_1" state="completed"'))).toBeNull();
  });

  it('returns null for text that is not a background-result', () => {
    expect(parseBackgroundResult('hello')).toBeNull();
    expect(parseBackgroundResult('')).toBeNull();
    expect(parseBackgroundResult(undefined)).toBeNull();
  });

  it('keeps a ">" inside a quoted description attribute out of the tag end', () => {
    const r = parseBackgroundResult(
      '<background-result kind="subagent" sessionID="ses_1" state="completed" description="a > b then <c>">\nreal body\n</background-result>'
    );
    expect(r).toEqual({ childId: 'ses_1', state: 'completed', body: 'real body' });
  });

  it('takes the body after the real tag end, multi-line, trimmed', () => {
    const r = parseBackgroundResult(
      '  <background-result kind="subagent" sessionID="ses_1" state="completed">\n\nline1\nline2\n\nStatus: COMPLETED\n</background-result>  '
    );
    expect(r?.body).toBe('line1\nline2\n\nStatus: COMPLETED');
  });

  it('parses a body without a closing tag', () => {
    const r = parseBackgroundResult('<background-result kind="subagent" sessionID="ses_1" state="error">boom');
    expect(r).toEqual({ childId: 'ses_1', state: 'error', body: 'boom' });
  });

  it('returns null (and does not hang) for an unterminated quote', () => {
    const text = `<background-result kind="subagent" sessionID="ses_1" state="completed" description="oops${' x'.repeat(5000)}>\nbody`;
    const started = Date.now();
    expect(parseBackgroundResult(text)).toBeNull();
    expect(Date.now() - started).toBeLessThan(1000);
  });

  it('returns null for a tag that never closes', () => {
    expect(parseBackgroundResult('<background-result kind="subagent" sessionID="ses_1" state="completed"')).toBeNull();
  });
});

describe('deriveAgentStatus: rules', () => {
  describe('rule 1: busy child', () => {
    it('is running even when the tool part is completed', () => {
      const s = deriveAgentStatus(sub(), { child: { busy: true } });
      expect(s.status).toBe('running');
      expect(s.label).toBe('Running');
    });

    it('beats a tool error', () => {
      expect(deriveAgentStatus(sub({ status: 'error' }), { child: { busy: true } }).status).toBe('running');
    });

    it('beats an older succeeded outcome and a stale completed result', () => {
      const child: ChildSignals = { busy: true, outcome: 'succeeded', result: { state: 'completed', body: 'old' } };
      expect(deriveAgentStatus(sub(), { child }).status).toBe('running');
    });

    it('beats an older cancelled result, failed outcome and interrupt reason', () => {
      const child: ChildSignals = {
        busy: true,
        outcome: 'failed',
        interruptedReason: 'user',
        result: { state: 'cancelled', body: 'x' }
      };
      expect(deriveAgentStatus(sub(), { child, parentLive: false }).status).toBe('running');
    });

    it('is running without a live parent', () => {
      expect(deriveAgentStatus(sub(), { child: { busy: true }, parentLive: false }).status).toBe('running');
    });

    it('ignores metadata.status "running" on a completed part when the child is not busy', () => {
      const part = sub({ metadata: { sessionID: 'ses_child', status: 'running' } });
      expect(deriveAgentStatus(part, { child: { busy: false, outcome: 'succeeded' } }).status).toBe('done');
      expect(deriveAgentStatus(part, { child: { busy: false } }).status).toBe('stopped');
    });
  });

  describe('rule 3: tool error', () => {
    it('is stopped for an aborted tool error without busy', () => {
      const s = deriveAgentStatus(sub({ status: 'error', error: { type: 'aborted', message: 'Tool execution interrupted' } }));
      expect(s.status).toBe('stopped');
      expect(s.label).toBe('Stopped');
      expect(s.summary).toBe('Tool execution interrupted');
    });

    it('is stopped for any other tool error, even with a live parent', () => {
      const s = deriveAgentStatus(sub({ status: 'error', error: 'Unknown agent' }), { parentLive: true });
      expect(s.status).toBe('stopped');
      expect(s.summary).toBe('Unknown agent');
    });

    it('beats a succeeded outcome', () => {
      expect(deriveAgentStatus(sub({ status: 'error' }), { child: { outcome: 'succeeded' } }).status).toBe('stopped');
    });

    it('falls back to "Failed to start" without error text', () => {
      expect(deriveAgentStatus(sub({ status: 'error' })).summary).toBe('Failed to start');
    });

    it('uses only the first line of a multi-line error', () => {
      const s = deriveAgentStatus(sub({ status: 'error', error: { message: 'first\nsecond' } }));
      expect(s.summary).toBe('first');
    });
  });

  describe('rule 4: ended signals', () => {
    it('result error is stopped with the first body line', () => {
      const s = deriveAgentStatus(sub(), { child: result('error', 'Compile exploded\nstack') });
      expect(s.status).toBe('stopped');
      expect(s.label).toBe('Stopped');
      expect(s.summary).toBe('Compile exploded');
    });

    it('result error with an empty body says "Failed"', () => {
      expect(deriveAgentStatus(sub(), { child: result('error', '') }).summary).toBe('Failed');
    });

    it('result cancelled is stopped with "Cancelled"', () => {
      const s = deriveAgentStatus(sub(), { child: result('cancelled', 'Subagent cancelled') });
      expect(s.status).toBe('stopped');
      expect(s.summary).toBe('Cancelled');
    });

    it('outcome failed is stopped with "Failed"', () => {
      const s = deriveAgentStatus(sub(), { child: { outcome: 'failed' } });
      expect(s.status).toBe('stopped');
      expect(s.summary).toBe('Failed');
    });

    it('outcome interrupted is stopped with "Interrupted"', () => {
      const s = deriveAgentStatus(sub(), { child: { outcome: 'interrupted' } });
      expect(s.status).toBe('stopped');
      expect(s.summary).toBe('Interrupted');
    });

    it.each([
      ['user', 'Stopped by user'],
      ['shutdown', 'Interrupted by restart'],
      ['superseded', 'Replaced by a newer prompt'],
      ['inactivity', 'Stopped after inactivity']
    ] as [InterruptReason, string][])('interrupt reason %s is stopped with its summary', (reason, summary) => {
      const s = deriveAgentStatus(sub(), { child: { interruptedReason: reason } });
      expect(s.status).toBe('stopped');
      expect(s.summary).toBe(summary);
    });

    it('beats an earlier succeeded outcome and completed result', () => {
      const child: ChildSignals = { outcome: 'succeeded', interruptedReason: 'user', result: { state: 'completed', body: 'ok' } };
      expect(deriveAgentStatus(sub(), { child }).status).toBe('stopped');
      const cancelled: ChildSignals = { outcome: 'succeeded', result: { state: 'cancelled', body: '' } };
      expect(deriveAgentStatus(sub(), { child: cancelled }).status).toBe('stopped');
    });

    it('the interrupt reason summary wins over a cancelled result', () => {
      const child: ChildSignals = { interruptedReason: 'user', result: { state: 'cancelled', body: '' } };
      expect(deriveAgentStatus(sub(), { child }).summary).toBe('Stopped by user');
    });

    it('is stopped even with a live parent', () => {
      expect(deriveAgentStatus(sub({ status: 'running' }), { child: { outcome: 'failed' }, parentLive: true }).status).toBe('stopped');
    });
  });

  describe('rule 5: succeeded', () => {
    it('outcome succeeded without a body is done and falls back to the description', () => {
      const s = deriveAgentStatus(sub(), { child: { outcome: 'succeeded' } });
      expect(s.status).toBe('done');
      expect(s.label).toBe('Done');
      expect(s.report).toBeNull();
      expect(s.summary).toBe('Build it');
    });

    it.each(['completed', 'running'] as BackgroundResultState[])('result %s without Status is done', (state) => {
      const s = deriveAgentStatus(sub(), { child: result(state, 'All good\nmore') });
      expect(s.status).toBe('done');
      expect(s.report).toBeNull();
      expect(s.summary).toBe('All good');
    });

    it('COMPLETED is done', () => {
      const s = deriveAgentStatus(sub(), { child: result('completed', 'Status: COMPLETED\nShipped it') });
      expect(s.status).toBe('done');
      expect(s.report).toBe('COMPLETED');
      expect(s.summary).toBe('Shipped it');
    });

    it('an unknown Status value is done with no report', () => {
      const s = deriveAgentStatus(sub(), { child: result('completed', 'Status: WEIRD\nline') });
      expect(s.status).toBe('done');
      expect(s.report).toBeNull();
    });

    it.each(['INPUT_REQUIRED', 'BLOCKED', 'INCOMPLETE', 'FAILED', 'REJECTED'] as AgentReportStatus[])(
      'stop Status %s beats a succeeded outcome',
      (report) => {
        const s = deriveAgentStatus(sub(), {
          child: { outcome: 'succeeded', result: { state: 'completed', body: `Status: ${report}\nReason here` } }
        });
        expect(s.status).toBe('stopped');
        expect(s.report).toBe(report);
        expect(s.summary).toBe('Reason here');
        expect(s.label).toBe(report === 'INPUT_REQUIRED' ? 'Stopped — needs a decision' : 'Stopped');
      }
    );

    it('a stop Status in a result with state running (outstanding work) is also stopped', () => {
      const body = 'Status: BLOCKED\nWaiting on x\n\nBackground work still running:\n- job 1';
      expect(deriveAgentStatus(sub(), { child: result('running', body) }).status).toBe('stopped');
    });

    it('a succeeded outcome is done even with a live parent and a running part', () => {
      expect(deriveAgentStatus(sub({ status: 'running' }), { child: { outcome: 'succeeded' }, parentLive: true }).status).toBe('done');
    });
  });

  describe('rule 6: launching with a live parent', () => {
    it.each(['pending', 'running', 'streaming'])('tool %s with live parent is running', (status) => {
      expect(deriveAgentStatus(sub({ status }), { parentLive: true }).status).toBe('running');
    });

    it('a part without a state status and a live parent is running', () => {
      const part = { type: 'tool', tool: 'subagent', id: 'p', state: { input: {} } } as unknown as Part;
      expect(deriveAgentStatus(part, { parentLive: true }).status).toBe('running');
    });

    it('a part without any state and a live parent is running', () => {
      const part = { type: 'tool', tool: 'subagent', id: 'p' } as unknown as Part;
      expect(deriveAgentStatus(part, { parentLive: true }).status).toBe('running');
    });

    it('completed with no signals and a live parent is running', () => {
      expect(deriveAgentStatus(sub(), { parentLive: true }).status).toBe('running');
    });

    it('completed with an empty child object and a live parent is running', () => {
      expect(deriveAgentStatus(sub(), { child: {}, parentLive: true }).status).toBe('running');
    });

    it('completed with busy known false and a live parent is NOT running', () => {
      expect(deriveAgentStatus(sub(), { child: { busy: false }, parentLive: true }).status).toBe('stopped');
    });

    it('a pending part with busy false and a live parent is still running (launching)', () => {
      expect(deriveAgentStatus(sub({ status: 'pending' }), { child: { busy: false }, parentLive: true }).status).toBe('running');
    });

    it('an unknown tool state with a live parent is stopped', () => {
      expect(deriveAgentStatus(sub({ status: 'weird' }), { parentLive: true }).status).toBe('stopped');
    });
  });

  describe('rule 7: stale', () => {
    it.each(['pending', 'running', 'streaming', 'completed'])('tool %s without a live parent is stopped "Not running"', (status) => {
      const s = deriveAgentStatus(sub({ status }));
      expect(s.status).toBe('stopped');
      expect(s.label).toBe('Stopped');
      expect(s.summary).toBe('Not running');
    });

    it('explicit parentLive false is stopped', () => {
      expect(deriveAgentStatus(sub(), { parentLive: false }).status).toBe('stopped');
    });

    it('an unknown tool state is stopped', () => {
      expect(deriveAgentStatus(sub({ status: 'weird' })).status).toBe('stopped');
    });

    it('works without a context argument', () => {
      expect(() => deriveAgentStatus(sub())).not.toThrow();
    });
  });
});

describe('deriveAgentStatus: legacy subagent envelope', () => {
  const legacy = (state: string, body: string) =>
    sub({ output: `<subagent sessionID="ses_old" state="${state}">\n${body}\n</subagent>`, metadata: undefined });

  it('a completed envelope without Status is done', () => {
    const s = deriveAgentStatus(legacy('completed', 'Reviewed everything'));
    expect(s.status).toBe('done');
    expect(s.summary).toBe('Reviewed everything');
    expect(s.childSessionId).toBe('ses_old');
  });

  it('a completed envelope with a stop Status is stopped', () => {
    const s = deriveAgentStatus(legacy('completed', 'Status: INPUT_REQUIRED\nPick one'));
    expect(s.status).toBe('stopped');
    expect(s.label).toBe('Stopped — needs a decision');
    expect(s.summary).toBe('Pick one');
  });

  it('an envelope without a state attribute defaults to completed', () => {
    const part = sub({ output: '<subagent sessionID="ses_old">\nok\n</subagent>', metadata: undefined });
    expect(deriveAgentStatus(part).status).toBe('done');
  });

  it('an error envelope is stopped', () => {
    expect(deriveAgentStatus(legacy('error', 'Subagent failed')).status).toBe('stopped');
  });

  it('a cancelled envelope is stopped "Cancelled"', () => {
    const s = deriveAgentStatus(legacy('cancelled', 'x'));
    expect(s.status).toBe('stopped');
    expect(s.summary).toBe('Cancelled');
  });

  it('a live store result takes precedence over the legacy envelope', () => {
    const s = deriveAgentStatus(legacy('completed', 'old report'), { child: result('error', 'new failure') });
    expect(s.status).toBe('stopped');
    expect(s.summary).toBe('new failure');
  });
});

describe('deriveAgentStatus: summary', () => {
  const done = (body: string, state: Status = {}) => deriveAgentStatus(sub(state), { child: result('completed', body) });

  it('uses the first content line after the Status line', () => {
    expect(done('Intro line\nStatus: COMPLETED\n\nThe summary line\nmore').summary).toBe('The summary line');
  });

  it('falls back to the first body line when the Status line is last', () => {
    expect(done('First line\nsecond\nStatus: COMPLETED').summary).toBe('First line');
  });

  it('falls back to the first body line when there is no Status line', () => {
    expect(done('\n\nFirst real line\nsecond').summary).toBe('First real line');
  });

  it('does not use the Status line itself when it is the only line', () => {
    expect(done('Status: COMPLETED').summary).toBe('Build it');
  });

  it('strips markdown markers', () => {
    expect(done('## **Heading** with `code`').summary).toBe('Heading with code');
    expect(done('- > item one').summary).toBe('item one');
  });

  it('treats the "no text" body as empty and falls back to the description', () => {
    expect(done('Subagent completed without a text response.').summary).toBe('Build it');
  });

  it('is an empty string when there is neither body nor description', () => {
    const part = sub({ input: { agent: 'Developer' } });
    expect(deriveAgentStatus(part, { child: { outcome: 'succeeded' } }).summary).toBe('');
  });

  it('collapses whitespace', () => {
    expect(done('a   lot \t of    space').summary).toBe('a lot of space');
  });

  it('never contains a newline', () => {
    expect(done('line one\nline two').summary).not.toContain('\n');
    expect(deriveAgentStatus(sub({ status: 'error', error: 'a\nb' })).summary).not.toContain('\n');
  });

  it('skips a "Turn N: LOADED" bootstrap line and a bare Result heading, and uses the first real sentence', () => {
    const body = 'Turn 1: LOADED (servoy-reviewer, servoy-platform)\n\n## Result\nNo errors or warnings. getCompilationErrors was called.\n\nLoaded: none needed';
    expect(done(body).summary).toBe('No errors or warnings. getCompilationErrors was called.');
  });

  it('skips a "Loaded:" line when it is the only thing before the content', () => {
    expect(done('Loaded: servoy-agent-shared\nBuilt the form.').summary).toBe('Built the form.');
  });

  it('keeps a real line that merely starts with the word Result', () => {
    expect(done('Result of the scan: 3 files changed').summary).toBe('Result of the scan: 3 files changed');
  });

  it('falls back to the task description when the body holds only bootstrap lines', () => {
    expect(done('Turn 1: LOADED (x)\nLoaded: none needed').summary).toBe('Build it');
  });

  it('keeps a summary of exactly 240 characters untouched', () => {
    const text = 'a'.repeat(240);
    expect(done(text).summary).toBe(text);
  });

  it('truncates a long single word to 240 characters ending with an ellipsis', () => {
    const s = done('b'.repeat(300)).summary;
    expect(s).toHaveLength(240);
    expect(s.endsWith('…')).toBe(true);
    expect(s.slice(0, 239)).toBe('b'.repeat(239));
  });

  it('cuts at the last whole word where possible', () => {
    const text = Array.from({ length: 80 }, (_, i) => `word${i}`).join(' ');
    const s = done(text).summary;
    expect(s.length).toBeLessThanOrEqual(240);
    expect(s.endsWith('…')).toBe(true);
    const kept = s.slice(0, -1);
    expect(text.startsWith(kept)).toBe(true);
    expect(text.charAt(kept.length)).toBe(' ');
    expect(kept.endsWith(' ')).toBe(false);
  });

  it('running uses the part title first', () => {
    const s = deriveAgentStatus(sub({ status: 'running', title: 'Reading files' }), { parentLive: true });
    expect(s.summary).toBe('Reading files');
  });

  it('running falls back to the description when the title is empty', () => {
    const s = deriveAgentStatus(sub({ status: 'running', title: '   ' }), { child: { busy: true } });
    expect(s.summary).toBe('Build it');
  });

  it('running without title or description is empty', () => {
    const s = deriveAgentStatus(sub({ status: 'running', input: {} }), { child: { busy: true } });
    expect(s.summary).toBe('');
  });

  it('a running status ignores a stale result body for its summary', () => {
    const s = deriveAgentStatus(sub({ title: 'Working' }), { child: { busy: true, result: { state: 'completed', body: 'old text' } } });
    expect(s.summary).toBe('Working');
  });

  it('truncates a long error text as well', () => {
    const s = deriveAgentStatus(sub({ status: 'error', error: 'e'.repeat(400) }));
    expect(s.summary).toHaveLength(240);
    expect(s.summary.endsWith('…')).toBe(true);
  });
});

describe('deriveAgentStatus: name, kind, child id', () => {
  const named = (input: Status) => deriveAgentStatus(sub({ input }));

  it('prefers input.agent', () => {
    expect(named({ agent: 'Developer', subagent_type: 'general', name: 'x' }).name).toBe('Developer');
  });

  it('falls back to subagent_type', () => {
    expect(named({ subagent_type: 'general', name: 'x' }).name).toBe('general');
  });

  it('falls back to name', () => {
    expect(named({ name: 'Reviewer' }).name).toBe('Reviewer');
  });

  it('falls back to "Agent"', () => {
    expect(named({}).name).toBe('Agent');
  });

  it('skips blank names', () => {
    expect(named({ agent: '  ', subagent_type: '', name: 'N' }).name).toBe('N');
  });

  it('is "Agent" when the part has no input at all', () => {
    const part = { type: 'tool', tool: 'subagent', id: 'p', state: { status: 'pending' } } as unknown as Part;
    expect(deriveAgentStatus(part).name).toBe('Agent');
  });

  it('is new without any sessionID or task_id', () => {
    expect(named({ agent: 'A' }).kind).toBe('new');
  });

  it('is continued with input.sessionID', () => {
    expect(named({ agent: 'A', sessionID: 'ses_x' }).kind).toBe('continued');
  });

  it('is continued with input.task_id', () => {
    expect(named({ agent: 'A', task_id: 'task_1' }).kind).toBe('continued');
  });

  it('is new for empty sessionID / task_id strings', () => {
    expect(named({ agent: 'A', sessionID: '', task_id: '  ' }).kind).toBe('new');
  });

  it('metadata.sessionID alone stays new', () => {
    const s = deriveAgentStatus(sub({ input: { agent: 'A' }, metadata: { sessionID: 'ses_meta' } }));
    expect(s.kind).toBe('new');
    expect(s.childSessionId).toBe('ses_meta');
  });

  it('childSessionId resolves from metadata, then input.sessionID, then the legacy marker, else null', () => {
    expect(deriveAgentStatus(sub()).childSessionId).toBe('ses_child');
    expect(deriveAgentStatus(sub({ metadata: undefined, input: { sessionID: 'ses_in' } })).childSessionId).toBe('ses_in');
    expect(
      deriveAgentStatus(sub({ metadata: undefined, output: '<subagent sessionID="ses_out" state="completed">x</subagent>' })).childSessionId
    ).toBe('ses_out');
    expect(deriveAgentStatus(sub({ metadata: undefined })).childSessionId).toBeNull();
  });

  it('also derives for the legacy task tool', () => {
    const s = deriveAgentStatus(sub({ input: { subagent_type: 'general', description: 'd' } }, 'task'), { child: { busy: true } });
    expect(s.status).toBe('running');
    expect(s.name).toBe('general');
  });
});

describe('deriveAgentStatus: never-spinner invariant', () => {
  const tools = [undefined, 'pending', 'running', 'streaming', 'completed', 'error', 'weird'];
  const busies = [true, false, undefined];
  const lives = [true, false, undefined];
  const outcomes: (ChildOutcome | undefined)[] = [undefined, 'succeeded', 'failed', 'interrupted'];
  const reasons: (InterruptReason | undefined)[] = [undefined, 'user', 'shutdown', 'superseded', 'inactivity'];
  const resultStates: (BackgroundResultState | undefined)[] = [undefined, 'completed', 'running', 'error', 'cancelled'];
  const reports: (AgentReportStatus | undefined)[] = [undefined, 'COMPLETED', 'INPUT_REQUIRED', 'BLOCKED'];

  function expectRunning(
    tool: string | undefined,
    busy: boolean | undefined,
    live: boolean | undefined,
    outcome: ChildOutcome | undefined,
    reason: InterruptReason | undefined,
    resultState: BackgroundResultState | undefined
  ): boolean {
    if (busy === true) {
      return true;
    }
    if (tool === 'error') {
      return false;
    }
    if (resultState === 'error' || resultState === 'cancelled' || outcome === 'failed' || outcome === 'interrupted' || reason) {
      return false;
    }
    if (resultState === 'completed' || resultState === 'running' || outcome === 'succeeded') {
      return false;
    }
    const nonTerminal = tool === undefined || tool === 'pending' || tool === 'running' || tool === 'streaming';
    const noSignals = busy === undefined;
    return live === true && (nonTerminal || (tool === 'completed' && noSignals));
  }

  it('is running only when the running rules hold, over every combination', () => {
    let combos = 0;
    for (const tool of tools) {
      for (const busy of busies) {
        for (const live of lives) {
          for (const outcome of outcomes) {
            for (const reason of reasons) {
              for (const resultState of resultStates) {
                for (const report of reports) {
                  if (resultState === undefined && report !== undefined) {
                    continue;
                  }
                  const child: ChildSignals = {
                    busy,
                    outcome,
                    interruptedReason: reason,
                    result: resultState
                      ? { state: resultState, body: report ? `Status: ${report}\nsummary` : 'summary' }
                      : undefined
                  };
                  const part = tool === undefined
                    ? ({ type: 'tool', tool: 'subagent', id: 'p', state: { input: { agent: 'A' } } } as unknown as Part)
                    : sub({ status: tool });
                  const s = deriveAgentStatus(part, { child, parentLive: live });
                  const key = JSON.stringify({ tool, busy, live, outcome, reason, resultState, report });
                  const expected = expectRunning(tool, busy, live, outcome, reason, resultState);
                  expect(s.status === 'running', key).toBe(expected);
                  expect(s.label === 'Running', key).toBe(s.status === 'running');
                  if (s.status !== 'running') {
                    expect(s.label === 'Done' || s.label.startsWith('Stopped'), key).toBe(true);
                  }
                  if (live !== true && busy !== true) {
                    expect(s.status, key).not.toBe('running');
                  }
                  combos++;
                }
              }
            }
          }
        }
      }
    }
    expect(combos).toBeGreaterThan(5000);
  });

  it('a stopped or done result never shows the Running label', () => {
    const stopped = deriveAgentStatus(sub(), { child: { busy: false, outcome: 'failed' }, parentLive: true });
    const done = deriveAgentStatus(sub(), { child: { busy: false, outcome: 'succeeded' }, parentLive: true });
    expect(stopped.label).not.toBe('Running');
    expect(done.label).not.toBe('Running');
  });
});
