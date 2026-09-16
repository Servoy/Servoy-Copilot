import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  input,
  viewChild
} from '@angular/core';

import { ChatMessage } from '../services/chat-store.service';
import { MessageItemComponent } from './message-item.component';

/**
 * Scrollable list of chat messages. Auto-scrolls to the bottom as new content
 * streams in (unless the user has scrolled up to read history).
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

  readonly scroll = viewChild.required<ElementRef<HTMLDivElement>>('scroll');

  private pinnedToBottom = true;

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
    // Runs after render whenever contentSignature changes (new/streamed
    // content), pinning to the bottom only when the user is already there.
    afterRenderEffect(() => {
      this.contentSignature();
      if (this.pinnedToBottom) {
        const el = this.scroll().nativeElement;
        el.scrollTop = el.scrollHeight;
      }
    });
  }

  onScroll(): void {
    const el = this.scroll().nativeElement;
    const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    this.pinnedToBottom = distanceFromBottom < 80;
  }

  messageKey(message: ChatMessage, index: number): string {
    return message.info.id ?? String(index);
  }
}
