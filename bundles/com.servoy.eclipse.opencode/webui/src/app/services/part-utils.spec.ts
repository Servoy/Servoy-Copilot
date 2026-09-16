import { describe, expect, it } from 'vitest';

import {
  isReasoningPart,
  isRenderablePart,
  isSyntheticPart,
  isTextPart,
  isToolPart,
  toolLabel,
  toolDisplayName,
  toolSubtitle,
  hasToolOutput,
  upsertPart
} from './part-utils';
import { Part } from '../models/opencode.models';

function part(overrides: Partial<Part> & { type: Part['type'] }): Part {
  return { ...overrides } as Part;
}

describe('isSyntheticPart', () => {
  it('is true when the synthetic flag is set', () => {
    expect(isSyntheticPart(part({ type: 'text', text: 'hi', synthetic: true }))).toBe(true);
  });

  it('is true for a text part starting with <system-reminder> (ignoring leading whitespace)', () => {
    expect(isSyntheticPart(part({ type: 'text', text: '<system-reminder>do X</system-reminder>' }))).toBe(true);
    expect(isSyntheticPart(part({ type: 'text', text: '   \n<system-reminder>x' }))).toBe(true);
  });

  it('is false for ordinary prose', () => {
    expect(isSyntheticPart(part({ type: 'text', text: 'hello world' }))).toBe(false);
  });

  it('is false for a non-text part with no synthetic flag', () => {
    expect(isSyntheticPart(part({ type: 'tool', tool: 'read' }))).toBe(false);
  });

  it('does not treat a system-reminder mention in the middle of text as synthetic', () => {
    expect(isSyntheticPart(part({ type: 'text', text: 'see <system-reminder> below' }))).toBe(false);
  });

  it('is true for a bare "[system: ...]" continuation marker', () => {
    expect(isSyntheticPart(part({ type: 'text', text: '[system: tool calling continues]' }))).toBe(true);
    expect(isSyntheticPart(part({ type: 'text', text: '  [system: continues]  ' }))).toBe(true);
  });

  it('does not treat prose merely containing "[system:" as synthetic', () => {
    expect(isSyntheticPart(part({ type: 'text', text: 'the log line [system: x] appeared' }))).toBe(false);
  });
});

describe('isTextPart', () => {
  it.each([
    ['visible prose', part({ type: 'text', text: 'hello' }), true],
    ['whitespace-only', part({ type: 'text', text: '   ' }), false],
    ['empty', part({ type: 'text', text: '' }), false],
    ['synthetic flagged', part({ type: 'text', text: 'x', synthetic: true }), false],
    ['system-reminder', part({ type: 'text', text: '<system-reminder>x' }), false],
    ['reasoning type', part({ type: 'reasoning', text: 'thinking' }), false],
    ['no text field', part({ type: 'text' }), false]
  ])('%s -> %s', (_label, p, expected) => {
    expect(isTextPart(p)).toBe(expected);
  });
});

describe('isReasoningPart', () => {
  it('is true for a reasoning part with content', () => {
    expect(isReasoningPart(part({ type: 'reasoning', text: 'because' }))).toBe(true);
  });

  it('is false for empty or whitespace reasoning', () => {
    expect(isReasoningPart(part({ type: 'reasoning', text: '  ' }))).toBe(false);
    expect(isReasoningPart(part({ type: 'reasoning' }))).toBe(false);
  });

  it('is false for a text part', () => {
    expect(isReasoningPart(part({ type: 'text', text: 'hi' }))).toBe(false);
  });
});

describe('isToolPart', () => {
  it('is true only for tool parts', () => {
    expect(isToolPart(part({ type: 'tool', tool: 'read' }))).toBe(true);
    expect(isToolPart(part({ type: 'text', text: 'hi' }))).toBe(false);
    expect(isToolPart(part({ type: 'file', filename: 'a.txt' }))).toBe(false);
  });
});

describe('isRenderablePart', () => {
  it('renders text, reasoning, and tool parts', () => {
    expect(isRenderablePart(part({ type: 'text', text: 'hi' }))).toBe(true);
    expect(isRenderablePart(part({ type: 'reasoning', text: 'x' }))).toBe(true);
    expect(isRenderablePart(part({ type: 'tool', tool: 'read' }))).toBe(true);
  });

  it('does not render synthetic parts even if they are text', () => {
    expect(isRenderablePart(part({ type: 'text', text: 'x', synthetic: true }))).toBe(false);
    expect(isRenderablePart(part({ type: 'text', text: '<system-reminder>x' }))).toBe(false);
  });

  it('does not render unsupported part types', () => {
    expect(isRenderablePart(part({ type: 'file', filename: 'a.txt' }))).toBe(false);
    expect(isRenderablePart(part({ type: 'step-start' }))).toBe(false);
    expect(isRenderablePart(part({ type: 'snapshot' }))).toBe(false);
  });

  it('does not render empty text parts', () => {
    expect(isRenderablePart(part({ type: 'text', text: '   ' }))).toBe(false);
  });
});

