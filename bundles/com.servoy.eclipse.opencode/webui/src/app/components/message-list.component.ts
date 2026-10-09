import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  input,
  output,
  viewChild
} from '@angular/core';

import { ChatMessage } from '../services/chat-store.service';
import { MessageItemComponent } from './message-item.component';

/**
 * Scrollable list of chat messages. Auto-scrolls to the bottom as new content
 * streams in (unless the user has scrolled up to read history), and lazily
 * loads OLDER history when the user scrolls to the top - the transcript seeds
 * with only the newest page, so this is how earlier messages come in.
 */
@Component({
  selector: 'svy-message-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MessageItemComponent],
  templateUrl: './message-list.component.html',
  styleUrl: './message-list.component.scss'
})
export class MessageListComponent {
  readonly messages = input<ChatMessage[]>([]);
  /** Whether there is older history to load (drives the top-scroll trigger). */
  readonly canLoadOlder = input<boolean>(false);

  /** Bubbled up from a subagent tool row: the child session id to open. */
  readonly openSession = output<string>();
  /** Requested when the user scrolls near the top and older history exists. */
  readonly loadOlder = output<void>();

  readonly scroll = viewChild.required<ElementRef<HTMLDivElement>>('scroll');

  private pinnedToBottom = true;
  /** True once a load-older request has fired for the current top dwell. */
  private loadOlderRequested = false;
  /** Scroll height at the previous render, to anchor the view on a prepend. */
  private prevScrollHeight = 0;
  /** First message id at the previous render, to detect a prepend. */
  private prevFirstId: string | null = null;

  /** Distance from the top below which we treat the user as "at the top". */
  private static readonly TOP_TRIGGER_PX = 120;
  /** Distance from the bottom within which we keep auto-scrolling. */
  private static readonly BOTTOM_PIN_PX = 80;

  /**
   * A signature of the streamed content: message count plus the length of each
   * message's parts and their text. It changes when a message is added or a
   * part streams in, but NOT when the user expands a tool row - so auto-scroll
   * only fires for genuinely new content, never on a local UI toggle. Without
   * this, expanding a row at the bottom of the transcript scrolled it out of
   * view.
   */
  private readonly contentSignature = computed(() => {
    const msgs = this.messages();
    let sig = `${msgs.length}`;
    for (const m of msgs) {
      sig += `|${m.info.id}:${m.parts.length}`;
      for (const p of m.parts) {
        sig += `,${p.text?.length ?? 0}`;
      }
    }
    return sig;
  });

  constructor() {
    // Runs after render whenever the content changes. Two cases:
    // - pinned to the bottom (reading the latest): keep it stuck to the bottom
    //   as new content streams in.
    // - older history was prepended (the first message id changed and the
    //   content grew while NOT pinned): keep the user's reading position by
    //   nudging scrollTop down by exactly the height that was added at the top,
    //   so the view does not jump.
    afterRenderEffect(() => {
      this.contentSignature();
      const msgs = this.messages();
      const el = this.scroll().nativeElement;
      const newHeight = el.scrollHeight;
      const firstId = msgs[0]?.info.id ?? null;

      if (this.pinnedToBottom) {
        el.scrollTop = el.scrollHeight;
      } else if (
        this.prevFirstId !== null &&
        firstId !== this.prevFirstId &&
        newHeight > this.prevScrollHeight
      ) {
        el.scrollTop += newHeight - this.prevScrollHeight;
      }

      this.prevScrollHeight = newHeight;
      this.prevFirstId = firstId;
    });
  }

  onScroll(): void {
    const el = this.scroll().nativeElement;
    const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    this.pinnedToBottom = distanceFromBottom < MessageListComponent.BOTTOM_PIN_PX;

    // Load older history when the user reaches the top, once per top dwell.
    if (el.scrollTop <= MessageListComponent.TOP_TRIGGER_PX) {
      if (!this.loadOlderRequested && this.canLoadOlder()) {
        this.loadOlderRequested = true;
        this.loadOlder.emit();
      }
    } else if (el.scrollTop > MessageListComponent.TOP_TRIGGER_PX * 2) {
      // Scrolled away from the top; allow the next page to be requested.
      this.loadOlderRequested = false;
    }
  }

  messageKey(message: ChatMessage, index: number): string {
    return message.info.id ?? String(index);
  }
}
