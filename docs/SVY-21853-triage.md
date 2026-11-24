# Triage Report - SVY-21853

**Verdict:** PROCEED (with explicit open decisions, see "Questions for the reporter"). Only the figures that have a real source are built. The monthly token limit has no source and must not be shown.

## Reported problem

Servoy AI shows no usage information. The ticket wants, in the status panel of SVY-21852 and in the composer:

- a "Usage - this month" block with Tokens and Spend as "used / limit", a bar, and "1.9M left - resets Dec 1". Without a limit: only "used", no bar, no "left";
- tokens of the open session and "Context 42% used";
- a small ring with the context percentage next to the send button. Click opens the status panel. From 80% it is orange, with a tooltip suggesting a new session or compacting;
- numbers update after every answer, without a reload.

The ticket proposes no implementation. The design (`ServoyAI Redesign.html`, attachment 78953 on SVY-21845) was downloaded and checked: the usage data is **hard-coded mock data**:

- `usage: [{label:'Tokens', used:'3.1M', limit:'5M', pct:'62%', left:'1.9M left - resets Dec 1'}, {label:'Spend', used:'41', limit:'100', ...'59 left this month'}]`
- `sessionTok: '64k tok' | '184k tok'`
- the composer ring carries "Usage: context 42% used".

The design does not say where any number comes from. The currency symbols in both the ticket and the design were corrupted to `?` / `�` in the encoding, so the currency is unknown (see decision 2).

## Root-cause assessment: where does each figure come from?

Sources checked:

- opencode checkout `C:/Users/jcomp/git/opencode`, tag `v2.4.1`, read with `git show` / `git grep` only (the working state was not touched);
- the repo files;
- the live BFF on `127.0.0.1:8183` (read-only GETs);
- `~/.servoy/opencode/kiro-usage.json` on this machine (values only, no secrets copied here).

Caveat: the live instance had **no sessions** (`GET /rest_api/session` returned `data: []`). Per-message `tokens` were therefore verified from the v2.4.1 schema, **not** from a live message.

### (a) Tokens of a session

- **Exists in opencode 2.4.1: yes.**
  - `packages/schema/src/session-message.ts` (~l.240): the `Assistant` message has `cost: Money.USD (optional)` and `tokens: TokenUsage.Info (optional)`.
  - `packages/schema/src/token-usage.ts`: `TokenUsage.Info = { input, output, reasoning, cache: { read, write } }`, and `total() = input + output + reasoning + cache.read + cache.write`.
  - Live events: `session.usage.updated` (ephemeral, `{ cost, tokens }`) and `session.usage.recorded` (durable, `source: 'title' | 'compaction' | 'generate'`) in `packages/schema/src/session-event.ts` (~l.155-175). `session.step.ended` / `session.step.failed` also carry `tokens` and `cost` (~l.372-410).
- **Already in the webui:**
  - `v2-mapping.ts:42-47` spreads the raw message into `info`, so `info.tokens` and `info.cost` are already available on every fetched message.
  - `MessageInfo` (`opencode.models.ts:117-125`) has an index signature but no typed `tokens`. A typed accessor is needed.
  - `session-export.spec.ts` already uses `tokens` in an export fixture.
- **Not verified:**
  - Whether a live-streamed assistant message keeps `info.tokens` after the `ChatStore` merge. Same class of open question as 21852 question 2.
  - Whether the `/event` SSE exposes `session.usage.updated` in 2.4.1.
  - Fallback if either fails: re-fetch the newest message page when the session goes idle ("after every answer"). The store already has a list/refresh path.
- **Meaning of "tokens used by this session":** the sum of `total(tokens)` over the session's assistant messages. Cache reads are counted again at every step, so this sum is much larger than the conversation size. Compaction messages carry their own usage (`Compaction*` has `cost`/`tokens`, documented "usage of the compaction request itself"), and `session.usage.recorded` has the `title`/`generate` sources. See decision 4.
- **Cost in USD:** `cost` is optional and the Kiro models have `cost: []` (live `GET /rest_api/model`), so `info.cost` is 0 or absent for Kiro. **Do not show a USD spend from opencode.**

### (a2) Model context limit

- **Exists: yes.** `packages/schema/src/model.ts`: `limit: { context: Int, output: Int }`.
- Live `GET /servoy_ai/rest_api/model` (proxied, not blocked) returns `data[]` with `id`, `providerID` and `limit`. Verified values for `providerID: kiro`:
  - `auto`, `claude-sonnet-5-5`, `claude-sonnet-5`, `claude-haiku-5-5`: `context` 1,000,000;
  - `claude-sonnet-4-5`, `claude-haiku-4-5`, `claude-opus-4-5`: `context` 200,000;
  - `deepseek-3.2`: 128,000.
