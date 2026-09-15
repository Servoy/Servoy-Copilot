import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { HealthStatus, McpStatusMap, ProviderStatus } from '../models/opencode.models';

/**
 * Typed wrappers over the BFF servlet's status/health endpoints (all proxied
 * from opencode-cli under {@code ./rest_api/**}).
 *
 * These are read-only diagnostics used by the status panel to answer "is the
 * server healthy, are the MCP servers connected, and is a model provider
 * authenticated?" - the questions that explain most failures the user sees.
 */
@Injectable({ providedIn: 'root' })
export class StatusService {
  private readonly http = inject(HttpClient);
  private readonly base = 'rest_api';

  /** Health of the opencode server itself (not directory-scoped). */
  health(): Observable<HealthStatus> {
    return this.http.get<HealthStatus>(`${this.base}/global/health`);
  }

  /** Map of MCP server name to its connection status. */
  mcp(): Observable<McpStatusMap> {
    return this.http.get<McpStatusMap>(`${this.base}/mcp`);
  }

  /** Which model providers are connected (authenticated) and their defaults. */
  providers(): Observable<ProviderStatus> {
    return this.http.get<ProviderStatus>(`${this.base}/provider`);
  }
}
