import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  input,
  output,
  viewChild
} from '@angular/core';

import { FormsModule } from '@angular/forms';

import { SendPart } from '../models/opencode.models';
import { ComposerDraft } from '../services/chat-store.service';
import { Attachment, AttachmentBarComponent } from './attachment-bar.component';

/**
 * Auto-growing composer: a textarea that grows with content (capped, then
 * scrolls); Enter sends, Shift+Enter inserts a newline. Shows a send button, or
 * a stop button while a turn is streaming (stop calls abort). Supports file /
 * image attach via a picker, drag-drop, and image paste.
 */
@Component({
  selector: 'svy-composer',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, AttachmentBarComponent],
  templateUrl: './composer.component.html',
  styleUrl: './composer.component.scss'
})
export class ComposerComponent {
  readonly streaming = input(false);
  /** When true the composer is read-only (e.g. viewing a subagent session). */
  readonly disabled = input(false);
  /** Explains why the composer is disabled; shown above the input when set. */
  readonly disabledHint = input('You\'re viewing a subagent session. Open its parent session to chat.');

  /**
   * The composer draft for the active session, owned by the store so it is
   * preserved per-session. The composer is "controlled": it renders this draft
   * and emits {@link draftChange} on every edit rather than holding its own
   * copy, so switching sessions swaps the draft without any stale local state.
   */
  readonly draft = input<ComposerDraft>({ text: '', attachments: [] });

  readonly sendMessage = output<SendPart[]>();
  readonly stop = output<void>();
  readonly draftChange = output<ComposerDraft>();

  readonly textarea = viewChild.required<ElementRef<HTMLTextAreaElement>>('textarea');
  readonly fileInput = viewChild.required<ElementRef<HTMLInputElement>>('fileInput');

  /** The current text, read from the active session's draft. */
  readonly text = computed(() => this.draft().text);
  /** The current attachments, read from the active session's draft. */
  readonly attachments = computed<Attachment[]>(() => this.draft().attachments);

  /**
   * Whether there is anything to send: non-blank text or at least one
   * attachment. Drives the send button's enabled/coloured state so an empty
   * composer shows a greyed-out button, and it lights up the moment the user
   * types or attaches something.
   */
  readonly canSend = computed<boolean>(
    () => this.text().trim().length > 0 || this.attachments().length > 0
  );

  private static readonly MAX_HEIGHT_PX = 200;

  /** Textarea input: persist the typed text to the draft and grow the box. */
  onInput(value: string): void {
    this.emitDraft(value, this.attachments());
    this.autoGrow();
  }

  onKeydown(event: KeyboardEvent): void {
    if (this.disabled()) {
      return;
    }
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.submit();
    }
  }

  onPaste(event: ClipboardEvent): void {
    const items = event.clipboardData?.items;
    if (!items) {
      return;
    }
    for (const item of Array.from(items)) {
      if (item.kind === 'file' && item.type.startsWith('image/')) {
        const file = item.getAsFile();
        if (file) {
          this.readImageFile(file);
        }
      }
    }
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    const files = event.dataTransfer?.files;
    if (!files) {
      return;
    }
    for (const file of Array.from(files)) {
      this.addFile(file);
    }
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
  }

  openFilePicker(): void {
    this.fileInput().nativeElement.click();
  }

  onFilesSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = input.files;
    if (files) {
      for (const file of Array.from(files)) {
        this.addFile(file);
      }
    }
    input.value = '';
  }

  removeAttachment(index: number): void {
    const next = this.attachments().filter((_, i) => i !== index);
    this.emitDraft(this.text(), next);
  }

  onStop(): void {
    this.stop.emit();
  }

  submit(): void {
    if (this.streaming() || this.disabled()) {
      return;
    }
    const trimmed = this.text().trim();
    const attachments = this.attachments();
    if (!trimmed && attachments.length === 0) {
      return;
    }

    const parts: SendPart[] = [];
    if (trimmed) {
      parts.push({ type: 'text', text: trimmed });
    }
    for (const attachment of attachments) {
      parts.push({
        type: 'file',
        filename: attachment.filename,
        mime: attachment.mime,
        url: attachment.url
      });
    }

    this.sendMessage.emit(parts);
    // The store clears this session's draft on send; mirror it locally so the
    // textarea empties immediately without waiting for the input binding.
    this.emitDraft('', []);
    this.resetHeight();
  }

  private addFile(file: File): void {
    if (file.type.startsWith('image/')) {
      this.readImageFile(file);
    } else {
      // Non-image files are referenced by name; opencode resolves them against
      // the project directory the servlet injects.
      this.emitDraft(this.text(), [
        ...this.attachments(),
        { filename: file.name, mime: file.type || 'application/octet-stream', url: file.name }
      ]);
    }
  }

  private readImageFile(file: File): void {
    const reader = new FileReader();
    reader.onload = () => {
      const url = typeof reader.result === 'string' ? reader.result : '';
      if (url) {
        this.emitDraft(this.text(), [
          ...this.attachments(),
          { filename: file.name || 'pasted-image.png', mime: file.type || 'image/png', url }
        ]);
      }
    };
    reader.readAsDataURL(file);
  }

  /**
   * Emit the current composer state back to the store as the active session's
   * draft. The composer holds no state of its own; the store owns the draft per
   * session, so this is the single write path.
   */
  private emitDraft(text: string, attachments: Attachment[]): void {
    this.draftChange.emit({ text, attachments });
  }

  private autoGrow(): void {
    const el = this.textarea().nativeElement;
    el.style.height = 'auto';
    const next = Math.min(el.scrollHeight, ComposerComponent.MAX_HEIGHT_PX);
    el.style.height = `${next}px`;
    el.style.overflowY = el.scrollHeight > ComposerComponent.MAX_HEIGHT_PX ? 'auto' : 'hidden';
  }

  private resetHeight(): void {
    const el = this.textarea().nativeElement;
    el.style.height = 'auto';
    el.style.overflowY = 'hidden';
  }
}
