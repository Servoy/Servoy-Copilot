/**
 * Type definitions mirroring the subset of the opencode (V2, {@code @opencode/cli}
 * 2.x) HTTP API used by the Servoy AI chat UI.
 *
 * V2 notes that shape these types:
 * - Every REST response wraps its payload in an envelope: {@code { data }} or
 *   {@code { location, data }}. The API service unwraps it, so the models below
 *   describe the already-unwrapped values.
 * - A message is a discriminated union on {@code type} ({@code user},
 *   {@code assistant}, {@code idle}, ...) and carries its rendered blocks in a
 *   {@code content} array (V1 called these {@code parts} and split the message
 *   into {@code info} + {@code parts}). The store keeps working with a
 *   normalised {@link MessageWithParts} ({@code info} + {@code parts}); the API
 *   service maps each raw V2 message into that shape.
 *
 * These are intentionally loose - opencode evolves its schema and unknown fields
 * are tolerated.
 */

export interface SessionTime {
  created?: number;
  updated?: number;
  /** Set (to an epoch millis timestamp) when a session is archived. */
  archived?: number;
  /** Set (to an epoch millis timestamp) when an assistant message finished. */
  completed?: number;
  /** V2 assistant message streaming timestamp. */
  streamed?: number;
}

export interface Session {
  id: string;
  parentID?: string;
  title?: string;
  /** V2 nests the working directory under {@code location.directory}. */
  location?: { directory?: string; [key: string]: unknown };
  time?: SessionTime;
  [key: string]: unknown;
}

export type PartType =
  | 'text'
  | 'reasoning'
  | 'tool'
  | 'file'
  | 'step-start'
  | 'step-finish'
  | 'snapshot'
  | string;

/**
 * A single rendered block of a message. This is the normalised shape the UI
 * renders; the API service derives it from a V2 message {@code content} entry
 * (or from a streamed {@code message.part.updated} event).
 */
export interface Part {
  id?: string;
  messageID?: string;
  sessionID?: string;
  type: PartType;
  /** text / reasoning parts */
  text?: string;
  /** tool parts: the tool name (V2 field is {@code name}). */
  tool?: string;
  callID?: string;
  state?: ToolState;
  /** file parts */
  filename?: string;
  mime?: string;
  url?: string;
  source?: unknown;
  /** synthetic marker used by opencode for injected system reminders */
  synthetic?: boolean;
  [key: string]: unknown;
}

export interface ToolState {
  status?: 'pending' | 'running' | 'streaming' | 'completed' | 'error' | string;
  title?: string;
  input?: unknown;
  /**
   * Rendered tool output. V1 exposed this as a plain {@code output} string; V2
   * carries a {@code content} array of {@code { type, text }} blocks, which the
   * API service flattens into this string.
   */
  output?: string;
  [key: string]: unknown;
}

export type MessageRole = 'user' | 'assistant' | string;

/**
 * Error attached to an assistant message when the model turn failed. V2 surfaces
 * turn failure through the message {@code outcome}/{@code finish} fields and a
 * {@code session.error} event; this keeps the loose V1-compatible shape the UI
 * already renders.
 */
export interface MessageError {
  name?: string;
  data?: { message?: string; [key: string]: unknown };
  [key: string]: unknown;
}

export interface MessageInfo {
  id: string;
  sessionID?: string;
  role: MessageRole;
  time?: SessionTime;
  /** Set when the assistant turn errored out. */
  error?: MessageError;
  [key: string]: unknown;
}

/**
 * A message with its ordered parts - the normalised shape the store holds.
 * Derived by the API service from a raw V2 message (its {@code content} array
 * becomes {@code parts}, everything else becomes {@code info}).
 */
export interface MessageWithParts {
  info: MessageInfo;
  parts: Part[];
}

/** File search result (V2 {@code GET /fs/find} returns {@code { path, type }}). */
export interface FileMatch {
  path: string;
  /** V2 entry kind, e.g. {@code file} or {@code directory}. */
  type?: string;
}

/** A part to send with a prompt (retained for the composer/attachment API). */
export interface SendPart {
  type: 'text' | 'file';
  text?: string;
  filename?: string;
  mime?: string;
  url?: string;
}

// ---------------------------------------------------------------------------
// Interactive forms (opencode V2 Form flow)
// ---------------------------------------------------------------------------

