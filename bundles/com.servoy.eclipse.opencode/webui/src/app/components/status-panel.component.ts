import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  output,
  signal
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval } from 'rxjs';

import { McpStatusMap, ProviderStatus } from '../models/opencode.models';
import { ChevronIconComponent } from './chevron-icon.component';
import { ChatStore } from '../services/chat-store.service';
import { StatusService } from '../services/status.service';
import { UsageStore } from '../services/usage-store.service';
import { formatTokens } from '../services/usage-utils';

type McpState = 'online' | 'offline' | 'disabled';

/** A flattened MCP row for the template. */
interface McpRow {
  name: string;
  state: McpState;
}

type OverallState = 'idle' | 'ok' | 'warn' | 'error';

interface StatusSummary {
  state: OverallState;
  text: string;
  headline: string;
  detail: string;
}

const READY_HEADLINE = 'Ready';
const READY_DETAIL = 'Servoy AI is connected';

const MCP_ORDER: Record<McpState, number> = { offline: 0, online: 1, disabled: 2 };

/**
 * A docked status panel (hosted by the shell's status area, whose open state
 * lives in the shell) that answers "can I work?" at a glance: one overall line
 * in words (also driving the header dot through {@code overall()}), the facts
 * Solution / Model / Agents (Model and Agents follow the session being
 * viewed), and the MCP servers folded away behind a single summary line.
 */
