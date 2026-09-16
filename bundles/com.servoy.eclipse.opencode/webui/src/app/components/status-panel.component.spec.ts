import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { StatusPanelComponent } from './status-panel.component';

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

  function flushAll(opts: {
    health?: unknown;
    mcp?: unknown;
    providers?: unknown;
  }): void {
    // Health is flushed last here only for ordering convenience; each axis is an
    // independent request now, so any order works.
    httpMock.expectOne('rest_api/global/health').flush(opts.health ?? { healthy: true, version: '1.0.0' });
    httpMock.expectOne('rest_api/mcp').flush(opts.mcp ?? {});
    httpMock.expectOne('rest_api/provider').flush(opts.providers ?? { connected: [], default: {} });
  }

  /** Trigger ngOnInit's initial fetch and flush it with the given payloads. */
  function init(opts: { health?: unknown; mcp?: unknown; providers?: unknown } = {}): void {
    fixture.detectChanges();
    flushAll(opts);
  }

  it('fetches once on init, before any interaction', () => {
    expect(component.overall()).toBe('idle');
    init({
      health: { healthy: true },
      mcp: { a: { status: 'connected' } },
      providers: { connected: ['kiro'], default: {} }
    });
    expect(component.initialized()).toBe(true);
    expect(component.overall()).toBe('ok');
  });

  it('starts in the idle (grey) state until the first fetch resolves', () => {
    fixture.detectChanges();
    // Requests are in flight but not yet flushed.
    expect(component.overall()).toBe('idle');
    flushAll({
      health: { healthy: true },
      mcp: {},
      providers: { connected: ['kiro'], default: {} }
    });
    expect(component.overall()).toBe('ok');
  });

  it('toggling open re-fetches all three status endpoints', () => {
    init();
    component.toggle();
    expect(component.open()).toBe(true);
    flushAll({
      health: { healthy: true, version: '1.18.31' },
      mcp: { time: { status: 'connected' } },
      providers: { connected: ['kiro'], default: { kiro: 'claude' } }
    });

    expect(component.healthy()).toBe(true);
    expect(component.version()).toBe('1.18.31');
    expect(component.connectedProviders()).toEqual(['kiro']);
    expect(component.loading()).toBe(false);
  });

  it('sorts MCP rows with failed servers first and counts connected', () => {
    init({
      mcp: {
        'servoy-git': { status: 'connected' },
        time: { status: 'connected' },
        broken: { status: 'failed', error: 'nope' }
      }
    });

    const rows = component.mcpRows();
    expect(rows[0].name).toBe('broken');
    expect(rows[0].ok).toBe(false);
    expect(rows[0].error).toBe('nope');
    expect(component.mcpConnectedCount()).toBe(2);
    expect(component.mcpTotalCount()).toBe(3);
  });

  it('overall is error when no provider is connected even if healthy', () => {
    init({ health: { healthy: true }, mcp: { a: { status: 'connected' } }, providers: { connected: [], default: {} } });
    expect(component.hasProviders()).toBe(false);
    expect(component.overall()).toBe('error');
  });

  it('overall is warn when a provider is connected but an MCP server is not', () => {
    init({
      health: { healthy: true },
      mcp: { a: { status: 'connected' }, b: { status: 'failed', error: 'x' } },
      providers: { connected: ['kiro'], default: {} }
    });
    expect(component.overall()).toBe('warn');
  });

  it('overall is ok when healthy, a provider is connected, and all MCP servers connected', () => {
    init({
      health: { healthy: true },
      mcp: { a: { status: 'connected' } },
      providers: { connected: ['kiro'], default: {} }
    });
    expect(component.overall()).toBe('ok');
  });

  it('overall is ok when healthy, a provider is connected, and there are no MCP servers', () => {
    init({
      health: { healthy: true },
      mcp: {},
      providers: { connected: ['kiro'], default: {} }
    });
    expect(component.overall()).toBe('ok');
  });

  it('pairs each connected provider with its default model', () => {
    init({
      providers: {
        connected: ['kiro', 'opencode'],
        default: { kiro: 'claude-sonnet-4-6', opencode: 'big-pickle' }
      }
    });
    const rows = component.providerRows();
    expect(rows).toEqual([
      { name: 'kiro', defaultModel: 'claude-sonnet-4-6' },
      { name: 'opencode', defaultModel: 'big-pickle' }
    ]);
  });

  it('sets loadError when the health request fails', () => {
    fixture.detectChanges();
    httpMock
      .expectOne('rest_api/global/health')
      .error(new ProgressEvent('error'), { status: 503, statusText: 'unavailable' });
    httpMock.expectOne('rest_api/mcp').flush({});
    httpMock.expectOne('rest_api/provider').flush({ connected: [], default: {} });

    expect(component.loadError()).toBe(true);
    expect(component.overall()).toBe('error');
    expect(component.loading()).toBe(false);
  });

  it('goes green from a healthy server even before the heavy provider list arrives', () => {
    fixture.detectChanges();
    // Health resolves first and paints the dot; provider request is still in flight.
    httpMock.expectOne('rest_api/global/health').flush({ healthy: true });
    httpMock.expectOne('rest_api/mcp').flush({ a: { status: 'connected' } });
    const providerReq = httpMock.expectOne('rest_api/provider');

    expect(component.initialized()).toBe(true);
    // Provider list not loaded yet -> treated as unknown, not an error.
    expect(component.overall()).toBe('ok');

    providerReq.flush({ connected: ['kiro'], default: {} });
    expect(component.overall()).toBe('ok');
  });

  it('is error once the provider list loads and is empty', () => {
    fixture.detectChanges();
    httpMock.expectOne('rest_api/global/health').flush({ healthy: true });
    httpMock.expectOne('rest_api/mcp').flush({});
    httpMock.expectOne('rest_api/provider').flush({ connected: [], default: {} });

    expect(component.overall()).toBe('error');
  });
});
