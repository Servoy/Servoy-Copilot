import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';

import { MessageWithParts, ModelLimit, UsageSnapshot } from '../models/opencode.models';
import { ChatStore } from './chat-store.service';
import { OpencodeApiService } from './opencode-api.service';
import { StatusService } from './status.service';
import { contextPercent, contextUsed, hasSessionTokens, messageModel, sessionTokens } from './usage-utils';

export const CONTEXT_HIGH_PERCENT = 80;
const STALE_AFTER_MS = 24 * 60 * 60 * 1000;
const MODEL_REFETCH_MS = 60_000;

export type CreditsState = 'fresh' | 'stale' | 'nolimit';

export interface CreditsView {
  used: number;
  limit: number | null;
  pct: number | null;
  resetAt: number | null;
  state: CreditsState;
}

export interface ContextView {
  percent: number | null;
  used: number;
  limit: number | null;
  high: boolean;
}

interface UsageOverlay {
  tokens?: unknown;
  model?: unknown;
  cost?: unknown;
}

/**
 * Single source of the usage numbers (session tokens, context fill, Kiro
 * credits) so the status panel and the composer ring cannot disagree.
 *
 * Live-streamed assistant messages are created from {@code session.text.*} /
 * {@code session.tool.*} events as placeholders that carry no {@code tokens} or
 * {@code model}; those only exist on messages fetched from {@code GET /message}.
 * When the viewed session goes idle the newest page is therefore re-fetched and
 * only the usage fields are overlaid onto the store's messages.
 */
@Injectable({ providedIn: 'root' })
export class UsageStore {
  private readonly store = inject(ChatStore);
  private readonly status = inject(StatusService);
  private readonly api = inject(OpencodeApiService);

  readonly modelLimits = signal<ModelLimit[]>([]);
  readonly credits = signal<CreditsView | null>(null);

  private readonly overlay = signal<{ sessionID: string | null; byId: Record<string, UsageOverlay> }>({
    sessionID: null,
    byId: {}
  });
  private lastModelFetch = 0;
  private lastModelFetchSession: string | null = null;
  private wasStreaming = false;
  private lastStreamSession: string | null = null;

  /** The viewed session's messages with the fetched usage fields laid over. */
  private readonly usageMessages = computed<MessageWithParts[]>(() => {
    const messages = this.store.messages();
    const { sessionID, byId } = this.overlay();
    if (sessionID !== this.store.activeSessionId() || Object.keys(byId).length === 0) {
      return messages;
    }
    return messages.map((m) => {
      const extra = byId[m.info.id];
      if (!extra) {
        return m;
      }
      const info = { ...m.info };
      if (info['tokens'] == null && extra.tokens != null) {
        info['tokens'] = extra.tokens;
      }
      if (info['model'] == null && extra.model != null) {
        info['model'] = extra.model;
      }
      if (info['cost'] == null && extra.cost != null) {
        info['cost'] = extra.cost;
      }
      return { ...m, info };
    });
  });

  readonly sessionTokens = computed(() => sessionTokens(this.usageMessages()));
  readonly hasSessionTokens = computed(() => hasSessionTokens(this.usageMessages()));

  readonly context = computed<ContextView>(() => {
    const ctx = contextUsed(this.usageMessages());
    if (!ctx) {
      return { percent: null, used: 0, limit: null, high: false };
    }
    const entry = ctx.model
      ? this.modelLimits().find((m) => m.providerID === ctx.model?.providerID && m.id === ctx.model?.id)
      : undefined;
    const limit = entry?.context ?? null;
    const percent = contextPercent(ctx.used, limit);
    return { percent, used: ctx.used, limit, high: percent !== null && percent >= CONTEXT_HIGH_PERCENT };
  });

  /** Provider of the newest assistant message with a model; null when none. */
  private readonly viewedProvider = computed<string | null>(() => {
    const messages = this.usageMessages();
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i].info.role !== 'assistant') {
        continue;
      }
      const model = messageModel(messages[i].info);
      if (model) {
        return model.providerID;
      }
    }
    return null;
  });

  /** Credits are shown unless the viewed session demonstrably uses a non-Kiro provider. */
  readonly creditsVisible = computed(() => {
    if (!this.credits()) {
      return false;
    }
    const provider = this.viewedProvider();
    return provider === null || provider.toLowerCase().includes('kiro');
  });

  constructor() {
    effect(() => {
      const ctx = contextUsed(this.usageMessages());
      const limits = this.modelLimits();
      const sessionID = this.store.activeSessionId();
      if (!ctx?.model) {
        return;
      }
      const known = limits.some((m) => m.providerID === ctx.model?.providerID && m.id === ctx.model?.id);
      if (!known) {
        untracked(() => this.loadModels(sessionID));
      }
    });

    effect(() => {
      const streaming = this.store.streaming();
      const sessionID = this.store.activeSessionId();
      untracked(() => {
        const ended = this.wasStreaming && !streaming && sessionID !== null && sessionID === this.lastStreamSession;
        this.wasStreaming = streaming;
        this.lastStreamSession = sessionID;
        if (ended) {
          this.fetchUsageOverlay(sessionID);
          this.loadCredits();
        }
      });
    });
  }

  refresh(): void {
    this.loadModels(this.store.activeSessionId(), this.modelLimits().length === 0);
    this.loadCredits();
  }

  private loadModels(sessionID: string | null, force = false): void {
    const now = Date.now();
    const sameSession = this.lastModelFetchSession === sessionID;
    if (!force && sameSession && now - this.lastModelFetch < MODEL_REFETCH_MS) {
      return;
    }
    this.lastModelFetch = now;
    this.lastModelFetchSession = sessionID;
    this.status.models().subscribe((models) => this.modelLimits.set(models));
  }

  private loadCredits(): void {
    this.status.usage().subscribe((snapshot) => this.credits.set(UsageStore.toCredits(snapshot, Date.now())));
  }

  private fetchUsageOverlay(sessionID: string): void {
    this.api.listMessagePage(sessionID).subscribe({
      next: (page) => {
        if (this.store.activeSessionId() !== sessionID) {
          return;
        }
        const byId: Record<string, UsageOverlay> = {};
        for (const m of page.messages ?? []) {
          if (m.info.role === 'assistant' && m.info['tokens'] != null) {
            byId[m.info.id] = { tokens: m.info['tokens'], model: m.info['model'], cost: m.info['cost'] };
          }
        }
        this.overlay.update((prev) => ({
          sessionID,
          byId: prev.sessionID === sessionID ? { ...prev.byId, ...byId } : byId
        }));
      },
      error: () => {
        // Transient: the next idle retries.
      }
    });
  }

  static toCredits(snapshot: UsageSnapshot | null, now: number): CreditsView | null {
    if (!snapshot || snapshot.available !== true || typeof snapshot.used !== 'number') {
      return null;
    }
    const limit = typeof snapshot.limit === 'number' && snapshot.limit > 0 ? snapshot.limit : null;
    const resetAt = typeof snapshot.resetAt === 'number' ? snapshot.resetAt : null;
    const updatedAt = typeof snapshot.updatedAt === 'number' ? snapshot.updatedAt : null;
    const stale =
      snapshot.stale === true ||
      (resetAt !== null && resetAt < now) ||
      updatedAt === null ||
      now - updatedAt > STALE_AFTER_MS;
    const pct = typeof snapshot.pct === 'number' ? snapshot.pct : null;
    const state: CreditsState = stale ? 'stale' : limit === null ? 'nolimit' : 'fresh';
    return { used: snapshot.used, limit, pct, resetAt, state };
  }
}
