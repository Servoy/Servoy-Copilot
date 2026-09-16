import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  input,
  model,
  output,
  viewChild
} from '@angular/core';

import { FormsModule } from '@angular/forms';

import { SendPart } from '../models/opencode.models';
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

  readonly sendMessage = output<SendPart[]>();
  readonly stop = output<void>();

  readonly textarea = viewChild.required<ElementRef<HTMLTextAreaElement>>('textarea');
  readonly fileInput = viewChild.required<ElementRef<HTMLInputElement>>('fileInput');

  readonly text = model('');
  readonly attachments = model<Attachment[]>([]);

  private static readonly MAX_HEIGHT_PX = 200;

  onInput(): void {
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
    this.attachments.update((current) => current.filter((_, i) => i !== index));
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
    this.text.set('');
    this.attachments.set([]);
    this.resetHeight();
  }

  private addFile(file: File): void {
    if (file.type.startsWith('image/')) {
      this.readImageFile(file);
    } else {
      // Non-image files are referenced by name; opencode resolves them against
      // the project directory the servlet injects.
      this.attachments.update((current) => [
        ...current,
        { filename: file.name, mime: file.type || 'application/octet-stream', url: file.name }
      ]);
    }
  }

  private readImageFile(file: File): void {
    const reader = new FileReader();
    reader.onload = () => {
      const url = typeof reader.result === 'string' ? reader.result : '';
      if (url) {
        this.attachments.update((current) => [
          ...current,
          { filename: file.name || 'pasted-image.png', mime: file.type || 'image/png', url }
        ]);
      }
    };
    reader.readAsDataURL(file);
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
