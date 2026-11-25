import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { ChevronIconComponent } from './chevron-icon.component';

@Component({
  standalone: true,
  imports: [ChevronIconComponent],
  template: `<svy-chevron [open]="open()" />`
})
class HostComponent {
  readonly open = signal(false);
}

describe('ChevronIconComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;

  beforeEach(() => {
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  const svg = (): SVGElement => fixture.nativeElement.querySelector('svg') as SVGElement;

  it('draws the same 16px stroked chevron path as the session list twisty', () => {
    expect(svg().getAttribute('viewBox')).toBe('0 0 16 16');
    expect(svg().getAttribute('width')).toBe('16');
    expect(svg().getAttribute('height')).toBe('16');
    const path = svg().querySelector('path')!;
    expect(path.getAttribute('d')).toBe('M6 3.5 10.5 8 6 12.5');
    expect(path.getAttribute('stroke')).toBe('currentColor');
    expect(path.getAttribute('stroke-width')).toBe('2');
    expect(path.getAttribute('fill')).toBe('none');
  });

  it('is decorative: aria-hidden and not focusable', () => {
    expect(svg().getAttribute('aria-hidden')).toBe('true');
    expect(svg().getAttribute('focusable')).toBe('false');
  });

  it('is closed by default and rotates only when open', () => {
    expect(svg().classList.contains('open')).toBe(false);
    host.open.set(true);
    expect(() => fixture.detectChanges()).not.toThrow();
    expect(svg().classList.contains('open')).toBe(true);
    host.open.set(false);
    fixture.detectChanges();
    expect(svg().classList.contains('open')).toBe(false);
  });
});
