import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { AgentViewReport } from '../services/agent-view';
import { MarkdownRendererComponent } from './markdown-renderer.component';

@Component({
  selector: 'svy-agent-report',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MarkdownRendererComponent],
  templateUrl: './agent-report.component.html',
  styleUrl: './agent-report.component.scss'
})
export class AgentReportComponent {
  readonly report = input.required<AgentViewReport>();

  readonly title = computed(() => {
    const r = this.report();
    if (r.needsDecision) {
      return 'Returned to Orchestrator - needs a decision';
    }
    return r.stopped ? 'Stopped' : 'Report to Orchestrator';
  });
}
