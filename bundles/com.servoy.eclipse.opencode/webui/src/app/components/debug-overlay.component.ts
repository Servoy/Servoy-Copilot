import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';

import { DebugEntry, clearDebugLog, debugEnabled, debugLog } from '../services/debug-log';

/**
 * DEBUG(title-timing): fixed overlay that renders the debug ring buffer so the
 * SSE / store / sidebar timing can be read straight from a DOM snapshot. Polls
 * the buffer every 250ms. Temporary - remove with the rest of the debug tracing.
 */
@Component({
  selector: 'svy-debug-overlay',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="debug-overlay" data-debug-overlay>
      <button type="button" (click)="clear()">clear</button>
      <div class="lines">
        @for (e of lines(); track $index) {
          <div class="line" [attr.data-tag]="e.tag">{{ fmt(e) }}</div>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .debug-overlay {
        position: fixed;
        right: 0;
        bottom: 0;
        width: 520px;
        max-height: 40vh;
        overflow: auto;
        background: rgba(0, 0, 0, 0.85);
        color: #b6f3c1;
        font: 11px/1.35 monospace;
        padding: 6px 8px;
        z-index: 99999;
        white-space: pre-wrap;
      }
      .debug-overlay button {
        margin-bottom: 4px;
      }
      .line[data-tag='sse'] {
        color: #8fd3ff;
      }
      .line[data-tag='store'] {
        color: #ffd479;
      }
      .line[data-tag='sidebar'] {
        color: #ff9db1;
      }
    `
  ]
})
export class DebugOverlayComponent {
  private readonly tick = signal(0);

  readonly lines = computed<readonly DebugEntry[]>(() => {
    this.tick();
    return debugLog().slice(-60);
  });

  constructor() {
    if (debugEnabled) {
      setInterval(() => this.tick.update((n) => n + 1), 250);
    }
  }

  fmt(e: DebugEntry): string {
    const d = new Date(e.t);
    const ms = d.getMilliseconds().toString().padStart(3, '0');
    const hms = d.toTimeString().slice(0, 8);
    return `${hms}.${ms} [${e.tag}] ${e.msg}`;
  }

  clear(): void {
    clearDebugLog();
    this.tick.update((n) => n + 1);
  }
}
