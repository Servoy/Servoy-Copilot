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
    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify();
  });

  function flushAll(opts: {
    health?: unknown;
    mcp?: unknown;
    providers?: unknown;
  }): void {
    httpMock.expectOne('rest_api/global/health').flush(opts.health ?? { healthy: true, version: '1.0.0' });
    httpMock.expectOne('rest_api/mcp').flush(opts.mcp ?? {});
    httpMock.expectOne('rest_api/provider').flush(opts.providers ?? { connected: [], default: {} });
  }

  it('does not fetch until opened', () => {
    expect(component.open()).toBe(false);
    httpMock.expectNone('rest_api/global/health');
  });

  it('toggling open fetches all three status endpoints', () => {
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
    component.toggle();
    flushAll({
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
    component.toggle();
    flushAll({ health: { healthy: true }, mcp: { a: { status: 'connected' } }, providers: { connected: [], default: {} } });
    expect(component.hasProviders()).toBe(false);
    expect(component.overall()).toBe('error');
  });

  it('overall is warn when a provider is connected but an MCP server is not', () => {
    component.toggle();
    flushAll({
      health: { healthy: true },
      mcp: { a: { status: 'connected' }, b: { status: 'failed', error: 'x' } },
      providers: { connected: ['kiro'], default: {} }
    });
    expect(component.overall()).toBe('warn');
  });

  it('overall is ok when healthy, a provider is connected, and all MCP servers connected', () => {
    component.toggle();
    flushAll({
      health: { healthy: true },
      mcp: { a: { status: 'connected' } },
      providers: { connected: ['kiro'], default: {} }
    });
    expect(component.overall()).toBe('ok');
  });

  it('sets loadError when a status request fails', () => {
    component.toggle();
    httpMock.expectOne('rest_api/global/health').flush({ healthy: true });
    httpMock.expectOne('rest_api/mcp').flush({});
    httpMock.expectOne('rest_api/provider').error(new ProgressEvent('error'), { status: 503, statusText: 'unavailable' });

    expect(component.loadError()).toBe(true);
    expect(component.overall()).toBe('error');
    expect(component.loading()).toBe(false);
  });
});
