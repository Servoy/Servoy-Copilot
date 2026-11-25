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

  it('returns the solution name from the BFF-owned relative solution endpoint', () => {
    let result: unknown = 'unset';
    service.solution().subscribe((r) => (result = r));
    const req = httpMock.expectOne('solution');
    expect(req.request.method).toBe('GET');
    req.flush({ name: 'crm_app' });
    expect(result).toBe('crm_app');
  });

  it('maps a null solution name to null', () => {
    let result: unknown = 'unset';
    service.solution().subscribe((r) => (result = r));
    httpMock.expectOne('solution').flush({ name: null });
    expect(result).toBeNull();
  });

  it('maps a missing solution name to null', () => {
    let result: unknown = 'unset';
    service.solution().subscribe((r) => (result = r));
    httpMock.expectOne('solution').flush({});
    expect(result).toBeNull();
  });

  it('maps an HTTP error on solution to null instead of failing', () => {
    let result: unknown = 'unset';
    let failed = false;
    service.solution().subscribe({ next: (r) => (result = r), error: () => (failed = true) });
    httpMock.expectOne('solution').flush('boom', { status: 500, statusText: 'err' });
    expect(failed).toBe(false);
    expect(result).toBeNull();
  });

  describe('defaultAgentModel()', () => {
    const ORCH = {
      id: 'Orchestrator',
      name: 'Orchestrator',
      mode: 'primary',
      hidden: false,
      model: { id: 'claude-sonnet-5-5', providerID: 'kiro', variant: 'medium' }
    };

    const load = (body: object): { result: unknown; failed: boolean } => {
      const out = { result: 'unset' as unknown, failed: false };
      service.defaultAgentModel().subscribe({ next: (r) => (out.result = r), error: () => (out.failed = true) });
      const req = httpMock.expectOne('rest_api/agent');
      expect(req.request.method).toBe('GET');
      req.flush(body);
      return out;
    };

    it('takes the model of the first agent, which V2 lists as the selected default', () => {
      const subagent = { id: 'Researcher', name: 'Researcher', mode: 'subagent', hidden: true, model: { id: 'claude-haiku-5-5', providerID: 'kiro' } };
      expect(load({ data: [ORCH, subagent] }).result).toEqual({ agent: 'Orchestrator', model: 'claude-sonnet-5-5' });
    });

    it('returns only the model id, not the provider or variant', () => {
      const r = load({ data: [ORCH] }).result as { model: string };
      expect(r.model).toBe('claude-sonnet-5-5');
      expect(Object.keys(r).sort()).toEqual(['agent', 'model']);
    });

    it('returns null for an empty list or a missing data field', () => {
      expect(load({ data: [] }).result).toBeNull();
      expect(load({}).result).toBeNull();
    });

    it('returns null when the first agent is a subagent or hidden (not a selectable default)', () => {
      expect(load({ data: [{ ...ORCH, mode: 'subagent' }] }).result).toBeNull();
      expect(load({ data: [{ ...ORCH, hidden: true }] }).result).toBeNull();
    });

    it('returns null when the agent carries no complete model (it then inherits the global default)', () => {
      expect(load({ data: [{ ...ORCH, model: undefined }] }).result).toBeNull();
      expect(load({ data: [{ ...ORCH, model: { id: 'x' } }] }).result).toBeNull();
      expect(load({ data: [{ ...ORCH, model: { id: '', providerID: 'kiro' } }] }).result).toBeNull();
      expect(load({ data: [{ ...ORCH, model: { id: 5, providerID: 'kiro' } }] }).result).toBeNull();
    });

    it('propagates an HTTP error so the caller can retry', () => {
      const out = { failed: false };
      service.defaultAgentModel().subscribe({ error: () => (out.failed = true) });
      httpMock.expectOne('rest_api/agent').flush('x', { status: 500, statusText: 'err' });
      expect(out.failed).toBe(true);
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

  it('maps /model data to provider, id and numeric context limit', () => {
    let result: unknown;
    service.models().subscribe((r) => (result = r));
    const req = httpMock.expectOne('rest_api/model');
    expect(req.request.method).toBe('GET');
    req.flush({
      data: [
        { id: 'm1', providerID: 'kiro', limit: { context: 200000, output: 8000 } },
        { id: 'm2', providerID: 'kiro', limit: {} },
        { id: 'm3', providerID: 'kiro', limit: { context: '1000' } },
        { id: 'm4', providerID: 'kiro' },
        { providerID: 'kiro', limit: { context: 5 } },
        { id: 'm6', limit: { context: 5 } }
      ]
    });
    expect(result).toEqual([
      { providerID: 'kiro', id: 'm1', context: 200000 },
      { providerID: 'kiro', id: 'm2', context: null },
      { providerID: 'kiro', id: 'm3', context: null },
      { providerID: 'kiro', id: 'm4', context: null }
    ]);
  });

  it('maps a /model response without data to an empty list', () => {
    let result: unknown;
    service.models().subscribe((r) => (result = r));
    httpMock.expectOne('rest_api/model').flush({});
    expect(result).toEqual([]);
  });

  it('maps an HTTP error on /model to an empty list', () => {
    let result: unknown = 'unset';
    let failed = false;
    service.models().subscribe({ next: (r) => (result = r), error: () => (failed = true) });
    httpMock.expectOne('rest_api/model').flush('boom', { status: 500, statusText: 'err' });
    expect(failed).toBe(false);
    expect(result).toEqual([]);
  });

  it('returns the usage snapshot from the BFF-owned relative usage endpoint', () => {
    let result: unknown;
    service.usage().subscribe((r) => (result = r));
    const req = httpMock.expectOne('usage');
    expect(req.request.method).toBe('GET');
    req.flush({ available: true, used: 3, limit: 10, stale: false });
    expect(result).toEqual({ available: true, used: 3, limit: 10, stale: false });
  });

  it('maps an HTTP error on usage to null', () => {
    let result: unknown = 'unset';
    let failed = false;
    service.usage().subscribe({ next: (r) => (result = r), error: () => (failed = true) });
    httpMock.expectOne('usage').flush('boom', { status: 500, statusText: 'err' });
    expect(failed).toBe(false);
    expect(result).toBeNull();
  });

  it('maps a non-object usage body to null', () => {
    let result: unknown = 'unset';
    service.usage().subscribe((r) => (result = r));
    httpMock.expectOne('usage').flush(null);
    expect(result).toBeNull();
  });
});
