import { Part } from '../models/opencode.models';

export type FileCaption = 'Spec' | 'Review' | 'Changed file';

export interface FileRef {
  path: string;
  name: string;
  caption: FileCaption;
}

export const FILE_TOOLS: readonly string[] = ['write', 'edit', 'apply_patch'];

const PATCH_LINE = /^\*\*\* (?:Add File|Update File|Move to):\s*(.+?)\s*$/gm;
const HANDOFF_RE =
  /(?:^|[\s"'`([{<,;:])((?:\.[\\/])?handoff[\\/][A-Za-z0-9._-]+[\\/](?:spec|review)\.md)(?=$|[\s"'`)\]}>,;:.!?])/gi;

function normalise(raw: string): string | null {
  let path = raw.trim().replace(/\\/g, '/');
  while (path.startsWith('./')) {
    path = path.slice(2);
  }
  if (!path || path.split('/').includes('..') || /[\u0000-\u001f]/.test(path)) {
    return null;
  }
  return path;
}

function toRef(raw: string): FileRef | null {
  const path = normalise(raw);
  if (!path) {
    return null;
  }
  const name = path.split('/').pop() ?? path;
  if (!name) {
    return null;
  }
  const lower = name.toLowerCase();
  const caption: FileCaption = lower === 'spec.md' ? 'Spec' : lower === 'review.md' ? 'Review' : 'Changed file';
  return { path, name, caption };
}

export function dedupeRefs(refs: FileRef[]): FileRef[] {
  const seen = new Set<string>();
  const out: FileRef[] = [];
  for (const ref of refs) {
    const key = ref.path.toLowerCase();
    if (!seen.has(key)) {
      seen.add(key);
      out.push(ref);
    }
  }
  return out;
}

function pathsOfTool(part: Part): string[] {
  const input = part.state?.input;
  if (!input || typeof input !== 'object') {
    return [];
  }
  const args = input as Record<string, unknown>;
  if (part.tool === 'apply_patch') {
    const patch = args['patchText'] ?? args['patch'] ?? args['input'];
    if (typeof patch !== 'string') {
      return [];
    }
    return Array.from(patch.matchAll(PATCH_LINE), (m) => m[1]);
  }
  const path = args['filePath'] ?? args['path'];
  return typeof path === 'string' ? [path] : [];
}

export function fileRefsFromParts(parts: Part[]): FileRef[] {
  const refs: FileRef[] = [];
  for (const part of parts) {
    if (part.type !== 'tool' || part.state?.status !== 'completed') {
      continue;
    }
    if (!part.tool || !FILE_TOOLS.includes(part.tool)) {
      continue;
    }
    for (const raw of pathsOfTool(part)) {
      const ref = toRef(raw);
      if (ref) {
        refs.push(ref);
      }
    }
  }
  return dedupeRefs(refs);
}

export function fileRefsFromText(text: string): FileRef[] {
  const refs: FileRef[] = [];
  for (const match of text.matchAll(HANDOFF_RE)) {
    const ref = toRef(match[1]);
    if (ref) {
      refs.push(ref);
    }
  }
  return dedupeRefs(refs);
}

export function fileRefsFromMessageParts(parts: Part[]): FileRef[] {
  const refs = fileRefsFromParts(parts);
  for (const part of parts) {
    if (part.type === 'text' && !part.synthetic && typeof part.text === 'string') {
      refs.push(...fileRefsFromText(part.text));
    }
  }
  return dedupeRefs(refs);
}

export function fileRefsFromMessages(messages: ReadonlyArray<{ parts: Part[] }>): FileRef[] {
  return dedupeRefs(messages.flatMap((m) => fileRefsFromMessageParts(m.parts)));
}
