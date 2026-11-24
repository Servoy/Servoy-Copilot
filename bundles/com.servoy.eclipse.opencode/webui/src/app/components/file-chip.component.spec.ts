import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { FileReaderService } from '../services/file-reader.service';
import { FileRef } from '../services/file-refs';
import { FileChipComponent } from './file-chip.component';

describe('FileChipComponent', () => {
  let fixture: ComponentFixture<FileChipComponent>;
  let el: HTMLElement;
  let reader: { open: ReturnType<typeof vi.fn> };

  const file: FileRef = { path: 'handoff/t/spec.md', name: 'spec.md', caption: 'Spec' };
  const button = () => el.querySelector('button') as HTMLButtonElement;

  beforeEach(async () => {
    reader = { open: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [FileChipComponent],
      providers: [{ provide: FileReaderService, useValue: reader }]
    }).compileComponents();
    fixture = TestBed.createComponent(FileChipComponent);
    el = fixture.nativeElement as HTMLElement;
    fixture.componentRef.setInput('file', file);
    fixture.detectChanges();
  });

  it('renders a real type=button with caption, name, title and aria-haspopup', () => {
    expect(button().tagName).toBe('BUTTON');
    expect(button().getAttribute('type')).toBe('button');
    expect(button().getAttribute('aria-haspopup')).toBe('dialog');
    expect(button().getAttribute('title')).toBe('handoff/t/spec.md');
    expect(el.querySelector('.caption')!.textContent!.trim()).toBe('Spec');
    expect(el.querySelector('.name')!.textContent!.trim()).toBe('spec.md');
    expect(el.querySelector('svg')!.getAttribute('aria-hidden')).toBe('true');
  });

  it('is md by default and sm when requested', () => {
    expect(button().classList.contains('sm')).toBe(false);
    fixture.componentRef.setInput('size', 'sm');
    fixture.detectChanges();
    expect(button().classList.contains('sm')).toBe(true);
    fixture.componentRef.setInput('size', 'md');
    fixture.detectChanges();
    expect(button().classList.contains('sm')).toBe(false);
  });

  it('opens the reader with the path and the button itself as the focus-return trigger', () => {
    button().click();
    expect(reader.open).toHaveBeenCalledTimes(1);
    expect(reader.open).toHaveBeenCalledWith('handoff/t/spec.md', button());
  });

  it('updates caption, name, title and the opened path when the file input changes', () => {
    fixture.componentRef.setInput('file', { path: 'src/a.js', name: 'a.js', caption: 'Changed file' });
    fixture.detectChanges();
    expect(el.querySelector('.caption')!.textContent!.trim()).toBe('Changed file');
    expect(el.querySelector('.name')!.textContent!.trim()).toBe('a.js');
    expect(button().getAttribute('title')).toBe('src/a.js');
    button().click();
    expect(reader.open).toHaveBeenCalledWith('src/a.js', button());
  });

  it('passes null as trigger when the event target is not an HTMLElement', () => {
    fixture.componentInstance.open({ currentTarget: null } as unknown as Event);
    expect(reader.open).toHaveBeenCalledWith('handoff/t/spec.md', null);
  });

  it('does not throw NG0600 when inputs change', () => {
    fixture.componentRef.setInput('size', 'sm');
    fixture.componentRef.setInput('file', { path: 'x.md', name: 'x.md', caption: 'Changed file' });
    expect(() => fixture.detectChanges()).not.toThrow();
  });
});
