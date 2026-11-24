import type { Session } from '../models/opencode.models';
import { AgentRunStatus, ChildSignals, deriveChildStatus } from './agent-status';
import type { SessionNode } from './chat-store.service';

export type SessionRunStatus = 'needs-answer' | 'running' | 'idle';
export type AgentRowStatus = AgentRunStatus;

function anyOf(node: SessionNode, ids: ReadonlySet<string>): boolean {
  return ids.has(node.session.id) || node.children.some((c) => ids.has(c.id));
}

export function sessionStatus(
  node: SessionNode,
  busy: ReadonlySet<string>,
  pendingForms: ReadonlySet<string>
): SessionRunStatus {
  if (anyOf(node, pendingForms)) {
    return 'needs-answer';
  }
  return anyOf(node, busy) ? 'running' : 'idle';
}

export function agentRowStatus(signals: ChildSignals | undefined): AgentRowStatus | null {
  if (
    !signals ||
    (signals.busy === undefined &&
      signals.outcome === undefined &&
      signals.result === undefined &&
      signals.interruptedReason === undefined)
  ) {
    return null;
  }
  return deriveChildStatus(signals).status;
}

export function agentName(child: Session): string {
  const agent = child['agent'];
  if (typeof agent === 'string' && agent.trim().length > 0) {
    return agent.trim();
  }
  const title = child.title?.trim();
  return title ? title : 'Agent';
}
