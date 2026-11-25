import { Part, Session } from '../models/opencode.models';
import {
  AgentRunStatus,
  ChildSignals,
  ChildStatus,
  deriveChildStatus,
  reportBodyText
} from './agent-status';
import { ChatMessage } from './chat-store.service';
import { isSyntheticPart, isTextPart } from './part-utils';

export interface AgentViewStep {
  readonly messageId: string;
  readonly part: Part;
  readonly kind: 'tool' | 'text';
  readonly running: boolean;
}

export interface AgentViewRound {
  readonly resume: string | null;
  readonly steps: AgentViewStep[];
  readonly stoppedAtEnd: boolean;
}

export interface AgentViewReport {
  readonly text: string;
  readonly status: AgentRunStatus;
  readonly label: string;
  readonly stopped: boolean;
  readonly needsDecision: boolean;
  readonly reason: string;
}

export interface AgentView {
  readonly name: string;
  readonly task: string | null;
  readonly rounds: AgentViewRound[];
  readonly report: AgentViewReport | null;
  readonly status: ChildStatus;
}

const NON_TERMINAL = ['pending', 'running', 'streaming'];
const TITLE_AGENT = /\(@([^)\s]+)\s+subagent\)/i;

interface RoundDraft {
  resume: string | null;
  messages: ChatMessage[];
}

function userText(message: ChatMessage): string | null {
  const texts = message.parts
    .filter((p) => p.type === 'text' && !isSyntheticPart(p) && typeof p.text === 'string' && p.text.trim())
    .map((p) => (p.text as string).trim());
  return texts.length > 0 ? texts.join('\n\n') : null;
}

function assistantText(message: ChatMessage): { parts: Part[]; text: string } {
  const parts = message.parts.filter((p) => isTextPart(p));
  return { parts, text: parts.map((p) => (p.text as string).trim()).join('\n\n') };
}

function nonEmpty(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null;
}

function resolveName(messages: ChatMessage[], session: Session | undefined): string {
  const firstAssistant = messages.find((m) => m.info.role === 'assistant');
  return (
    nonEmpty(firstAssistant?.info['agent']) ??
    nonEmpty(session?.['agent']) ??
    TITLE_AGENT.exec(session?.title ?? '')?.[1] ??
    'Agent'
  );
}

export function buildAgentView(
  messages: ChatMessage[],
  session: Session | undefined,
  child: ChildSignals | undefined
): AgentView {
  let task: string | null = null;
  let taskSeen = false;
  const drafts: RoundDraft[] = [{ resume: null, messages: [] }];

  for (const message of messages) {
    if (message.info.role === 'user') {
      const text = userText(message);
      if (text === null) {
        continue;
      }
      if (!taskSeen) {
        taskSeen = true;
        task = text;
      } else {
        drafts.push({ resume: text, messages: [] });
      }
    } else if (message.info.role === 'assistant') {
      drafts[drafts.length - 1].messages.push(message);
    }
  }

  const lastDraft = drafts[drafts.length - 1];
  const lastAssistant = lastDraft.messages[lastDraft.messages.length - 1];
  const lastText = lastAssistant ? assistantText(lastAssistant) : { parts: [], text: '' };
  const resultBody = child?.result?.body ? reportBodyText(child.result.body) : '';
  const reportSource = lastText.text || resultBody;

  const status = deriveChildStatus(child, reportSource ? { reportBody: reportSource } : {});
  const running = status.status === 'running';
  const reportText = running ? '' : reportSource;
  const hoisted = new Set<Part>(!running && lastText.text ? lastText.parts : []);

  const rounds: AgentViewRound[] = drafts.map((draft, index) => {
    const steps: AgentViewStep[] = [];
    for (const message of draft.messages) {
      const messageId = message.info.id;
      for (const part of message.parts) {
        if (hoisted.has(part)) {
          continue;
        }
        if (part.type === 'tool') {
          const toolStatus = part.state?.status;
          steps.push({
            messageId,
            part,
            kind: 'tool',
            running: running && typeof toolStatus === 'string' && NON_TERMINAL.includes(toolStatus)
          });
        } else if (part.type === 'text' && isTextPart(part)) {
          steps.push({ messageId, part, kind: 'text', running: false });
        }
      }
    }
    return { resume: draft.resume, steps, stoppedAtEnd: index < drafts.length - 1 };
  });

  const stopped = status.status === 'stopped';
  let report: AgentViewReport | null = null;
  const normalizedText = reportText.replace(/\s+/g, ' ').toLowerCase();
  const reason =
    reportText && normalizedText.includes(status.summary.replace(/\s+/g, ' ').toLowerCase())
      ? ''
      : status.summary;
  if (!running && (reportText || stopped)) {
    report = {
      text: reportText,
      status: status.status,
      label: status.label,
      stopped,
      needsDecision: status.report === 'INPUT_REQUIRED',
      reason
    };
  }

  return { name: resolveName(messages, session), task, rounds, report, status };
}
