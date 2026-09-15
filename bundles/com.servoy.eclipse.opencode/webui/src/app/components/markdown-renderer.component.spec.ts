import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { MarkdownRendererComponent } from './markdown-renderer.component';

describe('MarkdownRendererComponent', () => {
  let fixture: ComponentFixture<MarkdownRendererComponent>;

  function hostHtml(): string {
    return (fixture.nativeElement as HTMLElement).querySelector('.markdown-body')!.innerHTML;
  }

  function setContent(content: string): void {
    fixture.componentRef.setInput('content', content);
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MarkdownRendererComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(MarkdownRendererComponent);
    fixture.detectChanges();
  });

  it('renders bold, headings and lists as the expected tags', () => {
    setContent('# Title\n\n**bold** text\n\n- one\n- two');
    const html = hostHtml();
    expect(html).toContain('<h1');
    expect(html).toContain('<strong>bold</strong>');
    expect(html).toContain('<ul>');
    expect(html).toContain('<li>one</li>');
  });

  it('strips <script> tags (sanitization)', () => {
    setContent('before <script>alert(1)</script> after');
    const html = hostHtml().toLowerCase();
    expect(html).not.toContain('<script');
    expect(html).not.toContain('alert(1)');
  });

  it('strips dangerous onerror handlers from injected HTML', () => {
    setContent('<img src="x" onerror="alert(1)">');
    const html = hostHtml().toLowerCase();
    expect(html).not.toContain('onerror');
  });

  it('renders a fenced code block as a highlighted <code> element', () => {
    setContent('```js\nconst x = 1;\n```');
    const el = fixture.nativeElement as HTMLElement;
    const code = el.querySelector('pre code');
    expect(code).toBeTruthy();
    expect(code!.textContent).toContain('const x = 1;');
    // highlight.js marks up processed blocks with the hljs class.
    expect(code!.className).toContain('hljs');
  });

  it('re-renders when content changes (streaming-safe)', () => {
    setContent('first');
    expect(hostHtml()).toContain('first');

    setContent('first and second');
    expect(hostHtml()).toContain('first and second');
  });

  it('handles empty content without throwing', () => {
    expect(() => setContent('')).not.toThrow();
  });
});
