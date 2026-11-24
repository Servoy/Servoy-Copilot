# Spec: SVY-21853 — Servoy AI: show tokens and spend used, what is left, and how full the conversation's context is

## 1. Goal

Show real usage figures in the Servoy AI chat UI:

- In the status panel: a "Usage - this month" block with the Kiro **credits** used / limit, a bar and "N left - resets <date>" (Layer B), plus the tokens of the viewed session and "Context N% used" (Layer A).
- In the composer: a small ring next to the send button with the context percentage. Click opens the status panel. From 80% it is orange with a tooltip suggesting a new session or a compact.
- Numbers update after every answer, without a reload.

Only figures that have a real source are shown. The ticket's monthly "Tokens used / limit" has no source and is dropped. The ticket's "Spend" is relabelled "Credits" (no currency sign).

## 2. Background

Authoritative input: `docs/SVY-21853-triage.md` (approach 1, layers A + B, user decisions below). Panel structure: `docs/SVY-21852-status-panel-overall-state.spec.md` (panel body is a stack of independent blocks; slot between `.facts` and `.mcp` reserved in 3.9; `statusOpen` / `toggleStatus()` in `app.component.ts`; `ChatStore.messages()` + `activeSessionId()` are the viewed-session source).

### 2.1 Decisions taken at the triage gate

1. Monthly Tokens row dropped entirely. No token bar.
2. Spend row labelled "Credits": used / limit, bar, "N left - resets <date>". No currency sign. Hidden for non-Kiro providers or when the file is absent.
3. Stale: `resetAt` in the past, or snapshot older than 24 h, shows "not synced" and no "left" and no bar.
4. Session tokens = sum of `input + output + reasoning` over the session's assistant messages (cache reads excluded). Context % is separate.
5. Several Kiro accounts: use the first healthy account (no summing).
6. Context % = newest non-compaction assistant message with tokens: `input + cache.read + cache.write + output + reasoning`, divided by the model's `limit.context` from `GET rest_api/model`, matched by `providerID` + `id`. Unknown limit: no ring and "Context -". Follows the viewed session.

### 2.2 Verified facts (from triage, opencode 2.4.1)

- Assistant messages carry optional `tokens: { input, output, reasoning, cache: { read, write } }`, optional `cost`, and `model: { id, providerID, variant? }`. `v2-mapping.ts` spreads the raw message into `info`, so these are on `info` already; `MessageInfo` (`models/opencode.models.ts`, index signature) has no typed accessors.
- `GET rest_api/model` returns `data[]` with `id`, `providerID`, `limit: { context, output }`. `GET /provider` does not carry limits.
- Kiro models have `cost: []`; never show a USD amount.
- `kiro-usage.json` lives in the Servoy opencode config dir (`OpenCodeUtil.getServoyOpencodeConfigDir()`, `OpenCodeUtil.java:105`). `accounts[]`: `email, id, isHealthy, limit, pct, region, resetAt (epoch ms), updatedAt, used`; top-level `writtenAt`. On the dev machine the file is stale (reset date in the past).
- The skilltest readers (`KiroCreditReader`/`KiroUsageReader`) cannot be reused (bundle cycle, SQLite). No SQLite and no new bundle imports.
- `OpencodeChatServlet` already has `/solution` and `/file` BFF routes, `isSameOriginLoopbackRequest(host, secFetchSite, origin)`, `sendJson(...)` (no-store, nosniff), a supplier seam style, `ServicesProvider` wiring. `/file` is the guard model to copy.
- `app.component.html` renders `<svy-composer>` only for non-subagent sessions (`@else` of `store.activeIsSubagent()`); in a subagent view `<svy-agent-footer>` is shown instead. So the ring exists only where the composer exists; the status panel numbers still follow a viewed subagent session.

### 2.3 Not verified (implementer must check against a real turn)

- A live-streamed assistant message keeps `info.tokens` and `info.model` after the `ChatStore` merge.
- The shape of a `compaction` message in the fetched list.
- Whether `/event` delivers `session.usage.updated` in 2.4.1.
- No test sessions with real model calls may be left in the workspace afterwards (delete any session created for the check).

## 3. Design

### 3.1 Typed accessors (webui, new file `services/usage-utils.ts`)

Pure functions, no Angular imports, unit-testable:

