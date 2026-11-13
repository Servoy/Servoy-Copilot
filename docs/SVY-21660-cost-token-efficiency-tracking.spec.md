# Spec: SVY-21660 — [skilltest] Cost / token / efficiency tracking

> Source triage: `docs/SVY-21660-triage.md` (verdict PROCEED, Approach 1).
> Follow-up of SVY-21366; sibling of the trend/history ticket (shared storage).
> This spec writes the **approved approach only**, with the Kiro provider findings
> folded in (tool-call count is primary; tokens are best-effort; Kiro
> requests/estCredits is optional/best-effort). It does **not** re-open scope the
> triage/human already settled.

---

> ## Scope expansion (SVY-21660 — accurate Kiro cost + per-step token attribution)
>
> The first iteration (tool-call count + best-effort tokens + optional Kiro
> `estCredits` + WARN-only budget) is **implemented, reviewed and tested**. This
> revision **adds** scope the human has now approved; it does **not** rewrite or
> drop anything above. Every existing invariant is preserved: the WARN-only budget
> never touches PASS/FAIL or the JUnit `failures`/`errors`; `last-results.json`
> stays additive and backward-compatible; a missing measurement reads **"unknown"**,
> never a misleading `0`.
>
> The driving goal of the expansion: **Servoy AI on Kiro is expensive, so the
> harness must show the accurate cost per skill-test scenario AND which tool/skill
> step is the most inefficient.** Four additions, labelled **A–D** throughout:
>
> - **A. SQLite dependency** — add the Xerial SQLite JDBC driver
>   (`org.xerial.sqlitejdbc`) to the target platform and the skilltest bundle
>   manifest so **`kiro.db`** can be read. The Eclipse JVM has **no** SQLite driver
>   today (`org.sqlite.JDBC` resolves to `TYPE_NOT_RESOLVED`). Reuse an existing
>   target-platform SQLite bundle if one is present (none is, confirmed below).
>   **Correction (live data):** the driver is used **only** by `KiroCreditReader`
>   for `kiro.db`; `opencode.db` is not read at all (see C).
> - **B. Accurate credits per scenario** — read the account's available Kiro
>   credits (used / limit) from **`kiro-usage.json`** (NOT `kiro.db`, which has no
>   account table — confirmed against live data) at the **start** and **end** of
>   each scenario and report **Δused** = the credits that scenario consumed. The
>   per-session `est_credits` fallback stays on `kiro.db` `session_usage`. Because
>   the JSON is synced only periodically, the end reading does a **bounded wait**
>   for the plugin to sync (the snapshot freshness stamp advancing) before being
>   read. Fix the config-dir probe to try `~/.servoy/opencode/` **first** (the real
>   Servoy AI opencode config dir).
> - **C. Per-request token attribution** — capture the per-request token figures
>   **from the HTTP export the harness already fetches**, in order, with the tool
>   calls/skill in each request, so the report can rank and highlight the **most
>   inefficient step** (the request re-sending a huge uncached context). Derive the
>   cache-read ratio and the fixed base input. **Correction (live data):** each
>   assistant message carries its own `tokens` object at the **MESSAGE top level**
>   (sibling of `id`/`type`/`agent`/`model`/`content`/`cost`), not under `info` —
>   `fromExport` reads that first and falls back to `info.tokens` for the older
>   shape. **`opencode.db` is NOT used:** the harness's sessions are never written
>   to its `session_v2`/`session_message` tables, so the DB reader produced nothing
>   and was removed, together with the deferred session delete it required. The
>   session is deleted **per trial** again.
> - **D. Surfacing** — log start/end credits + Δ per scenario; add the accurate
>   credits-used and a per-request token table to the view detail, the HTML report
>   and the JUnit `<system-out>`. Tool-call count stays primary; the WARN-only
>   budget is unchanged; everything degrades to **"unknown"** when a source/driver
>   is absent.
>
> New design subsections: **§3.8–§3.12**. New implementation steps appended to **§4**.
> New acceptance ticks in **§5**, kept exclusions reaffirmed in **§6**, new resolved
> decisions appended to **§7**.

---

## 1. Goal

Add a cost / efficiency dimension to the skill-test harness so a run that stays
correct but becomes wasteful is **visible**, without ever changing the PASS/FAIL
verdict. Today a baseline is graded only on correctness (outcome checks / JSUnit /
acceptance judge). Two runs that both PASS can differ several-fold in work done, and
that regression is invisible.

Concretely, each run must record and surface:

1. **Tool-call count** — the *primary*, always-reliable, provider-independent metric.
   Already in hand as `actual.getToolCalls().size()` (includes nested Code-Mode
   `execute` calls via `collectNestedCodeModeCalls`, SVY-21711).
