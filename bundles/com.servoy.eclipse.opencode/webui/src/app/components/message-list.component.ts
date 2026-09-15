import {
  AfterViewChecked,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
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
export class MessageListComponent implements AfterViewChecked {
  readonly messages = input<ChatMessage[]>([]);

  readonly scroll = viewChild.required<ElementRef<HTMLDivElement>>('scroll');

  private pinnedToBottom = true;

  ngAfterViewChecked(): void {
    if (this.pinnedToBottom) {
      const el = this.scroll().nativeElement;
      el.scrollTop = el.scrollHeight;
    }
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
