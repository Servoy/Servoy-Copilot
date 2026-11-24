import { describe, expect, it } from 'vitest';

import { Part } from '../models/opencode.models';
import {
  FILE_TOOLS,
  dedupeRefs,
  fileRefsFromMessageParts,
  fileRefsFromMessages,
  fileRefsFromParts,
  fileRefsFromText
} from './file-refs';

const toolPart = (tool: string, input: unknown, status = 'completed'): Part =>
  ({ type: 'tool', tool, state: { status, input } }) as unknown as Part;
const textPart = (text: string, extra: Record<string, unknown> = {}): Part =>
  ({ type: 'text', text, ...extra }) as unknown as Part;
const paths = (refs: { path: string }[]) => refs.map((r) => r.path);

describe('FILE_TOOLS', () => {
  it('allowlists write, edit and apply_patch only', () => {
    expect([...FILE_TOOLS]).toEqual(['write', 'edit', 'apply_patch']);
  });
});

describe('fileRefsFromParts', () => {
  it('reads filePath from a completed write and edit', () => {
    const refs = fileRefsFromParts([
      toolPart('write', { filePath: 'src/a.js', content: 'x' }),
      toolPart('edit', { filePath: 'src/b.js', oldString: 'a', newString: 'b' })
    ]);
    expect(refs).toEqual([
      { path: 'src/a.js', name: 'a.js', caption: 'Changed file' },
      { path: 'src/b.js', name: 'b.js', caption: 'Changed file' }
    ]);
  });

  it('falls back to input.path when filePath is absent, and prefers filePath when both exist', () => {
    expect(paths(fileRefsFromParts([toolPart('write', { path: 'p.js' })]))).toEqual(['p.js']);
    expect(paths(fileRefsFromParts([toolPart('write', { filePath: 'f.js', path: 'p.js' })]))).toEqual(['f.js']);
  });

  it('ignores a non-string path and missing/odd inputs', () => {
    expect(fileRefsFromParts([toolPart('write', { filePath: 5 })])).toEqual([]);
    expect(fileRefsFromParts([toolPart('write', null)])).toEqual([]);
    expect(fileRefsFromParts([toolPart('write', 'str')])).toEqual([]);
    expect(fileRefsFromParts([{ type: 'tool', tool: 'write' } as Part])).toEqual([]);
  });

  it('ignores errored, running and pending tool parts', () => {
    const refs = fileRefsFromParts([
      toolPart('write', { filePath: 'e.js' }, 'error'),
      toolPart('write', { filePath: 'r.js' }, 'running'),
      toolPart('write', { filePath: 'p.js' }, 'pending'),
      toolPart('write', { filePath: 'ok.js' })
    ]);
    expect(paths(refs)).toEqual(['ok.js']);
  });

  it('ignores tools outside the allowlist and non-tool parts', () => {
    const refs = fileRefsFromParts([
      toolPart('read', { filePath: 'r.js' }),
      toolPart('eclipse-coder_createFile', { filePath: 'c.java' }),
      { type: 'text', text: 'x', state: { status: 'completed', input: { filePath: 't.js' } } } as unknown as Part
    ]);
    expect(refs).toEqual([]);
  });

  describe('apply_patch', () => {
    const patch = [
      '*** Begin Patch',
      '*** Add File: src/new.js',
      '+hello',
      '*** Update File: src/upd.js',
      '@@',
      '-a',
      '+b',
      '*** Move to: src/moved.js',
      '*** Delete File: src/gone.js',
      '*** End Patch'
    ].join('\n');

    it('extracts Add, Update and Move lines and ignores Delete', () => {
      expect(paths(fileRefsFromParts([toolPart('apply_patch', { patchText: patch })]))).toEqual([
        'src/new.js',
        'src/upd.js',
        'src/moved.js'
      ]);
    });

    it.each(['patchText', 'patch', 'input'])('reads the patch from the %s field', (field) => {
      expect(paths(fileRefsFromParts([toolPart('apply_patch', { [field]: patch })]))).toEqual([
        'src/new.js',
        'src/upd.js',
        'src/moved.js'
      ]);
    });

    it('prefers patchText over patch over input', () => {
      const refs = fileRefsFromParts([
        toolPart('apply_patch', {
          input: '*** Add File: c.js',
          patch: '*** Add File: b.js',
          patchText: '*** Add File: a.js'
        })
      ]);
      expect(paths(refs)).toEqual(['a.js']);
    });

    it('does not use filePath for apply_patch and ignores a non-string patch', () => {
      expect(fileRefsFromParts([toolPart('apply_patch', { filePath: 'x.js' })])).toEqual([]);
      expect(fileRefsFromParts([toolPart('apply_patch', { patchText: 42 })])).toEqual([]);
    });

    it('only matches directive lines at the start of a line and trims trailing whitespace', () => {
      const refs = fileRefsFromParts([
        toolPart('apply_patch', { patchText: '  *** Add File: indented.js\n*** Add File: spaced.js   \r\n*** Delete File: d.js' })
      ]);
      expect(paths(refs)).toEqual(['spaced.js']);
    });

    it('keeps a path with spaces from tool input', () => {
      const refs = fileRefsFromParts([toolPart('apply_patch', { patchText: '*** Add File: my docs/a b.md' })]);
      expect(refs).toEqual([{ path: 'my docs/a b.md', name: 'a b.md', caption: 'Changed file' }]);
    });

    it('ignores an errored apply_patch', () => {
      expect(fileRefsFromParts([toolPart('apply_patch', { patchText: '*** Add File: a.js' }, 'error')])).toEqual([]);
    });
  });

  describe('normalisation', () => {
    it('converts backslashes, strips leading ./ (repeatedly) and trims', () => {
      expect(paths(fileRefsFromParts([toolPart('write', { filePath: '  .\\src\\a.js ' })]))).toEqual(['src/a.js']);
      expect(paths(fileRefsFromParts([toolPart('write', { filePath: './././a.js' })]))).toEqual(['a.js']);
    });

    it('drops empty and whitespace-only values', () => {
      expect(fileRefsFromParts([toolPart('write', { filePath: '' }), toolPart('write', { filePath: '   ' })])).toEqual([]);
    });

    it('drops paths with a .. segment but keeps names merely containing dots', () => {
      const refs = fileRefsFromParts([
        toolPart('write', { filePath: '../outside.js' }),
        toolPart('write', { filePath: 'a/../b.js' }),
        toolPart('write', { filePath: 'a\\..\\b.js' }),
        toolPart('write', { filePath: 'a..b/c.js' }),
        toolPart('write', { filePath: '..hidden' })
      ]);
      expect(paths(refs)).toEqual(['a..b/c.js', '..hidden']);
    });

    it('drops values with control characters', () => {
      expect(fileRefsFromParts([toolPart('write', { filePath: 'a\u0000.js' }), toolPart('write', { filePath: 'a\nb.js' })])).toEqual([]);
    });

    it('drops a path that ends in a separator (no file name)', () => {
      expect(fileRefsFromParts([toolPart('write', { filePath: 'src/' })])).toEqual([]);
    });

    it('keeps absolute paths (the server decides)', () => {
      const refs = fileRefsFromParts([
        toolPart('write', { filePath: 'D:\\git\\ws\\src\\a.js' }),
        toolPart('write', { filePath: '/home/u/ws/b.js' })
      ]);
      expect(paths(refs)).toEqual(['D:/git/ws/src/a.js', '/home/u/ws/b.js']);
    });
  });

  describe('captions', () => {
    it('derives the caption from the base name only', () => {
      const refs = fileRefsFromParts([
        toolPart('write', { filePath: 'handoff/t/spec.md' }),
        toolPart('write', { filePath: 'handoff/t/review.md' }),
        toolPart('write', { filePath: 'handoff/u/SPEC.MD' }),
        toolPart('write', { filePath: 'docs/spec.md.bak' }),
        toolPart('write', { filePath: 'spec.md/other.txt' }),
        toolPart('write', { filePath: 'myspec.md' })
      ]);
      expect(refs.map((r) => r.caption)).toEqual([
        'Spec',
        'Review',
        'Spec',
        'Changed file',
        'Changed file',
        'Changed file'
      ]);
    });
  });

  describe('dedupe and order', () => {
    it('dedupes case-insensitively across separators and keeps the first-seen spelling and order', () => {
      const refs = fileRefsFromParts([
        toolPart('write', { filePath: 'Src/A.js' }),
        toolPart('write', { filePath: 'b.js' }),
        toolPart('edit', { filePath: 'src\\a.js' }),
        toolPart('edit', { filePath: './SRC/A.JS' })
      ]);
      expect(paths(refs)).toEqual(['Src/A.js', 'b.js']);
    });

    it('does not cap the number of refs', () => {
      const parts = Array.from({ length: 30 }, (_, i) => toolPart('write', { filePath: `f${i}.js` }));
      expect(fileRefsFromParts(parts)).toHaveLength(30);
    });
  });
});