@Component({
  selector: 'svy-status-panel',
  standalone: true,
  imports: [ChevronIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './status-panel.component.html',
  styleUrl: './status-panel.component.scss'
})
export class StatusPanelComponent implements OnInit {
  private readonly status = inject(StatusService);
  private readonly store = inject(ChatStore);
  readonly usage = inject(UsageStore);
  private readonly destroyRef = inject(DestroyRef);

  /** How often the status is polled in the background (ms). */
  private static readonly POLL_INTERVAL_MS = 15_000;

  /** Emitted when the user presses the panel's close button. */
  readonly closed = output<void>();

  readonly loading = signal(false);
  readonly loadError = signal(false);
  /** True until the first status fetch resolves, so the dot isn't misread as a real state. */
  readonly initialized = signal(false);

  readonly healthy = signal<boolean | null>(null);
  readonly mcp = signal<McpStatusMap>({});
  readonly providers = signal<ProviderStatus | null>(null);
  readonly solutionName = signal<string | null>(null);
  readonly mcpOpen = signal(false);

  /** MCP entries flattened and sorted (offline first, then online, then disabled). */
  readonly mcpRows = computed<McpRow[]>(() => {
    const map = this.mcp();
    const rows = Object.keys(map).map((name): McpRow => {
      const status = map[name].status;
      const state: McpState =
        status === 'connected' ? 'online' : status === 'disabled' ? 'disabled' : 'offline';
      return { name, state };
    });
    return rows.sort((a, b) => {
      if (a.state !== b.state) {
        return MCP_ORDER[a.state] - MCP_ORDER[b.state];
      }
      return a.name.localeCompare(b.name);
    });
  });

  readonly mcpOnline = computed(() => this.mcpRows().filter((r) => r.state === 'online').length);
  readonly mcpOffline = computed(() => this.mcpRows().filter((r) => r.state === 'offline').length);
  readonly mcpDisabled = computed(() => this.mcpRows().filter((r) => r.state === 'disabled').length);
  readonly mcpEnabled = computed(() => this.mcpOnline() + this.mcpOffline());

  readonly connectedProviders = computed(() => this.providers()?.connected ?? []);
  readonly hasProviders = computed(() => this.connectedProviders().length > 0);

  /** Model of the default agent: what a new turn will use before any message exists. */
  readonly defaultModel = signal<string | null>(null);

  /** Model id of the newest message of the viewed session that carries one. */
  private readonly messageModel = computed<string | null>(() => {
    const messages = this.store.messages();
    for (let i = messages.length - 1; i >= 0; i--) {
      const model = messages[i].info['model'] as { id?: unknown } | null | undefined;
      const id = model?.id;
      if (typeof id === 'string' && id.length > 0) {
        return id;
      }
    }
    return null;
  });

  /**
   * The model shown for the viewed session: the model of its newest message
   * (what really ran) wins; before the first turn it is the default agent's
   * model (what the next turn will use). A subagent session without a message
   * yet has its own agent's model, which is not known here, so it stays null
   * rather than showing the default agent's.
   */
  readonly currentModel = computed<string | null>(() => {
    const fromMessages = this.messageModel();
    if (fromMessages !== null) {
      return fromMessages;
    }
    return this.store.activeIsSubagent() ? null : this.defaultModel();
  });

  /** True while the viewed session has a turn running. */
  readonly agentsWorking = computed(() => this.store.streaming());

  /**
   * Single source of truth for the overall state and its wording; the head
   * dot and the summary line both derive from it. Priority, first match wins:
   * not yet known (idle), unreachable/unhealthy (error), no provider (error),
   * an enabled MCP server offline (warn), otherwise ok.
   */
  readonly summary = computed<StatusSummary>(() => {
    if (!this.initialized()) {
      const text = 'Checking Servoy AI…';
      return { state: 'idle', text, headline: text, detail: '' };
    }
    if (this.loadError() || this.healthy() !== true) {
      const text = 'Could not reach Servoy AI. It may still be starting.';
      return { state: 'error', text, headline: text, detail: '' };
    }
    if (this.providers() && !this.hasProviders()) {
      const text = 'No model provider connected';
      return { state: 'error', text, headline: text, detail: '' };
    }
    const state: OverallState = this.mcpOffline() > 0 ? 'warn' : 'ok';
    return {
      state,
      text: `${READY_HEADLINE} - ${READY_DETAIL}`,
      headline: READY_HEADLINE,
      detail: READY_DETAIL
    };
  });

  readonly overall = computed<OverallState>(() => this.summary().state);

  /** Kiro credits block; null hides it. */
  readonly creditsView = computed(() => {
    const c = this.usage.credits();
    if (!c || !this.usage.creditsVisible()) {
      return null;
    }
    const nf = new Intl.NumberFormat('en-US');
    const used = nf.format(Math.round(c.used));
    if (c.state === 'stale') {
      return { state: c.state, value: 'not synced', bar: false, percent: 0, left: '', level: 'ok' };
    }
    if (c.state === 'nolimit' || c.limit === null) {
      return { state: c.state, value: used, bar: false, percent: 0, left: '', level: 'ok' };
    }
    const rawPercent = (c.used / c.limit) * 100;
    const left = nf.format(Math.max(0, Math.round(c.limit - c.used)));
    const reset =
      c.resetAt !== null
        ? ` - resets ${new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' }).format(new Date(c.resetAt))}`
        : '';
    return {
      state: c.state,
      value: `${used} / ${nf.format(c.limit)}`,
      bar: true,
      percent: Math.max(0, Math.min(100, Math.round(rawPercent))),
      left: `${left} left${reset}`,
      level: rawPercent >= 100 ? 'danger' : rawPercent >= 80 ? 'high' : 'ok'
    };
  });

  readonly sessionTokensText = computed(() =>
    this.usage.hasSessionTokens() ? `${formatTokens(this.usage.sessionTokens())} tok` : '-'
  );

  readonly contextText = computed(() => {
    const p = this.usage.context().percent;
    return p === null ? '-' : `${p}% used`;
  });

  ngOnInit(): void {
    // Fetch once immediately so the dot reflects real state without a click,
    // then keep it fresh with a lightweight background poll.
    this.refresh();
    interval(StatusPanelComponent.POLL_INTERVAL_MS)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh(this.defaultModel() === null));
  }

  toggleMcp(): void {
    this.mcpOpen.update((open) => !open);
  }

  /**
   * Refresh the status axes independently rather than as one combined
   * request. This matters because {@code GET /provider} returns the full
   * provider+model catalogue (multiple megabytes); waiting on it inside a
   * {@code forkJoin} kept the indicator stuck grey until that giant payload
   * finished. Now health (the primary signal) lands first and paints the dot,
   * and the heavy provider call updates it when it arrives - a slow or failing
   * axis can no longer block the others.
   *
   * The default agent's model comes from {@code GET /agent}, a large payload
   * (every agent's system prompt) whose answer rarely changes, so the
   * background poll only asks for it while it is still unknown; opening the
   * panel or pressing Refresh always re-reads it.
   */
  refresh(includeDefaultModel = true): void {
    this.loading.set(true);

    this.status.health().subscribe({
      next: (health) => {
        this.healthy.set(health?.healthy ?? null);
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

    this.status.solution().subscribe((name) => this.solutionName.set(name));

    if (includeDefaultModel) {
      this.status.defaultAgentModel().subscribe({
        next: (info) => this.defaultModel.set(info?.model ?? null),
        error: () => undefined
      });
    }

    this.usage.refresh();
  }
}