```ts
export interface TokenUsage { input: number; output: number; reasoning: number; cacheRead: number; cacheWrite: number }
export function messageTokens(info: MessageInfo): TokenUsage | null   // null when tokens absent/not an object; missing numeric fields = 0; non-finite = 0
export function messageModel(info: MessageInfo): { id: string; providerID: string } | null
export function messageCost(info: MessageInfo): number | null
export function isCompaction(info: MessageInfo): boolean               // type === 'compaction' (confirm shape, 2.3)
export function sessionTokens(messages: MessageWithParts[]): number    // sum(input+output+reasoning) over assistant messages with tokens, compaction skipped
export function contextUsed(messages): { used: number; model: {id; providerID} | null } | null
   // scan newest -> oldest; first assistant, non-compaction message with tokens and a model; used = input+cacheRead+cacheWrite+output+reasoning
export function contextPercent(used: number, limit: number | null | undefined): number | null  // null when limit not finite or <= 0; Math.round(used/limit*100); NOT clamped below 0; clamped to max 100 for display bar width only (text may show >100)
export function formatTokens(n: number): string                        // 0..999 -> "812", 1k.. -> "64k", 1.2M -> "1.2M"
```

`MessageInfo` also gets optional typed fields (`tokens?`, `cost?`, `model?`) only if this does not break the existing index signature usages; otherwise the accessors alone are enough.

### 3.2 Model limits (webui)

`StatusService.models(): Observable<ModelLimit[]>`: `GET rest_api/model`, maps `data[]` to `{ providerID, id, context }` (`context = limit?.context` as number, else `null`), `catchError(() => of([]))`. A new `UsageStore` (injectable, `providedIn: 'root'`, signals, OnPush-safe) is the single place for the numbers so the panel and the composer cannot disagree:

```ts
readonly modelLimits = signal<ModelLimit[]>([])
readonly sessionTokens = computed(() => sessionTokens(store.messages()))
readonly context = computed(() => { used/model from contextUsed(); limit from modelLimits by providerID+id; percent via contextPercent })
   // { percent: number | null, used: number, limit: number | null, high: boolean }  high = percent !== null && percent >= 80
readonly credits = signal<CreditsView | null>(null)          // see 3.5
refresh(): void   // fetches models (once if empty or when a message model is not in the list) and credits
```

- Percentage follows the viewed session: it is derived only from `ChatStore.messages()` (which is reset on session switch). A fresh session with no assistant message gives `percent = null` (panel "Context -", no ring).
- Right after a compaction the newest non-compaction assistant message may be older than the compaction; the last known value stays (triage 2c). No value computed from the compaction message.
- `modelLimits` is loaded on first use and re-fetched when the current model is missing from the list (at most once per session switch / per 60 s, to avoid a loop when the model genuinely does not exist).

### 3.3 Live updates

All of session tokens, context and ring are `computed` over the store's `messages()` signal, so they update whenever a message/step update lands, without extra HTTP. Fallback required by the triage: if verification (2.3) shows streamed messages drop `info.tokens`, `UsageStore` re-fetches the newest message page when the active session goes idle (use the store's existing refresh path; do not add a poll). The implementer chooses based on the check and documents the result in the final report; the fallback code is only added if needed, with a test.

### 3.4 Status panel blocks (webui)

Insert between `.facts` and `.mcp` in `status-panel.component.html` (slot from SVY-21852 3.9), without touching the other blocks. Two independent elements:

1. `.usage` (only rendered when `credits()` is not null, i.e. Kiro snapshot exists; see 3.5): heading "Usage - this month" (ASCII hyphen as in the ticket text; visible text exactly `Usage - this month`), one row "Credits":
   - fresh: value `381 / 10,000` (used rounded to a whole number, limit with thousands separator), a bar (`role="progressbar"`, `aria-valuenow/min/max`, `aria-label="Credits used"`; fill `--svy-ok`, `--svy-accent` at >= 80%, `--svy-danger` at >= 100%), and text `9,619 left - resets Dec 1` (date via `Intl.DateTimeFormat`, month short + day, from `resetAt`).
   - stale: value text `not synced`, no bar, no "left".
   - no limit (limit missing/0) but fresh: only `used`, no bar, no "left".
2. `.session-usage` (always rendered once the panel is mounted; two rows, same visual style as `.facts`, monospace muted values): `This session` -> `64k tok` (`formatTokens(sessionTokens)`, `-` when no assistant message with tokens), `Context` -> `42% used` or `-`. Context value uses `--svy-acc-fg` text when `high`. Both follow the viewed session.