2. **Token usage** — *best-effort*, parsed tolerantly from the opencode export's
   `info.tokens`. **Absent → "unknown", never a misleading `0`.** (Expected to be
   frequently absent under this project's provider, AWS Kiro.)
3. **Kiro requests + estimated credits** — *optional, best-effort*, read from the
   `@servoy/opencode-kiro-auth` plugin's `kiro-usage.json` by session id, clearly
   labelled an **estimate**. Degrades silently to "unknown" when unavailable. The
   core feature must not depend on it.

Plus an **optional, WARN-only** per-baseline soft budget that flags (never fails) a
regression beyond a configurable threshold. Default budgeted metric: **tool-call
count** (the reliable one). Tokens are budgeted only when token data is actually
present.

---

## 2. Background

### 2.1 Why tool-call count is primary (AWS Kiro provider reality)

This project runs against AWS Kiro. The human-approved investigation established:

- **Kiro bills per REQUEST (invocation), not per token.** Its per-token counts are
  unreliable and frequently absent/zero. There is **no** exact per-token or
  per-dollar cost for a run under Kiro. A spec that treated tokens as *the*
  authoritative efficiency number would be wrong for this provider.
- **Tool-call count is the one always-reliable, provider-independent efficiency
  metric**, and the harness already computes it. So it is the **primary** metric and
  the **default budgeted** metric.
- **Token usage** is captured best-effort; a missing figure must read "unknown", not
  `0`, so a missing measurement is never mistaken for a free run.
- **Kiro requests/estCredits** is an optional proxy the harness *can* surface because
  it runs on the same machine as the auth plugin, but it is an apportioned **estimate**
  (on a shared account, other users' usage mixes in) and must be labelled as such.

### 2.2 Where the data already lives (confirmed in code)

All skill-test classes are in **project and package
`com.servoy.eclipse.opencode.skilltest`** (not `com.servoy.eclipse.developer.mcp` —
the harness was moved; use the real paths below). Tests are in
`com.servoy.eclipse.opencode.skilltest.tests`.

- **Tool-call count** — `SkillTestRunner.runBaseline(...)` already holds
  `lastActualCalls = actual.getToolCalls()` per trial (`SkillTestRunner.java`
  ~line 266, inside the `maxAttempts` loop). `size()` is the count; no new parsing.
- **Token usage** — `SessionTranscript.fromExport(JsonNode)` (lines 124–213) already
  reads `info.directory`/`info.agent`/`info.model` from the session `info` and from
  each message's `info`, with null-tolerant helpers (`textValue`, `longValue`,
  `firstNode`). It does **not** yet read `info.tokens`. The webui side
  (`session-export.ts` / `session-export.spec.ts`) already treats
  `info.tokens = { input, output, reasoning, cache:{read,write} }` as first-class, so
  the field exists in the payload the Java harness reads — when the provider supplies it.
- **Kiro usage file** — `@servoy/opencode-kiro-auth` writes `kiro-usage.json` into
  opencode's config dir, whose location is **OS-dependent**. The panel itself probes
  `~/.config/opencode/kiro-usage.json` **and** `~/AppData/Roaming/opencode/kiro-usage.json`
  in order and uses the first that exists. The harness must do the same, because it runs on
  **both** developer Windows machines **and Jenkins (Linux/macOS)** — a Windows-only path
  would silently yield "unknown" on CI. Shape modelled on the panel's `UsageFile`/`SessionUsage`:
  `{ version, accounts[], sessions: [{ sessionId, requests, estCredits, ... }] }`.

### 2.4 Why a Kiro run is expensive, and where the accurate cost lives (scope expansion)

The iteration-1 `estCredits` figure is a per-session **estimate** apportioned from a
shared account. The human-approved expansion asks for the **accurate per-run cost**
and the **most inefficient step**, both of which the harness can get without reading
AWS or any bearer token:

- **Accurate cost is a credit bracket, not a per-session estimate (B).** The Kiro
  plugin records the account's live `used`/`limit`. Reading the account's `used`
  immediately **before** a scenario's first prompt and again immediately **after**
  completion (before `deleteSession`) gives **Δused = the credits that scenario
  actually consumed** — the exact per-run cost. The per-session `est_credits` row
  remains the **fallback** when the account delta is not reliable. On a **shared
  account**, concurrent use during the bracket inflates Δ; skill-test runs are
  sequential on a dedicated account, so this is usually fine, but the caveat is
  labelled everywhere the figure appears.

- **The config dir is `~/.servoy/opencode/`, not the panel's defaults (B).** The
  Servoy AI opencode config dir is `~/.servoy/opencode/` (where `ProviderConfigWriter`
  / `McpConfigWriter` write `opencode.json`), **not** `~/.config/opencode` or
  `%APPDATA%\opencode`. The current `KiroUsageReader` probes only the latter two, so
  it reads "unknown" for every real run. The probe order must try
  `~/.servoy/opencode/` **first**, then keep the existing cross-platform fallbacks.

- **`kiro.db` is the live source; `kiro-usage.json` is the quick path (A/B).** The
  plugin writes a SQLite DB `kiro.db` (live, WAL) alongside the JSON snapshot. Verified
  live schema: `session_usage(session_id, account_id, title, directory, requests
  INTEGER, est_credits REAL, first_used, last_used)` (read per `session_id`, summing
  across `account_id` rows) plus `plugin_instances`. Verified `kiro-usage.json` shape
  (plugin v2.9.0): `{ version, writtenAt, pluginVersion, pluginInstances[],
  accounts:[{ id, email, region, used, limit, pct, resetAt, isHealthy, updatedAt }],
  sessions:[{ sessionId, directory, requests, estCredits, firstUsed, lastUsed,
  accounts[] }], burn:[{ accountId, windowDays, credits, earliestUsed }] }`. Reading
  the DB needs a JDBC driver the Eclipse JVM does not have (A).

- **The cost cause is requests × large uncached input (C).** opencode meters tokens
  **per request**, not per tool call. The observed cause of the expense is **no
  caching**: `cache read 0` with a large input (~474k tokens) re-sent on **every**
  turn, so the per-request **input** is the fixed cost multiplied by the request count.
  The most inefficient step is therefore the request(s) with the largest input; the
  derived **cache-read ratio** (`cache.read / input`, flagged when ~0) and the fixed
  **base input** make the cause visible. Because a request may contain several tool
  calls that share that request's token figure, per-tool cost is an
  **attribution/estimate**, not an exact split — stated honestly in every surface.

- **Per-request tokens come from the HTTP export — the ONLY source (C).** Measured
  against a real export dump: every assistant message carries its own `tokens` object
  at the **MESSAGE top level**, a sibling of `id`/`type`/`agent`/`model`/`content`/
  `cost`, with **no** `info` wrapper (the older shape nests it under `info.tokens`,
  which stays supported as a fallback). `SessionTranscript.fromExport` reads
  `message.tokens` first, falls back to `info.tokens`, sums them into one `TokenUsage`
  and builds the ordered **per-request list** from the same per-message figures,
  each entry carrying its own exact `callCount` (`calls.size() - callsBefore`,
  nested Code-Mode calls included).

  `opencode.db` is **NOT used** and no DB reader exists: the harness's sessions are
  never written to its `session_v2`/`session_message` tables at all (measured:
  `inSessionV2=false`, 0 rows), so that path yielded nothing and has been removed.
  Consequently the session is deleted **per trial**, in the trial's own `finally`, as
  it always was — the "deferred delete" that existed only to keep those (non-existent)
  rows readable is gone.

- **The SQLite dependency stays, for `kiro.db` only (A).** `eclipse-ide_explainTypeResolution`
  for `org.sqlite.JDBC` and `org.sqlite.SQLiteConfig` both returned **TYPE_NOT_RESOLVED**
  before this work, and `searchTypes org.sqlite.*` found **0** types in the
  workspace/target — the Servoy target platform provides no SQLite JDBC bundle, so the
  Xerial driver was added. It is still required: `KiroCreditReader` reads the
  per-session `est_credits` from **`kiro.db`** through it. Only the `opencode.db`
  reader is gone, not the driver.

### 2.3 Extension points (all confirmed to exist)

| Point | File / method | How it is used today |
|---|---|---|
| Transcript parse | `SessionTranscript.fromExport` (124–213) | reads `info`; add a tolerant `info.tokens` read |
| Per-trial capture | `SkillTestRunner.runBaseline(...)` loop (160–412) | holds `actual` + `lastActualCalls` per attempt |
| Result carrier | `SkillTestResult` builders `withTranscript`/`withOutcomeNodes`/`withJsUnit`/`withTrials`/`withAgent` + the `Trial` record (32–49) | add `withMetrics(...)` + per-trial metrics |
| Persistence | `SkillTestReporter.writeLastResults` / `loadLastResults` / `readTrials` | additive JSON fields, `node.has(...)`-tolerant reads |
| View columns | `SkillTestView.createColumn(...)` / `createPartControl` (94–130) | add "Tool calls" / "Tokens" columns |
| Detail HTML | `SkillTestHtml.render(...)` (57–156) | add a "Metrics" section |
| CI report | `JUnitXmlReporter.writeReport(...)` — already emits `<system-out>` on PASS (46–55) | emit metrics in `<system-out>` for every status |
| Sidecar parse | `BaselineLoader.parseJsUnitVerify` / `parseAcceptanceVerify` / `load` + nested `record` pattern (`Baseline.JsUnitVerify`) | add a `budget` block + `Baseline.Budget` record |

---

## 3. Design

### 3.1 The `RunMetrics` value type

Introduce an immutable `RunMetrics` carrier as a **top-level `RunMetrics.java` record**
in the `skilltest` package (decision 2 — so `SessionTranscript` can build it without
depending on `SkillTestResult`). It holds:

```
record RunMetrics(
    int toolCalls,          // always present (>= 0); primary metric
    TokenUsage tokens,      // nullable -> "unknown"
    KiroUsage kiro          // nullable -> "unknown"
)
record TokenUsage(long input, long output, long reasoning, long cacheRead, long cacheWrite) {
    long total() { return input + output + reasoning; }  // decision 1: cache read/write captured + shown, but EXCLUDED from total
}
record KiroUsage(int requests, double estCredits)        // estimate; labelled in all surfaces
```

- **`toolCalls`** is never null — it is the authoritative number.
- **`tokens == null`** means token data was absent in the export → every token
  surface shows **"unknown"**, never `0`.
- **`kiro == null`** means the usage file was absent / the session id was not found →
  Kiro surfaces show **"unknown"**.
- Provide a formatting helper (e.g. `String tokensLabel()` / `kiroLabel()`) so the
  view, HTML and JUnit surfaces render the "unknown"/estimate wording identically.

### 3.2 Token parsing in `SessionTranscript.fromExport` (best-effort, version-tolerant)

Add a `TokenUsage tokens` field to `SessionTranscript` (nullable) with a getter, and
parse it inside the existing walk:

- Read **session-level** `info.tokens` first (`exportRoot.get("info").get("tokens")`).
- If absent or zero across the board, **sum per-message** `messages[].info.tokens`
  (opencode v2 carries tokens per assistant message; the session `info` may carry
  none — mirror exactly how agent/model are already resolved session-first then
  per-message).
- Sub-fields read best-effort and null-tolerantly: `input`, `output`, `reasoning`,
  and `cache.read` / `cache.write` (nested object). Use the existing `longValue` /
  `firstNode` helpers; add a `longValue(node, "a", "b")` nested variant if needed.
- **If nothing resolvable is found, leave `tokens == null`** (→ "unknown"). Do not
  fabricate zeros. A present-but-all-zero payload under Kiro is treated as absent
  (→ null) so a genuine "no token data" does not read as `0 tokens`.
- opencode's export shape varies by version, so the read must never throw on a
  missing/renamed field — tolerate and degrade.

`SkillTestRunner` then builds `RunMetrics` per trial from
`actual.getToolCalls().size()` + `actual.getTokens()`, and attaches the Kiro figure
(§3.4) keyed by the trial's `sessionId`.

### 3.3 Carrying metrics on `SkillTestResult` and `Trial`

- Add `private RunMetrics metrics;` + `public SkillTestResult withMetrics(RunMetrics)`
  (returns `this`, same chaining pattern as `withAgent`) + `getMetrics()`.
- Add a `RunMetrics metrics` component to the `Trial` record (last component, so
  existing positional construction sites are updated in one place — the single
  `new Trial(...)` in `SkillTestRunner` and the one in `SkillTestReporter.readTrials`).
  Per-trial metrics let the detail view show the distribution of cost across trials.
- **Aggregation policy (documented, not silent averaging):** the result's top-level
  `metrics` are the **representative/kept trial's** metrics — the *same* trial whose
  detail (`keptOutcomeNodes`/`keptDiff`/`keptJsUnit`) is already kept today (first
  failing trial, else last passing). This keeps the headline figure consistent with
  the detail the report already shows. State this explicitly in the Javadoc of
  `withMetrics` and in the view column tooltip. (Per-trial figures remain available in
  the `Trial` records for anyone who wants the spread.)

### 3.4 Kiro usage read (optional, best-effort, labelled estimate)

New helper `KiroUsageReader` (top-level class in `skilltest`):

- Resolves the file by probing a **cross-platform** ordered list of candidate paths — the
  same set the panel uses — and using the first that exists. **Do not hardcode a Windows-only
  path**; the harness also runs on **Jenkins (Linux/macOS)**, where a Windows path yields
  nothing. Candidate order (first existing wins):
  1. `$XDG_CONFIG_HOME/opencode/kiro-usage.json` when `XDG_CONFIG_HOME` is set (Linux/macOS
     config override),
  2. `~/.config/opencode/kiro-usage.json` (Linux/macOS default, and the panel's first probe),
  3. `%APPDATA%\opencode\kiro-usage.json` (Windows; from the `APPDATA` env var, else
     `~/AppData/Roaming/opencode/kiro-usage.json`).

  Resolve `~` from the `user.home` system property and read env vars via `System.getenv(...)`
  (null-tolerant). This mirrors the panel's `USAGE_FILES` probe-both-defaults behaviour so the
  figure appears identically on a developer box and on CI when the file is present.
- Parses tolerantly (Jackson) into the panel's shape
  `{ sessions: [{ sessionId, requests, estCredits }] }`; returns
  `Optional<KiroUsage>` for a given `sessionId`.
- **Never reads a token/bearer and never reaches AWS** — it only reads this local
  snapshot file, exactly as the panel does. File absent / unreadable / session not
  found → `Optional.empty()` (→ "unknown"). Must never throw into the run.
- Called in `SkillTestRunner.runBaseline(...)` right after a trial's export, with that
  trial's `sessionId`, and folded into the trial's `RunMetrics`. Because the harness
  deletes the session at trial end (the existing `finally client.deleteSession`), read
  the usage file **before** deletion (the file keyed by session id may be purged with
  the session).

### 3.5 Soft budget — WARN only, never FAIL

New nested record `Baseline.Budget` (mirrors the `JsUnitVerify`/`AcceptanceVerify`
record pattern), parsed by a new `BaselineLoader.parseBudget(JsonNode)` from a
`budget` block in the sidecar `baseline.json`:

```jsonc
"budget": {
  "metric": "toolCalls",        // default; "tokens" allowed but only honoured when token data is present
  "max": 25,                    // absolute ceiling; OR
  "baselineValue": 18,          // a recorded reference, with
  "tolerancePct": 25            // flag when current exceeds baselineValue * (1 + tolerancePct/100)
}
```

- **Entirely optional.** No `budget` block → no budget evaluation, nothing changes.
- Evaluated **after** the verdict is decided, by a small `BudgetEvaluator` that
  compares the result's representative `RunMetrics` against the budget and returns an
  optional **warning string** (e.g. `"tool-calls 31 exceeds budget 25 (+24%)"`).
- **The warning NEVER touches:** the PASS/FAIL verdict, `SkillTestResult.Status`, the
  JUnit `failures`/`errors` counts, or the `<testcase>` body. It is surfaced only as:
  - a `<system-out>` line in `JUnitXmlReporter` (on any status), and
  - a view annotation (e.g. a "⚠ over budget" marker in the metrics column / detail).
- Default budgeted metric is **`toolCalls`**. If `metric: "tokens"` is configured but
  the run produced no token data (`metrics.tokens == null`), the budget is **skipped**
  (not failed, not warned) with a note that tokens were unavailable — never warn on
  absent data.

### 3.6 Persistence — additive, backward-compatible

In `SkillTestReporter.writeLastResults`, write a `metrics` object on each result node
(and on each `trials[]` node), only when present:

```jsonc
"metrics": {
  "toolCalls": 18,
  "tokens": { "input": 1234, "output": 567, "reasoning": 0, "cacheRead": 0, "cacheWrite": 0 },  // omitted entirely when unknown
  "kiro": { "requests": 3, "estCredits": 0.42 }                                                 // omitted entirely when unknown
}
```

- `toolCalls` always written when metrics exist; `tokens`/`kiro` objects **omitted
  entirely** when unknown (so a reader distinguishes "unknown" from a real zero).
- In `loadLastResults` / `readTrials`, read `metrics` with `node.has("metrics")`
  guards and rebuild `RunMetrics` (absent sub-objects → null → "unknown"). Older
  `last-results.json` files simply lack `metrics` → `getMetrics()` is null → surfaces
  show "unknown". **No migration, no version bump.**

### 3.7 Surfacing

- **`SkillTestView`**: add exactly two columns via `createColumn(...)` in
  `createPartControl` — **"Tool calls"** (primary) and **"Tokens"** (shows "unknown"
  when absent). **No separate "Kiro" column** (decision 3) — the Kiro figure lives in the
  HTML detail and `<system-out>` only. An over-budget result shows a "⚠" affordance.
  Columns read from `lastResults.get(id).getMetrics()`.
- **`SkillTestHtml.render`**: add a **"Metrics"** section (before "Tool calls
  (informational)") listing tool-call count (primary), token breakdown or "unknown",
  and Kiro requests/estCredits or "unknown (estimate)". For multi-trial runs, show the
  per-trial metrics alongside the existing per-trial sections. Any budget warning
  renders as a non-fatal "⚠ over budget" note, visually distinct from FAIL.
- **`JUnitXmlReporter.writeReport`**: emit a `<system-out>` block for **every** status
  (extend the current PASS-only JSUnit emission) containing a one-line metrics summary
  and, when present, the budget warning line. The `<testcase>`, `failures` and
  `errors` stay exactly as today — metrics and warnings are informational only.

---

## 3. Design — scope expansion (A–D)

The subsections below **extend** the design above. Nothing in §3.1–§3.7 is removed;
the new `RunMetrics` fields are additive (null/empty ⇒ "unknown"), the runner bracket
runs around the existing `maxAttempts` loop, and the new reader degrades to "unknown"
exactly like `KiroUsageReader`.

### 3.8 SQLite dependency (A)

- **Driver:** `org.xerial.sqlitejdbc` (the standard Xerial SQLite JDBC driver, which
  exports the `org.sqlite` package and registers `org.sqlite.JDBC`). Use the latest
  stable `org.xerial:sqlite-jdbc` release available on Maven Central.
- **Prefer an existing bundle if the target already has one.** Confirmed it does
  **not** (§2.4: `org.sqlite.*` resolves to nothing in the workspace/target), so it
  must be added. Should a future Servoy target start providing an SQLite JDBC bundle,
  reuse that rather than duplicate.
- **Target platform** — add a Maven-location `<dependency>` for
  `org.xerial:sqlite-jdbc:<version>` to
  `launch_target_aiplugin/com.servoy.eclipse.servoypilot..target` (inside the existing
  `<location type="Maven">` block, alongside the other jars; the block already uses
  `missingManifest="generate"`, so the jar is OSGi-wrapped). Bump the target
  `sequenceNumber`. This is the only place the driver must be declared for the
  build/Jenkins to resolve it.
- **skilltest bundle manifest** — add `org.sqlite;version="[<lo>,<hi>)"` to the
  `Import-Package` list in
  `com.servoy.eclipse.opencode.skilltest/META-INF/MANIFEST.MF` (consistent with how
  Jackson/MCP are imported there). `Import-Package` is preferred over `Require-Bundle`
  because the generated-manifest bundle's symbolic name is not guaranteed stable,
  whereas the `org.sqlite` package is.
- **Driver registration:** read through a `jdbc:sqlite:` URL; if the JDBC auto
  service-loader does not pick the driver up under OSGi, `Class.forName("org.sqlite.JDBC")`
  explicitly (guarded, non-fatal → "unknown" on failure). Must resolve on Jenkins too.

### 3.9 `KiroCreditReader` — account credits from `kiro-usage.json`, per-session from `kiro.db` (A/B)

New helper `KiroCreditReader` (top-level class in `skilltest`), **separate from**
`KiroUsageReader` (which keeps reading the per-session `estCredits` estimate for §3.4):

- **Config-dir source — the single source of truth, NOT a hardcoded `~/.servoy`
  literal (B):** resolve the opencode config dir by calling
  **`com.servoy.eclipse.opencode.OpenCodeUtil.getServoyOpencodeConfigDir()`** — the
  exact method the opencode bundle itself uses to decide where to write
  `kiro-usage.json` / `kiro.db`. The skill-test harness talks to the embedded opencode
  that *this same bundle* launched with *that same* config dir, so the reader and the
  server agree **by construction** in the same JVM: whatever `user.home` (or a future
  override) resolves to — including on Jenkins — the writer and the reader follow the
  one method together. **Do not** duplicate a `~/.servoy/opencode` string; the whole
  point is that the path lives in one place.
  - The skilltest bundle already depends on `com.servoy.eclipse.opencode`, so the method
    is callable directly. (If a classpath reason made that awkward, the fallback below
    still covers it — but the method is the intended source.)
  - **Fallback probe (secondary safety net only), first existing wins:** if the method is
    unavailable (e.g. a plainjunit test with no OSGi), probe
    1. `$SERVOY_OPENCODE_CONFIG/` if that env var is set,
    2. `<user.home>/.servoy/opencode/` (the method's own current default),
    3. `$XDG_CONFIG_HOME/opencode/`, `~/.config/opencode/`,
    4. `%APPDATA%\opencode\` (env `APPDATA`, else `~/AppData/Roaming/opencode/`).

    Resolve `~` from `user.home`; read env via `System.getenv` (null-tolerant). The
    injectable candidate-dir seam (below) is how a `plainjunit` test supplies a temp dir
    without the OSGi method.
  - **Apply the same source-of-truth resolution to `KiroUsageReader`** so the iteration-1
    `estCredits` figure also stops reading "unknown" for real runs.
- **The account figure lives in `kiro-usage.json`, NOT `kiro.db` (live-data
  correction).** Confirmed against live data: `kiro.db` has **no account table** — only
  `session_usage(session_id, account_id, requests, est_credits, ...)`. The account's
  available-credit figure (`used` / `limit` / `pct` / `updatedAt`, plus top-level
  `writtenAt`) exists **only** in `kiro-usage.json` (`accounts[]`). So the account
  bracket reads the JSON, and the per-session `est_credits` fallback reads `kiro.db`
  `session_usage`. (This **revises** the earlier "credits from `kiro.db` only" decision;
  see decisions 9 & 10.) The iteration-1 `KiroUsageReader` keeps reading the JSON
  per-session `estCredits` for its own §3.4 purpose — separate and unchanged.
- **`accountUsed()` — the bracket reading:** returns the account's currently-`used`
  credits as `OptionalDouble`, summed over `kiro-usage.json` `accounts[].used`; else
  `OptionalDouble.empty()` → "unknown". `accountLimit()` likewise sums
  `accounts[].limit`.
- **`snapshotStamp()` — the freshness reading:** returns the snapshot's freshness as an
  epoch-millis `OptionalLong` — the largest `accounts[].updatedAt`, else the top-level
  `writtenAt` (each parsed from an epoch number or an ISO-8601 string). The runner reads
  it before a scenario and polls it after completion, waiting for the plugin to sync
  (the stamp advancing) before reading the end of the bracket, so Δ does not read ~0 off
  a pre-run snapshot.
- **`creditsForSession(sessionId)` — the per-session fallback figure:** returns the
  per-session consumed credits as `OptionalDouble`, from `kiro.db` `session_usage` by
  `session_id` (SQLite, read-only `immutable=1`), summing `est_credits`; else
  `OptionalDouble.empty()` → "unknown". This part works today and stays on the DB.
- **Access is read-only and never throws into the run.** The JSON is parsed tolerantly
  (absent / unreadable / malformed → "unknown"); the `kiro.db` `session_usage` read is
  opened `immutable=1` so the plugin's WAL writer is never disturbed; every access is
  wrapped so an absent driver / absent file / locked DB / malformed row/JSON degrades to
  empty (→ "unknown"). **No network, no token/bearer read.**
- **Testable seam:** keep the config-dir resolution behind an injectable candidate-dir
  list (as `KiroUsageReader` already does) so `KiroCreditReaderTest` can point it at a
  temp dir with a fixture `kiro-usage.json` / `kiro.db` and stay `plainjunit`.

### 3.10 Start/end credit bracket in the runner (B)

In `SkillTestRunner.runBaseline(...)`, around the existing `maxAttempts` loop:

- Build one `KiroCreditReader` for the baseline (next to the existing
  `KiroUsageReader kiroReader = new KiroUsageReader();`).
- **Start:** immediately **before** the scenario's first prompt, read
  `creditsStart = reader.accountUsed()` and `beforeStamp = reader.snapshotStamp()`
  (and, for surfacing, `creditsLimit = reader.accountLimit()`). Taken once per scenario
  (before trial 1, not per trial) so it brackets the whole scenario's cost.
- **End:** after completion and **before** the `finally` `deleteSession` of the final
  trial, **bounded-wait** for the plugin to sync `kiro-usage.json`: poll
  `reader.snapshotStamp()` until it advances past `beforeStamp` (or `accountUsed()`
  moves off the start value), up to ~10 s using the existing poll interval and
  respecting cancellation (never a raw unbounded sleep). Then read
  `creditsEnd = reader.accountUsed()`. If the snapshot never advances within the budget,
  leave `creditsEnd` empty so the Δ reads **"sync pending / unknown"**.
- **Δused** = `creditsEnd - creditsStart` when both are present and `end >= start`;
  otherwise fall back to `reader.creditsForSession(keptSessionId)` for the kept trial's
  session; otherwise `null` → "unknown". Compute Δ once and attach it — together with
  the **before / after / limit** bracket readings — to the scenario's headline
  `RunMetrics` (§3.11), so the surfaces show before + after + Δ, not only Δ. Never
  throw; a read failure ⇒ "unknown".
- **Log** the start, end and Δ per scenario (via the existing `logger.log(...)` sink),
  with the estimate / shared-account caveat.
- Timing is exactly as approved — **before the first prompt / after completion before
  `deleteSession`** — and the existing per-trial Kiro `estCredits` read (§3.4) stays
  where it is as the fallback source.

### 3.11 Per-request token list + derived signals on `RunMetrics` / `SessionTranscript` (C)

- **Per-request tokens come from the HTTP export — the single source.** The export the
  harness already fetches carries each assistant message's own token figures, so no
  second store is consulted. **Measured against a real export dump:** the figures sit at
  the **MESSAGE top level**, a sibling of `id`/`type`/`agent`/`model`/`content`/`cost`:
  ```json
  { "id": "msg_1", "type": "assistant", "agent": "Orchestrator", "content": [],
    "cost": 0,
    "tokens": { "input": 49755, "output": 61, "reasoning": 0,
                "cache": { "read": 0, "write": 0 } } }
  ```
  with **no** `info` wrapper. Older exports nest the same object under `info.tokens`;
  `fromExport` reads the top level first and falls back to `info`, so both shapes are
  measured. Verified live: 7 requests, input 49741..68463, cache 0.

  - **`opencode.db` is NOT read, and there is no DB reader.** The earlier design made
    the DB the primary source, which required the skill-test session to outlive the
    read and therefore a *deferred* delete. Both are gone: the harness's sessions are
    **never written to `opencode.db` at all** — a JDBC probe of recent skill-test
    session ids returned `inSessionV2=false` with **0 rows** for every one, so the
    reader could never yield data, deferring the delete bought nothing, and the
    `OpencodeTokenReader` class has been **deleted**. The export was never the
    degraded option; it is the authoritative one.
  - **The session delete is per trial**, back in the trial's own
    `finally { if (sessionId != null) client.deleteSession(sessionId); }`, which also
    covers the cancellation/interrupt early return. The `abort(sessionId)` on an
    `IOException` stays (aborting is not deleting), and deletion failures remain
    non-fatal. No `createdSessionIds` collection and no post-run delete phase.
  - **`callCount` needs no zipping.** Each `RequestTokens` entry is built in the same
    walk that collects the request's tool calls, so it already carries its own exact
    `callCount` (`calls.size() - callsBefore`, nested Code-Mode calls included). The
    `zipCallCounts` helper that existed only to graft the transcript's call boundaries
    onto DB-sourced token rows is removed; the transcript list is passed straight
    through.
  - **The SQLite driver stays** — `KiroCreditReader` still reads the per-session
    `est_credits` from `kiro.db` through it (§3.9/§3.10). Its access is read-only and
    **never throws into the run**; no network, no token/bearer.
- **`SessionTranscript`:** keep the summed `TokenUsage tokens` (§3.2 is unchanged) and
  **add**, in the same `fromExport` walk, an ordered `List<RequestTokens> requestTokens`
  (with a getter) — one entry per assistant message/request, each carrying
  `input/output/reasoning/cacheRead/cacheWrite`, its `callCount` and the tool calls/skill
  in that request (incl. nested Code-Mode calls). The per-request entry is keyed on the
  presence of a **non-zero tokens object** (the reliable assistant-request marker),
  **not** on the fragile resolved `role` string — a real export's assistant message may
  carry no top-level `role`/`type` at all. The same resolved `tokens` node feeds both the
  session sum and the per-request entry, so the two can never disagree about where the
  figures live. The request's `agent` is read from the message top level, falling back to
  `info.agent`. Absent ⇒ empty list → "unknown" (never fabricated). Keep every existing
  constructor working; add a new one.
- **`RunMetrics`:** add **additive** fields (null/empty ⇒ "unknown"):
  - `Double creditsDelta` — the accurate Δused per scenario (§3.10), labelled an
    estimate; `null` ⇒ "unknown".
  - `Double creditsBefore` / `Double creditsAfter` / `Double creditsLimit` — the account
    `used` read before/after the scenario and the account `limit`, so `creditsLabel()`
    renders "before: X / limit, after: Y, Δ: Z" and not only the delta (live-data
    follow-up). Each `null` ⇒ "unknown" independently. Convenience constructors keep the
    pre-existing three- and five-arg call sites compiling.
  - `List<RequestTokens> requestTokens` — the per-request list, for ranking the most
    inefficient step. Introduce `record RequestTokens(int index, TokenUsage tokens,
    List<String> toolNames, String skill)` with derived helpers:
    - `double cacheReadRatio()` = `cacheRead / input` (0 when input is 0),
    - `boolean noCaching()` (ratio ≈ 0 with non-trivial input),
    - a helper to pick the **most inefficient** request (largest `input`) and the fixed
      **base input** (the minimum recurring input across requests — the context paid
      every turn).
  - A `requestTokens`-aware label helper on `RunMetrics` so the view/HTML/JUnit render
    the ranking and the "unknown" wording identically.
- **Honest caveat:** opencode meters **per request**, so when a request contains several
  tool calls they share that request's token figure — per-tool cost is an
  **attribution/estimate**, not an exact split. State this in the `RequestTokens`
  Javadoc and echo it in every surface that shows the table.

### 3.12 Surfacing the expansion (D)

Extend the three surfaces from §3.7 (keep their existing content):

- **`SkillTestView`** detail: add the accurate **credits-used (Δ)** with the
  estimate/shared-account caveat, and a **per-request token table** ranked so the most
  inefficient step is visible (request index, its tool calls/skill,
  input/output/cache, cache-ratio). The two existing columns ("Tool calls", "Tokens")
  stay; the credit Δ and per-request ranking live in the detail (as the Kiro figure
  already does — decision 3), not as new sidebar columns.
- **`SkillTestHtml.render`**: in the "Metrics" section, add the credits Δ (labelled
  estimate) and a per-request token table ranked by input, highlighting the top
  (most-inefficient) request and showing the cache-read ratio and base input, with the
  "no caching — full context re-sent every turn" note when the ratio is ~0.
- **`JUnitXmlReporter`**: in the per-status `<system-out>`, add the credits-used Δ line
  and a compact per-request token ranking (at least the top request's input + its
  tools/skill + cache ratio). `<testcase>`, `failures` and `errors` stay exactly as
  today — all of this is informational.
- Every one of these degrades to **"unknown"** when its source is absent (no driver,
  no `kiro.db`/JSON, no export tokens).

---

## 4. Implementation plan

Concrete, ordered task list for the coder. Use the Eclipse MCP tools (Java bundles).
All paths are under project `com.servoy.eclipse.opencode.skilltest`,
`src/com/servoy/eclipse/opencode/skilltest/`.

1. **`RunMetrics.java` (new)** — top-level records `RunMetrics(int toolCalls,
   TokenUsage tokens, KiroUsage kiro)`, `TokenUsage(long input, output, reasoning,
   cacheRead, cacheWrite)` with `total()`, `KiroUsage(int requests, double
   estCredits)`. Add `tokensLabel()` / `kiroLabel()` formatting helpers that render
   "unknown" / "(estimate)" consistently. Null `tokens`/`kiro` ⇒ "unknown".

2. **`SessionTranscript.java`** — add `private final TokenUsage tokens;` + getter;
   overload/extend the constructor used by `fromExport` to carry it. In `fromExport`,
   read session-level `info.tokens`, else sum `messages[].info.tokens`; parse
   `input`/`output`/`reasoning`/`cache.read`/`cache.write` tolerantly; **leave null
   when nothing resolvable (and when an all-zero payload is the only thing found)**.
   Add a nested `longValue` helper if needed. Keep every existing constructor
   signature working (add a new one; do not break the four existing ones).

3. **`KiroUsageReader.java` (new)** — `Optional<KiroUsage> readForSession(String
   sessionId)`. Resolve the usage file by probing the **cross-platform** candidate list
   (§3.4): `$XDG_CONFIG_HOME/opencode/kiro-usage.json`, `~/.config/opencode/kiro-usage.json`,
   then `%APPDATA%\opencode\kiro-usage.json` (env `APPDATA`, else `~/AppData/Roaming/...`),
   first existing wins — **must work on Jenkins (Linux/macOS), not just Windows**. Jackson-parse
   the `sessions[]` array; match `sessionId`; return `requests`/`estCredits`. Never throw;
   absent/unreadable/not-found ⇒ empty. No network, no token read. Keep the candidate-path
   resolution behind a testable seam (e.g. an injectable base-dir list or a package-visible
   method) so `KiroUsageReaderTest` can point it at a temp file and stay `plainjunit`.

4. **`SkillTestResult.java`** — add `private RunMetrics metrics;` +
   `withMetrics(RunMetrics)` (chaining, documents the kept-trial aggregation policy) +
   `getMetrics()`. Add a `RunMetrics metrics` component to the `Trial` record (append
   as the last component); update the record Javadoc.

5. **`SkillTestRunner.java`** — inside the `maxAttempts` loop: after `lastActualCalls =
   actual.getToolCalls();`, read the Kiro figure via `KiroUsageReader` for the trial's
   `sessionId` **before** the `finally` `deleteSession`, build the trial's
   `RunMetrics` (`size()` + `actual.getTokens()` + Kiro), and pass it to the
   `new Trial(...)` call (now with the extra component). On the final return paths
   (PASS and FAIL), attach the **kept/representative** trial's `RunMetrics` via
   `.withMetrics(...)`, consistent with the existing kept-detail selection.

6. **`Baseline.java`** — add nested `record Budget(String metric, Integer max, Integer
   baselineValue, Integer tolerancePct)` (metric defaults to `"toolCalls"`); add a
   `budget` field + accessor + `hasBudget()`, and a new all-args constructor overload
   (keep existing ones compiling).

7. **`BaselineLoader.java`** — add `parseBudget(JsonNode)` and call it from `load(...)`
   (alongside `parseJsUnitVerify`/`parseAcceptanceVerify`); absent `budget` block ⇒
   `null`/`hasBudget()==false`.

8. **`BudgetEvaluator.java` (new)** — `Optional<String> evaluate(Baseline.Budget,
   RunMetrics)` returning a human warning when over budget; skips (empty) when the
   budgeted metric's data is absent (e.g. `metric:"tokens"` but `tokens==null`). Pure,
   unit-testable, no side effects. **Returns a warning only — never a failure.**

9. **`SkillTestReporter.java`** — in `writeLastResults`, write the `metrics` object on
   each result node and each `trials[]` node (omit `tokens`/`kiro` when unknown; always
   write `toolCalls` when metrics exist). In `loadLastResults` and `readTrials`, read
   `metrics` with `has(...)` guards into `RunMetrics` (absent ⇒ null ⇒ "unknown").
   Backward-compatible; no version field change.

10. **`SkillTestView.java`** — add "Tool calls" and "Tokens" columns (no "Kiro" column —
    decision 3) in `createPartControl` via `createColumn(...)`, reading
    `lastResults.get(id).getMetrics()`; render "unknown" when absent and a "⚠" when a
    budget warning applies. (Budget warnings for a run come from `BudgetEvaluator`
    against the loaded baseline + result.)

11. **`SkillTestHtml.java`** — add a "Metrics" section in `render(...)` (tool calls,
    tokens-or-unknown, Kiro-or-unknown-estimate) and per-trial metrics in the
    multi-trial branch; render any budget warning as a non-fatal "⚠ over budget" note.

12. **`JUnitXmlReporter.java`** — in `writeReport`, emit a `<system-out>` metrics line
    for **every** status (extend the PASS-only JSUnit emission); include the budget
    warning line when present. Do **not** change `failures`/`errors`/`<testcase>`.

### Scope-expansion steps (A–D) — appended, ordered after the above

13a. **Target platform (A)** — in
    `launch_target_aiplugin/com.servoy.eclipse.servoypilot..target`, add inside the
    existing `<location type="Maven">` block a `<dependency>` for
    `org.xerial:sqlite-jdbc:<latest>` (`<type>jar</type>`; the block already sets
    `missingManifest="generate"`), and bump the `<target ... sequenceNumber="...">`.
    First verify the target still has no SQLite bundle (it does not). After editing,
    reload the target and confirm `org.sqlite.JDBC` now resolves
    (`eclipse-ide_explainTypeResolution`).

13b. **skilltest manifest (A)** — add
    `org.sqlite;version="[<lo>,<hi>)"` to the `Import-Package` list in
    `com.servoy.eclipse.opencode.skilltest/META-INF/MANIFEST.MF` (match the version
    range to the added driver; keep the list's existing formatting).

15. **`SessionTranscript.java` — per-request token list (C)** — keep the summed
    `TokenUsage tokens`; add `private final List<RunMetrics.RequestTokens> requestTokens;`
    + getter and a new constructor overload (do not break the existing five). In
    `fromExport`'s message walk, resolve each message's tokens node **once** —
    `message.get("tokens")` first, falling back to `info.get("tokens")` for the older
    shape — then feed that same node to both the session sum (`accumulateTokens`) and one
    `RequestTokens` entry per message with a non-zero payload, together with the tool
    calls collected for that message (including nested Code-Mode `execute` calls) and
    their count. The request's agent comes from the message top level, falling back to
    `info.agent`. Nothing resolvable ⇒ empty list.

16. **`RunMetrics.java` — expansion fields (B/C)** — add the record components
    `Double creditsDelta` and `List<RequestTokens> requestTokens` (append; update the
    `new RunMetrics(...)` sites in `SkillTestRunner` and `SkillTestReporter`). Add
    `record RequestTokens(int index, TokenUsage tokens, List<String> toolNames, String
    skill)` with `cacheReadRatio()`, `noCaching()`, and helpers for the most-inefficient
    request + base input. Add `creditsLabel()` and a per-request ranking label helper,
    all rendering "unknown"/"(estimate)" consistently. `null`/empty ⇒ "unknown".

17. **`KiroCreditReader.java` (new, A/B)** — config-dir probe
    `~/.servoy/opencode/` **first**, then `$XDG_CONFIG_HOME/opencode/`,
    `~/.config/opencode/`, `%APPDATA%\opencode\` (§3.9). `OptionalDouble accountUsed()`
    (JSON `accounts[].used` → `kiro.db` → empty) and `OptionalDouble
    creditsForSession(String)` (JSON `sessions[].estCredits` → `kiro.db` `session_usage`
    by `session_id`, read-only/`immutable=1` or copy-then-read → empty). Never throws; no
    network; no token read. Injectable candidate-dir seam for `plainjunit` tests.
    **Also fix `KiroUsageReader.defaultCandidates()` to prepend `~/.servoy/opencode/`.**

18. **`SkillTestRunner.java` — credit bracket (B) + per-request list (C)** — build one
    `KiroCreditReader`; read `creditsStart = accountUsed()` immediately before the first
    prompt (once per scenario), `creditsEnd = accountUsed()` after completion before the
    trial's `finally deleteSession`; compute Δ (fallback `creditsForSession(keptSessionId)`,
    else "unknown"); log start/end/Δ; attach Δ + the kept trial's per-request token list
    to the headline `RunMetrics` via the extended `new RunMetrics(...)`. The per-request
    list is simply the kept trial's `actual.getRequestTokens()` — no DB read, no
    post-run phase and no call-count zipping — and each trial's session is deleted in
    that trial's own `finally` (the `abort(sessionId)` on `IOException` stays).

19. **`SkillTestReporter.java` (B/C)** — extend `writeMetrics`/`readMetrics`
    additively: write `creditsDelta` and a `requestTokens[]` array when present (omit
    when unknown); read them back with `has(...)` guards (absent ⇒ null/empty). No
    version bump; older files still load with the new fields reading "unknown".

20. **`SkillTestView.java` / `SkillTestHtml.java` / `JUnitXmlReporter.java` (D)** — add
    the credits-used Δ (estimate/shared-account caveat) and the ranked per-request token
    table/line per §3.12. Existing columns, `<testcase>`, `failures`/`errors` unchanged.

13. **Tests** (project `com.servoy.eclipse.opencode.skilltest.tests`, Jupiter,
    `plainjunit` — these are pure/parsing/formatting):
    - `SessionTranscriptTest`: token read across export shapes — session-level
      `info.tokens`; summed per-message; `cache.read/write` nested; **absent ⇒ null**;
      all-zero ⇒ null (not `0`).
    - `RunMetricsTest` / formatting: "unknown" for null tokens/kiro; "(estimate)" label
      on Kiro; `total()` excludes cache.
    - `KiroUsageReaderTest`: match by session id; file absent ⇒ empty; session missing
      ⇒ empty; malformed JSON ⇒ empty (no throw). Exercise the **cross-platform candidate
      resolution** — a Linux/macOS-style `~/.config/opencode` / `XDG_CONFIG_HOME` location is
      found as well as a Windows `%APPDATA%` one, and first-existing-wins ordering holds — so
      CI (non-Windows) is covered. (Point the reader at a temp file via a seam so it stays
      `plainjunit`.)
    - `BudgetEvaluatorTest`: over/under `max`; over/under `baselineValue`+`tolerancePct`;
      `metric:"tokens"` with null tokens ⇒ skipped (no warning, no failure); default
      metric is `toolCalls`.
    - `SkillTestReporterTest` (or extend existing): round-trip `metrics` (present +
      unknown); **old file without `metrics` loads with `getMetrics()==null`**; trial
      metrics round-trip.
    - `JUnitXmlReporterTest`: metrics line in `<system-out>` on PASS and on FAIL; a
      budget warning appears in `<system-out>` but `failures`/`errors` unchanged; a
      FAIL `<testcase>` body is unchanged by metrics.
    - **Expansion (A–D):**
      - `SessionTranscriptTest`: a `requestTokens` entry per assistant message of the
        **real export shape** (per-message **top-level** `tokens`, as in
        `{"id":"msg_1","type":"assistant","agent":"Orchestrator","content":[],"cost":0,`
        `"tokens":{"input":49755,"output":61,"reasoning":0,"cache":{"read":0,"write":0}}}`),
        in order, carrying that request's figures, its `callCount` and its tool calls
        (incl. nested Code-Mode); the summed `tokens` still equals the sum of the
        per-request entries; a zero-token / all-zero message fabricates no entry; absent
        ⇒ empty list; and a kept case for the **legacy `info.tokens`** shape so the
        fallback (and the `info.agent` fallback) stays covered.
      - `RequestTokensTest` / `RunMetricsTest`: `cacheReadRatio()` (0 when input 0),
        `noCaching()` flag, most-inefficient (largest-input) pick, base-input; the ranking
        label; `creditsLabel()` "unknown" vs the Δ-with-estimate wording.
      - `KiroCreditReaderTest`: `accountUsed()` from a fixture `kiro-usage.json`
        (`accounts[].used`) and from a fixture `kiro.db` when the JSON is absent;
        `creditsForSession` by `sessionId` (JSON) then `session_id` (DB `session_usage`,
        summed across `account_id` rows); `~/.servoy/opencode/`-**first** probe order and
        first-existing-wins; absent dir / absent file / malformed JSON / absent driver ⇒
        empty (no throw). Point the reader at a temp dir via the seam so it stays
        `plainjunit`; build the fixture `kiro.db` with the added driver.
      - `KiroUsageReaderTest`: add a case asserting `~/.servoy/opencode/` is probed
        **before** the previous defaults.
      - `SkillTestReporterTest`: round-trip `creditsDelta` + `requestTokens[]` (present +
        unknown); an old file lacking them still loads with those reading "unknown".
      - `JUnitXmlReporterTest`: the credits-Δ line and the per-request ranking appear in
        `<system-out>`, but `failures`/`errors`/`<testcase>` are unchanged.

14. **Compile loop** — after edits, `eclipse-ide_getCompilationErrors`, apply safe
    quick-fixes, re-check to zero errors. Fix top-two-severity SpotBugs in touched code.

---

## 5. Acceptance criteria

- [ ] Every run records a **tool-call count** (primary metric) on `SkillTestResult`,
      equal to `actual.getToolCalls().size()` (including nested Code-Mode calls).
- [ ] Token usage is parsed best-effort from the export's `info.tokens`
      (session-level or summed per-message), and shows **"unknown"** — never `0` — when
      absent or all-zero.
- [ ] An optional Kiro requests/estCredits figure is read from `kiro-usage.json` by
      session id, labelled an **estimate**, and shows **"unknown"** when the file is
      absent or the session is not found; the reader makes no network call and reads no
      token. The usage-file path is resolved **cross-platform** (Linux/macOS
      `$XDG_CONFIG_HOME`/`~/.config/opencode` and Windows `%APPDATA%\opencode`, first
      existing wins) so it works on **Jenkins (non-Windows)**, not only on developer Windows
      machines.
- [ ] Metrics are captured **per trial** and the result's headline metrics are the
      **documented representative (kept) trial's** figures (not a silent average).
- [ ] An optional per-baseline **soft budget** flags a regression beyond its threshold
      as a **warning only**: it never changes PASS/FAIL, never changes the JUnit
      `failures`/`errors` counts, and never alters a `<testcase>` body. Default budgeted
      metric is tool-call count; a `tokens` budget is skipped when token data is absent.
- [ ] `last-results.json` gains `metrics` **additively**: a file written by the current
      reporter (no `metrics`) still loads, with metrics reading "unknown". No version
      bump.
- [ ] Metrics appear in the **Skill Test view** (Tool calls + Tokens columns, "unknown"
      rendered correctly) and in `SkillTestHtml` detail (incl. per-trial for multi-trial
      runs).
- [ ] `JUnitXmlReporter` emits a metrics summary (and any budget warning) in
      `<system-out>` for every status; PASS stays green.
- [ ] New `plainjunit` tests cover: token present/summed/absent/all-zero; "unknown"
      formatting; Kiro reader match/absent/missing/malformed; budget over/under and
      tokens-absent-skip; reporter round-trip + old-file compatibility; JUnit
      `<system-out>` + unchanged failure counts.
- [ ] Zero compilation errors; no new top-two-severity SpotBugs in touched code.

### Scope-expansion acceptance (A–D)

- [ ] **(A)** `org.xerial:sqlite-jdbc` is added to the target platform
      (`com.servoy.eclipse.servoypilot..target`, Maven location, `sequenceNumber`
      bumped) and `org.sqlite` is imported in the skilltest bundle manifest; after a
      target reload `org.sqlite.JDBC` resolves. If the Servoy target already provided an
      SQLite JDBC bundle, that one is reused instead (it does not today). Resolves on
      Jenkins.
- [ ] **(B)** The opencode config dir probe tries `~/.servoy/opencode/` **first** (both
      `KiroCreditReader` and the fixed `KiroUsageReader`), so real runs stop reading
      "unknown".
- [ ] **(B)** The account `used`/`limit` figure is read from **`kiro-usage.json`**
      (`accounts[]`), not `kiro.db` (which has no account table). Each scenario records
      the account `used` **before the first prompt** and, after a **bounded wait** for
      the plugin to sync the snapshot (freshness stamp advancing), **after completion
      before `deleteSession`**, and reports **before / after / Δused**; the per-session
      `est_credits` (`kiro.db` `session_usage`) is the fallback when the account delta is
      not reliable; a snapshot that never syncs ⇒ "sync pending / unknown". Start/after/Δ
      are logged and surfaced. Labelled an estimate with the shared-account caveat. Reads
      are read-only, never throw into the run, make no network call and read no token;
      absent driver/file/JSON ⇒ "unknown".
- [ ] **(C)** Per-request token figures are captured from the HTTP **export** — the only
      source — in order, each with its own `callCount` and the tool calls/skill in that
      request (incl. nested Code-Mode `execute`). The list is built from each assistant
      message's **top-level** `tokens` object (falling back to `info.tokens` for the
      older shape) and keyed on a non-zero tokens object, not the fragile `role` string,
      so it is populated for the real export shape. **`opencode.db` is not read** and
      the session is deleted **per trial** (no deferred delete); the SQLite driver is
      retained for `kiro.db` only. The derived **cache-read ratio** and fixed **base
      input** are computed, the most inefficient request (largest input) is
      identifiable, and the per-tool figure is labelled an **attribution/estimate**
      (per-request metering). Absent token data ⇒ "unknown"/empty.
- [ ] **(D)** The accurate credits-used (Δ) and a ranked per-request token table appear
      in the Skill Test view detail, the `SkillTestHtml` report and the
      `JUnitXmlReporter` `<system-out>`; tool-call count stays primary; the WARN-only
      budget is unchanged (never PASS/FAIL, never JUnit `failures`/`errors`); every new
      surface degrades to "unknown" when its source is absent.
- [ ] **(B/C)** `last-results.json` gains `creditsDelta` and `requestTokens[]`
      **additively** — an older file without them still loads, reading "unknown"; no
      version bump.

---

## 6. Out of scope

- Changing the PASS/FAIL verdict based on cost, or any hard CI gate on efficiency.
- Trend/history charts over time (the sibling ticket owns this; this spec only shares
  the additive `last-results.json` storage).
- Any exact per-dollar / per-token Kiro billing figure (Kiro bills per request; no
  exact cost exists — only the labelled estimate). The accurate Δused credit figure is
  still an estimate labelled with the shared-account caveat, not an exact dollar cost.
- Reading AWS, tokens, or bearer credentials; contacting the Kiro service. The harness
  only reads the local `kiro-usage.json` snapshot and the local `kiro.db` (read-only),
  mirroring the panel.
- **Reading `opencode.db` at all (C).** Per-request tokens come from the HTTP export;
  the harness's sessions are never written to `opencode.db`'s
  `session_v2`/`session_message` tables, so nothing reads that file (and the ~120 MB
  file is certainly never copied). `kiro.db` is still read, read-only, for credits.
- **Exact per-tool token splits (C).** opencode meters per request; a request's tokens
  are shared across its tool calls, so per-tool cost is an attribution/estimate only.
- Enforcing a global default budget across all baselines (budget is per-baseline and
  optional; a global default may be a later follow-up).
- Changes to the webui / Angular chat UI (Java-only task).

---

## 7. Resolved decisions

All questions raised during spec review are now **decided** — the coder implements these
answers; they are not open.

| # | Question | Decision |
|---|---|---|
| 1 | Does `TokenUsage.total()` include `cache.read`/`cache.write`? | **Excluded.** `total() = input + output + reasoning`. Cache read/write are captured and shown in the breakdown but not in the headline "total", matching how the panel/export treat cache as a cost modifier rather than new tokens. Documented in §3.1. |
| 2 | Where does `RunMetrics` live? | **Top-level `RunMetrics.java`** in the `skilltest` package (with nested `TokenUsage` / `KiroUsage` records). Avoids a `SessionTranscript`→`SkillTestResult` dependency and keeps the value type reusable. |
| 3 | Compact "Kiro" view column — include it? | **No separate Kiro column.** The view gets only **"Tool calls"** and **"Tokens"** columns (avoids sidebar clutter). The Kiro requests/estCredits figure appears in the **`SkillTestHtml` detail** and the **`JUnitXmlReporter` `<system-out>`** only. |
| 4 | Soft-budget config location. | **Per-baseline `budget` block in `baseline.json`** only. No global default this iteration (a global default is a possible later follow-up — already listed under Out of scope). |
| 5 | Exact `kiro-usage.json` field names. | **Confirmed from the panel's `UsageFile`/`SessionUsage` TypeScript interfaces** (`Servoy/openchamber-panel` `panel/main.ts`): top-level `{ version, writtenAt, pluginVersion?, accounts[], sessions[] }`; each session is `{ sessionId, title?, directory?, requests, estCredits, firstUsed, lastUsed, accounts? }`. The reader keys on **`sessionId`** and reads **`requests`** (int) and **`estCredits`** (double). Still parse **tolerantly** (null-safe, ignore unknown fields) because the shape may evolve by plugin version. |

> Note on decision 5: the panel uses `estCredits` (not `estimatedCredits`); the reader uses
> that exact key but tolerates its absence (→ "unknown"). The `accounts[]` split and the
> `title`/`directory` fields are not needed by the iteration-1 reader and are ignored —
> but the expansion's `KiroCreditReader` **does** read `accounts[].used` (decision 9).

### Scope-expansion decisions (A–D) — approved, not open

| # | Question | Decision |
|---|---|---|
| 6 | How is the accurate per-run cost measured? | **A credit bracket.** Read the account's `used` immediately **before** the first prompt and immediately **after** completion while the session still exists (i.e. before that trial's `finally deleteSession`), and report **Δused**. Fall back to the per-session `est_credits` (`session_usage`, or JSON `estCredits`) when the account delta is not reliable. Labelled an estimate with the shared-account inflation caveat (sequential/dedicated runs are usually fine). This credits logic is untouched by the decision-8 revision. |
| 7 | Which SQLite bundle? | **`org.xerial.sqlitejdbc`** (`org.xerial:sqlite-jdbc`, latest stable), added to the target platform's Maven location + `org.sqlite` imported in the skilltest manifest — **unless** the Servoy target already provides an SQLite JDBC bundle, in which case reuse that. Confirmed none exists today. **The dependency stays** after the decision-8 revision: `KiroCreditReader` still needs it for `kiro.db` (decision 9). |
| 8 | Where do per-request tokens come from? | **REVISED (live data): the HTTP export is the ONLY source; `opencode.db` is not read.** Every assistant message in the export carries its own `tokens` object at the **MESSAGE top level** — a sibling of `id`/`type`/`agent`/`model`/`content`/`cost`, with no `info` wrapper (older exports nest it under `info.tokens`, still supported as a fallback). `SessionTranscript.fromExport` reads `message.tokens` first, falls back to `info.tokens`, and builds the ordered per-request list keyed on a **non-zero tokens object** (not the fragile `role` string), each entry carrying its own exact `callCount`. Verified live: 7 requests, input 49741..68463, cache 0. The previously specified `opencode.db` primary source has been **removed** along with its `OpencodeTokenReader` class: the harness's sessions are **never written** to `session_v2`/`session_message` (probe: `inSessionV2=false`, 0 rows), so the DB read could never return anything. Because nothing has to outlive the read, the **deferred delete is also gone** — the session is deleted **per trial** in the trial's own `finally`, as originally — and `zipCallCounts` is gone with it (the transcript entries already carry their call counts). Absent token data ⇒ "unknown". See §3.11. |
| 9 | Where does the account credit figure come from? | **REVISED (live data): the account `used`/`limit` figure lives in `kiro-usage.json`, NOT `kiro.db`.** `kiro.db` has **no account table** — only `session_usage(session_id, account_id, requests, est_credits, ...)`. The account's available-credit figure (`accounts[].used` / `.limit` / `.pct` / `.updatedAt`, plus top-level `writtenAt`) exists **only** in the JSON snapshot. So `KiroCreditReader.accountUsed()`/`accountLimit()`/`snapshotStamp()` read the JSON (parsed tolerantly), while the per-session `est_credits` fallback (`creditsForSession`) stays on `kiro.db` `session_usage`, opened read-only `immutable=1` (that part reflects the just-finished scenario and works today). Because the JSON is synced only periodically, the runner **bounded-waits** for the snapshot freshness stamp to advance before reading the end of the bracket (else Δ reads ~0 off a stale snapshot); a never-advancing snapshot ⇒ "sync pending / unknown". Never throws (absent/locked/malformed ⇒ "unknown"). No network, no token read. Separate from the iteration-1 `KiroUsageReader`. |
| 10 | Which config dir? | **The single source of truth — `OpenCodeUtil.getServoyOpencodeConfigDir()`**, the same method the opencode bundle uses to decide where it writes `kiro-usage.json`/`kiro.db`/`opencode.db`. Reader and writer are the same bundle in the same JVM, so they agree by construction on whatever that method resolves — **including on Jenkins** (it is **not** a hardcoded `~/.servoy` literal; it follows `user.home`/any future override). A probe fallback (`$SERVOY_OPENCODE_CONFIG`, then `<user.home>/.servoy/opencode`, then `$XDG_CONFIG_HOME/opencode`, `~/.config/opencode`, `%APPDATA%\opencode`) is only a safety net for a no-OSGi `plainjunit` context, which otherwise uses the injectable candidate-dir seam. Applied to both the new `KiroCreditReader` and the existing `KiroUsageReader`. (`opencode.db` is listed here only as one of the files that method's directory holds; nothing reads it — decision 8.) |
| 11 | How is per-tool cost presented, given per-request metering? | **An attribution/estimate, stated honestly.** Tokens are surfaced **per request** with the tool calls/skill in that request; when a request has several tool calls they share its figure. The report ranks by request input and highlights the most inefficient step (the uncached ~474k-input re-send), with the derived cache-read ratio and base input. |
