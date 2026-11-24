import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';

import { WorkspaceFile } from '../models/opencode.models';
import { OpencodeApiService } from './opencode-api.service';

export type FileReaderStatus =
  | 'loading'
  | 'ready'
  | 'not-found'
  | 'forbidden'
  | 'binary'
  | 'too-large'
  | 'error';

export interface FileReaderState {
  open: boolean;
  path: string | null;
  status: FileReaderStatus;
  file: WorkspaceFile | null;
}

const CLOSED: FileReaderState = { open: false, path: null, status: 'loading', file: null };

export function statusFromError(err: unknown): FileReaderStatus {
  const code = err instanceof HttpErrorResponse ? err.status : 0;
  switch (code) {
    case 404:
      return 'not-found';
    case 403:
      return 'forbidden';
    case 415:
      return 'binary';
    case 413:
      return 'too-large';
    default:
      return 'error';
  }
}

@Injectable({ providedIn: 'root' })
export class FileReaderService {
  private readonly api = inject(OpencodeApiService);

  private readonly stateSig = signal<FileReaderState>(CLOSED);
  readonly state = computed(() => this.stateSig());

  private requestId = 0;
  private trigger: HTMLElement | null = null;
  private inflight: Subscription | null = null;

  open(path: string, trigger?: HTMLElement | null): void {
    this.trigger = trigger ?? null;
    this.load(path);
  }

  retry(): void {
    const path = this.stateSig().path;
    if (path) {
      this.load(path);
    }
  }

  close(): void {
    this.requestId++;
    this.cancelInflight();
    this.stateSig.set(CLOSED);
    const trigger = this.trigger;
    this.trigger = null;
    const target = trigger && trigger.isConnected ? trigger : document.body;
    target.focus?.();
  }

  private load(path: string): void {
    const id = ++this.requestId;
    this.cancelInflight();
    this.stateSig.set({ open: true, path, status: 'loading', file: null });
    this.inflight = this.api.readWorkspaceFile(path).subscribe({
      next: (file) => {
        if (id === this.requestId) {
          this.stateSig.set({ open: true, path, status: 'ready', file });
        }
      },
      error: (err) => {
        if (id === this.requestId) {
          this.stateSig.set({ open: true, path, status: statusFromError(err), file: null });
        }
      }
    });
  }

  private cancelInflight(): void {
    this.inflight?.unsubscribe();
    this.inflight = null;
  }
}
