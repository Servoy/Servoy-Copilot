import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

import { HealthStatus, McpStatus, McpStatusMap, ProviderStatus } from '../models/opencode.models';

/**
 * Typed wrappers over the BFF servlet's status/health endpoints (proxied from
 * opencode V2 under {@code ./rest_api/**}).
 *
 * These are read-only diagnostics used by the status panel to answer "is the
 * server healthy, are the MCP servers connected, and is a model provider
 * available?" - the questions that explain most failures the user sees. All
 * three adapt V2 response shapes back into the compact maps the panel renders.
 */
@Injectable({ providedIn: 'root' })
export class StatusService {
  private readonly http = inject(HttpClient);
  private readonly base = 'rest_api';

  /**
   * Health of the opencode server itself. V2 has no {@code /global/health}
   * route, so we use {@code GET /info}: a response carrying a {@code version}
   * means the server is up.
   */
  health(): Observable<HealthStatus> {
    return this.http
      .get<{ version?: string }>(`${this.base}/info`)
      .pipe(map((info) => ({ healthy: !!info?.version, version: info?.version })));
  }

  /**
   * Map of MCP server name to its connection status. V2 {@code GET /mcp}
   * returns {@code { location, data: [{ name, status:{ status, error? } }] }};
   * we fold that array back into the {name -> status} map the panel expects.
   */
  mcp(): Observable<McpStatusMap> {
    return this.http
      .get<{ data?: Array<{ name?: string; status?: McpStatus }> }>(`${this.base}/mcp`)
      .pipe(
        map((r) => {
          const out: McpStatusMap = {};
          for (const entry of r?.data ?? []) {
            if (entry?.name && entry.status) {
              out[entry.name] = entry.status;
            }
          }
          return out;
        })
      );
  }

  /**
   * Which model providers are available. V2 {@code GET /provider} returns
   * {@code { location, data: [{ id, activation, ... }] }}; a provider counts as
   * "connected" when its {@code activation} is not {@code disabled}.
   */
  providers(): Observable<ProviderStatus> {
    return this.http
      .get<{ data?: Array<{ id?: string; activation?: string }> }>(`${this.base}/provider`)
      .pipe(
        map((r) => {
          const connected: string[] = [];
          for (const p of r?.data ?? []) {
            if (p?.id && p.activation !== 'disabled') {
              connected.push(p.id);
            }
          }
          return { connected, default: {} } satisfies ProviderStatus;
        })
      );
  }
}
