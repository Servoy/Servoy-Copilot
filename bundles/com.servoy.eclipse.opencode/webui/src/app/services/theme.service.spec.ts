import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  const originalUrl = window.location.pathname + window.location.search;

  function setQuery(query: string): void {
    history.replaceState(null, '', window.location.pathname + query);
  }

  function create(): ThemeService {
    return TestBed.inject(ThemeService);
  }

  beforeEach(() => {
    document.documentElement.removeAttribute('data-theme');
    setQuery('');
  });

  afterEach(() => {
    vi.restoreAllMocks();
    document.documentElement.removeAttribute('data-theme');
    history.replaceState(null, '', originalUrl);
  });

  describe('resolve()', () => {
    it('is light when darkmode is absent', () => {
      expect(create().resolve()).toBe('light');
    });

    it.each([
      ['?darkmode=', 'dark'],
      ['?darkmode=true', 'dark'],
      ['?darkmode=1', 'dark'],
      ['?darkmode=false', 'light'],
      ['?darkmode=0', 'light'],
      ['?darkmode=garbage', 'light'],
      ['?other=true', 'light']
    ])('%s -> %s', (query, expected) => {
      setQuery(query);
      expect(create().resolve()).toBe(expected);
    });
  });

  describe('theme signal', () => {
    it('starts light when no darkmode param is present', () => {
      expect(create().theme()).toBe('light');
    });

    it('starts dark when ?darkmode=true so a DI instance agrees with main.ts', () => {
      setQuery('?darkmode=true');
      expect(create().theme()).toBe('dark');
    });
  });

  describe('apply()', () => {
    it('sets data-theme on the document root without changing the signal', () => {
      const service = create();
      service.apply('dark');
      expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
      expect(service.theme()).toBe('light');
    });
  });

  describe('applyResolved()', () => {
    it('applies dark from the URL, updates signal and attribute, returns the theme', () => {
      setQuery('?darkmode=true');
      const service = create();
      service.theme.set('light');
      expect(service.applyResolved()).toBe('dark');
      expect(service.theme()).toBe('dark');
      expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    });

    it('applies light when the param is absent', () => {
      const service = create();
      expect(service.applyResolved()).toBe('light');
      expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    });
  });

  describe('set() and toggle()', () => {
    it('set() updates signal and attribute', () => {
      const service = create();
      service.set('dark');
      expect(service.theme()).toBe('dark');
      expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
      service.set('light');
      expect(service.theme()).toBe('light');
      expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    });

    it('toggle() flips light -> dark -> light on signal and attribute', () => {
      const service = create();
      service.toggle();
      expect(service.theme()).toBe('dark');
      expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
      service.toggle();
      expect(service.theme()).toBe('light');
      expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    });

    it('toggle() from a dark start goes light', () => {
      setQuery('?darkmode=true');
      const service = create();
      service.toggle();
      expect(service.theme()).toBe('light');
    });

    it('does not rewrite the URL, navigate, or persist anything', () => {
      setQuery('?darkmode=true');
      const service = create();
      const replace = vi.spyOn(history, 'replaceState');
      const push = vi.spyOn(history, 'pushState');
      const setItem = vi.spyOn(Storage.prototype, 'setItem');
      const before = window.location.href;

      service.toggle();
      service.toggle();
      service.set('dark');

      expect(replace).not.toHaveBeenCalled();
      expect(push).not.toHaveBeenCalled();
      expect(setItem).not.toHaveBeenCalled();
      expect(window.location.href).toBe(before);
    });
  });
});
