import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { Part } from '../models/opencode.models';
import {
  isReasoningPart,
  isRenderablePart,
  isTextPart,
  isToolPart,
  toolLabel
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

  private readonly expanded = signal<Set<string>>(new Set());

  readonly renderable = computed(() => this.parts().filter(isRenderablePart));

  isText(part: Part): boolean {
    return isTextPart(part);
  }

  isReasoning(part: Part): boolean {
    return isReasoningPart(part);
  }

  isTool(part: Part): boolean {
    return isToolPart(part);
  }

  label(part: Part): string {
    return toolLabel(part);
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