describe('toolLabel', () => {
  it('combines tool name and status', () => {
    expect(toolLabel(part({ type: 'tool', tool: 'read', state: { status: 'running' } }))).toBe('read · running');
  });

  it('returns just the name when there is no status', () => {
    expect(toolLabel(part({ type: 'tool', tool: 'read' }))).toBe('read');
    expect(toolLabel(part({ type: 'tool', tool: 'read', state: {} }))).toBe('read');
  });

  it('falls back to "tool" when the name is missing', () => {
    expect(toolLabel(part({ type: 'tool' }))).toBe('tool');
    expect(toolLabel(part({ type: 'tool', state: { status: 'completed' } }))).toBe('tool · completed');
  });
});

describe('toolDisplayName', () => {
  it('maps known tool ids to friendly names', () => {
    expect(toolDisplayName(part({ type: 'tool', tool: 'read' }))).toBe('Read File');
    expect(toolDisplayName(part({ type: 'tool', tool: 'bash' }))).toBe('Shell Command');
    expect(toolDisplayName(part({ type: 'tool', tool: 'edit' }))).toBe('Edit File');
  });

  it('maps the skill tool to "Load Skill"', () => {
    expect(toolDisplayName(part({ type: 'tool', tool: 'skill' }))).toBe('Load Skill');
  });

  it('title-cases unknown tool ids', () => {
    expect(toolDisplayName(part({ type: 'tool', tool: 'custom_mcp_tool' }))).toBe('Custom Mcp Tool');
  });

  it('falls back to "Tool" when no name is present', () => {
    expect(toolDisplayName(part({ type: 'tool' }))).toBe('Tool');
  });
});

describe('toolSubtitle', () => {
  it('surfaces the most meaningful input argument', () => {
    expect(toolSubtitle(part({ type: 'tool', tool: 'read', state: { input: { filePath: '/a/b.ts' } } }))).toBe('/a/b.ts');
    expect(toolSubtitle(part({ type: 'tool', tool: 'bash', state: { input: { command: 'ls -la' } } }))).toBe('ls -la');
    expect(toolSubtitle(part({ type: 'tool', tool: 'grep', state: { input: { pattern: 'foo' } } }))).toBe('foo');
    expect(toolSubtitle(part({ type: 'tool', tool: 'skill', state: { input: { name: 'pdf-forms' } } }))).toBe('pdf-forms');
  });

  it('is empty when there is no usable input', () => {
    expect(toolSubtitle(part({ type: 'tool', tool: 'read' }))).toBe('');
    expect(toolSubtitle(part({ type: 'tool', tool: 'read', state: { input: {} } }))).toBe('');
    expect(toolSubtitle(part({ type: 'tool', tool: 'read', state: { input: 'string' } }))).toBe('');
  });
});

describe('hasToolOutput', () => {
  it('is true only when there is non-empty output', () => {
    expect(hasToolOutput(part({ type: 'tool', state: { output: 'body' } }))).toBe(true);
    expect(hasToolOutput(part({ type: 'tool', state: { output: '   ' } }))).toBe(false);
    expect(hasToolOutput(part({ type: 'tool' }))).toBe(false);
  });

  it('is false for the skill tool even when it has output (skill content is internal)', () => {
    expect(
      hasToolOutput(part({ type: 'tool', tool: 'skill', state: { output: 'the whole skill file' } }))
    ).toBe(false);
  });
});

describe('upsertPart', () => {
  it('appends when the incoming part has no id', () => {
    const existing: Part[] = [part({ type: 'text', id: 'a', text: 'a' })];
    const result = upsertPart(existing, part({ type: 'text', text: 'b' }));
    expect(result).toHaveLength(2);
    expect(result[1].text).toBe('b');
  });

  it('appends when the id is not found', () => {
    const existing: Part[] = [part({ type: 'text', id: 'a', text: 'a' })];
    const result = upsertPart(existing, part({ type: 'text', id: 'b', text: 'b' }));
    expect(result).toHaveLength(2);
    expect(result.map((p) => p.id)).toEqual(['a', 'b']);
  });

  it('merges into the existing part when the id matches, preserving prior fields', () => {
    const existing: Part[] = [
      part({ type: 'text', id: 'a', text: 'partial', messageID: 'm1' })
    ];
    const result = upsertPart(existing, part({ type: 'text', id: 'a', text: 'partial complete' }));
    expect(result).toHaveLength(1);
    expect(result[0].text).toBe('partial complete');
    expect(result[0].messageID).toBe('m1');
  });

  it('returns a new array and does not mutate the input (immutability)', () => {
    const existing: Part[] = [part({ type: 'text', id: 'a', text: 'a' })];
    const snapshot = existing[0];
    const result = upsertPart(existing, part({ type: 'text', id: 'a', text: 'updated' }));
    expect(result).not.toBe(existing);
    expect(existing[0]).toBe(snapshot);
    expect(existing[0].text).toBe('a');
  });

  it('keeps order stable when merging (index position unchanged)', () => {
    const existing: Part[] = [
      part({ type: 'text', id: 'a', text: 'a' }),
      part({ type: 'text', id: 'b', text: 'b' })
    ];
    const result = upsertPart(existing, part({ type: 'text', id: 'a', text: 'A2' }));
    expect(result.map((p) => p.id)).toEqual(['a', 'b']);
    expect(result[0].text).toBe('A2');
  });
});
