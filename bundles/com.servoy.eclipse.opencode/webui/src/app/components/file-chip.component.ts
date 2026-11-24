import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';

import { FileReaderService } from '../services/file-reader.service';
import { FileRef } from '../services/file-refs';

@Component({
  selector: 'svy-file-chip',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './file-chip.component.html',
  styleUrl: './file-chip.component.scss'
})
export class FileChipComponent {
  readonly file = input.required<FileRef>();
  readonly size = input<'md' | 'sm'>('md');

  private readonly reader = inject(FileReaderService);

  open(event: Event): void {
    const target = event.currentTarget;
    this.reader.open(this.file().path, target instanceof HTMLElement ? target : null);
  }
}
