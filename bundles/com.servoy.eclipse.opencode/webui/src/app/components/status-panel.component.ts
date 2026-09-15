import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';

import { McpStatusMap, ProviderStatus } from '../models/opencode.models';
import { StatusService } from '../services/status.service';

/** A flattened MCP row for the template. */
interface McpRow {
  name: string;
  status: string;
  error?: string;
  ok: boolean;
}

/**
 * A small status indicator button that, when clicked, reveals a compact panel
 * showing the health of the opencode server, the connection status of each MCP
 * server, and which model providers are authenticated. This surfaces the state
 * that explains most failures (server still starting, an MCP server failed to
 * connect, or no model provider is authenticated - the "No accounts" case).
 */
@Component({
  selector: 'svy-status-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './status-panel.component.html',
  styleUrl: './status-panel.component.scss'
})
export class StatusPanelComponent {
  private readonly status = inject(StatusService);

  readonly open = signal(false);
  readonly loading = signal(false);
  readonly loadError = signal(false);

  readonly healthy = signal<boolean | null>(null);
  readonly version = signal<string | null>(null);
  readonly mcp = signal<McpStatusMap>({});
  readonly providers = signal<ProviderStatus | null>(null);

  /** MCP entries flattened and sorted (failed/needs-attention first). */
  readonly mcpRows = computed<McpRow[]>(() => {
    const map = this.mcp();
    const rows = Object.keys(map).map((name) => {
      const entry = map[name];
      const ok = entry.status === 'connected';
      return { name, status: entry.status, error: entry.error, ok };
    });
    return rows.sort((a, b) => {
      if (a.ok !== b.ok) {
        return a.ok ? 1 : -1;
      }
      return a.name.localeCompare(b.name);
    });
  });

  readonly mcpConnectedCount = computed(() => this.mcpRows().filter((r) => r.ok).length);
  readonly mcpTotalCount = computed(() => this.mcpRows().length);

  readonly connectedProviders = computed(() => this.providers()?.connected ?? []);
  readonly hasProviders = computed(() => this.connectedProviders().length > 0);

  /** Overall dot colour: red on load error / unhealthy / no provider, amber if any MCP not connected, else green. */
  readonly overall = computed<'ok' | 'warn' | 'error'>(() => {
    if (this.loadError() || this.healthy() === false) {
      return 'error';
    }
    if (this.healthy() === null) {
      return 'warn';
    }
    if (!this.hasProviders()) {
      return 'error';
    }
    if (this.mcpTotalCount() > 0 && this.mcpConnectedCount() < this.mcpTotalCount()) {
      return 'warn';
    }
    return 'ok';
  });

  toggle(): void {
    const next = !this.open();
    this.open.set(next);
    if (next) {
      this.refresh();
    }
  }

  refresh(): void {
    this.loading.set(true);
    this.loadError.set(false);
    forkJoin({
      health: this.status.health(),
      mcp: this.status.mcp(),
      providers: this.status.providers()
    }).subscribe({
      next: ({ health, mcp, providers }) => {
        this.healthy.set(health?.healthy ?? null);
        this.version.set(health?.version ?? null);
        this.mcp.set(mcp ?? {});
        this.providers.set(providers ?? null);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set(true);
        this.loading.set(false);
      }
    });
  }
}