/**
 * The opencode V2 interactive Form exchange, which replaced V1's {@code question}
 * tool. When an agent needs a decision it creates a Form; the server emits a
 * {@code form.created} bus event carrying a {@link FormInfo}, blocks the turn,
 * and waits for {@code POST /session/:id/form/:formID/reply}. {@code form.replied}
 * / {@code form.cancelled} events (and a 204 from reply) settle it.
 *
 * These types mirror the V2 OpenAPI {@code Form.*} schemas and the live-captured
 * question-form shape ({@code metadata.kind === 'question'}). Kept loose - the
 * server may add fields.
 */

/** A selectable option for a {@code string} / {@code multiselect} field. */
export interface FormOption {
  value: string;
  label: string;
  description?: string;
  [key: string]: unknown;
}

/**
 * A conditional-visibility clause: the field is shown only when the answer for
 * {@code key} satisfies {@code op} against {@code value}. A field's {@code when}
 * array is ANDed (all clauses must hold).
 */
export interface FormWhen {
  key: string;
  op: 'eq' | 'neq' | string;
  value: string | number | boolean;
  [key: string]: unknown;
}

/** Fields common to every non-{@code external} form field. */
export interface FormFieldBase {
  key: string;
  type: string;
  title?: string;
  description?: string;
  required?: boolean;
  hidden?: boolean;
  when?: FormWhen[];
  [key: string]: unknown;
}

export interface FormStringField extends FormFieldBase {
  type: 'string';
  format?: 'email' | 'uri' | 'date' | 'date-time' | string;
  minLength?: number;
  maxLength?: number;
  pattern?: string;
  placeholder?: string;
  default?: string;
  options?: FormOption[];
  /** Allow a free-text entry alongside {@code options}. */
  custom?: boolean;
}

export interface FormNumberField extends FormFieldBase {
  type: 'number' | 'integer';
  minimum?: number;
  maximum?: number;
  /** May serialize as the string sentinels {@code "Infinity"}/{@code "-Infinity"}/{@code "NaN"}. */
  default?: number | string;
}

export interface FormBooleanField extends FormFieldBase {
  type: 'boolean';
  default?: boolean;
}

export interface FormMultiselectField extends FormFieldBase {
  type: 'multiselect';
  options: FormOption[];
  minItems?: number;
  maxItems?: number;
  custom?: boolean;
  default?: string[];
}

/** An external link the user must acknowledge; carries no answer value. */
export interface FormExternalField extends FormFieldBase {
  type: 'external';
  url: string;
}

/** Discriminated union of all form field kinds. */
export type FormField =
  | FormStringField
  | FormNumberField
  | FormBooleanField
  | FormMultiselectField
  | FormExternalField;

/** A pending form as delivered by {@code form.created} / {@code GET .../form}. */
export interface FormInfo {
  id: string;
  sessionID: string;
  title: string;
  /** e.g. {@code { kind: 'question', tool: { messageID, id } }}. */
  metadata?: { kind?: string; tool?: { messageID?: string; id?: string }; [key: string]: unknown };
  fields: FormField[];
  [key: string]: unknown;
}

/** A single answered value. */
export type FormValue = string | number | boolean | string[];

/** The answer map, keyed by each field's {@code key}. */
export type FormAnswer = Record<string, FormValue>;

/** The reply body for {@code POST .../reply}. */
export interface FormReply {
  answer: FormAnswer;
}

// ---------------------------------------------------------------------------
// Status / health
// ---------------------------------------------------------------------------

/**
 * Health of the opencode server itself. V2 has no dedicated health route; the
 * status service derives this from {@code GET /info}, which returns the running
 * server {@code version} (its presence means the server is up).
 */
export interface HealthStatus {
  healthy: boolean;
  version?: string;
}

/** Connection status of a single MCP server. */
export type McpConnectionStatus =
  | 'connected'
  | 'disabled'
  | 'failed'
  | 'pending'
  | 'needs_auth'
  | 'needs_client_registration'
  | string;

/** A single MCP server's status entry. */
export interface McpStatus {
  status: McpConnectionStatus;
  /** Present for the {@code failed} / {@code needs_auth} states. */
  error?: string;
}

/** Map of MCP server name to its status (derived from V2 {@code GET /mcp}). */
export type McpStatusMap = Record<string, McpStatus>;

/**
 * Provider/model connection status. {@code connected} lists the provider ids
 * that are enabled/authenticated; {@code default} maps each provider id to its
 * default model id. Derived from V2 {@code GET /provider}, whose {@code data} is
 * an array of provider descriptors with an {@code activation} field.
 */
export interface ProviderStatus {
  connected: string[];
  default: Record<string, string>;
}
