import { describe, expect, it } from 'vitest';

import { STARTER_NAMES, starterCommands } from './starters';
import { SvyCommand } from './svy-commands';

const cmd = (name: string, description = `${name} description`): SvyCommand => ({ name, description });

describe('starterCommands', () => {
  it('declares the four starters in display order', () => {
    expect(STARTER_NAMES).toEqual(['svyImplement', 'svyFixTicket', 'svyFixStacktrace', 'svyCreateE2E']);
  });

  it('returns the starters in STARTER_NAMES order regardless of catalog order', () => {
    const list = [cmd('svyCreateE2E'), cmd('svyFixStacktrace'), cmd('svyFixTicket'), cmd('svyImplement')];
    expect(starterCommands(list).map((c) => c.name)).toEqual([
      'svyImplement',
      'svyFixTicket',
      'svyFixStacktrace',
      'svyCreateE2E'
    ]);
  });

  it('skips a starter that is missing from the catalog', () => {
    const list = [cmd('svyImplement'), cmd('svyFixStacktrace'), cmd('svyCreateE2E')];
    expect(starterCommands(list).map((c) => c.name)).toEqual(['svyImplement', 'svyFixStacktrace', 'svyCreateE2E']);
  });

  it('returns an empty list for an empty catalog', () => {
    expect(starterCommands([])).toEqual([]);
  });

  it('ignores commands that are not starters', () => {
    const list = [cmd('svyOptimize'), cmd('svyImplement'), cmd('other')];
    expect(starterCommands(list).map((c) => c.name)).toEqual(['svyImplement']);
  });

  it('returns an empty list when only non-starters exist', () => {
    expect(starterCommands([cmd('svyOptimize'), cmd('x')])).toEqual([]);
  });

  it('yields the first match once for duplicate names', () => {
    const first = cmd('svyImplement', 'first');
    const result = starterCommands([first, cmd('svyImplement', 'second')]);
    expect(result).toHaveLength(1);
    expect(result[0]).toBe(first);
  });

  it('keeps the catalog description on the returned objects', () => {
    const result = starterCommands([cmd('svyFixTicket', 'Fix a Jira ticket')]);
    expect(result).toEqual([{ name: 'svyFixTicket', description: 'Fix a Jira ticket' }]);
  });
});
