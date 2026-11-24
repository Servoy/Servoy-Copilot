import { Part } from '../models/opencode.models';
import { subagentSessionId } from './part-utils';

export type AgentRunStatus = 'running' | 'done' | 'stopped';
export type AgentKind = 'new' | 'continued';
export type AgentReportStatus =
  | 'COMPLETED'
  | 'INPUT_REQUIRED'
  | 'BLOCKED'
  | 'INCOMPLETE'
  | 'FAILED'
  | 'REJECTED';
export type ChildOutcome = 'succeeded' | 'failed' | 'interrupted';
export type InterruptReason = 'user' | 'shutdown' | 'superseded' | 'inactivity';
export type BackgroundResultState = 'completed' | 'error' | 'cancelled' | 'running';

export interface ChildSignals {
  readonly busy?: boolean;
  readonly outcome?: ChildOutcome;
  readonly interruptedReason?: InterruptReason;
  readonly result?: { readonly state: BackgroundResultState; readonly body: string };
}

export interface AgentStatusContext {
  readonly child?: ChildSignals;
  readonly parentLive?: boolean;
}

export interface AgentStatus {
  readonly status: AgentRunStatus;
  readonly kind: AgentKind;
  readonly name: string;
  readonly label: string;
  readonly summary: string;
  readonly childSessionId: string | null;
  readonly report: AgentReportStatus | null;
}

