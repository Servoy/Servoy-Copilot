import { describe, expect, it } from 'vitest';

import { Part, Session } from '../models/opencode.models';
import { ChildSignals } from './agent-status';
import { buildAgentView } from './agent-view';
import { ChatMessage } from './chat-store.service';

const msg = (id: string, role: string, parts: Part[], info: Record<string, unknown> = {}): ChatMessage =>
  ({ info: { id, role, ...info }, parts }) as unknown as ChatMessage;
const text = (t: string, extra: Record<string, unknown> = {}): Part => ({ type: 'text', text: t, ...extra }) as Part;
const tool = (status: string, name = 'read'): Part =>
  ({ type: 'tool', tool: name, state: { status } }) as unknown as Part;
const user = (id: string, t: string) => msg(id, 'user', [text(t)]);

function deepFreeze<T>(value: T): T {
  if (value && typeof value === 'object' && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const v of Object.values(value as Record<string, unknown>)) {
      deepFreeze(v);
    }
  }
  return value;
}

describe('buildAgentView', () => {
  describe('task and rounds', () => {
    it('uses the first non-synthetic user message as the task', () => {
      const view = buildAgentView([user('u1', 'Do the thing'), msg('a1', 'assistant', [text('ok')])], undefined, undefined);
      expect(view.task).toBe('Do the thing');
    });

    it('ignores user messages made only of synthetic parts', () => {
      const view = buildAgentView(
        [
          msg('u0', 'user', [text('<system-reminder>hidden')]),
          msg('u00', 'user', [text('flagged', { synthetic: true })]),
          user('u1', 'Real task')
        ],
        undefined,
        undefined
      );
      expect(view.task).toBe('Real task');
      expect(view.rounds).toHaveLength(1);
    });

    it('ignores a bare [system: ...] continuation marker as task', () => {
      const view = buildAgentView(
        [msg('u0', 'user', [text('[system: tool calling continues]')]), user('u1', 'Real task')],
        undefined,
        undefined
      );
      expect(view.task).toBe('Real task');
    });

    it('joins several text parts of the task with a blank line', () => {
      const view = buildAgentView([msg('u1', 'user', [text('one'), text('two')])], undefined, undefined);
      expect(view.task).toBe('one\n\ntwo');
    });

    it('has no resume when there is only one user message', () => {
      const view = buildAgentView([user('u1', 'Task'), msg('a1', 'assistant', [tool('completed')])], undefined, undefined);
      expect(view.rounds).toHaveLength(1);
      expect(view.rounds[0].resume).toBeNull();
    });

    it('starts a second round with the resume text of the second user message', () => {
      const view = buildAgentView(
        [
          user('u1', 'Task'),
          msg('a1', 'assistant', [tool('completed')]),
          user('u2', 'Use option B'),
          msg('a2', 'assistant', [tool('completed', 'grep')])
        ],
        undefined,
        undefined
      );
      expect(view.rounds.map((r) => r.resume)).toEqual([null, 'Use option B']);
      expect(view.rounds[0].steps.map((s) => s.messageId)).toEqual(['a1']);
      expect(view.rounds[1].steps.map((s) => s.messageId)).toEqual(['a2']);
    });

    it('builds three rounds in order for two resumes', () => {
      const view = buildAgentView(
        [user('u1', 'T'), user('u2', 'first'), user('u3', 'second')],
        undefined,
        undefined
      );
      expect(view.rounds.map((r) => r.resume)).toEqual([null, 'first', 'second']);
    });

    it('marks earlier rounds stoppedAtEnd and the last round not', () => {
      const view = buildAgentView(
        [user('u1', 'T'), user('u2', 'first'), user('u3', 'second')],
        undefined,
        undefined
      );
      expect(view.rounds.map((r) => r.stoppedAtEnd)).toEqual([true, true, false]);
    });

    it('gives task null and one round when there is no user message', () => {
      const view = buildAgentView([msg('a1', 'assistant', [tool('completed')])], undefined, undefined);
      expect(view.task).toBeNull();
      expect(view.rounds).toHaveLength(1);
      expect(view.rounds[0].steps).toHaveLength(1);
    });
  });

  describe('steps', () => {
    it('maps tool, text and reasoning parts and drops other part types', () => {
      const view = buildAgentView(
        [
          user('u1', 'T'),
          msg('a1', 'assistant', [
            tool('completed'),
            text('thinking out loud'),
            { type: 'reasoning', text: 'because' } as Part,
            { type: 'step-start' } as Part,
            { type: 'file', filename: 'a.txt' } as Part
          ]),
          msg('a2', 'assistant', [tool('completed')])
        ],
        undefined,
        { busy: true }
      );
      expect(view.rounds[0].steps.map((s) => s.kind)).toEqual(['tool', 'text', 'reasoning', 'tool']);
    });

    it('marks a running tool part running only when the child is busy', () => {
      const messages = [user('u1', 'T'), msg('a1', 'assistant', [tool('running')])];
      expect(buildAgentView(messages, undefined, { busy: true }).rounds[0].steps[0].running).toBe(true);
      expect(buildAgentView(messages, undefined, { busy: false }).rounds[0].steps[0].running).toBe(false);
      expect(buildAgentView(messages, undefined, undefined).rounds[0].steps[0].running).toBe(false);
    });

    it('does not mark a completed tool part running even when busy', () => {
      const view = buildAgentView([user('u1', 'T'), msg('a1', 'assistant', [tool('completed')])], undefined, { busy: true });
      expect(view.rounds[0].steps[0].running).toBe(false);
    });
  });

  describe('report', () => {
    it('hoists the last assistant text into the report and out of the steps', () => {
      const final = text('Status: COMPLETED\nAll done');
      const view = buildAgentView(
        [user('u1', 'T'), msg('a1', 'assistant', [tool('completed'), final])],
        undefined,
        undefined
      );
      expect(view.report?.text).toBe('Status: COMPLETED\nAll done');
      expect(view.rounds[0].steps.map((s) => s.kind)).toEqual(['tool']);
    });

    it('is null while busy and keeps the trailing text as a step', () => {
      const view = buildAgentView(
        [user('u1', 'T'), msg('a1', 'assistant', [tool('running'), text('partial')])],
        undefined,
        { busy: true }
      );
      expect(view.report).toBeNull();
      expect(view.rounds[0].steps.map((s) => s.kind)).toEqual(['tool', 'text']);
    });

    it('flags INPUT_REQUIRED as stopped and needing a decision', () => {
      const view = buildAgentView(
        [user('u1', 'T'), msg('a1', 'assistant', [text('Status: INPUT_REQUIRED\nWhich DB?')])],
        undefined,
        undefined
      );
      expect(view.report?.stopped).toBe(true);
      expect(view.report?.needsDecision).toBe(true);
      expect(view.report?.label).toBe('Stopped — needs a decision');
    });

    it('flags BLOCKED as stopped without needing a decision', () => {
      const view = buildAgentView(
        [user('u1', 'T'), msg('a1', 'assistant', [text('Status: BLOCKED\nNo access')])],
        undefined,
        undefined
      );
      expect(view.report?.stopped).toBe(true);
      expect(view.report?.needsDecision).toBe(false);
    });

    it('leaves the reason empty when the stop summary already appears in the report text', () => {
      const view = buildAgentView(
        [user('u1', 'T'), msg('a1', 'assistant', [text('Status: BLOCKED\nNo access')])],
        undefined,
        undefined
      );
      expect(view.status.summary).toBe('No access');
      expect(view.report?.stopped).toBe(true);
      expect(view.report?.reason).toBe('');
    });

    it('uses the status summary as reason when a stopped report text does not contain it', () => {
      const view = buildAgentView(
        [user('u1', 'T'), msg('a1', 'assistant', [text('Partial findings so far')])],
        undefined,
        { interruptedReason: 'user' }
      );
      expect(view.report?.text).toBe('Partial findings so far');
      expect(view.report?.stopped).toBe(true);
      expect(view.report?.reason).toBe(view.status.summary);
      expect(view.report?.reason).toBe('Stopped by user');
    });

    it('flags COMPLETED as neither stopped nor needing a decision', () => {
      const view = buildAgentView(
        [user('u1', 'T'), msg('a1', 'assistant', [text('Status: COMPLETED\nFine')])],
        undefined,
        undefined
      );
      expect(view.report?.stopped).toBe(false);
      expect(view.report?.needsDecision).toBe(false);
      expect(view.report?.status).toBe('done');
    });

    it('produces an empty-text report with the reason for an interrupted child', () => {
      const view = buildAgentView(
        [user('u1', 'T'), msg('a1', 'assistant', [tool('completed')])],
        undefined,
        { interruptedReason: 'user' }
      );
      expect(view.report).not.toBeNull();
      expect(view.report?.text).toBe('');
      expect(view.report?.stopped).toBe(true);
      expect(view.report?.reason).toBe('Stopped by user');
    });

    it('has no report when the child succeeded but produced neither text nor a result body', () => {
      const view = buildAgentView(
        [user('u1', 'T'), msg('a1', 'assistant', [tool('completed')])],
        undefined,
        { outcome: 'succeeded' }
      );
      expect(view.status.status).toBe('done');
      expect(view.report).toBeNull();
    });

    it('treats a child without any signals as stopped and shows an empty report with Not running (deviates from spec 3.2 rule 4)', () => {
      const view = buildAgentView([user('u1', 'T'), msg('a1', 'assistant', [tool('completed')])], undefined, undefined);
      expect(view.report?.text).toBe('');
      expect(view.report?.stopped).toBe(true);
      expect(view.report?.reason).toBe('Not running');
    });

    it('only turns the last round text into the report; earlier text stays a step', () => {
      const view = buildAgentView(
        [
          user('u1', 'T'),
          msg('a1', 'assistant', [text('first round text')]),
          user('u2', 'again'),
          msg('a2', 'assistant', [text('Status: COMPLETED\nsecond')])
        ],
        undefined,
        undefined
      );
      expect(view.report?.text).toBe('Status: COMPLETED\nsecond');
      expect(view.rounds[0].steps.map((s) => (s.part.text ?? ''))).toEqual(['first round text']);
      expect(view.rounds[1].steps).toHaveLength(0);
    });

    it('falls back to the result body with the envelope stripped when the child has no text', () => {
      const child: ChildSignals = {
        result: {
          state: 'completed',
          body: '<background-result kind="subagent" sessionID="c1" state="completed">\nStatus: COMPLETED\nFrom the result\n</background-result>'
        }
      };
      const view = buildAgentView([user('u1', 'T'), msg('a1', 'assistant', [tool('completed')])], undefined, child);
      expect(view.report?.text).toBe('Status: COMPLETED\nFrom the result');
    });

    it('prefers the child text over the result body', () => {
      const child: ChildSignals = { result: { state: 'completed', body: 'Status: COMPLETED\nfrom result' } };
      const view = buildAgentView(
        [user('u1', 'T'), msg('a1', 'assistant', [text('Status: COMPLETED\nfrom child')])],
        undefined,
        child
      );
      expect(view.report?.text).toBe('Status: COMPLETED\nfrom child');
    });
  });

  describe('agent name', () => {
    const base = [user('u1', 'T')];

    it('prefers info.agent of the first assistant message', () => {
      const messages = [...base, msg('a1', 'assistant', [], { agent: 'coder' })];
      const session = { id: 's', agent: 'other', title: 'X (@reviewer subagent)' } as unknown as Session;
      expect(buildAgentView(messages, session, undefined).name).toBe('coder');
    });

    it('then session.agent', () => {
      const session = { id: 's', agent: 'planner', title: 'X (@reviewer subagent)' } as unknown as Session;
      expect(buildAgentView([...base, msg('a1', 'assistant', [])], session, undefined).name).toBe('planner');
    });

    it('then the (@name subagent) title pattern', () => {
      const session = { id: 's', title: 'Check it (@reviewer subagent)' } as Session;
      expect(buildAgentView(base, session, undefined).name).toBe('reviewer');
    });

    it('then Agent', () => {
      expect(buildAgentView(base, { id: 's', title: 'plain' } as Session, undefined).name).toBe('Agent');
      expect(buildAgentView(base, undefined, undefined).name).toBe('Agent');
    });
  });

  it('does not mutate deep-frozen inputs', () => {
    const messages = deepFreeze([
      user('u1', 'T'),
      msg('a1', 'assistant', [tool('completed'), text('Status: COMPLETED\nDone')], { agent: 'coder' }),
      user('u2', 'again'),
      msg('a2', 'assistant', [text('final')])
    ]);
    const session = deepFreeze({ id: 's', title: 't (@x subagent)' } as Session);
    const child = deepFreeze<ChildSignals>({ result: { state: 'completed', body: 'Status: COMPLETED\nok' } });
    const snapshot = JSON.stringify(messages);
    expect(() => buildAgentView(messages, session, child)).not.toThrow();
    expect(JSON.stringify(messages)).toBe(snapshot);
  });
});
