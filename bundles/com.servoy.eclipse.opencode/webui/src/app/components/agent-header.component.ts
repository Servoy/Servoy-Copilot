import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

@Component({
  selector: 'svy-agent-header',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './agent-header.component.html',
  styleUrl: './agent-header.component.scss'
})
export class AgentHeaderComponent {
  readonly parentTitle = input<string | null>(null);
  readonly parentId = input<string | null>(null);
  readonly agentName = input<string>('Agent');

  readonly back = output<void>();

  readonly parentLabel = computed(() => this.parentTitle() || 'Parent session');
  readonly backLabel = computed(() => 'Back to parent session' + (this.parentTitle() ? ': ' + this.parentTitle() : ''));
}
