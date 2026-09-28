import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { StatusService } from './status.service';

/**
 * Tests for the V2 status adapters. V2 has no {@code /global/health} route, so
 * health is derived from {@code GET /info}; {@code /mcp} and {@code /provider}
 * now return {@code { location, data: [...] }} arrays that the service folds
 * back into the compact maps the status panel renders.
 */
describe('StatusService', () => {
  let service: StatusService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [StatusService, provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(StatusService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('derives health from ./rest_api/info (version present => healthy)', () => {
    let result: unknown;
    service.health().subscribe((r) => (result = r));

    const req = httpMock.expectOne('rest_api/info');
    expect(req.request.method).toBe('GET');
    req.flush({ version: '2.0.18', pid: 123 });

    expect(result).toEqual({ healthy: true, version: '2.0.18' });
  });

  it('reports unhealthy when /info carries no version', () => {
    let result: unknown;
    service.health().subscribe((r) => (result = r));
    const req = httpMock.expectOne('rest_api/info');
    req.flush({});
    expect(result).toEqual({ healthy: false, version: undefined });
  });

  it('folds the V2 /mcp data array into a name -> status map', () => {
    let result: unknown;
    service.mcp().subscribe((r) => (result = r));

    const req = httpMock.expectOne('rest_api/mcp');
    expect(req.request.method).toBe('GET');
    req.flush({
      location: { directory: '/proj' },
      data: [
        { name: 'servoy-editor', status: { status: 'connected' } },
        { name: 'servoy-git', status: { status: 'failed', error: 'boom' } }
      ]
    });

    expect(result).toEqual({
      'servoy-editor': { status: 'connected' },
      'servoy-git': { status: 'failed', error: 'boom' }
    });
  });

  it('derives connected providers from the V2 /provider activation field', () => {
    let result: unknown;
    service.providers().subscribe((r) => (result = r));

    const req = httpMock.expectOne('rest_api/provider');
    expect(req.request.method).toBe('GET');
    req.flush({
      location: { directory: '/proj' },
      data: [
        { id: 'opencode', activation: 'enabled' },
        { id: 'kiro', activation: 'auto' },
        { id: 'disabledone', activation: 'disabled' }
      ]
    });

    expect(result).toEqual({ connected: ['opencode', 'kiro'], default: {} });
  });
});
