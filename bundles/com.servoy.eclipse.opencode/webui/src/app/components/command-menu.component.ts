import { ChangeDetectionStrategy, Component, ElementRef, effect, inject, input, output } from '@angular/core';

import { SvyCommand } from '../services/svy-commands';

@Component({
  selector: 'svy-command-menu',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './command-menu.component.html',
  styleUrl: './command-menu.component.scss'
})
export class CommandMenuComponent {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly items = input<SvyCommand[]>([]);
  readonly activeIndex = input(0);
  readonly idPrefix = input('svy-cmd');

  readonly pick = output<SvyCommand>();
  readonly hover = output<number>();

  constructor() {
    effect(() => {
      const index = this.activeIndex();
      const el = this.host.nativeElement.querySelector<HTMLElement>(`[data-index="${index}"]`);
      if (el && typeof el.scrollIntoView === 'function') {
        el.scrollIntoView({ block: 'nearest' });
      }
    });
  }

  onMouseDown(event: MouseEvent, command: SvyCommand): void {
    event.preventDefault();
    this.pick.emit(command);
  }

  onMouseEnter(index: number): void {
    this.hover.emit(index);
  }
}
