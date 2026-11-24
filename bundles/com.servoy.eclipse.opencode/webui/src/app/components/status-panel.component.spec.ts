import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ChatStore } from '../services/chat-store.service';
import { StatusPanelComponent } from './status-panel.component';

type McpData = Array<{ name: string; status: { status: string; error?: string } }>;
type ProviderData = Array<{ id: string; activation?: string }>;

interface FlushOpts {
  info?: unknown;
  mcpData?: McpData;
  providerData?: ProviderData;
  solution?: unknown;
}

describe('StatusPanelComponent', () => {
  let fixture: ComponentFixture<StatusPanelComponent>;
  let component: StatusPanelComponent;
  let httpMock: HttpTestingController;
  let messages: ReturnType<typeof signal<unknown[]>>;
  let streaming: ReturnType<typeof signal<boolean>>;

  beforeEach(() => {
    messages = signal<unknown[]>([]);
    streaming = signal(false);
    TestBed.configureTestingModule({
      imports: [StatusPanelComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ChatStore, useValue: { messages, streaming } }
      ]
    });
    fixture = TestBed.createComponent(StatusPanelComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  function flushAll(opts: FlushOpts = {}): void {
    httpMock.expectOne('rest_api/info').flush(opts.info ?? { version: '2.0.18' });
    httpMock.expectOne('rest_api/mcp').flush({ data: opts.mcpData ?? [] });
    httpMock.expectOne('rest_api/provider').flush({ data: opts.providerData ?? [] });
    httpMock.expectOne('solution').flush(opts.solution ?? { name: null });
  }

  const KIRO: ProviderData = [{ id: 'kiro', activation: 'auto' }];

  function init(opts: FlushOpts = {}): void {
    fixture.detectChanges();
    flushAll(opts);
    fixture.detectChanges();
  }

  function initReady(opts: FlushOpts = {}): void {
    init({ providerData: KIRO, ...opts });
  }

  const el = (): HTMLElement => fixture.nativeElement;
  const q = <T extends Element>(sel: string): T | null => el().querySelector<T>(sel);
  const text = (sel: string): string => (q(sel)?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const summaryText = (): string => (q('.summary-text')?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const headDot = (): HTMLElement => q<HTMLElement>('.summary > .dot')!;
  const toggle = (): HTMLButtonElement => q<HTMLButtonElement>('button.mcp-toggle')!;
  const factValues = (): string[] =>
    Array.from(el().querySelectorAll('.fact .fact-value')).map((e) => (e.textContent ?? '').replace(/\s+/g, ' ').trim());
  const factLabels = (): string[] =>
    Array.from(el().querySelectorAll('.fact .fact-label')).map((e) => (e.textContent ?? '').trim());
  const mcpRowsDom = (): Array<{ name: string; state: string }> =>
    Array.from(el().querySelectorAll('.mcp-row')).map((r) => ({
      name: (r.querySelector('.mcp-name')?.textContent ?? '').trim(),
      state: (r.querySelector('.mcp-state')?.textContent ?? '').trim()
    }));

  function openMcp(): void {
    toggle().click();
    fixture.detectChanges();
  }

  const msg = (type: string, model?: unknown) => ({ info: { type, ...(model !== undefined ? { model } : {}) }, parts: [] });

  describe('lifecycle', () => {
    it('fetches once on init, before any interaction', () => {
      expect(component.overall()).toBe('idle');
      initReady({ mcpData: [{ name: 'a', status: { status: 'connected' } }] });
      expect(component.initialized()).toBe(true);
      expect(component.overall()).toBe('ok');
    });

    it('starts in the idle state until the first fetch resolves', () => {
      fixture.detectChanges();
      expect(component.overall()).toBe('idle');
      flushAll({ providerData: KIRO });
      expect(component.overall()).toBe('ok');
    });

    it('renders as a plain docked panel with no toggle, popover or dialog role', () => {
      init();
      expect(q('.status-toggle')).toBeNull();
      expect(q('.popover')).toBeNull();
      expect(q('[role="dialog"]')).toBeNull();
    });

    it('emits closed when the close button is clicked', () => {
      init();
      const closed: unknown[] = [];
      component.closed.subscribe((v) => closed.push(v));
      const btn = q<HTMLButtonElement>('button.close')!;
      expect(btn.getAttribute('aria-label')).toBe('Close status');
      btn.click();
      expect(closed.length).toBe(1);
    });

    it('does not emit closed when the refresh button is clicked', () => {
      init();
      let count = 0;
      component.closed.subscribe(() => count++);
      q<HTMLButtonElement>('button.refresh')!.click();
      flushAll();
      expect(count).toBe(0);
    });

    it('does not throw NG0600 after setting signals', () => {
      init();
      expect(() => {
        component.mcp.set({ a: { status: 'failed' } });
        component.mcpOpen.set(true);
        component.solutionName.set('x');
        messages.set([msg('assistant', { id: 'm1' })]);
        streaming.set(true);
        fixture.detectChanges();
      }).not.toThrow();
    });
  });

  describe('summary states', () => {
    it('shows "Checking Servoy AI…" and no state class before the first result', () => {
      fixture.detectChanges();
      expect(summaryText()).toBe('Checking Servoy AI…');
      expect(component.summary().state).toBe('idle');
      expect(headDot().classList.contains('ok')).toBe(false);
      expect(headDot().classList.contains('warn')).toBe(false);
      expect(headDot().classList.contains('error')).toBe(false);
      flushAll();
    });

    it('reads exactly "Ready - Servoy AI is connected" with an ok dot when everything is fine', () => {
      initReady({ mcpData: [{ name: 'a', status: { status: 'connected' } }] });
      expect(summaryText()).toBe('Ready - Servoy AI is connected');
      expect(q('.summary-text')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('Ready - Servoy AI is connected');
      expect(text('.headline')).toBe('Ready');
      expect(text('.detail')).toBe('Servoy AI is connected');
      expect(component.summary().state).toBe('ok');
      expect(headDot().classList.contains('ok')).toBe(true);
      expect(q('.summary-text')!.getAttribute('role')).toBe('status');
    });

    it('reports an error when /info has no version', () => {
      init({ info: {}, providerData: KIRO });
      expect(summaryText()).toBe('Could not reach Servoy AI. It may still be starting.');
      expect(component.overall()).toBe('error');
      expect(headDot().classList.contains('error')).toBe(true);
      expect(q('.detail')).toBeNull();
    });

    it('reports an error when the health request fails', () => {
      fixture.detectChanges();
      httpMock.expectOne('rest_api/info').error(new ProgressEvent('error'), { status: 503, statusText: 'unavailable' });
      httpMock.expectOne('rest_api/mcp').flush({ data: [] });
      httpMock.expectOne('rest_api/provider').flush({ data: KIRO });
      httpMock.expectOne('solution').flush({ name: null });
      fixture.detectChanges();
      expect(component.loadError()).toBe(true);
      expect(component.loading()).toBe(false);
      expect(summaryText()).toBe('Could not reach Servoy AI. It may still be starting.');
      expect(component.overall()).toBe('error');
    });

    it('reports "No model provider connected" for a loaded empty provider list', () => {
      init({ providerData: [], mcpData: [{ name: 'a', status: { status: 'connected' } }] });
      expect(component.hasProviders()).toBe(false);
      expect(summaryText()).toBe('No model provider connected');
      expect(component.overall()).toBe('error');
      expect(headDot().classList.contains('error')).toBe(true);
    });

    it('lets "Could not reach" win over "No model provider"', () => {
      init({ info: {}, providerData: [] });
      expect(summaryText()).toBe('Could not reach Servoy AI. It may still be starting.');
    });

    it('is warn with the "Ready" text when an enabled MCP server is offline', () => {
      initReady({
        mcpData: [
          { name: 'a', status: { status: 'connected' } },
          { name: 'b', status: { status: 'failed', error: 'x' } }
        ]
      });
      expect(summaryText()).toBe('Ready - Servoy AI is connected');
      expect(component.overall()).toBe('warn');
      expect(headDot().classList.contains('warn')).toBe(true);
      expect(headDot().classList.contains('ok')).toBe(false);
    });

    it('is ok, not warn, when only disabled MCP servers exist', () => {
      initReady({
        mcpData: [
          { name: 'a', status: { status: 'connected' } },
          { name: 'b', status: { status: 'disabled' } }
        ]
      });
      expect(component.overall()).toBe('ok');
      expect(headDot().classList.contains('warn')).toBe(false);
    });

    it('is ok with no MCP servers', () => {
      initReady();
      expect(component.overall()).toBe('ok');
    });

    it('goes ok from a healthy server before the heavy provider list arrives, then error on an empty list', () => {
      fixture.detectChanges();
      httpMock.expectOne('rest_api/info').flush({ version: '2.0.18' });
      httpMock.expectOne('rest_api/mcp').flush({ data: [{ name: 'a', status: { status: 'connected' } }] });
      const providerReq = httpMock.expectOne('rest_api/provider');
      httpMock.expectOne('solution').flush({ name: null });
      expect(component.initialized()).toBe(true);
      expect(component.overall()).toBe('ok');
      providerReq.flush({ data: [] });
      expect(component.overall()).toBe('error');
    });

    it('keeps summary().state equal to overall() and to the head dot class', () => {
      initReady({ mcpData: [{ name: 'b', status: { status: 'failed' } }] });
      expect(component.summary().state).toBe(component.overall());
      expect(headDot().classList.contains(component.overall())).toBe(true);
    });
  });

  describe('MCP block', () => {
    const thirteen = (failed: string[] = [], disabled: string[] = []): McpData =>
      Array.from({ length: 13 }, (_, i) => {
        const name = `srv-${String(i).padStart(2, '0')}`;
        const status = failed.includes(name) ? 'failed' : disabled.includes(name) ? 'disabled' : 'connected';
        return { name, status: { status, error: status === 'failed' ? 'secret failure detail' : undefined } };
      });

    it('is folded by default: aria-expanded false, no server names in the DOM', () => {
      initReady({ mcpData: thirteen() });
      expect(toggle().getAttribute('aria-expanded')).toBe('false');
      expect(toggle().tagName).toBe('BUTTON');
      expect(toggle().getAttribute('type')).toBe('button');
      expect(toggle().getAttribute('aria-controls')).toBe('status-mcp-list');
      expect(q('.mcp-list')).toBeNull();
      expect(el().textContent).not.toContain('srv-00');
    });

    it('shows "MCP servers · 12 of 13 online · 1 offline"', () => {
      initReady({ mcpData: thirteen(['srv-03']) });
      expect(text('button.mcp-toggle')).toBe('MCP servers · 12 of 13 online · 1 offline');
    });

    it('omits the offline part when 0 offline', () => {
      initReady({ mcpData: thirteen() });
      expect(text('button.mcp-toggle')).toBe('MCP servers · 13 of 13 online');
      expect(q('.mcp-offline')).toBeNull();
      expect(q('.mcp-disabled')).toBeNull();
    });

    it('shows "11 of 11 online · 2 disabled" when 2 are disabled and the rest online', () => {
      initReady({ mcpData: thirteen([], ['srv-01', 'srv-02']) });
      expect(text('button.mcp-toggle')).toBe('MCP servers · 11 of 11 online · 2 disabled');
      expect(component.overall()).toBe('ok');
    });

    it('shows "10 of 11 · 1 offline · 2 disabled" in that order', () => {
      initReady({ mcpData: thirteen(['srv-05'], ['srv-01', 'srv-02']) });
      expect(text('button.mcp-toggle')).toBe('MCP servers · 10 of 11 online · 1 offline · 2 disabled');
    });

    it('renders "0 of 0 online" for zero servers with the normal toggle', () => {
      initReady();
      expect(text('button.mcp-toggle')).toBe('MCP servers · 0 of 0 online');
      expect(toggle().getAttribute('aria-expanded')).toBe('false');
    });

    it('counts failed, needs_auth, needs_client_registration and pending as offline; disabled excluded from the total', () => {
      initReady({
        mcpData: [
          { name: 'a', status: { status: 'connected' } },
          { name: 'b', status: { status: 'failed' } },
          { name: 'c', status: { status: 'needs_auth' } },
          { name: 'd', status: { status: 'needs_client_registration' } },
          { name: 'e', status: { status: 'pending' } },
          { name: 'f', status: { status: 'disabled' } }
        ]
      });
      expect(component.mcpOnline()).toBe(1);
      expect(component.mcpOffline()).toBe(4);
      expect(component.mcpDisabled()).toBe(1);
      expect(component.mcpEnabled()).toBe(5);
      expect(text('button.mcp-toggle')).toBe('MCP servers · 1 of 5 online · 4 offline · 1 disabled');
    });

    it('unfolds on click, sorted offline, online, disabled and alphabetical in each group', () => {
      initReady({
        mcpData: [
          { name: 'zeta', status: { status: 'connected' } },
          { name: 'dis-b', status: { status: 'disabled' } },
          { name: 'beta', status: { status: 'connected' } },
          { name: 'zz-bad', status: { status: 'pending' } },
          { name: 'aa-bad', status: { status: 'failed' } },
          { name: 'dis-a', status: { status: 'disabled' } }
        ]
      });
      openMcp();
      expect(toggle().getAttribute('aria-expanded')).toBe('true');
      expect(mcpRowsDom()).toEqual([
        { name: 'aa-bad', state: 'offline' },
        { name: 'zz-bad', state: 'offline' },
        { name: 'beta', state: 'online' },
        { name: 'zeta', state: 'online' },
        { name: 'dis-a', state: 'disabled' },
        { name: 'dis-b', state: 'disabled' }
      ]);
    });

    it('folds again on a second click', () => {
      initReady({ mcpData: [{ name: 'a', status: { status: 'connected' } }] });
      openMcp();
      expect(q('.mcp-list')).not.toBeNull();
      openMcp();
      expect(q('.mcp-list')).toBeNull();
      expect(toggle().getAttribute('aria-expanded')).toBe('false');
    });

    it('uses a native button so Enter/Space activation is built in, and toggleMcp flips the state', () => {
      initReady();
      expect(toggle().tagName).toBe('BUTTON');
      expect(toggle().disabled).toBe(false);
      component.toggleMcp();
      fixture.detectChanges();
      expect(toggle().getAttribute('aria-expanded')).toBe('true');
      component.toggleMcp();
      fixture.detectChanges();
      expect(toggle().getAttribute('aria-expanded')).toBe('false');
    });

    it('shows no raw status text, error text or warning icon in unfolded rows', () => {
      initReady({
        mcpData: [
          { name: 'a', status: { status: 'connected' } },
          { name: 'b', status: { status: 'failed', error: 'secret failure detail' } },
          { name: 'c', status: { status: 'needs_auth' } },
          { name: 'd', status: { status: 'disabled' } }
        ]
      });
      openMcp();
      const list = q('.mcp-list')!;
      const content = list.textContent!;
      expect(content).not.toMatch(/connected|failed|needs_auth|pending|secret failure detail/);
      expect(list.querySelector('svg')).toBeNull();
      expect(list.querySelector('[title]')).toBeNull();
      expect(list.querySelectorAll('.mcp-row').length).toBe(4);
    });

    it('keeps the fold state across refresh()', () => {
      initReady({ mcpData: [{ name: 'a', status: { status: 'connected' } }] });
      openMcp();
      component.refresh();
      flushAll({ providerData: KIRO, mcpData: [{ name: 'a', status: { status: 'failed' } }] });
      fixture.detectChanges();
      expect(toggle().getAttribute('aria-expanded')).toBe('true');
      expect(mcpRowsDom()).toEqual([{ name: 'a', state: 'offline' }]);
    });

    it('renders the dot classes by state in unfolded rows', () => {
      initReady({
        mcpData: [
          { name: 'a', status: { status: 'connected' } },
          { name: 'b', status: { status: 'failed' } },
          { name: 'c', status: { status: 'disabled' } }
        ]
      });
      openMcp();
      const dots = Array.from(el().querySelectorAll('.mcp-row .dot')) as HTMLElement[];
      expect(dots[0].classList.contains('error')).toBe(true);
      expect(dots[1].classList.contains('ok')).toBe(true);
      expect(dots[2].classList.contains('ok')).toBe(false);
      expect(dots[2].classList.contains('error')).toBe(false);
    });
  });

  describe('facts rows', () => {
    it('lists Solution, Model, Agents in that order, between the summary and the MCP block', () => {
      initReady();
      expect(factLabels()).toEqual(['Solution', 'Model', 'Agents']);
      const order = Array.from(q('.panel')!.children).map((c) => c.className.split(' ')[0]);
      expect(order).toEqual(['summary', 'facts', 'mcp']);
    });

    it('shows the solution name returned by the endpoint and a green dot', () => {
      initReady({ solution: { name: 'crm_app' } });
      expect(factValues()[0]).toBe('crm_app');
      expect(el().querySelectorAll('.fact')[0].querySelector('.dot')!.classList.contains('ok')).toBe(true);
    });

    it('shows "-" before the solution arrives and when it is null', () => {
      fixture.detectChanges();
      expect(factValues()[0]).toBe('-');
      flushAll({ providerData: KIRO, solution: { name: null } });
      fixture.detectChanges();
      expect(factValues()[0]).toBe('-');
      expect(el().querySelectorAll('.fact')[0].querySelector('.dot')!.classList.contains('ok')).toBe(false);
    });

    it('shows "-" when the solution call fails and does not turn the state red', () => {
      fixture.detectChanges();
      httpMock.expectOne('rest_api/info').flush({ version: '2' });
      httpMock.expectOne('rest_api/mcp').flush({ data: [] });
      httpMock.expectOne('rest_api/provider').flush({ data: KIRO });
      httpMock.expectOne('solution').flush('x', { status: 500, statusText: 'err' });
      fixture.detectChanges();
      expect(factValues()[0]).toBe('-');
      expect(component.overall()).toBe('ok');
    });

    it('reflects a changed solution name on the next refresh()', () => {
      initReady({ solution: { name: 'one' } });
      expect(factValues()[0]).toBe('one');
      component.refresh();
      flushAll({ providerData: KIRO, solution: { name: 'two' } });
      fixture.detectChanges();
      expect(factValues()[0]).toBe('two');
    });

    it('shows "-" for the model when there are no messages', () => {
      initReady();
      expect(factValues()[1]).toBe('-');
      expect(component.currentModel()).toBeNull();
    });

    it('shows the newest message model id, skipping user and idle messages', () => {
      messages.set([
        msg('assistant', { id: 'old-model', providerID: 'kiro' }),
        msg('assistant', { id: 'new-model', providerID: 'kiro', variant: 'medium' }),
        msg('user'),
        msg('idle')
      ]);
      initReady();
      expect(factValues()[1]).toBe('new-model');
      expect(el().querySelectorAll('.fact')[1].querySelector('.dot')!.classList.contains('ok')).toBe(true);
    });

    it('picks up the model of a model-switched message', () => {
      messages.set([msg('assistant', { id: 'a' }), msg('model-switched', { id: 'b', providerID: 'kiro' })]);
      initReady();
      expect(factValues()[1]).toBe('b');
    });

    it('ignores empty or non-string model ids and falls back to an older one', () => {
      messages.set([msg('assistant', { id: 'good' }), msg('assistant', { id: '' }), msg('assistant', { id: 5 }), msg('assistant', null)]);
      initReady();
      expect(factValues()[1]).toBe('good');
    });

    it('follows a newer message and a replaced message list', () => {
      messages.set([msg('assistant', { id: 'm1' })]);
      initReady();
      expect(factValues()[1]).toBe('m1');
      messages.update((m) => [...m, msg('assistant', { id: 'm2' })]);
      fixture.detectChanges();
      expect(factValues()[1]).toBe('m2');
      messages.set([msg('assistant', { id: 'other-session-model' })]);
      fixture.detectChanges();
      expect(factValues()[1]).toBe('other-session-model');
      messages.set([]);
      fixture.detectChanges();
      expect(factValues()[1]).toBe('-');
    });

    it('shows Agents idle without a spinner when the viewed session is not streaming', () => {
      initReady();
      expect(factValues()[2]).toBe('idle');
      expect(q('.spinner')).toBeNull();
      expect(el().querySelectorAll('.fact')[2].querySelector('.dot')!.classList.contains('warn')).toBe(false);
    });

    it('shows Agents working with an aria-hidden spinner while store.streaming() is true, and follows it', () => {
      initReady();
      streaming.set(true);
      fixture.detectChanges();
      expect(factValues()[2]).toBe('working');
      const spinner = q('.spinner')!;
      expect(spinner.getAttribute('aria-hidden')).toBe('true');
      expect(el().querySelectorAll('.fact')[2].querySelector('.dot')!.classList.contains('warn')).toBe(true);
      streaming.set(false);
      fixture.detectChanges();
      expect(factValues()[2]).toBe('idle');
      expect(q('.spinner')).toBeNull();
    });

    it('sets the title of the solution and model values', () => {
      messages.set([msg('assistant', { id: 'm1' })]);
      initReady({ solution: { name: 'crm_app' } });
      const values = Array.from(el().querySelectorAll('.fact .fact-value')) as HTMLElement[];
      expect(values[0].getAttribute('title')).toBe('crm_app');
      expect(values[1].getAttribute('title')).toBe('m1');
    });
  });

  describe('content guard', () => {
    it('never renders skills, plugins, agent definitions, tool counts, version or old section titles', () => {
      messages.set([msg('assistant', { id: 'claude-x' })]);
      initReady({
        info: { version: '9.8.7' },
        solution: { name: 'crm_app' },
        providerData: [
          { id: 'kiro', activation: 'auto' },
          { id: 'openai', activation: 'enabled' }
        ],
        mcpData: [
          { name: 'a', status: { status: 'connected' } },
          { name: 'b', status: { status: 'failed', error: 'boom' } },
          { name: 'c', status: { status: 'disabled' } }
        ]
      });
      openMcp();
      const content = el().textContent!;
      expect(content).not.toMatch(/skill|plugin|agent definition|tools?\b.*\d|\d+ tools|9\.8\.7|Providers|Server\b|boom|kiro|openai/i);
      expect(el().querySelector('.title')).toBeNull();
    });
  });

  describe('refresh', () => {
    it('re-fetches info, mcp, provider and solution once each and updates state', () => {
      init();
      component.refresh();
      flushAll({
        providerData: KIRO,
        mcpData: [{ name: 'time', status: { status: 'connected' } }],
        solution: { name: 'crm_app' }
      });
      expect(component.healthy()).toBe(true);
      expect(component.connectedProviders()).toEqual(['kiro']);
      expect(component.solutionName()).toBe('crm_app');
      expect(component.loading()).toBe(false);
    });

    it('is an icon-only button that spins and is disabled while loading, then restores it', () => {
      init();
      const btn = q<HTMLButtonElement>('button.refresh')!;
      expect(btn.textContent!.trim()).toBe('');
      expect(btn.querySelector('svg')).not.toBeNull();
      expect(btn.getAttribute('aria-label')).toBe('Refresh status');
      expect(btn.classList.contains('spinning')).toBe(false);
      expect(btn.disabled).toBe(false);
      btn.click();
      fixture.detectChanges();
      expect(btn.getAttribute('aria-label')).toBe('Refreshing…');
      expect(btn.classList.contains('spinning')).toBe(true);
      expect(btn.disabled).toBe(true);
      flushAll({ providerData: KIRO });
      fixture.detectChanges();
      expect(btn.getAttribute('aria-label')).toBe('Refresh status');
      expect(btn.classList.contains('spinning')).toBe(false);
      expect(btn.disabled).toBe(false);
    });

    it('sets mcp to empty when the mcp request fails', () => {
      init({ mcpData: [{ name: 'a', status: { status: 'connected' } }] });
      expect(component.mcpRows().length).toBe(1);
      component.refresh();
      httpMock.expectOne('rest_api/info').flush({ version: '2' });
      httpMock.expectOne('rest_api/mcp').flush('x', { status: 500, statusText: 'err' });
      httpMock.expectOne('rest_api/provider').flush({ data: KIRO });
      httpMock.expectOne('solution').flush({ name: null });
      expect(component.mcpRows()).toEqual([]);
    });
  });
});
