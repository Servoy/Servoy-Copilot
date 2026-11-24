import { Injectable, inject, signal } from '@angular/core';

import { OpencodeApiService } from './opencode-api.service';
import { SvyCommand, toSvyCommands } from './svy-commands';

@Injectable({ providedIn: 'root' })
export class CommandCatalogService {
  private readonly api = inject(OpencodeApiService);
  private readonly _commands = signal<SvyCommand[]>([]);
  private requested = false;

  readonly commands = this._commands.asReadonly();

  load(): void {
    if (this.requested) {
      return;
    }
    this.requested = true;
    this.api.listCommands().subscribe({
      next: (list) => this._commands.set(toSvyCommands(list)),
      error: () => {
        this.requested = false;
      }
    });
  }
}
