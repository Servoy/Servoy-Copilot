import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { installNativeUiGuard } from './native-ui-guard';

describe('installNativeUiGuard', () => {
  let dispose: () => void;
  let host: HTMLElement;

  beforeEach(() => {
    dispose = installNativeUiGuard();
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(() => {
    dispose();
    host.remove();
    vi.unstubAllGlobals();
    window.getSelection()?.removeAllRanges();
  });

  function add<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Record<string, string> = {}): HTMLElementTagNameMap[K] {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      el.setAttribute(k, v);
    }
    host.appendChild(el);
    return el;
  }

  function mouse(type: string, button: number): MouseEvent {
    return new MouseEvent(type, { button, bubbles: true, cancelable: true });
  }

  function key(k: string, init: KeyboardEventInit = {}): KeyboardEvent {
    return new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true, ...init });
  }

  function stubPlatform(platform: string): void {
    vi.stubGlobal('navigator', { platform });
  }

  describe('contextmenu', () => {
    it('is prevented on a plain element', () => {
      const el = add('div');
      const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true });
      el.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
    });

    it('is prevented on a textarea', () => {
      const el = add('textarea');
      const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true });
      el.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
    });

    it('is prevented on an input', () => {
      const el = add('input');
      const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true });
      el.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
    });

    it('is prevented with a non-empty text selection', () => {
      const el = add('p');
      el.textContent = 'some selected text';
      const range = document.createRange();
      range.selectNodeContents(el);
      const selection = window.getSelection()!;
      selection.removeAllRanges();
      selection.addRange(range);
      expect(selection.toString()).toBe('some selected text');

      const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true });
      el.dispatchEvent(event);

      expect(event.defaultPrevented).toBe(true);
    });

    it('still fires the element own listener and does not stop propagation', () => {
      const parent = add('div');
      const child = document.createElement('span');
      parent.appendChild(child);
      const childSeen = vi.fn();
      const parentSeen = vi.fn();
      child.addEventListener('contextmenu', childSeen);
      parent.addEventListener('contextmenu', parentSeen);

      const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true });
      child.dispatchEvent(event);

      expect(childSeen).toHaveBeenCalledTimes(1);
      expect(parentSeen).toHaveBeenCalledTimes(1);
      expect(event.defaultPrevented).toBe(true);
    });

  });

  describe('mouse side buttons', () => {
    const types = ['mousedown', 'mouseup', 'auxclick', 'pointerup'];
    const cases = types.flatMap((type) => [3, 4].map((button) => [type, button] as const));
    const allowed = types.flatMap((type) => [0, 1, 2].map((button) => [type, button] as const));

    it.each(cases)('%s with button %i is prevented', (type, button) => {
      const el = add('button');
      const event = mouse(type, button);
      el.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
    });

    it.each(allowed)('%s with button %i is not prevented', (type, button) => {
      const el = add('button');
      const event = mouse(type, button);
      el.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
    });
  });

  describe('history keys', () => {
    it.each(['ArrowLeft', 'ArrowRight'])('Alt+%s is prevented on Windows', (k) => {
      stubPlatform('Win32');
      const el = add('div');
      const event = key(k, { altKey: true });
      el.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
    });

    it('ArrowLeft alone is not prevented', () => {
      const event = key('ArrowLeft');
      add('div').dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
    });

    it('ArrowRight alone is not prevented', () => {
      const event = key('ArrowRight');
      add('div').dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
    });

    it('Ctrl+ArrowLeft is not prevented', () => {
      const event = key('ArrowLeft', { ctrlKey: true });
      add('div').dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
    });

    it.each(['c', 'v', 'x'])('Ctrl+%s is not prevented', (k) => {
      const event = key(k, { ctrlKey: true });
      add('textarea').dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
    });

    it('Alt with an unrelated key is not prevented', () => {
      const event = key('ArrowUp', { altKey: true });
      add('div').dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
    });

    it('Alt+Shift+ArrowLeft is not prevented', () => {
      stubPlatform('Win32');
      const event = key('ArrowLeft', { altKey: true, shiftKey: true });
      add('div').dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
    });
  });

  describe('macOS exception', () => {
    it('does not prevent Alt+ArrowLeft in a textarea on macOS', () => {
      stubPlatform('MacIntel');
      const event = key('ArrowLeft', { altKey: true });
      add('textarea').dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
    });

    it('does not prevent Alt+ArrowRight in an input on macOS', () => {
      stubPlatform('MacIntel');
      const event = key('ArrowRight', { altKey: true });
      add('input').dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
    });

    it('does not prevent Alt+ArrowLeft in a contenteditable on macOS', () => {
      stubPlatform('MacIntel');
      const event = key('ArrowLeft', { altKey: true });
      add('div', { contenteditable: 'true' }).dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
    });

    it('prevents Alt+ArrowLeft in contenteditable="false" on macOS', () => {
      stubPlatform('MacIntel');
      const event = key('ArrowLeft', { altKey: true });
      add('div', { contenteditable: 'false' }).dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
    });

    it('prevents Alt+ArrowLeft on a non-editable target on macOS', () => {
      stubPlatform('MacIntel');
      const event = key('ArrowLeft', { altKey: true });
      add('div').dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
    });

    it('prevents Alt+ArrowRight on a non-editable target on macOS', () => {
      stubPlatform('MacIntel');
      const event = key('ArrowRight', { altKey: true });
      add('button').dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
    });

    it('prevents Alt+ArrowLeft in a textarea on Windows', () => {
      stubPlatform('Win32');
      const event = key('ArrowLeft', { altKey: true });
      add('textarea').dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
    });

    it('prevents Alt+ArrowRight in an input on Windows', () => {
      stubPlatform('Win32');
      const event = key('ArrowRight', { altKey: true });
      add('input').dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
    });
  });

  describe('disposer', () => {
    it('stops preventing everything once called', () => {
      stubPlatform('Win32');
      const el = add('textarea');
      dispose();

      const ctx = new MouseEvent('contextmenu', { bubbles: true, cancelable: true });
      el.dispatchEvent(ctx);
      const types = ['mousedown', 'mouseup', 'auxclick', 'pointerup'];
      const side = types.map((t) => mouse(t, 3));
      side.forEach((e) => el.dispatchEvent(e));
      const left = key('ArrowLeft', { altKey: true });
      el.dispatchEvent(left);

      expect(ctx.defaultPrevented).toBe(false);
      expect(side.map((e) => e.defaultPrevented)).toEqual([false, false, false, false]);
      expect(left.defaultPrevented).toBe(false);
      dispose = () => undefined;
    });
  });
});
