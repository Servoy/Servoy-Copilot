import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { MessageError, Part } from '../models/opencode.models';
import {
  isFilePart,
  isImageFilePart,
  isReasoningPart,
  isRenderablePart,
  isScriptTool,
  isSubagentTool,
  isTextPart,
  isToolExpandable,
  isToolPart,
  subagentSessionId,
  toolDisplayName,
  toolScript,
  toolSubtitle
} from '../services/part-utils';
import { MarkdownRendererComponent } from './markdown-renderer.component';

/**
 * Renders a single message's {@code Part[]}: text parts as markdown, reasoning
 * as a muted block, tool parts as a compact collapsed row. Synthetic /
 * system-reminder parts are filtered out.
 */
@Component({
  selector: 'svy-message-item',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MarkdownRendererComponent],
  templateUrl: './message-item.component.html',
  styleUrl: './message-item.component.scss'
})
export class MessageItemComponent {
  readonly role = input<string>('assistant');
  readonly parts = input<Part[]>([]);
  readonly error = input<MessageError | null>(null);
  /**
   * Epoch millis when the assistant turn finished, or null while it is still
   * running. Used to tell "still thinking" apart from "finished but empty".
   */
  readonly completed = input<number | null>(null);

  /**
   * Emitted when the user clicks a subagent tool row: the id of the child
   * session to open. The shell opens it (and the sidebar tree expands and
   * selects it), the same navigation the tree offers - so a subagent is
   * reachable straight from the transcript.
   */
  readonly openSession = output<string>();

  private readonly expanded = signal<Set<string>>(new Set());

  readonly renderable = computed(() => this.parts().filter(isRenderablePart));

  /** A human-readable message for a failed assistant turn, or null. */
  readonly errorText = computed<string | null>(() => {
    const err = this.error();
    if (!err) {
      return null;
    }
    const detail = err.data?.message?.trim();
    if (detail === 'No accounts') {
      return 'No AI account is connected. Sign in to a provider to start chatting.';
    }
    return detail || err.name || 'The assistant could not complete this turn.';
  });

  /** Whether the assistant turn has finished (a completion timestamp exists). */
  readonly isComplete = computed(() => this.completed() != null);

  /** True while an assistant turn is still running with nothing to show yet. */
  readonly isThinking = computed(
    () =>
      this.role() !== 'user' &&
      !this.isComplete() &&
      this.renderable().length === 0 &&
      !this.errorText()
  );

  /**
   * True only when the turn has finished, produced no renderable parts, and had
   * no error - the genuine "empty response" case (not the streaming case).
   */
  readonly isEmpty = computed(
    () => this.isComplete() && this.renderable().length === 0 && !this.errorText()
  );

  isText(part: Part): boolean {
    return isTextPart(part);
  }

  isReasoning(part: Part): boolean {
    return isReasoningPart(part);
  }

  isTool(part: Part): boolean {
    return isToolPart(part);
  }

  /** A file/image attachment part. */
  isFile(part: Part): boolean {
    return isFilePart(part);
  }

  /** Whether the attachment should render as an inline image thumbnail. */
  isImageFile(part: Part): boolean {
    return isImageFilePart(part);
  }

  /** The attachment's URL (a data: URL or a file reference). */
  fileUrl(part: Part): string {
    return part.url ?? '';
  }

  /** The attachment's display name, falling back to a generic label. */
  fileName(part: Part): string {
    return part.filename && part.filename.trim().length > 0 ? part.filename : 'attachment';
  }

  /** Friendly tool name, e.g. "Read File". */
  toolName(part: Part): string {
    return toolDisplayName(part);
  }

  /** Muted inline argument summary, e.g. a file path or command. */
  toolSubtitle(part: Part): string {
    return toolSubtitle(part);
  }

  /** Whether this tool part has output (or a script) that can be expanded. */
  canExpand(part: Part): boolean {
    return isToolExpandable(part);
  }

  /** True for the Code Mode {@code execute} ("Script") tool. */
  isScript(part: Part): boolean {
    return isScriptTool(part);
  }

  /**
   * True when this tool part is a subagent spawn whose child session can be
   * opened. The row still expands/collapses to show the subagent's output; a
   * separate "Open subagent" link below navigates into the child session.
   */
  isSubagentLink(part: Part): boolean {
    return isSubagentTool(part) && subagentSessionId(part) !== null;
  }

  /**
   * The label shown on the "Open subagent" link, e.g. the subagent's
   * description ("Build orders/details master-detail") when it has one, else a
   * generic "subagent". Mirrors OpenChamber's "Open &lt;name&gt; subtask".
   */
  subagentLabel(part: Part): string {
    const subtitle = toolSubtitle(part);
    return subtitle ? `${subtitle} subagent` : 'subagent';
  }

  /** Open the child session a subagent row links to. */
  openSubagent(part: Part): void {
    const id = subagentSessionId(part);
    if (id) {
      this.openSession.emit(id);
    }
  }

  /**
   * The script source of an {@code execute} part, wrapped as a fenced
   * TypeScript code block so the markdown renderer highlights it. Empty string
   * when there is no script.
   */
  scriptMarkdown(part: Part): string {
    const code = toolScript(part);
    return code ? '```ts\n' + code + '\n```' : '';
  }

  partKey(part: Part, index: number): string {
    return part.id ?? `${part.type}-${index}`;
  }

  toolOutput(part: Part): string {
    return part.state?.output ?? '';
  }

  isExpanded(part: Part, index: number): boolean {
    return this.expanded().has(this.partKey(part, index));
  }

  toggle(part: Part, index: number): void {
    if (!this.canExpand(part)) {
      return;
    }
    const key = this.partKey(part, index);
    const next = new Set(this.expanded());
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    this.expanded.set(next);
  }
}