- `GET /provider` (what `StatusService.providers()` calls) does NOT carry limits for the kiro provider (descriptor keys are `id, integrationID, name, activation, package, settings`), so a new `StatusService.models()` over `rest_api/model` is needed, matched on `info.model.providerID` + `info.model.id` (the model ref is on every assistant message, per the SVY-21852 spec 2.4).
- Edge: a message model id may not exist in the list, and `limit.context` may be 0 or absent (opencode itself guards `limit.context <= 0`, `core/src/session/model-request.ts:98`). In that case show no ring and "Context -". Do not guess a limit.

### (b) Monthly token / spend limit and reset date

- **Tokens, monthly limit: no source anywhere.** There is no token limit in opencode (no account concept; `limit` is per model) and none in the Kiro account data. Kiro meters **credits**, not tokens. The "5M tokens" in the design is invented. A monthly token total would need summing every session's messages for the month: opencode has no aggregate endpoint, the BFF is scoped to one project directory, and cache reads inflate it. **It should not be shown** (decision 1).
- **Spend: a real source exists, but in Kiro credits, not currency.** `@servoy/opencode-kiro-auth` (pinned `2.10.0` in `~/.servoy/opencode/opencode.json`) writes `kiro-usage.json` into the Servoy opencode config dir.
  - Fields (read from the live file, values only): top level `accounts, pluginInstances, pluginVersion, sessions, version, writtenAt`.
  - `accounts[]`: `email, id, isHealthy, limit, pct, region, resetAt, updatedAt, used`. Here `used=381.29`, `limit=10000`, `pct=4`.
  - `resetAt` is epoch ms. The live value `1793491200000` is `2026-11-01T00:00Z`, i.e. the 1st of a month, consistent with "resets Dec 1". So a **monthly limit and reset date do exist**, as credits.
  - `sessions[]`: `sessionId, directory, requests, estCredits, firstUsed, lastUsed, accounts[]`. A per-session credit estimate also exists, but not tokens.
  - A real, usable "used / limit / left / resets" figure therefore exists only for the Kiro provider and only in credits. The bar and "N left - resets <date>" can be built from it. Labelling it "Spend" with a currency sign would be wrong (decision 2).
- **Staleness risk (verified on this machine):**
  - The file has `updatedAt`/`writtenAt` `2026-10-02 14:12Z`; today is 2026-11-24.
  - `resetAt` (2026-11-01) is already **in the past**. The numbers are the previous period's.
  - The file is only rewritten by the plugin while it syncs, so the figures are only as fresh as the last Kiro activity.
  - Rule: if `resetAt < now`, or `updatedAt` is older than a threshold, show "unknown / not synced" and no "left", never the old number as current. The staleness threshold is a decision (decision 3).
- **Existing readers (skilltest):**
  - `KiroCreditReader.java:195-243` (`accountUsed()` / `accountLimit()`, summed over `accounts[]`) and `KiroUsageReader.java` read the JSON; `creditsForSession` reads `kiro.db` through SQLite (`org.sqlite`).
  - They live in `com.servoy.eclipse.opencode.skilltest`, which `Require-Bundle`s `com.servoy.eclipse.opencode`. The opencode bundle cannot depend back on it (cycle), and it is an internal test bundle. Reuse the idea, not the class.
  - SQLite is **not needed**: `accounts[].used/limit/resetAt` are in the JSON only. `org.sqlite` is imported only in the skilltest MANIFEST, not in the opencode bundle's MANIFEST. Do not add it.
  - Path: `OpenCodeUtil.getServoyOpencodeConfigDir()` (`OpenCodeUtil.java:105`) is the single source of truth, already used by `Activator`/`OpencodeFolderCreatorJob`, so it works on every OS. The skilltest probe lists (`XDG_CONFIG_HOME`, `APPDATA`, `KIRO_CONFIG_DIR`) are test-harness fallbacks; the BFF should read **only** the Servoy config dir (plus nothing else), to avoid reading arbitrary locations.
- **Availability:** the file only exists for the Kiro provider. With a `GENAI_API_KEY` / openai-compatible provider the file is absent, so no usage block (show nothing, or only per-session numbers).

**New BFF endpoint judgement: feasible and safe if built like `/file` and `/solution`:**

