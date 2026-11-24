import { CommandInfo } from '../models/opencode.models';

export interface SvyCommand {
  name: string;
  description: string;
}

export function isSvyCommand(name: string): boolean {
  return name.startsWith('svy');
}

export function toSvyCommands(list: CommandInfo[]): SvyCommand[] {
  return list
    .filter((c) => isSvyCommand(c.name))
    .map((c) => ({ name: c.name, description: (c.description ?? '').replace(/\s+/g, ' ').trim() }));
}

export function filterSvyCommands(list: SvyCommand[], query: string): SvyCommand[] {
  const q = query.toLowerCase();
  return q ? list.filter((c) => c.name.toLowerCase().includes(q)) : list;
}

export function slashQuery(text: string): string | null {
  return text.startsWith('/') && !/\s/.test(text) ? text.slice(1) : null;
}

export function insertionText(command: SvyCommand): string {
  return '/' + command.name + ' ';
}
