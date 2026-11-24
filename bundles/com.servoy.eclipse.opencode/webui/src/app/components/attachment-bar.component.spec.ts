import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { Attachment, AttachmentBarComponent } from './attachment-bar.component';

describe('AttachmentBarComponent', () => {
  let fixture: ComponentFixture<AttachmentBarComponent>;
  let component: AttachmentBarComponent;

  const file = (name: string): Attachment => ({ filename: name, mime: 'text/plain', url: name });
  const image = (name: string): Attachment => ({ filename: name, mime: 'image/png', url: 'data:image/png;base64,AAAA' });

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [AttachmentBarComponent] }).compileComponents();
    fixture = TestBed.createComponent(AttachmentBarComponent);
    component = fixture.componentInstance;
  });

  function set(attachments: Attachment[]): void {
    fixture.componentRef.setInput('attachments', attachments);
    fixture.detectChanges();
  }

  const el = (): HTMLElement => fixture.nativeElement;

  it('renders nothing for an empty list', () => {
    set([]);
    expect(el().querySelector('.chips')).toBeNull();
  });

  it('renders an image with a data URL as a thumbnail, not a chip', () => {
    set([image('shot.png')]);
    const img = el().querySelector('img');
    expect(img?.getAttribute('src')).toBe('data:image/png;base64,AAAA');
    expect(img?.getAttribute('alt')).toBe('shot.png');
    expect(el().querySelectorAll('.chip')).toHaveLength(0);
    expect(el().querySelectorAll('.thumb')).toHaveLength(1);
  });

  it('keeps non-image files as chips', () => {
    set([file('a.txt')]);
    expect(el().querySelectorAll('.chip')).toHaveLength(1);
    expect(el().querySelector('.chip-name')?.textContent).toContain('a.txt');
    expect(el().querySelector('img')).toBeNull();
  });

  it('keeps an image referenced by name (non-data URL) as a chip', () => {
    set([{ filename: 'pic.png', mime: 'image/png', url: 'pic.png' }]);
    expect(el().querySelectorAll('.chip')).toHaveLength(1);
    expect(el().querySelector('img')).toBeNull();
  });

  it('keeps a data URL with a non-image mime as a chip', () => {
    set([{ filename: 'x.bin', mime: 'application/octet-stream', url: 'data:application/octet-stream;base64,AA' }]);
    expect(el().querySelectorAll('.chip')).toHaveLength(1);
    expect(el().querySelector('img')).toBeNull();
  });

  it('emits the original index for mixed lists', () => {
    set([file('a.txt'), image('one.png'), file('b.txt'), image('two.png')]);
    const removed: number[] = [];
    component.remove.subscribe((i) => removed.push(i));

    const thumbButtons = el().querySelectorAll<HTMLButtonElement>('.thumb-remove');
    expect(thumbButtons).toHaveLength(2);
    thumbButtons[1].click();
    el().querySelectorAll<HTMLButtonElement>('.chip-remove')[1].click();
    thumbButtons[0].click();
    el().querySelectorAll<HTMLButtonElement>('.chip-remove')[0].click();

    expect(removed).toEqual([3, 2, 1, 0]);
  });

  it('gives the thumbnail remove button an accessible label with the file name', () => {
    set([image('shot.png')]);
    const button = el().querySelector('.thumb-remove');
    expect(button?.getAttribute('aria-label')).toBe('Remove shot.png');
    expect(button?.getAttribute('type')).toBe('button');
  });

  it('isImage requires an image mime and a data URL', () => {
    expect(component.isImage(image('a.png'))).toBe(true);
    expect(component.isImage({ filename: 'a', mime: 'image/png', url: 'a.png' })).toBe(false);
    expect(component.isImage({ filename: 'a', mime: 'text/plain', url: 'data:text/plain,x' })).toBe(false);
  });

  it('does not throw when attachments change', () => {
    set([image('a.png')]);
    fixture.componentRef.setInput('attachments', [file('a.txt'), image('b.png')]);
    expect(() => fixture.detectChanges()).not.toThrow();
  });
});
