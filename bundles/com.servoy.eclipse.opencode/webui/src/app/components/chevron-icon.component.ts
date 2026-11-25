import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The expand/collapse chevron shared by the disclosure rows: a 16 px stroked
 * chevron in {@code --svy-fg2} that rotates a quarter turn when open. Same
 * glyph and weight as the session list's subagent twisty, so every disclosure
 * in the app reads the same. The host only draws the icon; the clickable
 * target (a button or a whole row) is owned by the consumer.
 */
@Component({
  selector: 'svy-chevron',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg class="icon" [class.open]="open()" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false">
      <path d="M6 3.5 10.5 8 6 12.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      flex: 0 0 auto;
      color: var(--svy-fg2, #4a4740);
    }

    .icon {
      display: block;
      transition: transform 0.12s ease;
    }

    .icon.open {
      transform: rotate(90deg);
    }

    @media (prefers-reduced-motion: reduce) {
      .icon {
        transition: none;
      }
    }
  `
})
export class ChevronIconComponent {
  /** Rotated a quarter turn (pointing down) when true. */
  readonly open = input(false);
}
