import { DestroyRef, Injectable, inject, signal } from '@angular/core';

import { OpencodeApiService } from './opencode-api.service';
import { SvyCommand, toSvyCommands } from './svy-commands';

@Injectable({ providedIn: 'root' })
export class CommandCatalogService {
  static readonly RETRY_DELAY_MS = 2000;
  static readonly MAX_RETRIES = 30;

  private readonly api = inject(OpencodeApiService);
  private readonly _commands = signal<SvyCommand[]>([]);
  private requested = false;
  private retries = 0;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;

  readonly commands = this._commands.asReadonly();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.clearRetry());
  }

  load(): void {
    if (this.requested) {
      return;
    }
    this.requested = true;
    this.api.listCommands().subscribe({
      next: (list) => {
        const commands = toSvyCommands(list);
        this._commands.set(commands);
        if (commands.length > 0) {
          this.retries = 0;
          return;
        }
        this.requested = false;
        this.scheduleRetry();
      },
      error: () => {
        this.requested = false;
        this.scheduleRetry();
      }
    });
  }

  private scheduleRetry(): void {
    if (this.retryTimer !== null || this.retries >= CommandCatalogService.MAX_RETRIES) {
      return;
    }
    this.retries++;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      this.load();
    }, CommandCatalogService.RETRY_DELAY_MS);
  }

  private clearRetry(): void {
    if (this.retryTimer !== null) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
  }
}
