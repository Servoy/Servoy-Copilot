import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'svy-agent-resume-divider',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './agent-resume-divider.component.html',
  styleUrl: './agent-resume-divider.component.scss'
})
export class AgentResumeDividerComponent {
  readonly answer = input.required<string>();
}
