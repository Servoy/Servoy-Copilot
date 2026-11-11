import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { MessageItemComponent } from './message-item.component';
import { Part } from '../models/opencode.models';

describe('MessageItemComponent', () => {
  let fixture: ComponentFixture<MessageItemComponent>;
  let component: MessageItemComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MessageItemComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(MessageItemComponent);
    component = fixture.componentInstance;
  });

  it('renders only renderable parts, filtering out synthetic and unsupported parts', () => {
    fixture.componentRef.setInput('parts', [
      { type: 'text', text: 'visible' } as Part,
      { type: 'text', text: '<system-reminder>hidden' } as Part,
      { type: 'text', text: 'flagged', synthetic: true } as Part,
      { type: 'reasoning', text: 'thinking' } as Part,
      { type: 'tool', tool: 'read' } as Part,
      { type: 'file', filename: 'a.txt' } as Part,
      { type: 'step-start' } as Part
    ]);

    const rendered = component.renderable();
    expect(rendered).toHaveLength(3);
    expect(rendered.map((p) => p.type)).toEqual(['text', 'reasoning', 'tool']);
    expect(rendered[0].text).toBe('visible');
  });

  it('does not render a message with only synthetic parts', () => {
    fixture.componentRef.setInput('parts', [
      { type: 'text', text: '<system-reminder>x' } as Part,
      { type: 'text', text: 'y', synthetic: true } as Part
    ]);
    expect(component.renderable()).toHaveLength(0);
  });

  it('classifies parts with the exposed helpers', () => {
    const text = { type: 'text', text: 'hi' } as Part;
    const reasoning = { type: 'reasoning', text: 'why' } as Part;
    const tool = { type: 'tool', tool: 'read', state: { status: 'running' } } as Part;

    expect(component.isText(text)).toBe(true);
    expect(component.isReasoning(reasoning)).toBe(true);
    expect(component.isTool(tool)).toBe(true);
    expect(component.toolName(tool)).toBe('Read File');
  });

  it('renders an image attachment part as a thumbnail in the DOM', () => {
    fixture.componentRef.setInput('role', 'user');
    fixture.componentRef.setInput('parts', [
      { type: 'file', mime: 'image/png', filename: 'shot.png', url: 'data:image/png;base64,AAA' } as Part,
      { type: 'text', text: 'what is this?' } as Part
    ]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const img = el.querySelector('img.attachment-image') as HTMLImageElement | null;
    expect(img).toBeTruthy();
    expect(img?.getAttribute('src')).toBe('data:image/png;base64,AAA');
    expect(img?.getAttribute('alt')).toBe('shot.png');
    expect(el.textContent).toContain('what is this?');
  });

  it('renders a non-image attachment part as a named chip', () => {
    fixture.componentRef.setInput('role', 'user');
    fixture.componentRef.setInput('parts', [
      { type: 'file', mime: 'application/pdf', filename: 'spec.pdf', url: 'file:///spec.pdf' } as Part
    ]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('img.attachment-image')).toBeNull();
    expect(el.querySelector('.attachment-chip')?.textContent).toContain('spec.pdf');
  });

  it('keeps a subagent row expandable (its output) AND exposes an open link that emits the child id', () => {
    const sub = {
      type: 'tool',
      tool: 'subagent',
      id: 'sa1',
      state: {
        status: 'completed',
        input: { description: 'Build it', agent: 'Developer' },
        output: '<subagent sessionID="ses_child_1" state="completed">\n## Summary'
      }
    } as Part;

    expect(component.isSubagentLink(sub)).toBe(true);
    // The row still expands/collapses to show the subagent output.
    expect(component.canExpand(sub)).toBe(true);
    expect(component.toolName(sub)).toBe('Subagent');
    expect(component.toolSubtitle(sub)).toBe('Build it');
    expect(component.subagentLabel(sub)).toBe('Build it subagent');

    // The separate "Open subagent" link navigates into the child session.
    let opened: string | null = null;
    component.openSession.subscribe((id) => (opened = id));
    component.openSubagent(sub);
    expect(opened).toBe('ses_child_1');
  });

  it('resolves the subagent child id from input.sessionID when the call continued a child', () => {
    const sub = {
      type: 'tool',
      tool: 'subagent',
      state: { status: 'completed', input: { description: 'Re-review', sessionID: 'ses_child_2' } }
    } as Part;
    expect(component.isSubagentLink(sub)).toBe(true);
    let opened: string | null = null;
    component.openSession.subscribe((id) => (opened = id));
    component.openSubagent(sub);
    expect(opened).toBe('ses_child_2');
  });

  it('a subagent part with no resolvable child id is not treated as a link', () => {
    const sub = {
      type: 'tool',
      tool: 'subagent',
      state: { status: 'running', input: { description: 'pending' } }
    } as Part;
    expect(component.isSubagentLink(sub)).toBe(false);
  });

  it('shows the loaded skill id as the subtitle of a skill tool part', () => {
    const skill = {
      type: 'tool',
      tool: 'skill',
      state: { status: 'completed', input: { id: 'servoy-orchestrator' } }
    } as Part;
    expect(component.toolName(skill)).toBe('Load Skill');
    expect(component.toolSubtitle(skill)).toBe('servoy-orchestrator');
  });

  it('shows a friendly name and an argument subtitle for a tool part', () => {
    const tool = {
      type: 'tool',
      tool: 'bash',
      state: { status: 'completed', input: { command: 'npm run build' } }
    } as Part;
    expect(component.toolName(tool)).toBe('Shell Command');
    expect(component.toolSubtitle(tool)).toBe('npm run build');
  });

  it('only allows expansion when the tool has output', () => {
    const withOutput = { type: 'tool', tool: 'read', id: 't1', state: { output: 'body' } } as Part;
    const withoutOutput = { type: 'tool', tool: 'read', id: 't2' } as Part;
    expect(component.canExpand(withOutput)).toBe(true);
    expect(component.canExpand(withoutOutput)).toBe(false);
  });

  it('toggles tool part expansion by key (only when expandable)', () => {
    const tool = { type: 'tool', tool: 'read', id: 't1', state: { output: 'body' } } as Part;
    expect(component.isExpanded(tool, 0)).toBe(false);
    component.toggle(tool, 0);
    expect(component.isExpanded(tool, 0)).toBe(true);
    component.toggle(tool, 0);
    expect(component.isExpanded(tool, 0)).toBe(false);
  });

  it('does not expand a tool part with no output', () => {
    const tool = { type: 'tool', tool: 'read', id: 't3' } as Part;
    component.toggle(tool, 0);
    expect(component.isExpanded(tool, 0)).toBe(false);
  });

  it('exposes tool output', () => {
    const tool = { type: 'tool', tool: 'read', state: { output: 'file body' } } as Part;
    expect(component.toolOutput(tool)).toBe('file body');
    expect(component.toolOutput({ type: 'tool' } as Part)).toBe('');
  });

  it('renders the visible text part in the DOM and not the synthetic one', () => {
    fixture.componentRef.setInput('parts', [
      { type: 'text', text: 'shown' } as Part,
      { type: 'text', text: '<system-reminder>secret' } as Part
    ]);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('shown');
    expect(text).not.toContain('secret');
  });

  it('surfaces a friendly message for the "No accounts" error', () => {
    fixture.componentRef.setInput('parts', []);
    fixture.componentRef.setInput('error', { name: 'UnknownError', data: { message: 'No accounts' } });
    expect(component.errorText()).toContain('No AI account is connected');
    expect(component.isEmpty()).toBe(false);

    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('No AI account is connected');
  });

  it('falls back to the error detail or name for other errors', () => {
    fixture.componentRef.setInput('error', { name: 'RateLimit', data: { message: 'slow down' } });
    expect(component.errorText()).toBe('slow down');

    fixture.componentRef.setInput('error', { name: 'RateLimit' });
    expect(component.errorText()).toBe('RateLimit');
  });

  it('shows a thinking indicator while an assistant turn is running (not completed, no parts)', () => {
    fixture.componentRef.setInput('role', 'assistant');
    fixture.componentRef.setInput('parts', []);
    fixture.componentRef.setInput('completed', null);
    expect(component.isThinking()).toBe(true);
    expect(component.isEmpty()).toBe(false);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.thinking')).toBeTruthy();
    expect((el.textContent ?? '')).not.toContain('No response was returned');
  });

  it('shows a muted "no response" notice only once the turn completed with no parts/error', () => {
    fixture.componentRef.setInput('role', 'assistant');
    fixture.componentRef.setInput('parts', []);
    fixture.componentRef.setInput('completed', 123);
    expect(component.isThinking()).toBe(false);
    expect(component.isEmpty()).toBe(true);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('No response was returned');
  });

  it('does not treat a user message as thinking', () => {
    fixture.componentRef.setInput('role', 'user');
    fixture.componentRef.setInput('parts', []);
    fixture.componentRef.setInput('completed', null);
    expect(component.isThinking()).toBe(false);
  });

  describe('reasoning collapse (SVY-21707)', () => {
    const reasoningPart = (over: Partial<Part> = {}): Part =>
      ({ type: 'reasoning', id: 'r1', text: 'because the model thought hard', ...over } as Part);

    it('renders a non-blank reasoning part collapsed by default: header present, body absent', () => {
      fixture.componentRef.setInput('parts', [reasoningPart()]);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;

      // The one-line "Reasoning" header row is present...
      const row = el.querySelector('.reasoning-row');
      expect(row).toBeTruthy();
      expect(row?.textContent).toContain('Reasoning');
      // ...but the reasoning markdown body is NOT in the DOM while collapsed.
      expect(el.querySelector('.reasoning')).toBeNull();
      expect(el.querySelector('.reasoning svy-markdown')).toBeNull();
      // Caret shows the collapsed glyph.
      expect(el.querySelector('.reasoning-caret')?.textContent?.trim()).toBe('›');
    });

    it('isReasoningExpanded defaults to false for a reasoning part', () => {
      const part = reasoningPart();
      expect(component.isReasoningExpanded(part, 0)).toBe(false);
    });

    it('expands the reasoning body when the header is clicked (caret flips › -> ▾)', () => {
      fixture.componentRef.setInput('parts', [reasoningPart()]);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;

      const row = el.querySelector('.reasoning-row') as HTMLElement;
      expect(row).toBeTruthy();
      row.click();
      fixture.detectChanges();

      // The reasoning markdown body is now rendered.
      const body = el.querySelector('.reasoning');
      expect(body).toBeTruthy();
      expect(body?.querySelector('svy-markdown')).toBeTruthy();
      // Caret flipped to the open glyph.
      expect(el.querySelector('.reasoning-caret')?.textContent?.trim()).toBe('▾');
    });

    it('collapses the reasoning body again on a second header click (body removed)', () => {
      fixture.componentRef.setInput('parts', [reasoningPart()]);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      const row = el.querySelector('.reasoning-row') as HTMLElement;

      row.click();
      fixture.detectChanges();
      expect(el.querySelector('.reasoning')).toBeTruthy();

      row.click();
      fixture.detectChanges();
      expect(el.querySelector('.reasoning')).toBeNull();
      expect(el.querySelector('.reasoning-caret')?.textContent?.trim()).toBe('›');
    });

    it('toggleReasoning flips isReasoningExpanded and is not gated by canExpand', () => {
      const part = reasoningPart();
      expect(component.isReasoningExpanded(part, 0)).toBe(false);
      component.toggleReasoning(part, 0);
      expect(component.isReasoningExpanded(part, 0)).toBe(true);
      component.toggleReasoning(part, 0);
      expect(component.isReasoningExpanded(part, 0)).toBe(false);
    });

    it('drops blank/placeholder reasoning ("...") entirely: no row, not renderable', () => {
      fixture.componentRef.setInput('parts', [
        reasoningPart({ id: 'r-blank', text: '...' }),
        reasoningPart({ id: 'r-ellipsis', text: '…' }),
        reasoningPart({ id: 'r-ws', text: '   ' })
      ]);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;

      expect(component.renderable()).toHaveLength(0);
      expect(el.querySelector('.reasoning-row')).toBeNull();
      expect(el.querySelector('.reasoning')).toBeNull();
    });

    it('keeps the reasoning part collapsed independently of turn completion', () => {
      // A completed turn still shows the reasoning collapsed (no auto-expand).
      fixture.componentRef.setInput('parts', [reasoningPart()]);
      fixture.componentRef.setInput('completed', 123);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('.reasoning-row')).toBeTruthy();
      expect(el.querySelector('.reasoning')).toBeNull();
      expect(el.querySelector('.reasoning svy-markdown')).toBeNull();
    });

    it('renders a still-streaming reasoning part (completed=null) collapsed by default', () => {
      // A reasoning part that has arrived but whose turn is still running
      // (message-level completed is null). The spec's §3.2 decision is that
      // reasoning is collapsed by default throughout streaming, with no
      // auto-expand while it streams.
      fixture.componentRef.setInput('role', 'assistant');
      fixture.componentRef.setInput('parts', [reasoningPart({ text: 'starting to think' })]);
      fixture.componentRef.setInput('completed', null);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;

      // The "Reasoning" header row is present while it streams...
      const row = el.querySelector('.reasoning-row');
      expect(row).toBeTruthy();
      expect(row?.textContent).toContain('Reasoning');
      // ...but the markdown body is NOT auto-expanded while streaming.
      expect(el.querySelector('.reasoning')).toBeNull();
      expect(el.querySelector('.reasoning svy-markdown')).toBeNull();
      expect(el.querySelector('.reasoning-caret')?.textContent?.trim()).toBe('›');
    });

    it('keeps a user-expanded reasoning part expanded as a streaming delta appends (completed=null)', () => {
      // User expands reasoning mid-stream, then more reasoning text streams in.
      // Because expand state is keyed by the stable part id, the body stays
      // expanded across the delta - no auto-collapse on new text.
      fixture.componentRef.setInput('role', 'assistant');
      fixture.componentRef.setInput('parts', [reasoningPart({ text: 'step one' })]);
      fixture.componentRef.setInput('completed', null);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;

      // User expands it mid-stream.
      const row = el.querySelector('.reasoning-row') as HTMLElement;
      row.click();
      fixture.detectChanges();
      expect(el.querySelector('.reasoning')).toBeTruthy();
      expect(el.querySelector('.reasoning svy-markdown')).toBeTruthy();

      // A delta arrives: the SAME part id, longer text, still streaming.
      fixture.componentRef.setInput('parts', [reasoningPart({ text: 'step one, then step two' })]);
      fixture.detectChanges();

      // The body is STILL expanded after the delta (expand survives deltas).
      expect(el.querySelector('.reasoning')).toBeTruthy();
      expect(el.querySelector('.reasoning svy-markdown')).toBeTruthy();
      expect(el.querySelector('.reasoning-caret')?.textContent?.trim()).toBe('▾');
    });
  });

  describe('image lightbox (SVY-21709)', () => {
    // A 1x1 transparent PNG data: URL - a real, non-empty image attachment so
    // isImageFilePart renders the thumbnail button.
    const IMG_URL =
      'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';

    /** Mount an image attachment and run a first change-detection pass. */
    function mountImage(filename = 'shot.png', url = IMG_URL): HTMLElement {
      fixture.componentRef.setInput('role', 'user');
      fixture.componentRef.setInput('parts', [
        { type: 'file', mime: 'image/png', filename, url } as Part
      ]);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    /** Open the lightbox by clicking the thumbnail trigger, then re-render. */
    function openViaClick(el: HTMLElement): void {
      const trigger = el.querySelector('.attachment-image-link') as HTMLButtonElement | null;
      expect(trigger).toBeTruthy();
      trigger!.click();
      fixture.detectChanges();
    }

    // AC(a): clicking the thumbnail opens the enlarged in-document overlay.
    it('opens the lightbox on thumbnail click, enlarging the image with its src', () => {
      const el = mountImage();
      // Closed to start: no overlay in the DOM.
      expect(el.querySelector('.image-lightbox')).toBeNull();

      openViaClick(el);

      const overlay = el.querySelector('.image-lightbox');
      expect(overlay).toBeTruthy();
      const enlarged = el.querySelector('img.image-lightbox-img') as HTMLImageElement | null;
      expect(enlarged).toBeTruthy();
      expect(enlarged?.getAttribute('src')).toBe(IMG_URL);
      // The enlarged image keeps an alt (from the filename) for assistive tech.
      expect(enlarged?.getAttribute('alt')).toBe('shot.png');
    });

    // AC(b): clicking the backdrop (the overlay container) dismisses it.
    it('closes the lightbox when the backdrop is clicked', () => {
      const el = mountImage();
      openViaClick(el);
      const overlay = el.querySelector('.image-lightbox') as HTMLElement;
      expect(overlay).toBeTruthy();

      overlay.click();
      fixture.detectChanges();

      expect(el.querySelector('.image-lightbox')).toBeNull();
    });

    // AC(b): clicking the enlarged image itself does NOT dismiss (stopPropagation).
    it('does NOT close when the enlarged image itself is clicked', () => {
      const el = mountImage();
      openViaClick(el);
      const enlarged = el.querySelector('img.image-lightbox-img') as HTMLImageElement;
      expect(enlarged).toBeTruthy();

      enlarged.click();
      fixture.detectChanges();

      // The overlay is still present - the click did not bubble to the backdrop.
      expect(el.querySelector('.image-lightbox')).toBeTruthy();
    });

    // AC(b)/(d): pressing Escape on the overlay dismisses it.
    it('closes the lightbox on Escape', () => {
      const el = mountImage();
      openViaClick(el);
      const overlay = el.querySelector('.image-lightbox') as HTMLElement;
      expect(overlay).toBeTruthy();

      overlay.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })
      );
      fixture.detectChanges();

      expect(el.querySelector('.image-lightbox')).toBeNull();
    });

    // AC(b): the dedicated close button (✕) dismisses it.
    it('closes the lightbox when the close button is clicked', () => {
      const el = mountImage();
      openViaClick(el);
      const close = el.querySelector('.image-lightbox-close') as HTMLButtonElement | null;
      expect(close).toBeTruthy();

      close!.click();
      fixture.detectChanges();

      expect(el.querySelector('.image-lightbox')).toBeNull();
    });

    // AC(c): no new-window regression - the image attachment is a <button>, not
    // an anchor, and nothing targets a new window.
    it('renders the image attachment with no target="_blank" / new-window anchor', () => {
      const el = mountImage();
      // The trigger is a <button>, not an <a>.
      const trigger = el.querySelector('.attachment-image-link');
      expect(trigger?.tagName.toLowerCase()).toBe('button');
      // No anchor opens a new window anywhere in the rendered attachment.
      expect(el.querySelector('a[target="_blank"]')).toBeNull();
      expect(el.querySelector('a.attachment-image-link')).toBeNull();
    });

    // AC(d): the open overlay is an accessible modal dialog.
    it('exposes role="dialog" and aria-modal="true" on the open overlay', () => {
      const el = mountImage();
      openViaClick(el);
      const overlay = el.querySelector('.image-lightbox') as HTMLElement;
      expect(overlay).toBeTruthy();
      expect(overlay.getAttribute('role')).toBe('dialog');
      expect(overlay.getAttribute('aria-modal')).toBe('true');
      // aria-label describes the modal from the image alt/filename.
      expect(overlay.getAttribute('aria-label')).toBe('shot.png');
    });

    it('openLightbox sets the signal and closeLightbox clears it', () => {
      const part = { type: 'file', mime: 'image/png', filename: 'p.png', url: IMG_URL } as Part;
      expect(component.lightboxImage()).toBeNull();

      component.openLightbox(part);
      expect(component.lightboxImage()).toEqual({ url: IMG_URL, alt: 'p.png' });

      component.closeLightbox();
      expect(component.lightboxImage()).toBeNull();
    });
  });

  describe('always-visible "Open subagent" link (SVY-21764)', () => {
    // NOTE on JSDOM/SCSS: Angular component SCSS is NOT applied in the Vitest
    // (JSDOM) unit setup, so the always-on accent/opacity declarations from
    // message-item.component.scss (opacity: 1; color: var(--svy-accent, ...))
    // cannot be read back via getComputedStyle here. The SVY-21764 guarantee is
    // therefore asserted on what IS observable in the DOM: the link element is
    // rendered on first paint (no hover event dispatched) whenever
    // isSubagentLink(part) is true, is not behind an [hidden] / aria-hidden gate,
    // carries the .subagent-open class, and its click wiring opens the child
    // session. The visual accent/opacity is covered by the SCSS itself + the
    // spec's manual visual check; these tests lock the structural guarantee that
    // the link is always present (not hover-revealed) and is the (only) open
    // target.

    /** A completed subagent tool part whose child id resolves from the output envelope. */
    function openableSubagentPart(): Part {
      return {
        type: 'tool',
        tool: 'subagent',
        id: 'sa1',
        state: {
          status: 'completed',
          input: { description: 'Build it', agent: 'Developer' },
          output: '<subagent sessionID="ses_child_1" state="completed">\n## Summary'
        }
      } as Part;
    }

    /** Render a single part and run a first change-detection pass. */
    function mount(parts: Part[]): HTMLElement {
      fixture.componentRef.setInput('parts', parts);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    // AC: the link is present and announces itself from first paint, no hover.
    it('renders the .subagent-open link on first paint without any hover, un-gated', () => {
      const el = mount([openableSubagentPart()]);

      const link = el.querySelector('.subagent-open') as HTMLButtonElement | null;
      // Present on the very first render - no hover/mouseover was dispatched.
      expect(link).toBeTruthy();
      // It is a real button and not hidden behind a visibility gate.
      expect(link!.tagName.toLowerCase()).toBe('button');
      expect(link!.hasAttribute('hidden')).toBe(false);
      expect(link!.getAttribute('aria-hidden')).toBeNull();
      // Carries the expected class and the "↗ Open ... subagent" affordance text.
      expect(link!.classList.contains('subagent-open')).toBe(true);
      expect(link!.textContent).toContain('Open');
      expect(link!.textContent).toContain('Build it subagent');
    });

    // AC: clicking the link opens the child session (emits openSession with the id).
    it('emits openSession with the resolved child id when the link is clicked', () => {
      const el = mount([openableSubagentPart()]);
      const link = el.querySelector('.subagent-open') as HTMLButtonElement | null;
      expect(link).toBeTruthy();

      let opened: string | null = null;
      component.openSession.subscribe((id) => (opened = id));

      link!.click();
      fixture.detectChanges();

      expect(opened).toBe('ses_child_1');
    });

    // AC: the whole row is NOT the open target - only the dedicated link opens it.
    it('does not emit openSession when the subagent tool row itself is clicked', () => {
      const el = mount([openableSubagentPart()]);

      let opened: string | null = null;
      component.openSession.subscribe((id) => (opened = id));

      const row = el.querySelector('.tool-row') as HTMLElement | null;
      expect(row).toBeTruthy();
      row!.click();
      fixture.detectChanges();

      // The row toggles expand/collapse; it must not navigate into the child.
      expect(opened).toBeNull();
    });

    // AC: not rendered when the part is not an openable subagent (id unresolved).
    it('does not render the .subagent-open link when no child id resolves', () => {
      const pending = {
        type: 'tool',
        tool: 'subagent',
        id: 'sa-pending',
        state: { status: 'running', input: { description: 'pending' } }
      } as Part;
      // No metadata.sessionID, no input.sessionID, no output marker -> not openable.
      expect(component.isSubagentLink(pending)).toBe(false);

      const el = mount([pending]);
      expect(el.querySelector('.subagent-open')).toBeNull();
    });

    // AC: not rendered for an ordinary (non-subagent) tool part.
    it('does not render the .subagent-open link for a non-subagent tool part', () => {
      const el = mount([
        { type: 'tool', tool: 'read', id: 't1', state: { status: 'completed', output: 'body' } } as Part
      ]);
      expect(el.querySelector('.subagent-open')).toBeNull();
    });

    // Regression guard: the hover-gated expand/collapse row/caret is untouched -
    // an openable subagent row is still expandable and still shows its caret.
    it('keeps the subagent tool row expandable with its caret (row affordance untouched)', () => {
      const el = mount([openableSubagentPart()]);

      const tool = el.querySelector('.tool') as HTMLElement | null;
      expect(tool).toBeTruthy();
      // The row is still an expandable tool (expand/collapse of the output).
      expect(tool!.classList.contains('expandable')).toBe(true);
      // The caret element (hover/open-revealed by SCSS) is still in the DOM.
      expect(el.querySelector('.tool-caret')).toBeTruthy();
      // ...and both affordances coexist: the open link is also present.
      expect(el.querySelector('.subagent-open')).toBeTruthy();
    });
  });
});
