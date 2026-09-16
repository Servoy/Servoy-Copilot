import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';

import { SendPart } from './models/opencode.models';
import { ChatStore } from './services/chat-store.service';
import { ComposerComponent } from './components/composer.component';
import { MessageListComponent } from './components/message-list.component';
import { SessionListComponent } from './components/session-list.component';
import { StatusPanelComponent } from './components/status-panel.component';

/**
 * Root chat surface: a session list drawer beside a scrollable message list
 * above an auto-growing composer. All state lives in {@link ChatStore}, which
 * is seeded from the BFF and updated live from the event stream.
 */
@Component({
  selector: 'svy-root',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SessionListComponent, MessageListComponent, ComposerComponent, StatusPanelComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent implements OnInit {
  readonly store = inject(ChatStore);

  ngOnInit(): void {
    // Startup: open the most recent session for this directory, or a fresh draft.
    this.store.bootstrap();
  }

  onSelectSession(id: string): void {
    this.store.openSession(id);
  }

  onNewSession(): void {
    this.store.newSession();
  }

  onRenameSession(event: { id: string; title: string }): void {
    this.store.renameSession(event.id, event.title);
  }

  onExportSession(id: string): void {
    this.store.exportSession(id);
  }

  onArchiveSession(id: string): void {
    this.store.archiveSession(id);
  }

  onDeleteSession(id: string): void {
    this.store.deleteSession(id);
  }

  onSend(parts: SendPart[]): void {
    this.store.send(parts);
  }

  onStop(): void {
    this.store.abort();
  }
}
