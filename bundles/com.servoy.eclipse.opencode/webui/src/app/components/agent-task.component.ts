import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { MarkdownRendererComponent } from './markdown-renderer.component';

export interface AgentFile {
  name: string;
  path?: string;
}

export function isLongTask(text: string): boolean {
  return text.length > 600 || text.split(/\r?\n/).length > 8;
}

@Component({
  selector: 'svy-agent-task',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MarkdownRendererComponent],
  templateUrl: './agent-task.component.html',
  styleUrl: './agent-task.component.scss'
})
export class AgentTaskComponent {
  readonly task = input.required<string>();
  readonly files = input<AgentFile[]>([]);

  readonly expanded = signal(false);
  readonly long = computed(() => isLongTask(this.task()));

  toggle(): void {
    this.expanded.set(!this.expanded());
  }
}
