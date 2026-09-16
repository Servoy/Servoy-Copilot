import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin, interval } from 'rxjs';

import { McpStatusMap, ProviderStatus } from '../models/opencode.models';
import { StatusService } from '../services/status.service';

/** A flattened MCP row for the template. */
interface McpRow {
  name: string;
  status: string;
  error?: string;
  ok: boolean;
}

/** A connected provider and the default model opencode picks for it. */
interface ProviderRow {
  name: string;
  defaultModel: string | null;
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
export class StatusPanelComponent implements OnInit {
  private readonly status = inject(StatusService);
  private readonly destroyRef = inject(DestroyRef);

  /** How often the status is polled in the background (ms). */
  private static readonly POLL_INTERVAL_MS = 15_000;

  readonly open = signal(false);
  readonly loading = signal(false);
  readonly loadError = signal(false);
  /** True until the first status fetch resolves, so the dot isn't misread as a real state. */
  readonly initialized = signal(false);

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

  /**
   * Connected providers paired with their default model id (from the
   * {@code default} map of {@code GET /provider}). These are providers - each
   * exposes a set of models; we show the default one opencode would pick.
   */
  readonly providerRows = computed<ProviderRow[]>(() => {
    const status = this.providers();
    if (!status) {
      return [];
    }
    const defaults = status.default ?? {};
    return (status.connected ?? []).map((name) => ({
      name,
      defaultModel: defaults[name] ?? null
    }));
  });

  /**
   * Overall dot colour, driven by the three health axes (server, providers,
   * MCP):
   * <ul>
   *   <li>grey ({@code 'idle'}) until the first fetch resolves, so the dot
   *       never claims a state it hasn't measured;</li>
   *   <li>red ({@code 'error'}) on a fetch failure, an unhealthy server, or no
   *       connected provider (the assistant cannot respond);</li>
   *   <li>amber ({@code 'warn'}) when everything essential is up but some MCP
   *       server is not connected;</li>
   *   <li>green ({@code 'ok'}) only when the server is healthy, at least one
   *       provider is connected, and every reported MCP server is connected.</li>
   * </ul>
   */
  readonly overall = computed<'idle' | 'ok' | 'warn' | 'error'>(() => {
    // Grey only until the primary (health) axis has resolved once.
    if (!this.initialized()) {
      return 'idle';
    }
    // A failed health fetch or an unhealthy server is a hard error.
    if (this.loadError() || this.healthy() === false || this.healthy() === null) {
      return 'error';
    }
    // Server is healthy. The provider and MCP axes load independently and may
    // not have arrived yet; treat a not-yet-loaded provider list as "unknown",
    // not as a failure, so the dot doesn't flash red on startup. Only an
    // explicitly empty provider list (loaded, zero connected) is an error.
    const providers = this.providers();
    if (providers && !this.hasProviders()) {
      return 'error';
    }
    if (this.mcpTotalCount() > 0 && this.mcpConnectedCount() < this.mcpTotalCount()) {
      return 'warn';
    }
    return 'ok';
  });

  ngOnInit(): void {
    // Fetch once immediately so the dot reflects real state without a click,
    // then keep it fresh with a lightweight background poll.
    this.refresh();
    interval(StatusPanelComponent.POLL_INTERVAL_MS)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh());
  }

  toggle(): void {
    const next = !this.open();
    this.open.set(next);
    if (next) {
      this.refresh();
    }
  }

  /**
   * Refresh the three status axes independently rather than as one combined
   * request. This matters because {@code GET /provider} returns the full
   * provider+model catalogue (multiple megabytes); waiting on it inside a
   * {@code forkJoin} kept the indicator stuck grey until that giant payload
   * finished. Now health (the primary signal) lands first and paints the dot,
   * and the heavy provider call updates it when it arrives - a slow or failing
   * axis can no longer block the others.
   */
  refresh(): void {
    this.loading.set(true);

    this.status.health().subscribe({
      next: (health) => {
        this.healthy.set(health?.healthy ?? null);
        this.version.set(health?.version ?? null);
        this.loadError.set(false);
        this.initialized.set(true);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set(true);
        this.initialized.set(true);
        this.loading.set(false);
      }
    });

    this.status.mcp().subscribe({
      next: (mcp) => this.mcp.set(mcp ?? {}),
      error: () => this.mcp.set({})
    });

    this.status.providers().subscribe({
      next: (providers) => this.providers.set(providers ?? null),
      error: () => this.providers.set(null)
    });
  }
}
