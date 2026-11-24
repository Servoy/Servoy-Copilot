import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { AgentStatus } from '../services/agent-status';

/**
 * Card for a subagent spawn: name, tag, live status, one-line summary and an
 * Open button that emits the child session id.
 */
@Component({
  selector: 'svy-agent-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './agent-card.component.html',
  styleUrl: './agent-card.component.scss'
})
export class AgentCardComponent {
  readonly agent = input.required<AgentStatus>();
  readonly open = output<string>();

  readonly tag = computed(() => (this.agent().kind === 'continued' ? 'continued' : 'new session'));
  readonly groupLabel = computed(() => {
    const a = this.agent();
    return `${a.name} agent, ${this.tag()}, ${a.label}`;
  });

  onOpen(): void {
    const id = this.agent().childSessionId;
    if (id) {
      this.open.emit(id);
    }
  }
}