describe('fileRefsFromText', () => {
  const found = (text: string) => paths(fileRefsFromText(text));

  it('matches handoff spec and review files with the right captions', () => {
    expect(fileRefsFromText('See handoff/task-1/spec.md and handoff/task-1/review.md now')).toEqual([
      { path: 'handoff/task-1/spec.md', name: 'spec.md', caption: 'Spec' },
      { path: 'handoff/task-1/review.md', name: 'review.md', caption: 'Review' }
    ]);
  });

  it('matches at the very start and very end of the text', () => {
    expect(found('handoff/t/spec.md')).toEqual(['handoff/t/spec.md']);
  });

  it('matches inside backticks, quotes and brackets', () => {
    expect(found('`handoff/t/spec.md`')).toEqual(['handoff/t/spec.md']);
    expect(found('"handoff/t/spec.md"')).toEqual(['handoff/t/spec.md']);
    expect(found("'handoff/t/review.md'")).toEqual(['handoff/t/review.md']);
    expect(found('(handoff/t/spec.md)')).toEqual(['handoff/t/spec.md']);
    expect(found('[handoff/t/spec.md]')).toEqual(['handoff/t/spec.md']);
    expect(found('{handoff/t/spec.md}')).toEqual(['handoff/t/spec.md']);
    expect(found('<handoff/t/spec.md>')).toEqual(['handoff/t/spec.md']);
  });

  it('matches before sentence punctuation', () => {
    for (const p of ['.', ',', ';', ':', '!', '?']) {
      expect(found(`written to handoff/t/spec.md${p} Done`)).toEqual(['handoff/t/spec.md']);
    }
    expect(found('see handoff/t/spec.md.')).toEqual(['handoff/t/spec.md']);
  });

  it('matches at the start of a new line', () => {
    expect(found('first\nhandoff/t/spec.md\nlast')).toEqual(['handoff/t/spec.md']);
  });

  it('accepts Windows separators and normalises them', () => {
    expect(found('open handoff\\t\\spec.md please')).toEqual(['handoff/t/spec.md']);
    expect(found('open handoff/t\\review.md please')).toEqual(['handoff/t/review.md']);
  });

  it('accepts a leading ./ and .\\ and strips it', () => {
    expect(found('see ./handoff/t/spec.md')).toEqual(['handoff/t/spec.md']);
    expect(found('see .\\handoff\\t\\spec.md')).toEqual(['handoff/t/spec.md']);
  });

  it('is case-insensitive and keeps the written spelling', () => {
    expect(fileRefsFromText('HANDOFF/Task/SPEC.MD')).toEqual([
      { path: 'HANDOFF/Task/SPEC.MD', name: 'SPEC.MD', caption: 'Spec' }
    ]);
  });

  it('accepts dots, underscores, digits and dashes in the task folder', () => {
    expect(found('handoff/SVY-21851_v1.2/spec.md')).toEqual(['handoff/SVY-21851_v1.2/spec.md']);
  });

  it('does not match other file names in handoff', () => {
    expect(found('handoff/t/notes.md')).toEqual([]);
    expect(found('handoff/t/spec.txt')).toEqual([]);
    expect(found('handoff/t/spec.mdx')).toEqual([]);
    expect(found('handoff/t/myspec.md')).toEqual([]);
    expect(found('handoff/t/spec.md5')).toEqual([]);
  });

  it('does not match when handoff is not the first segment or is glued to a word or path', () => {
    expect(found('xhandoff/t/spec.md')).toEqual([]);
    expect(found('src/handoff/t/spec.md')).toEqual([]);
    expect(found('D:\\ws\\handoff\\t\\spec.md')).toEqual([]);
    expect(found('/ws/handoff/t/spec.md')).toEqual([]);
  });

  it('does not match a missing or nested task folder', () => {
    expect(found('handoff/spec.md')).toEqual([]);
    expect(found('handoff/a/b/spec.md')).toEqual([]);
  });

  it('does not match a task folder with spaces or other characters in prose', () => {
    expect(found('handoff/my task/spec.md')).toEqual([]);
    expect(found('handoff/t$/spec.md')).toEqual([]);
  });

  it('does not match traversal inside the task folder', () => {
    expect(found('handoff/../spec.md')).toEqual([]);
    expect(found('handoff/../x/spec.md')).toEqual([]);
  });

  it('dedupes case-insensitively and keeps order', () => {
    expect(found('handoff/b/spec.md handoff/a/review.md HANDOFF/B/SPEC.MD')).toEqual([
      'handoff/b/spec.md',
      'handoff/a/review.md'
    ]);
  });

  it('returns nothing for empty text', () => {
    expect(fileRefsFromText('')).toEqual([]);
  });
});