No monthly tokens row, no token bar, no currency sign, no USD cost anywhere. Text colours only from `--svy-*` tokens: orange text `--svy-acc-fg`, orange graphics `--svy-accent`; never `--svy-accent` for text.

Content guard from SVY-21852 still holds (no skills/plugins/agent definitions/version text); the new copy must not contain those words.

### 3.5 Java/BFF: `GET /servoy_ai/usage`

BFF-owned, read-only, never proxied, routed in `service()` beside `/solution` and `/file` (before `isApiRequest` and the static fallback).

- Route classifier `public static boolean isUsageRequest(String pathInfo)`: exactly `/usage` (null safe; `/usage/x`, `/rest_api/usage`, `/Usage` are false). `USAGE_PATH = "/usage"`.
- Guards identical to `/file`: only `GET`/`HEAD` (else `405` + `Allow: GET, HEAD`); `isSameOriginLoopbackRequest(Host, Sec-Fetch-Site, Origin)` else `403 {"error":"forbidden"}` (reuse `FORBIDDEN_JSON`); response via `sendJson` (`application/json;charset=UTF-8`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`). No readiness gate, no opencode port needed, no active solution needed.
- Source: a new small class `KiroUsageSnapshotReader` (package `com.servoy.eclipse.opencode`, or `.tomcat` next to `WorkspaceFileReader`; implementer picks, document it) with
  `Snapshot read(Path configDir, long nowMillis)`; constructed with a `Supplier<Path>`; `ServicesProvider` passes `OpenCodeUtil::getServoyOpencodeConfigDir`. It reads ONLY `<configDir>/kiro-usage.json` (fixed file name, no user-supplied path component, no other file, not `auth.json`, `kiro.json`, `mcp-auth.json`, `opencode.db`), size-capped (1 MiB), Jackson tree (already used by `McpConfigWriter`), tolerant: absent, unreadable, oversize, malformed, wrong shape, no account: `available=false`. A `RuntimeException`/`IOException` is caught (logged without content) and answered as `{"available":false}`, status 200.
- Account choice: the first account in `accounts[]` with `isHealthy == true`; if none is healthy, `available=false`.
- Response (whitelist, built field by field, never a copy of the tree):

  ```json
  {"available":true,"used":381.29,"limit":10000,"pct":4,"resetAt":1793491200000,"updatedAt":1759414320000,"stale":true}
  ```
  and `{"available":false}`. Numbers only (plus booleans). Missing/non-numeric numeric field: omitted (the webui treats absent as unknown); `limit <= 0` is emitted as absent. `updatedAt` = account `updatedAt`, else top-level `writtenAt`, parsed from epoch ms number or ISO-8601 string (the live file shows `2026-10-02 14:12Z` style; accept both, tolerant; unparsable: omitted). `stale` is computed on the server with the supplied `nowMillis`: `true` when `resetAt` present and `< now`, or when `updatedAt` is missing or `now - updatedAt > 24 h`. The webui additionally re-derives staleness client-side from the same fields so a long-open panel does not show an expired snapshot as fresh.
  Never emitted: `email`, `id`, `region`, `directory`, `sessions`, `pluginInstances`, `pluginVersion`, any path or token.
- No `Content-Length` mismatch for HEAD (follow `sendJson`).
- `OpencodeChatServlet`: new field `Supplier<String> usageJsonSupplier` (returns the response JSON; the servlet only sends it, so the servlet test needs no file system), new 8-argument constructor; the existing 6- and 7-argument constructors keep compiling and delegate with `() -> "{\"available\":false}"`. `ServicesProvider` wires `() -> new KiroUsageSnapshotReader(OpenCodeUtil::getServoyOpencodeConfigDir).readJson(System.currentTimeMillis())`.
- Non-Kiro provider: file absent gives `available=false`, so the Credits row is hidden. If the file exists from a past Kiro use but the active provider is not Kiro, the panel hides the block when the viewed session's provider (or, when none, the configured providers list) does not include `kiro`; use `messageModel(...).providerID` of the newest assistant message when present, else show per the file only.
- Honest limit (as for `/file`, AGENTS.md): the proxy has no authentication of its own; the endpoint is no weaker than `/file` and exposes only whitelisted numbers.
- AGENTS.md bundle table/BFF description gets one line for `/usage` (the AGENTS.md is edited by the implementation phase only if the user/orchestrator allows it; do not change it in this spec phase).

### 3.6 Credits view model (webui)

`StatusService.usage(): Observable<UsageSnapshot | null>`: `GET usage` (relative, like `solution`), `catchError(() => of(null))`. `UsageStore.credits` maps it to `{ used, limit|null, pct, resetAt|null, state: 'fresh' | 'stale' | 'nolimit' }` or `null` when `available !== true`. Refresh: on panel `refresh()` (initial, 15 s poll, Refresh button, opening) and on idle of the active session; no separate timer.

### 3.7 Composer ring (webui)

- `ComposerComponent` gets two inputs, set by `app.component.html`: `[contextPercent]="usage.context().percent"` and `(contextClick)="toggleStatus()"`. `AppComponent` injects `UsageStore`.
- Rendered next to the send/stop button, only when `contextPercent !== null`. A native `<button type="button" class="ctx-ring">` with an inline SVG ring (stroke-dasharray from the percent, `aria-hidden`), `aria-label="Context N% used. Open status"`; keyboard operable; visible `:focus-visible` outline using the app's existing outline token; min target size 24 px.
- Colour: below 80% ring `--svy-ok`... (use the neutral `--svy-muted` track and `--svy-ok` arc); >= 80% arc `--svy-accent`. Any text inside/next to the ring (percentage digits if shown) uses `--svy-acc-fg`, never `--svy-accent`. The meaning is also in the `aria-label`/`title`, not colour only.
- Tooltip (`title`): below 80%: `Context N% used`; at or above 80%: `Context N% used - start a new session or compact`. Percentages over 100 render as e.g. `103%`; the ring fill caps at full.
- Click calls `toggleStatus()`; the panel opens (and refreshes via the existing code).
- No ring and no change in layout width when the limit/model is unknown. The ring is absent in a subagent view (composer not rendered there, 2.2); the panel still shows that session's numbers.
- Update after every answer without reload: ring and panel read the same `UsageStore.context` computed (3.2/3.3).

## 4. Implementation plan

Webui conventions as in SVY-21852 (standalone, OnPush, signals, `readonly`, `inject()`, single quotes, NG0600 safe, `--svy-*` tokens only). Java: tabs, license header, `//$NON-NLS-1$`, no new bundle imports, no SQLite; use the Eclipse MCP tools for the Java bundle, `ng`/`npm` for `webui/`. Another session works in the same directory: touch only the files below.

Layer A (webui):
1. `services/usage-utils.ts` (+ spec): accessors and math (3.1).
2. `models/opencode.models.ts`: optional typed token/model/cost types.
3. `services/status.service.ts`: `models()` (3.2) and `usage()` (3.6) (+ spec additions).
4. `services/usage-store.service.ts` (+ spec): `UsageStore` (3.2, 3.3, 3.6).
5. `components/status-panel.component.{ts,html,scss}`: `.session-usage` block and `.usage` block between `.facts` and `.mcp` (3.4); call `usage.refresh()` from `refresh()`.
6. `components/composer.component.{ts,html?,scss}`: ring input/output/markup/styles (3.7); `app.component.{ts,html}`: inject `UsageStore`, bind ring input and `contextClick`.

Layer B (Java, do before wiring the panel to live data):
7. New `KiroUsageSnapshotReader` (3.5).
8. `OpencodeChatServlet`: `USAGE_PATH`, `isUsageRequest`, `usageJsonSupplier`, new constructor, `service()` route, `handleUsage` (guards as `handleFile`, `sendJson`).
9. `ServicesProvider`: wire the reader.
10. Compile check with `eclipse-ide_getCompilationErrors` for `com.servoy.eclipse.opencode` and `com.servoy.eclipse.opencode.tests`; fix errors and any high-severity Spotbugs finding in the new code.

Verification:
11. Webui: `npx ng test --no-watch` (touched specs first), `npx ng build`. Check against a real turn (2.3) and report: tokens present on live messages, model kept on merge, compaction shape, `session.usage.updated` on `/event`. Delete any session created for the check; leave no test sessions with real model calls.
12. Java: run plain JUnit with `eclipse-ide_runJUnitTests` (project `com.servoy.eclipse.opencode.tests`, package `com.servoy.eclipse.opencode.tests.plainjunit`), poll with `getOperationStatus`.
13. Production build of the webui before any commit; commit messages carry `SVY-21853` and end with ` [ai]`; show staged files and message first; push nothing.

## 5. Tests

### 5.1 Vitest (webui)

- `usage-utils.spec.ts`: tokens accessor (absent, partial, non-finite, string numbers ignored); `sessionTokens` sums `input+output+reasoning`, excludes cache, skips user/compaction messages; `contextUsed` picks the newest non-compaction assistant message with tokens, includes cache read/write, output and reasoning; `contextPercent` rounds, returns `null` for limit `0`/`undefined`/`NaN`; 80% threshold boundaries (79 / 80 / 100 / 103); `formatTokens` (999, 1000, 64000, 1.2M).
- `usage-store.service.spec.ts`: context follows `messages()` replacement (session switch) and a new streamed assistant message; `null` when model missing from the model list or limit unknown; refetches models once when the model is absent, not in a loop; credits mapping fresh / stale (`resetAt` past, `updatedAt` older than 24 h) / nolimit / unavailable; HTTP errors give `null`; (if the 2.3 check requires it) idle re-fetch fallback.
- `status.service.spec.ts`: `models()` and `usage()` mapping and error fallback.
- `status-panel.component.spec.ts`: `Usage - this month` block order between `.facts` and `.mcp`; fresh text `381 / 10,000`, `9,619 left - resets Dec 1`, bar with `role=progressbar` and correct `aria-valuenow`; stale shows `not synced`, no bar, no `left`; hidden when `available:false` or provider not kiro; no "Tokens" monthly row, no currency sign, no `$`/euro; This session `64k tok`; `Context 42% used` / `Context -`; orange text class at >= 80%; SVY-21852 content guard and fold tests still pass; each `flushAll` also flushes the new `usage`/`model` requests.
- `composer.component.spec.ts`: ring absent when percent `null`; present with `aria-label` and title; title gains `start a new session or compact` at >= 80% and not at 79%; click emits `contextClick`; ring has no layout effect on the send button when absent.
- `app.component.spec.ts`: ring click calls `toggleStatus()` (opens the panel); ring percent updates when a new assistant message with larger tokens is pushed into the store mock, without a reload.

### 5.2 Plain JUnit (`com.servoy.eclipse.opencode.tests.plainjunit`, no OSGi)

- `OpencodeChatServletTest`: `isUsageRequest("/usage")` true; null, `""`, `/`, `/usage/x`, `/rest_api/usage`, `/Usage` false; `/usage` is not an API request and not a file request.
- `OpencodeChatServletGuardTest` (new nested `UsageEndpoint`, same dynamic-proxy fakes): `GET /usage` with a supplier answers 200, JSON content type, `Cache-Control: no-store`, body equals the supplier JSON; `HEAD` has no body; `POST` gives 405 with `Allow`; non-loopback Host gives 403; cross-site `Sec-Fetch-Site` gives 403; foreign `Origin` gives 403; a throwing supplier gives 200 `{"available":false}`; answered when `serverReadySupplier` is false and the port is `-1` (no gate, `waitForServer` never invoked); 6/7-arg constructors answer `{"available":false}`; `/rest_api/**` 409 and `/solution` behaviour unchanged.
- `KiroUsageSnapshotReaderTest` (fixtures in a `@TempDir`): healthy account mapped (`used`, `limit`, `pct`, `resetAt`, `updatedAt`); first healthy chosen when several; first unhealthy skipped; no healthy gives `available=false`; file absent, empty, malformed JSON, wrong shape (array root, accounts not an array), oversize file: `available=false`; missing limit/`limit 0` omitted; ISO `updatedAt` and epoch ms both parsed; stale rule: `resetAt < now` true, `updatedAt` older than 24 h true, fresh both false, missing `updatedAt` true; config dir null gives `available=false`; only `kiro-usage.json` is read (other files in the dir, e.g. an `auth.json` with a marker string, never appear in output).
- **Field whitelist negative test:** fixture containing `email`, `id`, `region`, `directory`, `sessions`, `pluginInstances`, `pluginVersion` with recognisable marker values: the output JSON parsed with Jackson has exactly the keys from `{available, used, limit, pct, resetAt, updatedAt, stale}` (a subset), and the raw string contains none of the marker values or the substrings `email`, `"id"`, `directory`, `region`.
- `ServicesProviderTest` still passes (one `ServletInstance` mapped to `/servoy_ai/*`).
- `eclipse-ide_getCompilationErrors`: zero errors in both Java projects.

## 5b. Acceptance criteria

Status panel
- [ ] A block `Usage - this month` appears between the facts and the MCP block when a fresh Kiro snapshot exists: `Credits`, `used / limit` (no currency sign), a progressbar and `N left - resets <Mon D>`.
- [ ] No monthly Tokens row, no token bar, no `$`/euro sign, no USD cost anywhere.
- [ ] Snapshot with `resetAt` in the past or `updatedAt` older than 24 h: shows `not synced`, no bar, no "left".
- [ ] Snapshot without limit: only `used`, no bar, no "left".
- [ ] Block hidden when `/servoy_ai/usage` returns `{"available":false}`, fails, or the provider is not Kiro.
- [ ] `This session` shows the sum of `input+output+reasoning` over the viewed session's assistant messages (cache reads excluded) formatted like `64k tok`; `-` when none.
- [ ] `Context N% used` uses the newest non-compaction assistant message (`input+cache.read+cache.write+output+reasoning`) over `limit.context` of the matching `providerID`+`id` model; `Context -` when the model or limit is unknown. Compaction and title/generate usage never feed it.
- [ ] Both session figures follow the viewed session (including a subagent child), and change when the session changes.

Composer ring
- [ ] Ring next to the send button when percent is known; absent when unknown; no layout shift otherwise.
- [ ] Click toggles the status panel (`toggleStatus()`); keyboard operable; has an accessible name with the percentage.
- [ ] `>= 80%`: orange ring (`--svy-accent`), orange text only via `--svy-acc-fg`, tooltip `Context N% used - start a new session or compact`; below 80% neutral colour and tooltip `Context N% used`.
- [ ] Ring, panel session tokens and panel context percentage always agree (single `UsageStore`).
- [ ] After each answer the numbers update without a reload (verified on a real turn; fallback re-fetch on idle implemented only if streamed messages drop `tokens`).

BFF
- [ ] `GET /servoy_ai/usage` answers a whitelisted JSON (`available, used, limit, pct, resetAt, updatedAt, stale`), `no-store`, never `email`/`id`/`region`/`directory`/paths/tokens.
- [ ] Only `GET`/`HEAD` (405 otherwise), loopback Host and same-origin checks as `/file` (403 otherwise).
- [ ] Absent/unreadable/malformed/oversize file gives 200 `{"available":false}`, never an error status; reads only `kiro-usage.json` in `OpenCodeUtil.getServoyOpencodeConfigDir()`; first healthy account used.
- [ ] No SQLite dependency, no new bundle imports; skilltest bundle untouched.

Quality
- [ ] All new Vitest and JUnit tests above exist and pass; full `ng test --no-watch` and `ng build` pass; zero compile errors in both Java projects.
- [ ] `expect(() => fixture.detectChanges()).not.toThrow()` after setting signals in the new specs (NG0600).
- [ ] No test sessions with real model calls left in the workspace after the live verification.

## 6. Out of scope

- A monthly token total or limit, token bar, currency spend, USD cost.
- Summing credits across Kiro accounts; per-session credit estimates (`sessions[].estCredits`); reading `kiro.db`/SQLite.
- Changes to the opencode proxy, readiness gate, directory injection, `Activator`, `OpenCodeView`, `McpConfigWriter`.
- A context ring in the subagent footer view; automatic compaction or "new session" actions; changing opencode's compaction thresholds.
- Editing `AGENTS.md`, `.opencode/sdd/project-context.md`, `opencode.json` in this phase.
- Pushing (nothing is pushed).

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Do live-streamed assistant messages keep `info.tokens` and `info.model` after the `ChatStore` merge? Decides whether the idle re-fetch fallback is needed. | Developer | Open, verify on a real turn, then clean up the test session |
| Exact shape of a `compaction` message in the fetched list (`type` value) for `isCompaction`. | Developer | Open |
| Does `/event` deliver `session.usage.updated` in 2.4.1? (Not required; messages drive the numbers.) | Developer | Open, informational |
| `updatedAt` format in `kiro-usage.json` is an ISO-like string on the dev machine, epoch ms elsewhere; reader accepts both. | Developer | Assumed |
| "Not Kiro" detection for a stale file left from past Kiro use (viewed session's `providerID`, else show per file). | Developer | Assumed as written in 3.5 |
| Reader class placement (`com.servoy.eclipse.opencode` vs `.tomcat`). | Developer | Implementer chooses |
