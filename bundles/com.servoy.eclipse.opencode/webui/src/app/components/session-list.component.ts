import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  input,
  output,
  signal,
  viewChild
} from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Session } from '../models/opencode.models';
import { SessionNode } from '../services/chat-store.service';

/** A rename request carried out of the list. */
export interface SessionRename {
  id: string;
  title: string;
}

/** Position of an open context menu, in viewport pixels. */
interface MenuPosition {
  x: number;
  y: number;
}

/**
 * Panel listing sessions as a tree: top-level sessions, each expandable to show
 * its subagent (child) sessions. A "New session" action and selection to switch
 * the active session. Each row has a context menu (right-click / 3-dots) with
 * Rename, Archive and Delete.
 */
@Component({
  selector: 'svy-session-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  templateUrl: './session-list.component.html',
  styleUrl: './session-list.component.scss'
})
export class SessionListComponent {
  readonly tree = input<SessionNode[]>([]);
  readonly activeSessionId = input<string | null>(null);

  readonly select = output<string>();
  readonly create = output<void>();
  readonly rename = output<SessionRename>();
  readonly export = output<string>();
  readonly archive = output<string>();
  readonly remove = output<string>();

  /** Ids of expanded top-level sessions (their subagent children are shown). */
  readonly expandedIds = signal<Set<string>>(new Set());

  /** Id of the session whose context menu is open, or null when none is. */
  readonly menuSessionId = signal<string | null>(null);
  /** Viewport position for the open menu. */
  readonly menuPosition = signal<MenuPosition>({ x: 0, y: 0 });
  /** Id of the session currently being renamed inline, or null. */
  readonly renamingSessionId = signal<string | null>(null);
  /** Working copy of the title while renaming. */
  readonly renameText = signal('');

  readonly renameInput = viewChild<ElementRef<HTMLInputElement>>('renameInput');

  /** True when there are no top-level sessions to show. */
  readonly isEmpty = computed(() => this.tree().length === 0);

  onSelect(id: string): void {
    if (this.renamingSessionId() === id) {
      return;
    }
    this.select.emit(id);
  }

  onNew(): void {
    this.create.emit();
  }

  isExpanded(id: string): boolean {
    return this.expandedIds().has(id);
  }

  toggleExpand(event: MouseEvent, id: string): void {
    event.stopPropagation();
    const next = new Set(this.expandedIds());
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    this.expandedIds.set(next);
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

  // -----------------------------------------------------------------------
  // Context menu
  // -----------------------------------------------------------------------

  /** Open the menu from a right-click, anchored at the cursor. */
  onContextMenu(event: MouseEvent, id: string): void {
    event.preventDefault();
    this.openMenu(id, event.clientX, event.clientY);
  }

  /** Open the menu from the 3-dots button, anchored under it. */
  onMenuButton(event: MouseEvent, id: string): void {
    event.preventDefault();
    event.stopPropagation();
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
    this.openMenu(id, rect.right, rect.bottom);
  }

  private openMenu(id: string, x: number, y: number): void {
    this.menuPosition.set({ x, y });
    this.menuSessionId.set(id);
  }

  closeMenu(): void {
    this.menuSessionId.set(null);
  }

  onRename(id: string): void {
    const session = this.findSession(id);
    this.renameText.set(session?.title?.trim() ?? '');
    this.renamingSessionId.set(id);
    this.closeMenu();
    queueMicrotask(() => {
      const el = this.renameInput()?.nativeElement;
      if (el) {
        el.focus();
        el.select();
      }
    });
  }

  commitRename(id: string): void {
    const title = this.renameText().trim();
    this.renamingSessionId.set(null);
    if (title) {
      this.rename.emit({ id, title });
    }
  }

  cancelRename(): void {
    this.renamingSessionId.set(null);
  }

  onRenameKeydown(event: KeyboardEvent, id: string): void {
    if (event.key === 'Enter') {
      event.preventDefault();
      this.commitRename(id);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      this.cancelRename();
    }
  }

  onExport(id: string): void {
    this.closeMenu();
    this.export.emit(id);
  }

  onArchive(id: string): void {
    this.closeMenu();
    this.archive.emit(id);
  }

  onDelete(id: string): void {
    this.closeMenu();
    this.remove.emit(id);
  }

  private findSession(id: string): Session | undefined {
    for (const node of this.tree()) {
      if (node.session.id === id) {
        return node.session;
      }
      const child = node.children.find((c) => c.id === id);
      if (child) {
        return child;
      }
    }
    return undefined;
  }
}