- `GET /servoy_ai/usage`, BFF-owned (not under `rest_api/**`, never proxied). Add it to `service()` before the static fallback.
- Same guards as the existing `/file` route: `GET`/`HEAD` only (`405` otherwise), loopback Host, same-origin Origin / `Sec-Fetch-Site` checks, `Cache-Control: no-store`.
- Parse with Jackson (already used by `McpConfigWriter`), tolerant: absent / unreadable / malformed returns `{"available":false}`, never an error status.
- **Whitelist the response.** Return only `{ available, used, limit, pct, resetAt, updatedAt }` (numbers). Never forward the raw file: it contains `email`, account `id` (hash), `directory` paths and `pluginInstances`. Never read `auth.json`, `kiro.json`, `mcp-auth.json` (tokens) or `opencode.db`.
- Multiple accounts: `KiroCreditReader` sums them. Which to use is decision 5.
- Honest limit (as in AGENTS.md): the proxy has no authentication; the extra Host/Origin checks make the endpoint no weaker than `/file`. The data is the user's own account usage; not a secret, but it is account metadata, so the whitelist and checks matter.
- Java changes: new small reader class in the opencode bundle, `OpencodeChatServlet` route plus `isUsageRequest`, `ServicesProvider` wiring (a supplier, same seam style as `solutionNameSupplier` in SVY-21852), plain-JUnit tests (fixture JSON, stale, missing, malformed, multi-account, field whitelist, 405, Host/Origin).

### (c) Context percentage

- **Formula (recommended):** `used = tokens of the newest assistant message that has tokens` and `percent = used / model.limit.context`.
  - opencode itself measures the live context as `input + cache.read + cache.write + output + reasoning` for the last completed step (`core/src/session/compaction.ts`, "measured" anchor, ~l.1138); a prompt-only variant `promptTokens = input + cache.read + cache.write` (~l.974) exists too. The ring should use the same measure as opencode's own compaction decision (`compaction.ts:1171` uses `min(limit.input, limit.context)` as the ceiling), i.e. including `output + reasoning`, because the output becomes part of the next request.
  - Not `total()` summed over the whole session: cache reads repeat every step and would exceed 100%.
- **Compaction / summary:**
  - A `compaction` message (`session-message.ts`, `Compaction*`) records the usage of the compaction request itself, "not the size of the resulting context". It must be skipped when picking the "newest message with tokens".
  - Right after a compaction the true size is unknown until the next assistant step reports it. Show the last known value or "-", do not compute from the compaction message.
  - `title`/`generate` usage (`session.usage.recorded` sources) belongs to other requests and must not feed the ring.
- **Subagent sessions:** the percentage follows the viewed session (`activeSessionId` / `messages`), as the SVY-21852 spec 3.4 already prescribes for Model/Agents.
- **Not verified live:** actual `info.tokens` numbers on a real message (no sessions on the live instance) and the exact `Compaction` message shape in the fetched list. The implementer must check both against a real turn (open questions).

### Webui integration points (existing, from SVY-21852)

- The panel layout reserves a slot between `.facts` and `.mcp` for this block (spec 3.9). SVY-21853 inserts its markup there without touching other blocks.
- The shell already has `statusOpen` / `toggleStatus()` for the ring click.
- `ChatStore.messages()` + `activeSessionId()` are the session source; `StatusService` gets `usage()` and `models()`.
- Existing tokens to use: `--svy-ok`, `--svy-accent` (graphics), `--svy-acc-fg` (orange text, AA), `--svy-muted`, `--svy-line2`, `--svy-danger`, `--svy-idle`.
- Refresh: the panel's existing 15 s poll and `refresh()` cover the account figure. Session tokens and the ring must be computed from the store signals, so they update after each answer without a reload.

## Ticket premise check

The ticket's premise is partly wrong:

- "Tokens used / limit this month" has no source. Kiro limits **credits**, not tokens. Showing "3.1M / 5M tokens" would be invented.
- "Spend used / limit" has a real source, but it is credits (`used` 381.29 / `limit` 10000, `resetAt`), not currency, and only for the Kiro provider.
- "Tokens of the open session", "Context 42% used" and the ring are reliable from opencode data (`info.tokens` plus `/model` `limit.context`), subject to the live-merge verification above.
- "No limit: only the amount used" is the correct behaviour for non-Kiro providers or when the snapshot is missing or stale.

## Approaches considered

1. **Credits from `kiro-usage.json` via a new whitelisted BFF endpoint, session/context from store + `/model` (recommended).**
   - Pros: every figure shown is real; the BFF pattern is established (`/file`, `/solution`); no SQLite and no new bundle dependencies; works wherever the Servoy config dir exists.
   - Cons: Kiro only; stale data needs handling; deviates from the design's "Tokens" and currency rows (relabel to credits).
