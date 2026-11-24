import { describe, expect, it } from 'vitest';

import { filterSvyCommands, insertionText, isSvyCommand, slashQuery, toSvyCommands } from './svy-commands';

describe('svy-commands', () => {
  describe('isSvyCommand', () => {
    it('is true for svy-prefixed names', () => {
      expect(isSvyCommand('svyFixTicket')).toBe(true);
      expect(isSvyCommand('svy')).toBe(true);
    });

    it('is false for other names and is case-sensitive', () => {
      for (const name of ['status', 'search', 'Svy', 'SVYX', 'svg', 'mysvy', '']) {
        expect(isSvyCommand(name)).toBe(false);
      }
    });
  });

  describe('toSvyCommands', () => {
    it('drops non-svy commands and preserves order', () => {
      const result = toSvyCommands([
        { name: 'svyB', description: 'b' },
        { name: 'init', description: 'i' },
        { name: 'svyA', description: 'a' },
        { name: 'status' }
      ]);
      expect(result.map((c) => c.name)).toEqual(['svyB', 'svyA']);
    });

    it('collapses whitespace and trims descriptions', () => {
      const [cmd] = toSvyCommands([{ name: 'svyX', description: '  Fix  a\n ticket  ' }]);
      expect(cmd.description).toBe('Fix a ticket');
    });

    it('uses an empty description when missing', () => {
      expect(toSvyCommands([{ name: 'svyX' }])).toEqual([{ name: 'svyX', description: '' }]);
    });

    it('returns [] for an empty list', () => {
      expect(toSvyCommands([])).toEqual([]);
    });
  });

  describe('filterSvyCommands', () => {
    const list = [
      { name: 'svyFixTicket', description: '' },
      { name: 'svyFixStacktrace', description: '' },
      { name: 'svyOptimize', description: '' }
    ];

    it('matches a substring in the middle of the name, case-insensitively', () => {
      expect(filterSvyCommands(list, 'fix').map((c) => c.name)).toEqual(['svyFixTicket', 'svyFixStacktrace']);
      expect(filterSvyCommands(list, 'OPTI').map((c) => c.name)).toEqual(['svyOptimize']);
    });

    it('returns all for an empty query', () => {
      expect(filterSvyCommands(list, '')).toEqual(list);
    });

    it('returns [] when nothing matches', () => {
      expect(filterSvyCommands(list, 'zzz')).toEqual([]);
    });
  });

  describe('slashQuery', () => {
    it('returns the text after the slash', () => {
      expect(slashQuery('/')).toBe('');
      expect(slashQuery('/svy')).toBe('svy');
    });

    it('returns null once whitespace is present', () => {
      expect(slashQuery('/svy x')).toBeNull();
      expect(slashQuery('/svy\nx')).toBeNull();
      expect(slashQuery('/svy\tx')).toBeNull();
      expect(slashQuery('/svyFixTicket ')).toBeNull();
    });

    it('returns null when the slash is not the first character', () => {
      expect(slashQuery('a/b')).toBeNull();
      expect(slashQuery('hello/')).toBeNull();
      expect(slashQuery(' /svy')).toBeNull();
      expect(slashQuery('')).toBeNull();
    });
  });

  describe('insertionText', () => {
    it('is slash, name and a trailing space', () => {
      expect(insertionText({ name: 'svyFixTicket', description: 'd' })).toBe('/svyFixTicket ');
    });
  });
});
