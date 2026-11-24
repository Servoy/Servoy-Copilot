import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  computed,
  inject,
  signal,
  viewChild
} from '@angular/core';

import { SendPart } from './models/opencode.models';
import { ChatStore, ComposerDraft } from './services/chat-store.service';
import { buildAgentView } from './services/agent-view';
import { debugEnabled } from './services/debug-log';
import { ThemeService } from './services/theme.service';
import { FormAnswer } from './models/opencode.models';
import { AgentFooterComponent } from './components/agent-footer.component';
import { AgentHeaderComponent } from './components/agent-header.component';
import { AgentViewComponent } from './components/agent-view.component';
import { ComposerComponent } from './components/composer.component';
import { DebugOverlayComponent } from './components/debug-overlay.component';
import { FormCardComponent } from './components/form-card.component';
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
  imports: [
    SessionListComponent,
    MessageListComponent,
    ComposerComponent,
    FormCardComponent,
    AgentViewComponent,
    AgentHeaderComponent,
    AgentFooterComponent,
    StatusPanelComponent,
    DebugOverlayComponent
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent implements OnInit {
  readonly store = inject(ChatStore);
  readonly themeService = inject(ThemeService);

  /**
   * Whether the status area is shown; the single source of truth for it. Docked and open on
   * wide viewports, an overlay that starts closed on narrow ones (see the media query in the SCSS).
   */
  readonly statusOpen = signal(AppComponent.matches('(min-width: 1080px)'));

  /**
   * Whether the sessions area is shown. Docked (and collapsible, pushing the chat) from 800px up,
   * an overlay that starts closed below that.
   */
  readonly sessionsOpen = signal(AppComponent.matches('(min-width: 800px)'));

  readonly restoreScroll = signal<{ top: number; pinned: boolean } | null>(null);

  private readonly messageList = viewChild(MessageListComponent);
  private readonly statusPanel = viewChild(StatusPanelComponent);
  private readonly statusButton = viewChild<ElementRef<HTMLButtonElement>>('statusButton');

  /** Overall state of the (always mounted) status panel, for the header dot. */
  readonly statusDot = computed(() => this.statusPanel()?.overall() ?? 'idle');

  /** Renders the debug overlay only when {@code ?debug_view=true}. */
  readonly debugEnabled = debugEnabled;

  private static matches(query: string): boolean {
    return typeof window.matchMedia !== 'function' || window.matchMedia(query).matches;
  }

  ngOnInit(): void {
    // Startup: open the most recent session for this directory, or a fresh draft.
    this.store.bootstrap();
  }

  toggleStatus(): void {
    const next = !this.statusOpen();
    this.statusOpen.set(next);
    if (next) {
      this.statusPanel()?.refresh();
    }
  }

  closeStatus(): void {
    this.statusOpen.set(false);
    this.statusButton()?.nativeElement.focus();
  }

  toggleSessions(): void {
    this.sessionsOpen.set(!this.sessionsOpen());
  }

  toggleTheme(): void {
    this.themeService.toggle();
  }

  onSelectSession(id: string): void {
    const activeId = this.store.activeSessionId();
    if (id !== activeId && activeId) {
      if (this.store.activeIsSubagent()) {
        this.restoreScroll.set(this.store.activeParent()?.id === id ? (this.store.takeScroll(id) ?? null) : null);
      } else {
        this.restoreScroll.set(null);
        const list = this.messageList();
        if (list && this.store.sessionParentId(id) === activeId) {
          this.store.saveScroll(activeId, list.getScrollState());
        }
      }
    }
    this.store.openSession(id);
    if (!AppComponent.matches('(min-width: 800px)')) {
      this.sessionsOpen.set(false);
    }
  }

  readonly agentName = computed(
    () => buildAgentView(this.store.messages(), this.store.activeSession(), this.store.activeChildSignals()).name
  );

  onBackToParent(): void {
    const parent = this.store.activeParent();
    if (parent) {
      this.onSelectSession(parent.id);
    }
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

  onDeleteSession(id: string): void {
    this.store.deleteSession(id);
  }

  onSend(parts: SendPart[]): void {
    this.store.send(parts);
  }

  onStop(): void {
    this.store.abort();
  }

  onDraftChange(draft: ComposerDraft): void {
    this.store.setActiveDraft(draft);
  }

  onLoadOlder(): void {
    this.store.loadOlderMessages();
  }

  onFormSubmit(answer: FormAnswer): void {
    this.store.submitForm(answer);
  }

  onFormCancel(): void {
    this.store.cancelPendingForm();
  }
}
