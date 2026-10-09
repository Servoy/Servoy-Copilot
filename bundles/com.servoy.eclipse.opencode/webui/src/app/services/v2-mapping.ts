import { MessageInfo, MessageWithParts, Part, ToolState } from '../models/opencode.models';

/**
 * Maps a raw opencode V2 message into the normalised {@link MessageWithParts}
 * shape the store and renderer use.
 *
 * V2 changed the message shape from V1's {@code { info, parts }} pair to a
 * single object that is a discriminated union on {@code type} and carries its
 * rendered blocks inline in a {@code content} array:
 *
 * ```jsonc
 * // assistant
 * { "id":"msg_..", "type":"assistant", "role" derived from type,
 *   "content":[ {"type":"reasoning","text":".."},
 *               {"type":"text","text":".."},
 *               {"type":"tool","name":"read","state":{"status":"completed","input":{..},"content":[{"type":"text","text":".."}]}} ],
 *   "time":{..} }
 * // user
 * { "id":"msg_..", "type":"user", "text":"hi", "time":{..} }
 * // idle / system markers carry no renderable content
 * ```
 *
 * The mapping:
 * - derives {@code info.role} from the message {@code type}
 *   ({@code assistant} -> assistant, everything else -> its own role, with
 *   {@code user} preserved);
 * - turns a {@code user} message's top-level {@code text} into a single text
 *   part (V2 does not put it in {@code content});
 * - copies each {@code content} entry into a {@link Part}, mapping the V2 tool
 *   fields ({@code name} -> {@code tool}) and flattening a tool state's
 *   {@code content} block array into a single {@code output} string so the
 *   existing renderer keeps working;
 * - stamps each part with the message + session id so the store's part-keyed
 *   upsert logic behaves the same for seeded and streamed parts.
 */
export function mapV2Message(raw: unknown): MessageWithParts {
  const m = (raw ?? {}) as Record<string, unknown>;
  const type = typeof m['type'] === 'string' ? (m['type'] as string) : 'assistant';
  const id = typeof m['id'] === 'string' ? (m['id'] as string) : '';
  const sessionID = typeof m['sessionID'] === 'string' ? (m['sessionID'] as string) : undefined;

  const info: MessageInfo = {
    ...(m as object),
    id,
    sessionID,
    role: type === 'assistant' ? 'assistant' : type
  } as MessageInfo;

  const parts: Part[] = [];

  // A V2 user message carries its prompt directly on `text`, not in `content`.
  if (type === 'user' && typeof m['text'] === 'string' && (m['text'] as string).length > 0) {
    parts.push({ type: 'text', text: m['text'] as string, messageID: id, sessionID });
  }

  // A V2 user message's attachments come back as a top-level `files` array
  // (NOT in `content`): each entry is { data(base64, no prefix), mime, name,
  // source }. Normalise them into file parts so the transcript renderer shows
  // them the same way as a just-sent (optimistic) attachment - otherwise an
  // uploaded image is invisible after a reload.
  const files = Array.isArray(m['files']) ? (m['files'] as unknown[]) : [];
  files.forEach((entry, index) => {
    const part = mapV2FilePart(entry, id, sessionID, index);
    if (part) {
      parts.push(part);
    }
  });

  const content = Array.isArray(m['content']) ? (m['content'] as unknown[]) : [];
  content.forEach((entry, index) => {
    const part = mapV2ContentPart(entry, id, sessionID, index);
    if (part) {
      parts.push(part);
    }
  });

  return { info, parts };
}

/**
 * Maps a V2 user-message attachment ({@code message.files[]} entry) into a file
 * {@link Part}. The entry is {@code { data(base64, no data: prefix), mime, name,
 * source }}; the content becomes a {@code data:} URL under {@code url} so the
 * renderer can show an inline image, with the mime and filename alongside. An
 * entry that carries a {@code source.uri}/{@code url} instead of inline data
 * uses that as the url. Returns {@code null} when there is nothing to show.
 */
export function mapV2FilePart(
  entry: unknown,
  messageID: string,
  sessionID: string | undefined,
  index: number
): Part | null {
  if (!entry || typeof entry !== 'object') {
    return null;
  }
  const f = entry as Record<string, unknown>;
  const mime = typeof f['mime'] === 'string' ? (f['mime'] as string) : undefined;
  const name = typeof f['name'] === 'string' ? (f['name'] as string) : undefined;
  const data = typeof f['data'] === 'string' ? (f['data'] as string) : undefined;
  const source = f['source'] as { uri?: string } | undefined;
  let url: string | undefined;
  if (data && mime) {
    url = `data:${mime};base64,${data}`;
  } else if (typeof f['url'] === 'string') {
    url = f['url'] as string;
  } else if (typeof source?.uri === 'string') {
    url = source.uri;
  }
  if (!url) {
    return null;
  }
  return { id: `${messageID}#file-${index}`, type: 'file', messageID, sessionID, filename: name, mime, url };
}

/**
 * Maps a single V2 message {@code content} entry (or a streamed
 * {@code message.part.updated} part) into a {@link Part}.
 *
 * V2 content parts often lack a stable {@code id}; a synthetic
 * {@code <messageID>#<index>} id is assigned so the store's id-keyed
 * {@code upsertPart} can merge streamed updates for the same block instead of
 * appending duplicates.
 */
export function mapV2ContentPart(
  entry: unknown,
  messageID: string,
  sessionID: string | undefined,
  index: number
): Part | null {
  if (!entry || typeof entry !== 'object') {
    return null;
  }
  const c = entry as Record<string, unknown>;
  const type = typeof c['type'] === 'string' ? (c['type'] as string) : '';
  if (!type) {
    return null;
  }

  const part: Part = {
    ...(c as object),
    type,
    messageID: (c['messageID'] as string | undefined) ?? messageID,
    sessionID: (c['sessionID'] as string | undefined) ?? sessionID,
    id: (c['id'] as string | undefined) ?? `${messageID}#${index}`
  } as Part;

  if (type === 'tool') {
    // V2 names the tool in `name`; the renderer reads `tool`.
    if (typeof c['name'] === 'string') {
      part.tool = c['name'] as string;
    }
    part.state = normalizeToolState(c['state']);
  }

  return part;
}

/**
 * Normalises a V2 tool state. V2 replaced V1's plain {@code output} string with
 * a {@code content} array of {@code { type:'text', text }} blocks; this
 * flattens those into a single {@code output} string while preserving the rest
 * of the state ({@code status}, {@code input}, {@code title}, ...).
 */
function normalizeToolState(rawState: unknown): ToolState | undefined {
  if (!rawState || typeof rawState !== 'object') {
    return rawState as ToolState | undefined;
  }
  const s = rawState as Record<string, unknown>;
  const state: ToolState = { ...(s as object) } as ToolState;

  if (state.output == null && Array.isArray(s['content'])) {
    const text = (s['content'] as unknown[])
      .map((block) => {
        if (block && typeof block === 'object' && typeof (block as Record<string, unknown>)['text'] === 'string') {
          return (block as Record<string, unknown>)['text'] as string;
        }
        return '';
      })
      .filter((t) => t.length > 0)
      .join('\n');
    if (text.length > 0) {
      state.output = text;
    }
  }
  return state;
}
