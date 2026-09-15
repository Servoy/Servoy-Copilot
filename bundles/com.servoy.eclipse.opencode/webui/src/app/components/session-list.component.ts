import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { Session } from '../models/opencode.models';

/**
 * Panel listing existing sessions (title + relative time), a "New session"
 * action, and selection to switch the active session.
 */
@Component({
  selector: 'svy-session-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './session-list.component.html',
  styleUrl: './session-list.component.scss'
})
export class SessionListComponent {
  readonly sessions = input<Session[]>([]);
  readonly activeSessionId = input<string | null>(null);

  readonly select = output<string>();
  readonly create = output<void>();

  onSelect(id: string): void {
    this.select.emit(id);
  }

  onNew(): void {
    this.create.emit();
  }

  title(session: Session): string {
    return session.title && session.title.trim().length > 0 ? session.title : 'Untitled session';
  }

  relativeTime(session: Session): string {
    const updated = session.time?.updated ?? session.time?.created;
    if (!updated) {
      return '';
    }
    const diff = Date.now() - updated;
    const minutes = Math.round(diff / 60000);
    if (minutes < 1) {
      return 'just now';
    }
    if (minutes < 60) {
      return `${minutes}m ago`;
    }
    const hours = Math.round(minutes / 60);
    if (hours < 24) {
      return `${hours}h ago`;
    }
    const days = Math.round(hours / 24);
    return `${days}d ago`;
  }
}
