import type { SvyCommand } from './svy-commands';

export const STARTER_NAMES: readonly string[] = [
  'svyImplement',
  'svyFixTicket',
  'svyFixStacktrace',
  'svyCreateE2E'
];

export function starterCommands(list: readonly SvyCommand[]): SvyCommand[] {
  const result: SvyCommand[] = [];
  for (const name of STARTER_NAMES) {
    const found = list.find((c) => c.name === name);
    if (found) {
      result.push(found);
    }
  }
  return result;
}
