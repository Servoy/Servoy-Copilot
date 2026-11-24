import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

@Component({
  selector: 'svy-agent-footer',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './agent-footer.component.html',
  styleUrl: './agent-footer.component.scss'
})
export class AgentFooterComponent {
  readonly canOpenParent = input<boolean>(false);

  readonly openParent = output<void>();
}
