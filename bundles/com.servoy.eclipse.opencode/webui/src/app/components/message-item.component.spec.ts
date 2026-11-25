import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { MessageItemComponent } from './message-item.component';
import { Part } from '../models/opencode.models';
import { ChildSignals } from '../services/agent-status';
import { FileReaderService } from '../services/file-reader.service';
import { LoadedMessagesService } from '../services/loaded-messages.service';

describe('MessageItemComponent', () => {
  let fixture: ComponentFixture<MessageItemComponent>;
  let component: MessageItemComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MessageItemComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
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
    expect(rendered).toHaveLength(2);
    expect(rendered.map((p) => p.type)).toEqual(['text', 'tool']);
    expect(rendered.some((p) => p.type === 'reasoning')).toBe(false);
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
    const tool = { type: 'tool', tool: 'read', state: { status: 'running' } } as Part;

    expect(component.isText(text)).toBe(true);
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

  it('renders a subagent part as an agent card whose Open button emits the child id', () => {
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
    fixture.componentRef.setInput('parts', [sub]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('svy-agent-card')).toBeTruthy();
    expect(el.querySelector('.tool-row')).toBeNull();

    let opened: string | null = null;
    component.openSession.subscribe((id) => (opened = id));
    (el.querySelector('.agent-open') as HTMLButtonElement).click();
    expect(opened).toBe('ses_child_1');
  });

  it('resolves the subagent child id from input.sessionID when the call continued a child', () => {
    const sub = {
      type: 'tool',
      tool: 'subagent',
      state: { status: 'completed', input: { description: 'Re-review', sessionID: 'ses_child_2' } }
    } as Part;
    fixture.componentRef.setInput('parts', [sub]);
    fixture.detectChanges();
    let opened: string | null = null;
    component.openSession.subscribe((id) => (opened = id));
    ((fixture.nativeElement as HTMLElement).querySelector('.agent-open') as HTMLButtonElement).click();
    expect(opened).toBe('ses_child_2');
  });

  it('disables the agent card Open button when no child id resolves', () => {
    const sub = {
      type: 'tool',
      tool: 'subagent',
      state: { status: 'running', input: { description: 'pending' } }
    } as Part;
    fixture.componentRef.setInput('parts', [sub]);
    fixture.detectChanges();
    const btn = (fixture.nativeElement as HTMLElement).querySelector('.agent-open') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
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
    fixture.componentRef.setInput('turnBusy', true);
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

  describe('reasoning is hidden, "Thinking..." stands in while the model works (SVY-21845)', () => {
    const reasoningPart = (over: Partial<Part> = {}): Part =>
      ({ type: 'reasoning', id: 'r1', text: 'because the model thought hard', ...over } as Part);
    const textPart = (): Part => ({ type: 'text', id: 't1', text: 'the answer' } as Part);
    const toolPart = (): Part => ({ type: 'tool', id: 'tool1', tool: 'read', state: { status: 'running' } } as Part);
    const el = () => fixture.nativeElement as HTMLElement;
    const render = (parts: Part[], inputs: Record<string, unknown> = {}): void => {
      fixture.componentRef.setInput('role', 'assistant');
      fixture.componentRef.setInput('parts', parts);
      fixture.componentRef.setInput('completed', null);
      fixture.componentRef.setInput('turnBusy', true);
      for (const [k, v] of Object.entries(inputs)) {
        fixture.componentRef.setInput(k, v);
      }
      fixture.detectChanges();
    };

    it('never renders the Reasoning label, caret or text, even when the reasoning has content', () => {
      render([reasoningPart(), textPart()]);
      expect(el().querySelector('.reasoning-block')).toBeNull();
      expect(el().querySelector('.reasoning-row')).toBeNull();
      expect(el().textContent).not.toContain('Reasoning');
      expect(el().textContent).not.toContain('because the model thought hard');
      expect(el().textContent).toContain('the answer');
    });

    it('does not count a reasoning part as renderable', () => {
      render([reasoningPart(), reasoningPart({ id: 'r2', text: '...' })]);
      expect(component.renderable()).toHaveLength(0);
    });

    it('shows "Thinking..." while the turn is busy and there is no part yet', () => {
      render([]);
      expect(component.isThinking()).toBe(true);
      expect(el().querySelector('svy-thinking .thinking')?.textContent?.trim()).toBe('Thinking...');
    });

    it('shows "Thinking..." while the model has only reasoned so far (reasoning stays hidden)', () => {
      render([reasoningPart()]);
      expect(component.isThinking()).toBe(true);
      expect(el().querySelector('svy-thinking')).not.toBeNull();
      expect(el().textContent).not.toContain('because the model thought hard');
    });

    it('shows "Thinking..." for a just-started, still empty reasoning part', () => {
      render([reasoningPart({ text: '' })]);
      expect(component.isThinking()).toBe(true);
    });

    it('shows "Thinking..." again when the model thinks after a finished tool call', () => {
      render([toolPart(), reasoningPart()]);
      expect(component.isThinking()).toBe(true);
    });

    it('goes away as soon as an answer is visible', () => {
      render([reasoningPart()]);
      expect(el().querySelector('svy-thinking')).not.toBeNull();
      fixture.componentRef.setInput('parts', [reasoningPart(), textPart()]);
      fixture.detectChanges();
      expect(component.isThinking()).toBe(false);
      expect(el().querySelector('svy-thinking')).toBeNull();
    });

    it('does not show next to a tool row that is running', () => {
      render([reasoningPart(), toolPart()]);
      expect(component.isThinking()).toBe(false);
      expect(el().querySelector('svy-thinking')).toBeNull();
      expect(el().querySelector('.tool')).not.toBeNull();
    });

    it('does not show next to a visible answer even though reasoning came after it', () => {
      render([textPart(), reasoningPart()]);
      expect(component.isThinking()).toBe(true);
      render([reasoningPart(), textPart()]);
      expect(component.isThinking()).toBe(false);
    });

    it('goes away when the turn completes', () => {
      render([reasoningPart()]);
      fixture.componentRef.setInput('completed', 123);
      fixture.detectChanges();
      expect(component.isThinking()).toBe(false);
      expect(el().querySelector('svy-thinking')).toBeNull();
    });

    it('goes away when the session is no longer busy (stopped or failed without a completion time)', () => {
      render([reasoningPart()]);
      expect(component.isThinking()).toBe(true);
      fixture.componentRef.setInput('turnBusy', false);
      fixture.detectChanges();
      expect(component.isThinking()).toBe(false);
      expect(el().querySelector('svy-thinking')).toBeNull();
    });

    it('goes away on an error and shows the error instead', () => {
      render([reasoningPart()], { error: { name: 'RateLimit', data: { message: 'slow down' } } });
      expect(component.isThinking()).toBe(false);
      expect(el().querySelector('svy-thinking')).toBeNull();
      expect(el().querySelector('.error-notice')?.textContent).toContain('slow down');
    });

    it('does not show on a message that is not the newest one', () => {
      render([reasoningPart()], { latest: false });
      expect(component.isThinking()).toBe(false);
      expect(el().querySelector('svy-thinking')).toBeNull();
    });

    it('does not show on a user message', () => {
      render([], { role: 'user' });
      expect(component.isThinking()).toBe(false);
    });

    it('does not say "No response was returned" for a finished turn that only had reasoning', () => {
      render([reasoningPart()], { completed: 123, turnBusy: false });
      expect(component.isEmpty()).toBe(false);
      expect(el().textContent).not.toContain('No response was returned');
    });

    it('collapses the host while a message has nothing to show (only hidden reasoning, finished)', () => {
      render([reasoningPart()], { completed: 123, turnBusy: false });
      expect(component.nothingToShow()).toBe(true);
      expect(el().classList.contains('nothing-to-show')).toBe(true);
    });

    it('does not collapse the host while "Thinking..." is shown', () => {
      render([reasoningPart()]);
      expect(component.nothingToShow()).toBe(false);
      expect(el().classList.contains('nothing-to-show')).toBe(false);
    });

    it('keeps the reasoning data on the part untouched', () => {
      const part = reasoningPart();
      render([part]);
      expect(part.text).toBe('because the model thought hard');
      expect(component.parts()[0]).toBe(part);
    });

    it('honours prefers-reduced-motion in the indicator styles', () => {
      render([]);
      const styles = Array.from(document.head.querySelectorAll('style')).map((s) => s.textContent ?? '').join('\n');
      expect(styles).toContain('prefers-reduced-motion');
      expect(styles).toContain('svy-thinking-pulse');
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

  describe('agent card Open button (SVY-21849, supersedes SVY-21764 link)', () => {
    function part(): Part {
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

    function mount(parts: Part[]): HTMLElement {
      fixture.componentRef.setInput('parts', parts);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    it('renders the Open button on first paint without hover', () => {
      const btn = mount([part()]).querySelector('.agent-open') as HTMLButtonElement | null;
      expect(btn).toBeTruthy();
      expect(btn!.disabled).toBe(false);
      expect(btn!.textContent).toContain('Open');
    });

    it('does not emit openSession when the card body is clicked', () => {
      const el = mount([part()]);
      let opened: string | null = null;
      component.openSession.subscribe((id) => (opened = id));
      (el.querySelector('.agent-card') as HTMLElement).click();
      expect(opened).toBeNull();
    });

    it('renders no agent card for a non-subagent tool part', () => {
      const el = mount([
        { type: 'tool', tool: 'read', id: 't1', state: { status: 'completed', output: 'body' } } as Part
      ]);
      expect(el.querySelector('svy-agent-card')).toBeNull();
      expect(el.querySelector('.tool-row')).toBeTruthy();
    });
  });

  describe('agent card live status (SVY-21849)', () => {
    const launched = (): Part =>
      ({
        type: 'tool',
        tool: 'subagent',
        id: 'sa-live',
        state: {
          status: 'completed',
          input: { agent: 'Developer', description: 'Build it' },
          metadata: { sessionID: 'ses_child' },
          output: 'The subagent is working in the background'
        }
      }) as Part;

    function mount(parts: Part[], inputs: Record<string, unknown> = {}): HTMLElement {
      fixture.componentRef.setInput('parts', parts);
      for (const [k, v] of Object.entries(inputs)) {
        fixture.componentRef.setInput(k, v);
      }
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    const statusText = (el: HTMLElement) => el.querySelector('.agent-status')?.textContent?.trim();

    it('renders the card with name, tag and no old tool row, caret or subagent link', () => {
      const el = mount([launched()]);
      expect(el.querySelector('svy-agent-card')).toBeTruthy();
      expect(el.querySelector('.agent-name')?.textContent?.trim()).toBe('Developer');
      expect(el.querySelector('.agent-tag')?.textContent?.trim()).toBe('new session');
      expect(el.querySelector('.tool-row')).toBeNull();
      expect(el.querySelector('.tool-caret')).toBeNull();
      expect(el.querySelector('.subagent-open')).toBeNull();
    });

    it('shows Running with a spinner when the child is busy although the tool part is completed', () => {
      const el = mount([launched()], { childSignals: new Map([['ses_child', { busy: true }]]) });
      expect(statusText(el)).toBe('Running');
      expect(el.querySelector('.spinner')).toBeTruthy();
    });

    it('flips from running to stopped when the busy signal disappears and the parent is not live', () => {
      const el = mount([launched()], { childSignals: new Map([['ses_child', { busy: true }]]) });
      expect(statusText(el)).toBe('Running');

      fixture.componentRef.setInput('childSignals', new Map([['ses_child', { busy: false }]]));
      expect(() => fixture.detectChanges()).not.toThrow();

      expect(statusText(el)).toBe('Stopped');
      expect(el.querySelector('.spinner')).toBeNull();
    });

    it('flips from running to done when the child outcome becomes succeeded', () => {
      const el = mount([launched()], { childSignals: new Map([['ses_child', { busy: true }]]) });
      fixture.componentRef.setInput('childSignals', new Map([['ses_child', { busy: false, outcome: 'succeeded' }]]));
      fixture.detectChanges();
      expect(statusText(el)).toBe('Done');
      expect(el.querySelector('.agent-card')?.classList.contains('done')).toBe(true);
    });

    it('a completed launch with no signals is Running only while the parent turn is live', () => {
      const el = mount([launched()], { turnBusy: true, completed: null });
      expect(statusText(el)).toBe('Running');

      fixture.componentRef.setInput('turnBusy', false);
      fixture.detectChanges();
      expect(statusText(el)).toBe('Stopped');
      expect(el.querySelector('.spinner')).toBeNull();
    });

    it('a finished (completed) assistant message is not live even when the turn is busy', () => {
      const el = mount([launched()], { turnBusy: true, completed: 12345 });
      expect(statusText(el)).toBe('Stopped');
      expect(el.querySelector('.spinner')).toBeNull();
    });

    it('a result with Status: INPUT_REQUIRED shows the needs-a-decision stop label', () => {
      const body = 'Analysis done\nStatus: INPUT_REQUIRED\nWhich option do you want?';
      const el = mount([launched()], {
        childSignals: new Map([['ses_child', { busy: false, outcome: 'succeeded', result: { state: 'completed', body } }]])
      });
      expect(statusText(el)).toBe('Stopped — needs a decision');
      expect(el.querySelector('.agent-card')?.classList.contains('stopped')).toBe(true);
      expect(el.querySelector('.agent-summary')?.textContent?.trim()).toBe('Which option do you want?');
    });

    it('a completed legacy tool output with Status: INPUT_REQUIRED shows the stop label', () => {
      const legacy = {
        type: 'tool',
        tool: 'subagent',
        id: 'sa-legacy',
        state: {
          status: 'completed',
          input: { agent: 'Developer', description: 'Old run' },
          output: '<subagent sessionID="ses_old" state="completed">\nStatus: INPUT_REQUIRED\nPick one\n</subagent>'
        }
      } as Part;
      const el = mount([legacy]);
      expect(statusText(el)).toBe('Stopped — needs a decision');
    });

    it('a plain completed result shows Done with the report line as summary', () => {
      const el = mount([launched()], {
        childSignals: new Map([['ses_child', { busy: false, result: { state: 'completed', body: 'Built it.\nStatus: COMPLETED' } }]])
      });
      expect(statusText(el)).toBe('Done');
      expect(el.querySelector('.agent-summary')?.textContent?.trim()).toBe('Built it.');
    });

    it('looks the child up by its id: signals for another child do not affect this card', () => {
      const el = mount([launched()], { childSignals: new Map([['ses_other', { busy: true }]]) });
      expect(statusText(el)).toBe('Stopped');
    });

    it('emits openSession with the child id from the card Open button exactly once', () => {
      const el = mount([launched()]);
      const opened: string[] = [];
      component.openSession.subscribe((id) => opened.push(id));
      (el.querySelector('.agent-open') as HTMLButtonElement).click();
      expect(opened).toEqual(['ses_child']);
    });

    it('renders a continued tag from input.sessionID', () => {
      const cont = {
        type: 'tool',
        tool: 'subagent',
        id: 'sa-cont',
        state: { status: 'completed', input: { agent: 'Developer', sessionID: 'ses_child' } }
      } as Part;
      expect(mount([cont]).querySelector('.agent-tag')?.textContent?.trim()).toBe('continued');
    });

    it('renders each subagent part as its own card with its own child state', () => {
      const second = {
        type: 'tool',
        tool: 'subagent',
        id: 'sa-2',
        state: { status: 'completed', input: { agent: 'Reviewer' }, metadata: { sessionID: 'ses_child_2' } }
      } as Part;
      const el = mount([launched(), second], {
        childSignals: new Map<string, { busy?: boolean; outcome?: 'succeeded' }>([
          ['ses_child', { busy: true }],
          ['ses_child_2', { busy: false, outcome: 'succeeded' }]
        ])
      });
      const statuses = Array.from(el.querySelectorAll('.agent-status')).map((e) => e.textContent?.trim());
      expect(statuses).toEqual(['Running', 'Done']);
    });

    it('leaves non-subagent tool rows unchanged next to a card', () => {
      const read = { type: 'tool', tool: 'read', id: 't1', state: { status: 'completed', output: 'body' } } as Part;
      const el = mount([launched(), read]);
      expect(el.querySelectorAll('svy-agent-card')).toHaveLength(1);
      expect(el.querySelectorAll('.tool-row')).toHaveLength(1);
    });

    it('a text part that is a background-result renders nothing', () => {
      fixture.componentRef.setInput('parts', [
        { type: 'text', text: '<background-result kind="subagent" sessionID="ses_child" state="completed">\nbody\n</background-result>' } as Part
      ]);
      fixture.detectChanges();
      expect(component.renderable()).toHaveLength(0);
      expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('background-result');
    });
  });

  describe('file chips (SVY-21851)', () => {
    const write = (filePath: string, status = 'completed'): Part =>
      ({ type: 'tool', tool: 'write', id: 'w-' + filePath, state: { status, input: { filePath } } }) as unknown as Part;
    const subagent = (childId: string): Part =>
      ({
        type: 'tool',
        tool: 'subagent',
        id: 'sa-' + childId,
        state: { status: 'completed', input: { agent: 'Dev' }, metadata: { sessionID: childId } }
      }) as unknown as Part;
    const el = () => fixture.nativeElement as HTMLElement;
    const mount = (parts: Part[], inputs: Record<string, unknown> = {}) => {
      fixture.componentRef.setInput('parts', parts);
      for (const [k, v] of Object.entries(inputs)) {
        fixture.componentRef.setInput(k, v);
      }
      fixture.detectChanges();
    };
    const chipNames = () =>
      Array.from(el().querySelectorAll('.files svy-file-chip .name')).map((n) => n.textContent!.trim());

    it('renders a Files group with md chips under an assistant message', () => {
      mount([write('src/a.js'), { type: 'text', text: 'see handoff/t/spec.md' } as Part]);
      const group = el().querySelector('.files')!;
      expect(group.getAttribute('role')).toBe('group');
      expect(group.getAttribute('aria-label')).toBe('Files');
      expect(chipNames()).toEqual(['a.js', 'spec.md']);
      expect(group.querySelectorAll('button.chip.sm')).toHaveLength(0);
      expect(group.querySelectorAll('button.chip')).toHaveLength(2);
    });

    it('places the chips after the parts', () => {
      mount([{ type: 'text', text: 'hello' } as Part, write('a.js')]);
      const message = el().querySelector('.message')!;
      const children = Array.from(message.children);
      const files = children.indexOf(el().querySelector('.files')!);
      const markdown = children.indexOf(el().querySelector('svy-markdown')!);
      expect(markdown).toBeGreaterThanOrEqual(0);
      expect(files).toBeGreaterThan(markdown);
    });

    it('renders no group when there are no files', () => {
      mount([{ type: 'text', text: 'hello' } as Part]);
      expect(el().querySelector('.files')).toBeNull();
    });

    it('renders no chips for a user message even with file refs', () => {
      mount([write('a.js'), { type: 'text', text: 'handoff/t/spec.md' } as Part], { role: 'user' });
      expect(el().querySelector('.files')).toBeNull();
      expect(component.hasFiles()).toBe(false);
    });

    it('renders no chips when showFiles is false', () => {
      mount([write('a.js')], { showFiles: false });
      expect(el().querySelector('.files')).toBeNull();
      expect(component.hasFiles()).toBe(false);
    });

    it('does not count errored tool calls', () => {
      mount([write('bad.js', 'error')]);
      expect(el().querySelector('.files')).toBeNull();
    });

    describe('cap at 6 with +N more', () => {
      const many = (n: number) => Array.from({ length: n }, (_, i) => write(`f${i}.js`));

      it('shows exactly 6 and no more button for 6 files', () => {
        mount(many(6));
        expect(chipNames()).toHaveLength(6);
        expect(el().querySelector('.files-more')).toBeNull();
      });

      it('shows the first 6 and a "+N more" button for 9 files', () => {
        mount(many(9));
        expect(chipNames()).toEqual(['f0.js', 'f1.js', 'f2.js', 'f3.js', 'f4.js', 'f5.js']);
        const more = el().querySelector<HTMLButtonElement>('.files-more')!;
        expect(more.getAttribute('type')).toBe('button');
        expect(more.textContent!.trim()).toBe('+3 more');
      });

      it('reveals the rest on click and removes the button', () => {
        mount(many(9));
        el().querySelector<HTMLButtonElement>('.files-more')!.click();
        fixture.detectChanges();
        expect(chipNames()).toHaveLength(9);
        expect(chipNames()[8]).toBe('f8.js');
        expect(el().querySelector('.files-more')).toBeNull();
      });

      it('shows "+1 more" for 7 files', () => {
        mount(many(7));
        expect(el().querySelector('.files-more')!.textContent!.trim()).toBe('+1 more');
      });
    });

    describe('child files', () => {
      it('includes files of an already loaded child session of a subagent part', () => {
        TestBed.inject(LoadedMessagesService).record('c1', [{ parts: [write('child/out.js')] }]);
        mount([write('own.js'), subagent('c1')]);
        expect(chipNames()).toEqual(['own.js', 'out.js']);
      });

      it('ignores loaded sessions not referenced by this message', () => {
        TestBed.inject(LoadedMessagesService).record('other', [{ parts: [write('other.js')] }]);
        mount([write('own.js'), subagent('c1')]);
        expect(chipNames()).toEqual(['own.js']);
      });

      it('includes handoff files named in the child result body', () => {
        const signals = new Map<string, ChildSignals>([
          ['c1', { busy: false, result: { state: 'completed', body: 'wrote handoff/t/review.md' } } as ChildSignals]
        ]);
        mount([subagent('c1')], { childSignals: signals });
        expect(chipNames()).toEqual(['review.md']);
      });

      it('picks up child files that are loaded after the first render, without NG0600', () => {
        mount([subagent('c1')]);
        expect(el().querySelector('.files')).toBeNull();
        TestBed.inject(LoadedMessagesService).record('c1', [{ parts: [write('late.js')] }]);
        expect(() => fixture.detectChanges()).not.toThrow();
        expect(chipNames()).toEqual(['late.js']);
      });
    });

    it('a chip click opens the reader with the file path', () => {
      const open = vi.spyOn(TestBed.inject(FileReaderService), 'open').mockImplementation(() => undefined);
      mount([write('src/a.js')]);
      el().querySelector<HTMLButtonElement>('.files button.chip')!.click();
      expect(open).toHaveBeenCalledWith('src/a.js', el().querySelector('.files button.chip'));
    });
  });
});
