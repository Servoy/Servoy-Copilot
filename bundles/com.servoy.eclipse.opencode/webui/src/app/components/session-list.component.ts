import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild
} from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Session } from '../models/opencode.models';
import { SessionNode } from '../services/chat-store.service';
import { dbg, debugEnabled } from '../services/debug-log';

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
  private readonly injector = inject(Injector);

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
  /**
   * The anchor the open menu was requested from (the ⋯ button rectangle, or a
   * zero-size rect at the cursor for a right-click). Kept so the menu can be
   * re-positioned once its own size is known: it opens downward from the
   * anchor, but flips upward when there is not enough room below, and is
   * clamped horizontally into the viewport - the way a popup library would,
   * without pulling in a dependency.
   */
  private menuAnchor: { left: number; top: number; bottom: number } | null = null;

  readonly contextMenu = viewChild<ElementRef<HTMLDivElement>>('contextMenu');
  /** Id of the session currently being renamed inline, or null. */
  readonly renamingSessionId = signal<string | null>(null);
  /** Working copy of the title while renaming. */
  readonly renameText = signal('');

  readonly renameInput = viewChild<ElementRef<HTMLInputElement>>('renameInput');

  /** True when there are no top-level sessions to show. */
  readonly isEmpty = computed(() => this.tree().length === 0);

  constructor() {
    // DEBUG(title-timing): fires whenever the reactive tree the sidebar renders
    // actually changes. Compare its timestamp to the [store] applySessionUpdate
    // log: a large gap means the signal wrote but change detection ran late.
    if (debugEnabled) {
      effect(() => {
        const titles = this.tree()
          .map((n) => n.session.title)
          .slice(0, 3);
        dbg('sidebar', `tree effect titles=${JSON.stringify(titles)}`);
      });
    }
  }

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
    // A cursor is a zero-size anchor: the menu opens from the click point.
    this.openMenu(id, { left: event.clientX, top: event.clientY, bottom: event.clientY });
  }

  /** Open the menu from the 3-dots button, anchored under it. */
  onMenuButton(event: MouseEvent, id: string): void {
    event.preventDefault();
    event.stopPropagation();
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
    this.openMenu(id, { left: rect.right, top: rect.top, bottom: rect.bottom });
  }

  /**
   * Open the menu and schedule a one-shot re-position once it is in the DOM and
   * its real size is known. The initial position is the anchor's bottom-left
   * (menu opening downward); {@link positionMenu} then flips it above the anchor
   * when it would overflow the bottom of the viewport, and clamps it so it never
   * spills off the right or left edge - the common "last row" case where a
   * downward menu is cut off.
   */
  private openMenu(id: string, anchor: { left: number; top: number; bottom: number }): void {
    this.menuAnchor = anchor;
    this.menuPosition.set({ x: anchor.left, y: anchor.bottom });
    this.menuSessionId.set(id);
    afterNextRender(
      {
        read: () => this.positionMenu()
      },
      { injector: this.injector }
    );
  }

  /**
   * Clamp the open menu into the viewport. Measures the rendered menu and,
   * relative to the remembered anchor, flips it above when there is not enough
   * room below, and shifts it left when it would overflow the right edge, with
   * an 8px margin on every side. A no-op if the menu was closed before this ran.
   */
  private positionMenu(): void {
    const el = this.contextMenu()?.nativeElement;
    const anchor = this.menuAnchor;
    if (!el || !anchor) {
      return;
    }
    const MARGIN = 8;
    const { width, height } = el.getBoundingClientRect();
    const vw = window.innerWidth;
    const vh = window.innerHeight;

    // Vertical: open downward from the anchor's bottom, but flip to open upward
    // from its top when the menu would run past the bottom edge - unless there
    // is even less room above, in which case keep it below and let it clamp.
    let y = anchor.bottom;
    const fitsBelow = anchor.bottom + height + MARGIN <= vh;
    const roomAbove = anchor.top;
    if (!fitsBelow && roomAbove >= height + MARGIN) {
      y = anchor.top - height;
    }
    // Final vertical clamp so it is never off-screen either way.
    y = Math.min(Math.max(y, MARGIN), Math.max(MARGIN, vh - height - MARGIN));

    // Horizontal: keep the left edge at the anchor, but pull it in when the menu
    // would overflow the right edge, and never past the left margin.
    let x = anchor.left;
    if (x + width + MARGIN > vw) {
      x = vw - width - MARGIN;
    }
    x = Math.max(x, MARGIN);

    this.menuPosition.set({ x, y });
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
