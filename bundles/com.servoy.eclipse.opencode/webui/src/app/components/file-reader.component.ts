import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  viewChild
} from '@angular/core';

import { FileReaderService, FileReaderStatus } from '../services/file-reader.service';
import { MarkdownRendererComponent } from './markdown-renderer.component';

const MESSAGES: Record<Exclude<FileReaderStatus, 'loading' | 'ready'>, string> = {
  'not-found': 'This file is no longer in the workspace.',
  forbidden: 'This file cannot be opened.',
  binary: 'This file is not a text file and cannot be shown.',
  'too-large': 'This file is too large to show here (limit 2 MB).',
  error: 'The file could not be read.'
};

const FOCUSABLE = 'button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])';

@Component({
  selector: 'svy-file-reader',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MarkdownRendererComponent],
  templateUrl: './file-reader.component.html',
  styleUrl: './file-reader.component.scss',
})
export class FileReaderComponent {
  private readonly reader = inject(FileReaderService);

  readonly state = this.reader.state;
  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');
  private readonly closeButton = viewChild<ElementRef<HTMLButtonElement>>('closeButton');

  readonly displayPath = computed(() => {
    const s = this.state();
    return s.file?.path ?? s.path ?? '';
  });

  readonly displayName = computed(() => {
    const s = this.state();
    if (s.file?.name) {
      return s.file.name;
    }
    const path = s.path ?? '';
    return path.split(/[\\/]/).filter(Boolean).pop() ?? path;
  });

  readonly isMarkdown = computed(() => /\.(md|markdown)$/i.test(this.displayName()));

  readonly message = computed(() => {
    const status = this.state().status;
    return status === 'loading' || status === 'ready' ? '' : MESSAGES[status];
  });

  constructor() {
    effect(() => {
      this.closeButton()?.nativeElement.focus();
    });
    // While open, Escape is handled in the capture phase on the document and
    // stopped there, so element-level Escape handlers (composer menu, image
    // lightbox, session rename) never also react to the same keypress.
    effect((onCleanup) => {
      if (!this.state().open) {
        return;
      }
      const handler = (event: KeyboardEvent) => this.onEscape(event);
      document.addEventListener('keydown', handler, true);
      onCleanup(() => document.removeEventListener('keydown', handler, true));
    });
  }

  close(): void {
    this.reader.close();
  }

  retry(): void {
    this.reader.retry();
  }

  onEscape(event: KeyboardEvent): void {
    if (event.key !== 'Escape' || event.isComposing || !this.state().open) {
      return;
    }
    event.preventDefault();
    event.stopImmediatePropagation();
    this.close();
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Tab') {
      return;
    }
    const root = this.panel()?.nativeElement;
    if (!root) {
      return;
    }
    const items = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (items.length === 0) {
      event.preventDefault();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || !root.contains(active))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (active === last || !root.contains(active))) {
      event.preventDefault();
      first.focus();
    }
  }
}
