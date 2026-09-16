import { describe, expect, it } from 'vitest';

import { MessageWithParts, Part, Session } from '../models/opencode.models';
import {
  SessionExportData,
  sessionExportBaseName,
  sessionExportToJson
} from './session-export';

function msg(role: string, parts: Part[], extra: Partial<MessageWithParts['info']> = {}): MessageWithParts {
  return { info: { id: 'm-' + role, role, ...extra }, parts };
}

describe('sessionExportBaseName', () => {
  it('slugifies the title', () => {
    expect(sessionExportBaseName({ id: 's1', title: 'Fix the Login Bug!' } as Session)).toBe('fix-the-login-bug');
  });

  it('falls back to the slug when there is no title', () => {
    expect(sessionExportBaseName({ id: 'ses_abc', slug: 'calm-sailor' } as Session)).toBe('calm-sailor');
  });

  it('falls back to the id when there is no title or slug', () => {
    expect(sessionExportBaseName({ id: 'ses_abc' } as Session)).toBe('ses_abc');
    expect(sessionExportBaseName({ id: 'ses_abc', title: '   ' } as Session)).toBe('ses_abc');
  });

  it('falls back to the id when the title slugifies to empty', () => {
    expect(sessionExportBaseName({ id: 'ses_abc', title: '!!!' } as Session)).toBe('ses_abc');
  });
});

describe('sessionExportToJson', () => {
  it('pretty-prints the CLI-shaped { info, messages } export', () => {
    const data: SessionExportData = {
      info: { id: 's1', title: 'T' } as Session,
      messages: [msg('user', [{ type: 'text', text: 'hi' } as Part])]
    };
    const json = sessionExportToJson(data);
    expect(JSON.parse(json)).toEqual(data);
    expect(json).toContain('\n  '); // indented
  });

  it('emits every part verbatim, including step/reasoning/tool parts, without filtering', () => {
    const parts: Part[] = [
      { id: 'p1', type: 'step-start' } as Part,
      { id: 'p2', type: 'reasoning', text: 'thinking' } as Part,
      { id: 'p3', type: 'text', text: '[system: tool calling continues]' } as Part,
      { id: 'p4', type: 'tool', tool: 'bash', state: { input: { command: 'x' }, output: 'y' } } as Part,
      { id: 'p5', type: 'step-finish' } as Part
    ];
    const data: SessionExportData = {
      info: { id: 's1' } as Session,
      messages: [msg('assistant', parts)]
    };
    const parsed = JSON.parse(sessionExportToJson(data)) as SessionExportData;
    // Nothing is dropped - the export is a faithful copy of the raw payloads.
    expect(parsed.messages[0].parts.map((p) => p.type)).toEqual([
      'step-start',
      'reasoning',
      'text',
      'tool',
      'step-finish'
    ]);
    expect(parsed.messages[0].parts[2].text).toBe('[system: tool calling continues]');
  });

  it('preserves the exact info object (slug, tokens, model, ...)', () => {
    const info = {
      id: 'ses_1',
      slug: 'calm-sailor',
      title: 'Form',
      tokens: { input: 10, output: 2 },
      model: { id: 'auto', providerID: 'kiro' }
    } as unknown as Session;
    const data: SessionExportData = { info, messages: [] };
    expect(JSON.parse(sessionExportToJson(data)).info).toEqual(info);
  });
});
