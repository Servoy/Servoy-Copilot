/**
 * Type definitions mirroring the subset of the opencode-cli HTTP API used by
 * the Servoy AI chat UI. These are intentionally loose - opencode evolves its
 * schema and the BFF proxies payloads verbatim, so unknown fields are tolerated.
 */

export interface SessionTime {
  created?: number;
  updated?: number;
}

export interface Session {
  id: string;
  parentID?: string;
  title?: string;
  directory?: string;
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

export interface Part {
  id?: string;
  messageID?: string;
  sessionID?: string;
  type: PartType;
  /** text / reasoning parts */
  text?: string;
  /** tool parts */
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
  status?: 'pending' | 'running' | 'completed' | 'error' | string;
  title?: string;
  input?: unknown;
  output?: string;
  [key: string]: unknown;
}

export type MessageRole = 'user' | 'assistant' | string;

export interface MessageInfo {
  id: string;
  sessionID?: string;
  role: MessageRole;
  time?: SessionTime;
  [key: string]: unknown;
}

/** A message with its ordered parts, as returned by GET /session/:id/message. */
export interface MessageWithParts {
  info: MessageInfo;
  parts: Part[];
}

/** File search result from GET /find/file. */
export interface FileMatch {
  path: string;
}

/** A part to send with a prompt. */
export interface SendPart {
  type: 'text' | 'file';
  text?: string;
  filename?: string;
  mime?: string;
  url?: string;
}

// ---------------------------------------------------------------------------
// Status / health (GET /global/health, GET /mcp, GET /provider)
// ---------------------------------------------------------------------------

/** Health of the opencode server itself (GET /global/health). */
export interface HealthStatus {
  healthy: boolean;
  version?: string;
}

/** Connection status of a single MCP server (values from GET /mcp). */
export type McpConnectionStatus =
  | 'connected'
  | 'disabled'
  | 'failed'
  | 'needs_auth'
  | 'needs_client_registration'
  | string;

/** A single MCP server's status entry (GET /mcp maps name -> this). */
export interface McpStatus {
  status: McpConnectionStatus;
  /** Present for the {@code failed} / {@code needs_client_registration} states. */
  error?: string;
}

/** GET /mcp response: a map of server name to its status. */
export type McpStatusMap = Record<string, McpStatus>;

/**
 * Provider/model connection status (GET /provider). {@code connected} lists the
 * provider ids that are authenticated; {@code default} maps each provider id to
 * its default model id. When {@code connected} is empty no model can be used
 * (this is the "No accounts" situation surfaced on assistant messages).
 */
export interface ProviderStatus {
  connected: string[];
  default: Record<string, string>;
}