2. **Compute monthly tokens by aggregating all sessions in the webui or BFF.**
   - Pros: matches the design's "Tokens" row.
   - Cons: no aggregate API; needs paging through every session and message; scoped to one project directory (misses other projects); no limit exists anyway, so no bar and no "left". Costly and still partial. Not recommended.
3. **Show only per-session tokens and context (ring + panel), no account block.**
   - Pros: smallest, no Java change, zero invented data.
   - Cons: drops the "left / resets" part of the ticket.
4. **No code change needed:** not appropriate. Nothing in the UI shows tokens or context today (grep of `webui/src/app` found no usage/context code), and the data to build most of it exists.

## Recommendation

PROCEED with approach 1, built in two layers so the safe part can ship independently:

- **Layer A (webui only, no open data question):**
  - session tokens;
  - context % = newest non-compaction assistant message's `input + cache.read + cache.write + output + reasoning` divided by `limit.context` from `GET rest_api/model` (matched by `providerID` + `id`);
  - the composer ring: click calls the existing `toggleStatus()`; at or above 80% it uses `--svy-accent` for the ring and `--svy-acc-fg` for any text; tooltip "Context N% used - start a new session or compact" at 80% and above; no ring when model or limit is unknown;
  - add typed `tokens` / `cost` / `model` accessors for `MessageInfo` (index signature today).
- **Layer B (Java + webui):** `GET /servoy_ai/usage` (whitelisted, guarded) feeding a "Usage - this month" block with **credits** used / limit, bar, "N left - resets <date>"; hidden when unavailable, stale (`resetAt < now`), or when the provider is not Kiro.
- Do NOT show a monthly token limit, a token bar, or a currency spend.
- Keep the unit tests plain JUnit for Java (route, guards, reader on fixtures) and Vitest for the webui (percent math, compaction skip, 80% threshold, no-limit rendering, stale handling). Add a negative test that the response carries none of `email`, `id`, `directory`.

## Git history findings

- `47a74a5` / SVY-21845 and the SVY-21852 spec: the status panel body and `statusOpen` exist; the spec explicitly keeps usage/token/cost/context markup for this case (spec sections 3.9, 6) and says the session blocks must read `activeSessionId`/`messages`.
- `SVY-21660` (`docs/SVY-21660-cost-token-efficiency-tracking.spec.md`, readers in `com.servoy.eclipse.opencode.skilltest`): documents the `kiro-usage.json` shape (`accounts:[{ id, email, region, used, limit, pct, resetAt, isHealthy, updatedAt }]`) and that `kiro.db` has no account table. It confirms the JSON is the only account-limit source.
- Version note: AGENTS.md pins opencode `2.4.1`; the SVY-21852 spec quoted the 2.2.0 protocol sources. This triage used the `v2.4.1` tag. The `tokens` / `limit` shapes read there are the 2.4.1 ones.

## Questions for the reporter

1. **Tokens limit:** there is no monthly token limit anywhere (Kiro gives credits). Should the "Tokens" row be dropped, or replaced by the per-session token figure only? We recommend dropping the monthly token row.
2. **Spend row:** the only real account figure is Kiro **credits** (for example 381 / 10000, resets on the 1st). Show it labelled "Credits" (not a currency amount, no `$`/euro sign)? And what should users with a non-Kiro provider (`GENAI_API_KEY`) see: nothing, or only the session numbers? We recommend labelling it "Credits" and hiding the block for non-Kiro.
3. **Stale data:** `kiro-usage.json` is only written while the Kiro plugin syncs, and on this machine it was last written on 2 Oct with a reset date already in the past. What should we show when `resetAt` is in the past or the snapshot is older than N hours: "not synced" with no "left" (recommended), or the last known value with a "last updated" hint? What N?
4. **"This session" tokens:** sum of all tokens including cache reads (large, repeated) or the last context size, or input + output only? Include subagent child sessions? We recommend showing the sum of `input + output + reasoning` across the session's assistant messages, excluding cache reads, plus the context percentage separately.
5. **Several Kiro accounts in the file:** sum them (as `KiroCreditReader` does), or use the first healthy one? We recommend the first healthy account (summing different accounts' limits is misleading).
6. **Verify on a real session (implementer checklist, not a user question):** a live assistant message really carries `info.tokens`; streamed messages keep `tokens` after the `ChatStore` merge; the shape of a `compaction` message in the fetched list; whether `/event` delivers `session.usage.updated` in 2.4.1. If a streamed message drops `tokens`, fall back to re-fetching the newest message page when the session goes idle.
