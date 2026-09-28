import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { StatusPanelComponent } from './status-panel.component';

/**
 * Tests for the status panel against the V2 ({@code @opencode/cli} 2.x) status
 * endpoints. The panel consumes {@code StatusService}, which now adapts the V2
 * responses: health comes from {@code GET /info} (a {@code version} means up),
 * {@code /mcp} and {@code /provider} return {@code { location, data:[...] }}
 * arrays. These specs feed those raw V2 HTTP shapes and assert the panel state.
 */
describe('StatusPanelComponent', () => {
  let fixture: ComponentFixture<StatusPanelComponent>;
  let component: StatusPanelComponent;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [StatusPanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    fixture = TestBed.createComponent(StatusPanelComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  /**
   * Flush the three independent status requests with raw V2 payloads.
   *
   * @param opts.info      the {@code GET /info} body (a {@code version} => healthy)
   * @param opts.mcpData   the {@code data} array for {@code GET /mcp}
   * @param opts.providerData the {@code data} array for {@code GET /provider}
   */
  function flushAll(opts: {
    info?: unknown;
    mcpData?: Array<{ name: string; status: { status: string; error?: string } }>;
    providerData?: Array<{ id: string; activation?: string }>;
  }): void {
    httpMock.expectOne('rest_api/info').flush(opts.info ?? { version: '2.0.18' });
    httpMock.expectOne('rest_api/mcp').flush({ data: opts.mcpData ?? [] });
    httpMock.expectOne('rest_api/provider').flush({ data: opts.providerData ?? [] });
  }

  /** Trigger ngOnInit's initial fetch and flush it with the given payloads. */
  function init(
    opts: {
      info?: unknown;
      mcpData?: Array<{ name: string; status: { status: string; error?: string } }>;
      providerData?: Array<{ id: string; activation?: string }>;
    } = {}
  ): void {
    fixture.detectChanges();
    flushAll(opts);
  }

  it('fetches once on init, before any interaction', () => {
    expect(component.overall()).toBe('idle');
    init({
      info: { version: '2.0.18' },
      mcpData: [{ name: 'a', status: { status: 'connected' } }],
      providerData: [{ id: 'kiro', activation: 'auto' }]
    });
    expect(component.initialized()).toBe(true);
    expect(component.overall()).toBe('ok');
  });

  it('starts in the idle (grey) state until the first fetch resolves', () => {
    fixture.detectChanges();
    // Requests are in flight but not yet flushed.
    expect(component.overall()).toBe('idle');
    flushAll({
      info: { version: '2.0.18' },
      mcpData: [],
      providerData: [{ id: 'kiro', activation: 'auto' }]
    });
    expect(component.overall()).toBe('ok');
  });

  it('toggling open re-fetches all three status endpoints', () => {
    init();
    component.toggle();
    expect(component.open()).toBe(true);
    flushAll({
      info: { version: '2.0.18' },
      mcpData: [{ name: 'time', status: { status: 'connected' } }],
      providerData: [{ id: 'kiro', activation: 'auto' }]
    });

    expect(component.healthy()).toBe(true);
    expect(component.version()).toBe('2.0.18');
    expect(component.connectedProviders()).toEqual(['kiro']);
    expect(component.loading()).toBe(false);
  });

  it('sorts MCP rows with failed servers first and counts connected', () => {
    init({
      mcpData: [
        { name: 'servoy-git', status: { status: 'connected' } },
        { name: 'time', status: { status: 'connected' } },
        { name: 'broken', status: { status: 'failed', error: 'nope' } }
      ]
    });

    const rows = component.mcpRows();
    expect(rows[0].name).toBe('broken');
    expect(rows[0].ok).toBe(false);
    expect(rows[0].error).toBe('nope');
    expect(component.mcpConnectedCount()).toBe(2);
    expect(component.mcpTotalCount()).toBe(3);
  });

  it('overall is error when no provider is connected even if healthy', () => {
    init({
      info: { version: '2.0.18' },
      mcpData: [{ name: 'a', status: { status: 'connected' } }],
      providerData: []
    });
    expect(component.hasProviders()).toBe(false);
    expect(component.overall()).toBe('error');
  });

  it('overall is warn when a provider is connected but an MCP server is not', () => {
    init({
      info: { version: '2.0.18' },
      mcpData: [
        { name: 'a', status: { status: 'connected' } },
        { name: 'b', status: { status: 'failed', error: 'x' } }
      ],
      providerData: [{ id: 'kiro', activation: 'auto' }]
    });
    expect(component.overall()).toBe('warn');
  });

  it('overall is ok when healthy, a provider is connected, and all MCP servers connected', () => {
    init({
      info: { version: '2.0.18' },
      mcpData: [{ name: 'a', status: { status: 'connected' } }],
      providerData: [{ id: 'kiro', activation: 'auto' }]
    });
    expect(component.overall()).toBe('ok');
  });

  it('overall is ok when healthy, a provider is connected, and there are no MCP servers', () => {
    init({
      info: { version: '2.0.18' },
      mcpData: [],
      providerData: [{ id: 'kiro', activation: 'auto' }]
    });
    expect(component.overall()).toBe('ok');
  });

  it('lists each connected provider (V2 has no per-provider default model)', () => {
    init({
      providerData: [
        { id: 'kiro', activation: 'auto' },
        { id: 'opencode', activation: 'enabled' }
      ]
    });
    const rows = component.providerRows();
    expect(rows).toEqual([
      { name: 'kiro', defaultModel: null },
      { name: 'opencode', defaultModel: null }
    ]);
  });

  it('sets loadError when the health request fails', () => {
    fixture.detectChanges();
    httpMock
      .expectOne('rest_api/info')
      .error(new ProgressEvent('error'), { status: 503, statusText: 'unavailable' });
    httpMock.expectOne('rest_api/mcp').flush({ data: [] });
    httpMock.expectOne('rest_api/provider').flush({ data: [] });

    expect(component.loadError()).toBe(true);
    expect(component.overall()).toBe('error');
    expect(component.loading()).toBe(false);
  });

  it('goes green from a healthy server even before the heavy provider list arrives', () => {
    fixture.detectChanges();
    // Health resolves first and paints the dot; provider request is still in flight.
    httpMock.expectOne('rest_api/info').flush({ version: '2.0.18' });
    httpMock.expectOne('rest_api/mcp').flush({ data: [{ name: 'a', status: { status: 'connected' } }] });
    const providerReq = httpMock.expectOne('rest_api/provider');

    expect(component.initialized()).toBe(true);
    // Provider list not loaded yet -> treated as unknown, not an error.
    expect(component.overall()).toBe('ok');

    providerReq.flush({ data: [{ id: 'kiro', activation: 'auto' }] });
    expect(component.overall()).toBe('ok');
  });

  it('is error once the provider list loads and is empty', () => {
    fixture.detectChanges();
    httpMock.expectOne('rest_api/info').flush({ version: '2.0.18' });
    httpMock.expectOne('rest_api/mcp').flush({ data: [] });
    httpMock.expectOne('rest_api/provider').flush({ data: [] });

    expect(component.overall()).toBe('error');
  });
});
