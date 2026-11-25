import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

import {
  HealthStatus,
  McpStatus,
  McpStatusMap,
  ModelLimit,
  ProviderStatus,
  UsageSnapshot
} from '../models/opencode.models';

/** The model the next turn will use: that of the default agent. */
export interface DefaultAgentModel {
  agent: string;
  model: string;
}

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
   * The real active Servoy solution name, served by the BFF itself (not
   * proxied) at {@code GET solution}. A failure maps to {@code null} so the
   * informational name never affects the health state.
   */
  solution(): Observable<string | null> {
    return this.http.get<{ name?: string | null }>('solution').pipe(
      map((r) => r?.name ?? null),
      catchError(() => of(null))
    );
  }

  /**
   * Context window per model. V2 {@code GET /model} returns
   * {@code { data: [{ id, providerID, limit: { context, output } }] }}.
   */
  models(): Observable<ModelLimit[]> {
    return this.http
      .get<{ data?: Array<{ id?: string; providerID?: string; limit?: { context?: unknown } }> }>(
        `${this.base}/model`
      )
      .pipe(
        map((r) => {
          const out: ModelLimit[] = [];
          for (const m of r?.data ?? []) {
            if (m?.id && m.providerID) {
              const context = m.limit?.context;
              out.push({
                providerID: m.providerID,
                id: m.id,
                context: typeof context === 'number' && Number.isFinite(context) ? context : null
              });
            }
          }
          return out;
        }),
        catchError(() => of([] as ModelLimit[]))
      );
  }

  /**
   * The model a new turn will use. A prompt sent without an explicit agent runs
   * the default agent, and that agent's own model wins over the global
   * {@code model} in the config. V2 {@code GET /agent} lists the selected
   * default agent first (core {@code Agent.list}), so the first entry is it,
   * provided it is a selectable primary agent with a complete model. Anything
   * else yields {@code null} (shown as "-" rather than a guess). An HTTP error
   * propagates so the caller can retry.
   */
  defaultAgentModel(): Observable<DefaultAgentModel | null> {
    return this.http
      .get<{
        data?: Array<{
          id?: string;
          name?: string;
          mode?: string;
          hidden?: boolean;
          model?: { id?: unknown; providerID?: unknown };
        }>;
      }>(`${this.base}/agent`)
      .pipe(
        map((r) => {
          const agent = r?.data?.[0];
          if (!agent || agent.mode === 'subagent' || agent.hidden) {
            return null;
          }
          const id = agent.model?.id;
          const providerID = agent.model?.providerID;
          if (typeof id !== 'string' || id.length === 0 || typeof providerID !== 'string' || providerID.length === 0) {
            return null;
          }
          return { agent: agent.name || agent.id || '', model: id };
        })
      );
  }

  /** Kiro credit snapshot served by the BFF itself at {@code GET usage}. */
  usage(): Observable<UsageSnapshot | null> {
    return this.http.get<UsageSnapshot>('usage').pipe(
      map((r) => (r && typeof r === 'object' ? r : null)),
      catchError(() => of(null))
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
