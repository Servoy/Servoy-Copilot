import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { SelectionCopyComponent } from './selection-copy.component';

describe('SelectionCopyComponent', () => {
  let fixture: ComponentFixture<SelectionCopyComponent>;
  let content: HTMLElement;
  let writeText: ReturnType<typeof vi.fn>;

  const flush = async (): Promise<void> => {
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  };

  const selectContent = (): void => {
    const range = document.createRange();
    range.selectNodeContents(content);
    const selection = document.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
  };

  const button = (): HTMLButtonElement | null => fixture.nativeElement.querySelector('button.copy');

  const rightClick = (target: EventTarget = content, x = 120, y = 80): void => {
    target.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: x, clientY: y }));
    fixture.detectChanges();
  };

  beforeEach(async () => {
    content = document.createElement('p');
    content.textContent = 'hello world';
    document.body.appendChild(content);
    writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { ...globalThis.navigator, clipboard: { writeText } });
    await TestBed.configureTestingModule({ imports: [SelectionCopyComponent] }).compileComponents();
    fixture = TestBed.createComponent(SelectionCopyComponent);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    document.getSelection()?.removeAllRanges();
    content.remove();
    vi.unstubAllGlobals();
  });

  it('does not show a button just because text is selected', async () => {
    selectContent();
    content.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, button: 0 }));
    await flush();
    expect(button()).toBeNull();
  });

  it('shows no button on right-click when nothing is selected', () => {
    rightClick();
    expect(button()).toBeNull();
  });

  it('shows a Copy button at the cursor on right-click over a selection', () => {
    selectContent();
    rightClick(content, 120, 80);
    const el = button();
    expect(el?.textContent?.trim()).toBe('Copy');
    expect(el?.style.left).toBe('120px');
    expect(el?.style.top).toBe('80px');
  });

  it('keeps the button inside the viewport near the edges', () => {
    selectContent();
    rightClick(content, window.innerWidth + 500, window.innerHeight + 500);
    const el = button();
    expect(parseFloat(el?.style.left ?? '0')).toBeLessThan(window.innerWidth);
    expect(parseFloat(el?.style.top ?? '0')).toBeLessThan(window.innerHeight);
  });

  it('ignores a whitespace-only selection', () => {
    content.textContent = '   ';
    selectContent();
    rightClick();
    expect(button()).toBeNull();
  });

  it('copies the selected text and confirms', async () => {
    selectContent();
    rightClick();
    button()?.click();
    await flush();
    expect(writeText).toHaveBeenCalledWith('hello world');
    expect(button()?.textContent?.trim()).toBe('Copied');
  });

  it('hides when the selection is cleared', () => {
    selectContent();
    rightClick();
    document.getSelection()?.removeAllRanges();
    document.dispatchEvent(new Event('selectionchange'));
    fixture.detectChanges();
    expect(button()).toBeNull();
  });

  it('hides on a mousedown outside the button', () => {
    selectContent();
    rightClick();
    content.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, button: 0 }));
    fixture.detectChanges();
    expect(button()).toBeNull();
  });

  it('hides on scroll and on keydown', () => {
    selectContent();
    rightClick();
    document.dispatchEvent(new Event('scroll'));
    fixture.detectChanges();
    expect(button()).toBeNull();

    selectContent();
    rightClick();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'a' }));
    fixture.detectChanges();
    expect(button()).toBeNull();
  });

  it('offers copy for text selected inside a textarea', async () => {
    const area = document.createElement('textarea');
    area.value = 'abc def';
    document.body.appendChild(area);
    area.focus();
    area.setSelectionRange(0, 3);
    rightClick(area);
    button()?.click();
    await flush();
    area.remove();
    expect(writeText).toHaveBeenCalledWith('abc');
  });

  it('hides without confirming when the clipboard write fails', async () => {
    writeText.mockRejectedValue(new Error('denied'));
    document.execCommand = vi.fn().mockReturnValue(false);
    selectContent();
    rightClick();
    button()?.click();
    await flush();
    expect(button()).toBeNull();
  });
});