const REPORT_VALUES: readonly AgentReportStatus[] = [
  'COMPLETED',
  'INPUT_REQUIRED',
  'BLOCKED',
  'INCOMPLETE',
  'FAILED',
  'REJECTED'
];
const STOP_REPORTS: readonly AgentReportStatus[] = [
  'INPUT_REQUIRED',
  'BLOCKED',
  'INCOMPLETE',
  'FAILED',
  'REJECTED'
];
const RESULT_STATES: readonly BackgroundResultState[] = ['completed', 'error', 'cancelled', 'running'];
const STATUS_LINE = /^\s*(?:[*_`>#\-\s]*)Status:\s*\**\s*([A-Z_]+)/i;
const ENVELOPE_LINE = /^\s*<\/?(?:background-result|subagent)\b[^>]*>\s*$/i;
const OUTSTANDING_HEADER = /^\s*Background work still running:/i;
const NO_TEXT = 'Subagent completed without a text response.';
const MAX_SUMMARY = 240;

function bodyLines(text: string | undefined): string[] {
  if (!text) {
    return [];
  }
  const lines = text.split(/\r?\n/).filter((l) => !ENVELOPE_LINE.test(l));
  const outstanding = lines.findIndex((l) => OUTSTANDING_HEADER.test(l));
  return outstanding >= 0 ? lines.slice(0, outstanding) : lines;
}

function statusLineIndex(lines: string[]): { index: number; value: AgentReportStatus } | null {
  for (let i = lines.length - 1; i >= 0; i--) {
    const match = STATUS_LINE.exec(lines[i]);
    if (match) {
      const value = match[1].toUpperCase() as AgentReportStatus;
      if (REPORT_VALUES.includes(value)) {
        return { index: i, value };
      }
    }
  }
  return null;
}

export function parseReportStatus(output: string | undefined): AgentReportStatus | null {
  return statusLineIndex(bodyLines(output))?.value ?? null;
}

export function parseBackgroundResult(
  text: string | undefined
): { childId: string; state: BackgroundResultState; body: string } | null {
  if (!text) {
    return null;
  }
  const trimmed = text.trim();
  const open = /^<background-result\b((?:[^>"]|"[^"]*")*)>/.exec(trimmed);
  if (!open) {
    return null;
  }
  const attrs = new Map<string, string>();
  for (const a of open[1].matchAll(/(\w+)="([^"]*)"/g)) {
    attrs.set(a[1], a[2]);
  }
  const kind = attrs.get('kind');
  const childId = attrs.get('sessionID');
  const state = attrs.get('state') as BackgroundResultState | undefined;
  if (kind !== 'subagent' || !childId || !state || !RESULT_STATES.includes(state)) {
    return null;
  }
  let body = trimmed.slice(open[0].length);
  const close = body.lastIndexOf('</background-result>');
  if (close >= 0) {
    body = body.slice(0, close);
  }
  return { childId, state, body: body.trim() };
}

function legacyResult(part: Part): { state: BackgroundResultState; body: string } | null {
  const output = part.state?.output;
  if (typeof output !== 'string') {
    return null;
  }
  const open = /<subagent\b([^>]*)>/.exec(output);
  if (!open) {
    return null;
  }
  const raw = /\bstate="([^"]*)"/.exec(open[1])?.[1];
  const state = RESULT_STATES.includes(raw as BackgroundResultState)
    ? (raw as BackgroundResultState)
    : 'completed';
  return { state, body: output };
}

function inputOf(part: Part): Record<string, unknown> {
  const input = part.state?.input;
  return input && typeof input === 'object' ? (input as Record<string, unknown>) : {};
}

function nonEmptyString(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null;
}

function agentName(part: Part): string {
  const input = inputOf(part);
  return (
    nonEmptyString(input['agent']) ??
    nonEmptyString(input['subagent_type']) ??
    nonEmptyString(input['name']) ??
    'Agent'
  );
}

function agentKind(part: Part): AgentKind {
  const input = inputOf(part);
  return nonEmptyString(input['sessionID']) || nonEmptyString(input['task_id']) ? 'continued' : 'new';
}

function oneLine(text: string): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  if (flat.length <= MAX_SUMMARY) {
    return flat;
  }
  const cut = flat.slice(0, MAX_SUMMARY - 1);
  const space = cut.lastIndexOf(' ');
  return (space > MAX_SUMMARY / 2 ? cut.slice(0, space) : cut).trimEnd() + '…';
}

function cleanLine(line: string): string {
  return line.replace(/^[\s#>*\-`]+/, '').replace(/[*`]+/g, '').trim();
}

function isContent(line: string): boolean {
  const cleaned = cleanLine(line);
  return cleaned.length > 0 && cleaned !== NO_TEXT;
}

const BOOTSTRAP_LINE = /^(?:turn\s+\d+\s*:\s*(?:loaded|skipped)\b|loaded\s*:)/i;
const HEADING_ONLY = /^(?:result|results|summary|report|findings|outcome)\s*:?$/i;

function isSummaryContent(line: string): boolean {
  if (!isContent(line)) {
    return false;
  }
  const cleaned = cleanLine(line);
  return !BOOTSTRAP_LINE.test(cleaned) && !HEADING_ONLY.test(cleaned);
}

function bodySummary(body: string): string {
  const lines = bodyLines(body);
  const status = statusLineIndex(lines);
  if (status) {
    const after = lines.slice(status.index + 1).find(isSummaryContent);
    if (after) {
      return oneLine(cleanLine(after));
    }
  }
  const first = lines.find((l, i) => isSummaryContent(l) && i !== status?.index);
  return first ? oneLine(cleanLine(first)) : '';
}

function firstLine(text: string): string {
  const line = text.split(/\r?\n/).find((l) => l.trim().length > 0);
  return line ? oneLine(line) : '';
}

function toolErrorText(part: Part): string {
  const error = part.state?.['error'];
  if (typeof error === 'string') {
    return firstLine(error);
  }
  if (error && typeof error === 'object') {
    const message = (error as Record<string, unknown>)['message'];
    return typeof message === 'string' ? firstLine(message) : '';
  }
  return '';
}

const INTERRUPT_SUMMARY: Record<InterruptReason, string> = {
  user: 'Stopped by user',
  shutdown: 'Interrupted by restart',
  superseded: 'Replaced by a newer prompt',
  inactivity: 'Stopped after inactivity'
};

type Via = 'busy' | 'toolError' | 'ended' | 'report' | 'launch' | 'stale' | 'done';

function labelOf(status: AgentRunStatus, report: AgentReportStatus | null): string {
  if (status === 'running') {
    return 'Running';
  }
  if (status === 'done') {
    return 'Done';
  }
  return report === 'INPUT_REQUIRED' ? 'Stopped — needs a decision' : 'Stopped';
}

interface CoreStatus {
  readonly status: AgentRunStatus;
  readonly via: Via;
  readonly report: AgentReportStatus | null;
}

function reportOutcome(body: string | undefined): CoreStatus {
  const report = parseReportStatus(body);
  const stopped = report !== null && STOP_REPORTS.includes(report);
  return { status: stopped ? 'stopped' : 'done', via: stopped ? 'report' : 'done', report };
}

function coreStatus(c: ChildSignals, reportBody?: string): CoreStatus | null {
  const resultEnded = c.result?.state === 'error' || c.result?.state === 'cancelled';
  const outcomeEnded = c.outcome === 'failed' || c.outcome === 'interrupted';
  if (c.busy === true) {
    return { status: 'running', via: 'busy', report: null };
  }
  if (resultEnded || outcomeEnded || c.interruptedReason !== undefined) {
    return { status: 'stopped', via: 'ended', report: null };
  }
  if (c.result?.state === 'completed' || c.result?.state === 'running' || c.outcome === 'succeeded') {
    return reportOutcome(c.result?.body ?? reportBody);
  }
  if (c.result === undefined && reportBody !== undefined && reportBody.trim().length > 0) {
    return reportOutcome(reportBody);
  }
  return null;
}

function endedSummary(c: ChildSignals): string {
  if (c.interruptedReason) {
    return INTERRUPT_SUMMARY[c.interruptedReason];
  }
  if (c.result?.state === 'cancelled') {
    return 'Cancelled';
  }
  if (c.result?.state === 'error' || c.outcome === 'failed') {
    return (c.result?.state === 'error' ? firstLine(c.result.body) : '') || 'Failed';
  }
  return 'Interrupted';
}

export interface ChildStatus {
  readonly status: AgentRunStatus;
  readonly label: string;
  readonly summary: string;
  readonly report: AgentReportStatus | null;
}

export function deriveChildStatus(
  child: ChildSignals | undefined,
  opts: { reportBody?: string } = {}
): ChildStatus {
  const c: ChildSignals = child ?? {};
  const core: CoreStatus = coreStatus(c, opts.reportBody) ?? { status: 'stopped', via: 'stale', report: null };
  let summary = '';
  if (core.via === 'done' || core.via === 'report') {
    summary = bodySummary(c.result?.body ?? opts.reportBody ?? '');
  } else if (core.via === 'ended') {
    summary = endedSummary(c);
  } else if (core.via === 'stale') {
    summary = 'Not running';
  }
  return {
    status: core.status,
    label: labelOf(core.status, core.report),
    summary: oneLine(summary),
    report: core.report
  };
}

export function reportBodyText(body: string | undefined): string {
  return bodyLines(body).join('\n').trim();
}

export function deriveAgentStatus(part: Part, ctx: AgentStatusContext = {}): AgentStatus {
  const tool = part.state?.status;
  const base = ctx.child ?? {};
  const result = base.result ?? legacyResult(part) ?? undefined;
  const c: ChildSignals = { ...base, result };
  const live = ctx.parentLive === true;
  const noSig =
    c.busy === undefined &&
    c.outcome === undefined &&
    c.result === undefined &&
    c.interruptedReason === undefined;
  const input = inputOf(part);
  const description = nonEmptyString(input['description']) ?? '';

  let status: AgentRunStatus;
  let via: Via;
  let report: AgentReportStatus | null = null;

  const nonTerminalTool =
    tool === undefined || tool === 'pending' || tool === 'running' || tool === 'streaming';

  const core = c.busy !== true && tool === 'error' ? null : coreStatus(c);

  if (core) {
    status = core.status;
    via = core.via;
    report = core.report;
  } else if (tool === 'error') {
    status = 'stopped';
    via = 'toolError';
  } else if ((nonTerminalTool || (tool === 'completed' && noSig)) && live) {
    status = 'running';
    via = 'launch';
  } else {
    status = 'stopped';
    via = 'stale';
  }

  let summary = '';
  if (status === 'running') {
    summary = nonEmptyString(part.state?.title) ?? description;
  } else if (via === 'done' || via === 'report') {
    summary = (c.result ? bodySummary(c.result.body) : '') || description;
  } else if (via === 'toolError') {
    summary = toolErrorText(part) || 'Failed to start';
  } else if (via === 'ended') {
    summary = endedSummary(c);
  } else {
    summary = 'Not running';
  }

  return {
    status,
    kind: agentKind(part),
    name: agentName(part),
    label: labelOf(status, report),
    summary: oneLine(summary),
    childSessionId: subagentSessionId(part),
    report
  };
}
