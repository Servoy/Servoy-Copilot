import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
  type ElementRef
} from '@angular/core';

import { MessageError, Part } from '../models/opencode.models';
import {
  isFilePart,
  isImageFilePart,
  hasReasoningType,
  isRenderablePart,
  isScriptTool,
  isSubagentTool,
  isTextPart,
  isToolExpandable,
  isToolPart,
  lastActivityPart,
  subagentSessionId,
  toolDisplayName,
  toolScript,
  toolSubtitle
} from '../services/part-utils';
import { AgentStatus, ChildSignals, deriveAgentStatus } from '../services/agent-status';
import { FileRefsService } from '../services/file-refs.service';
import { AgentCardComponent } from './agent-card.component';
import { FileChipComponent } from './file-chip.component';
import { MarkdownRendererComponent } from './markdown-renderer.component';
import { ThinkingIndicatorComponent } from './thinking-indicator.component';

/**
 * Renders a single message's {@code Part[]}: text parts as markdown and tool parts as a
 * compact collapsed row. Reasoning is not shown; while the model is working a short
 * "Thinking..." line stands in for it. Synthetic / system-reminder parts are filtered out.
 */
@Component({
  selector: 'svy-message-item',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MarkdownRendererComponent, AgentCardComponent, FileChipComponent, ThinkingIndicatorComponent],
  host: { '[class.nothing-to-show]': 'nothingToShow()' },
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
  /** Per child session signals (busy/outcome/result) for the agent cards. */
  readonly childSignals = input<ReadonlyMap<string, ChildSignals>>(new Map());
  /** Whether the active session has a turn running. */
  readonly turnBusy = input<boolean>(false);

  /** Only the in-progress assistant message of a busy session counts as live. */
  readonly parentLive = computed(() => this.turnBusy() && this.completed() == null);

  /**
   * Emitted when the user clicks a subagent tool row: the id of the child
   * session to open. The shell opens it (and the sidebar tree expands and
   * selects it), the same navigation the tree offers - so a subagent is
   * reachable straight from the transcript.
   */
  readonly openSession = output<string>();

  private readonly expanded = signal<Set<string>>(new Set());

  /** The image currently shown enlarged in the lightbox, or null when closed. */
  private readonly lightbox = signal<{ url: string; alt: string } | null>(null);

  /** The open image for the template (null = lightbox closed). */
  readonly lightboxImage = computed(() => this.lightbox());

  /** The overlay element, present only while the lightbox is open. */
  private readonly lightboxEl = viewChild<ElementRef<HTMLElement>>('lightboxEl');

  /** The thumbnail that opened the lightbox, to return focus to on close. */
  private lastTrigger: HTMLElement | null = null;

  constructor() {
    // Move focus into the overlay once it is in the DOM so Escape works and
    // focus is not left behind on the now-hidden thumbnail. Signal-driven, so
    // it runs without any zone.js reliance (zoneless-safe).
    effect(() => {
      const el = this.lightboxEl()?.nativeElement;
      if (el) {
        el.focus();
      }
    });
  }

  readonly renderable = computed(() => this.parts().filter(isRenderablePart));

  /** False in the agent view, which shows its own chips in the task block. */
  readonly showFiles = input<boolean>(true);

  private readonly fileRefsService = inject(FileRefsService);
  private readonly showAllFiles = signal(false);

  private readonly fileRefs = computed(() =>
    this.role() === 'user' || !this.showFiles()
      ? []
      : this.fileRefsService.refsForParts(this.parts(), this.childSignals())
  );

  readonly visibleFiles = computed(() =>
    this.showAllFiles() ? this.fileRefs() : this.fileRefs().slice(0, MessageItemComponent.MAX_CHIPS)
  );

  readonly hiddenFileCount = computed(() => this.fileRefs().length - this.visibleFiles().length);
  readonly hasFiles = computed(() => this.fileRefs().length > 0);

  private static readonly MAX_CHIPS = 6;

  revealFiles(): void {
    this.showAllFiles.set(true);
  }

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

  /**
   * False for an older message of the transcript: only the newest message can be the one the
   * model is working on, so a stale unfinished one (e.g. from an aborted turn) never shows
   * the "Thinking..." line.
   */
  readonly latest = input<boolean>(true);

  /** True when the message has nothing to show (e.g. only hidden reasoning): the host collapses. */
  readonly nothingToShow = computed(
    () =>
      this.renderable().length === 0 &&
      !this.isThinking() &&
      !this.isEmpty() &&
      !this.errorText() &&
      !this.hasFiles()
  );

  /**
   * True while the model is working on this message and nothing newer than its reasoning is
   * visible: no parts yet, or the last thing it did was think. It goes away as soon as an
   * answer or a tool row appears (they already show the model is working), when the turn
   * completes, fails or is stopped (the session is no longer busy), and never shows on a
   * message that is not the newest.
   */
  readonly isThinking = computed(() => {
    if (this.role() === 'user' || this.isComplete() || this.errorText() || !this.turnBusy() || !this.latest()) {
      return false;
    }
    const last = lastActivityPart(this.parts());
    return last === undefined || last.type === 'reasoning';
  });

  /**
   * True only when the turn has finished, produced nothing visible (and no reasoning, which is
   * an intermediate step rather than a missing answer), and had no error - the genuine "empty
   * response" case (not the streaming case).
   */
  readonly isEmpty = computed(
    () =>
      this.isComplete() &&
      this.renderable().length === 0 &&
      !hasReasoningType(this.parts()) &&
      !this.errorText()
  );

  isText(part: Part): boolean {
    return isTextPart(part);
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

  /**
   * Open the lightbox for an image attachment part, enlarging it in an
   * in-document overlay instead of opening a new OS window. Remembers the
   * triggering thumbnail so focus can return to it on close.
   */
  openLightbox(part: Part, event?: Event): void {
    const trigger = event?.currentTarget;
    this.lastTrigger = trigger instanceof HTMLElement ? trigger : null;
    this.lightbox.set({ url: this.fileUrl(part), alt: this.fileName(part) });
  }

  /** Close the lightbox, returning focus to the triggering thumbnail if possible. */
  closeLightbox(): void {
    this.lightbox.set(null);
    const trigger = this.lastTrigger;
    this.lastTrigger = null;
    // Best-effort focus-return; harmless if the element is gone.
    trigger?.focus?.();
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

  /** True for a subagent spawn, rendered as an agent card. */
  isSubagent(part: Part): boolean {
    return isSubagentTool(part);
  }

  /** Live status of a subagent part, derived from the child session's signals. */
  agentOf(part: Part): AgentStatus {
    const childId = subagentSessionId(part);
    return deriveAgentStatus(part, {
      child: childId ? this.childSignals().get(childId) : undefined,
      parentLive: this.parentLive()
    });
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