describe('fileRefsFromMessageParts', () => {
  it('unions tool refs first, then text refs, deduped', () => {
    const refs = fileRefsFromMessageParts([
      textPart('Done: handoff/t/spec.md and handoff/t/review.md'),
      toolPart('write', { filePath: 'src/a.js' }),
      toolPart('write', { filePath: 'HANDOFF/t/spec.md' })
    ]);
    expect(paths(refs)).toEqual(['src/a.js', 'HANDOFF/t/spec.md', 'handoff/t/review.md']);
  });

  it('ignores synthetic text parts and non-string text', () => {
    const refs = fileRefsFromMessageParts([
      textPart('handoff/t/spec.md', { synthetic: true }),
      { type: 'text' } as Part,
      textPart('handoff/t/review.md')
    ]);
    expect(paths(refs)).toEqual(['handoff/t/review.md']);
  });

  it('does not take refs from reasoning parts', () => {
    const refs = fileRefsFromMessageParts([{ type: 'reasoning', text: 'handoff/t/spec.md' } as Part]);
    expect(refs).toEqual([]);
  });
});

describe('fileRefsFromMessages', () => {
  it('unions over messages in order and dedupes across them', () => {
    const refs = fileRefsFromMessages([
      { parts: [toolPart('write', { filePath: 'a.js' })] },
      { parts: [textPart('handoff/t/spec.md'), toolPart('write', { filePath: 'A.js' })] },
      { parts: [toolPart('edit', { filePath: 'b.js' })] }
    ]);
    expect(paths(refs)).toEqual(['a.js', 'handoff/t/spec.md', 'b.js']);
  });

  it('is empty for no messages', () => {
    expect(fileRefsFromMessages([])).toEqual([]);
  });
});

describe('dedupeRefs', () => {
  it('keeps the first of equal paths ignoring case', () => {
    const a = { path: 'X.md', name: 'X.md', caption: 'Changed file' as const };
    const b = { path: 'x.md', name: 'x.md', caption: 'Changed file' as const };
    expect(dedupeRefs([a, b])).toEqual([a]);
  });
});
