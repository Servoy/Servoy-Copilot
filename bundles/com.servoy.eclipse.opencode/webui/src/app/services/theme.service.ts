import { Injectable, signal } from '@angular/core';

/** The two themes the UI supports. */
export type Theme = 'light' | 'dark';

/**
 * Resolves and applies the UI theme, mirroring the surrounding Eclipse IDE.
 *
 * The initial theme is driven by the {@code ?darkmode=} URL param, which the
 * Eclipse side sets from {@code UIUtils.isDarkThemeSelected(...)} when it opens
 * this page. {@code ?darkmode=true} -> dark, anything else (or absent) -> light.
 * The user can then flip it at runtime; the choice is in-memory only.
 *
 * The OS {@code prefers-color-scheme} is deliberately NOT consulted: the app
 * must follow the Eclipse IDE theme, not the operating system.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  /** The effective theme; initialised from the URL so every instance agrees. */
  readonly theme = signal<Theme>(this.resolve());

  /** The effective theme, read from the {@code darkmode} URL param. */
  resolve(): Theme {
    return this.fromUrl() ?? 'light';
  }

  /** Apply a theme by setting {@code data-theme} on the document root. */
  apply(theme: Theme): void {
    document.documentElement.setAttribute('data-theme', theme);
  }

  /** Resolve and apply in one step; returns the theme that was applied. */
  applyResolved(): Theme {
    const theme = this.resolve();
    this.theme.set(theme);
    this.apply(theme);
    return theme;
  }

  /** Set and apply a theme (in-memory only). */
  set(theme: Theme): void {
    this.theme.set(theme);
    this.apply(theme);
  }

  /** Flip between light and dark. */
  toggle(): void {
    this.set(this.theme() === 'dark' ? 'light' : 'dark');
  }

  /** {@code ?darkmode=true} -> dark, {@code ?darkmode=false} -> light, else null. */
  private fromUrl(): Theme | null {
    try {
      const value = new URLSearchParams(window.location.search).get('darkmode');
      if (value === null) {
        return null;
      }
      return value === '' || value === 'true' || value === '1' ? 'dark' : 'light';
    } catch {
      return null;
    }
  }
}
