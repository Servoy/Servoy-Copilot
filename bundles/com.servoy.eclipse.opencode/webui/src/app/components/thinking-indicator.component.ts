import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * The short "Thinking..." line shown while the model works and nothing else is visible yet.
 * The model's reasoning itself is never shown; this line replaces it and disappears as soon
 * as an answer or a tool row appears, or the turn ends.
 */
@Component({
  selector: 'svy-thinking',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="thinking" role="status" aria-label="Thinking"><span class="label">Thinking...</span></div>`,
  styles: `
    .thinking {
      padding: 0.15rem 0;
      font-size: 0.85rem;
      line-height: 1.4;
      color: var(--svy-muted, #9aa0a6);
    }

    .label {
      display: inline-block;
      animation: svy-thinking-pulse 1.6s ease-in-out infinite;
    }

    @keyframes svy-thinking-pulse {
      0%,
      100% {
        opacity: 0.45;
      }
      50% {
        opacity: 1;
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .label {
        animation: none;
      }
    }
  `
})
export class ThinkingIndicatorComponent {}
