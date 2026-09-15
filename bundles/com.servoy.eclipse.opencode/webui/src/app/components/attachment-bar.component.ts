import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/** A file selected for attachment, with an optional inline data URL. */
export interface Attachment {
  filename: string;
  mime: string;
  /** data: URL for pasted/inline images, or a project-relative path reference. */
  url: string;
}

/**
 * Shows selected attachments as removable chips. File/image selection and
 * drag-drop/paste are wired by the parent {@code Composer}; this component only
 * renders the chips and emits removals.
 */
@Component({
  selector: 'svy-attachment-bar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './attachment-bar.component.html',
  styleUrl: './attachment-bar.component.scss'
})
export class AttachmentBarComponent {
  readonly attachments = input<Attachment[]>([]);
  readonly remove = output<number>();

  onRemove(index: number): void {
    this.remove.emit(index);
  }

  attachmentKey(attachment: Attachment, index: number): string {
    return `${attachment.filename}-${index}`;
  }
}
