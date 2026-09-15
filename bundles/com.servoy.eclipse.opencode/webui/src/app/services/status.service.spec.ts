import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { StatusService } from './status.service';

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

  it('requests server health from ./rest_api/global/health', () => {
    let result: unknown;
    service.health().subscribe((r) => (result = r));

    const req = httpMock.expectOne('rest_api/global/health');
    expect(req.request.method).toBe('GET');
    req.flush({ healthy: true, version: '1.18.31' });

    expect(result).toEqual({ healthy: true, version: '1.18.31' });
  });

  it('requests the MCP status map from ./rest_api/mcp', () => {
    let result: unknown;
    service.mcp().subscribe((r) => (result = r));

    const req = httpMock.expectOne('rest_api/mcp');
    expect(req.request.method).toBe('GET');
    const body = { time: { status: 'connected' }, 'servoy-git': { status: 'failed', error: 'boom' } };
    req.flush(body);

    expect(result).toEqual(body);
  });

  it('requests provider status from ./rest_api/provider', () => {
    let result: unknown;
    service.providers().subscribe((r) => (result = r));

    const req = httpMock.expectOne('rest_api/provider');
    expect(req.request.method).toBe('GET');
    req.flush({ connected: ['kiro', 'opencode'], default: { kiro: 'claude-sonnet-4-6' } });

    expect(result).toEqual({ connected: ['kiro', 'opencode'], default: { kiro: 'claude-sonnet-4-6' } });
  });
});
