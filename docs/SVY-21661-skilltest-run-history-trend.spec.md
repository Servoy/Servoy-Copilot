# Spec: SVY-21661 — [skilltest] Trend / history reporting

## 1. Goal
Keep a compact, append-only history of skill-test runs, so a skill that degrades slowly across
model or skill updates becomes visible. Every run, from the IDE Skill Test view or the headless CI
runner, appends one JSON line per baseline to a `run-history.jsonl` file through one shared,
best-effort writer. The IDE view reads its local history and shows a per-baseline pass-rate trend
over the last N runs. On CI the history goes to a fixed, persistent directory on the Jenkins agent,
so it builds up across nightly runs, and each build archives a copy. The user's question "where
could we keep the data about runs (usage)? this should work locally and on CI" has this answer:
locally in the git-ignored `reports/` folder, and on CI in `$JENKINS_HOME/skilltest-history/` (owned by the Jenkins user, not public).

On CI the same history also drives a CSP-safe **trend report** (`trend.html` + external `skilltest-report.css`, no JS, kept only as a
Jenkins build artifact behind the login, never on the public `/latest/` site) and a set of **trend alerts** ("something is off": regression,
pass-rate drop, flakiness, cost rise). When there are alerts the build is marked UNSTABLE (Teams
is deliberately **not** notified for trend alerts during the trial) and an alert email is sent to a configurable recipient list
(initially `emera@servoy.com`).

## 2. Background

### 2.1 Current persistence (local)
- `SkillTestPaths` resolves `<workspace>/servoy_ai_skilltests/`. `reports/` holds derived output
  and gets a self-written `.gitignore` (`*`) when it is created.
- `SkillTestReporter.writeRun(results, markdown)` is called only from `SkillTestView` (job body,
  around line 501). It overwrites `reports/last-results.json`, which is a snapshot holding the
  latest result per baseline, and writes a `run-<timestamp>.md` that nothing ever reads back.
  `loadLastResults()` restores only the last-result column. The view never sees history.
- SVY-21660 metrics (`RunMetrics`: `toolCalls`, `tokens`, `kiro`, `creditsDelta`, bracket
  readings, `requestTokens`) are already persisted in `last-results.json`.

### 2.2 Current persistence (CI, `Jenkinsfile.skilltest`)
- Nightly `pollSCM('H 3 * * *')` with path-filtered checkout, `agent any`. There is **no**
  `disableConcurrentBuilds()` yet.
- `SkillTestHeadlessRunner.runSkillTests` never calls `SkillTestReporter`. It writes only
  `TEST-skilltest.xml` (`JUnitXmlReporter`) and `coverage.html`
  (`CoverageReportHtml.emitCoverage`, best-effort) into `-Dservoy.skilltest.outputDir`.
- Every location the build writes to is wiped per build by design: `skilltest-reports/`
  (`rm -rf` at the start of the run, commit b79c5b2), `target/work/data` (`mvn clean`), and
  `kiro-state/` (it holds the key, commit dd5f10a). So nothing accumulates today.
- The job already hard-depends on `/data/www/...` existing on the agent (servoy update site,
  tarball, p2 deploy target), but the Jenkins user cannot create new directories under `/data`
  (build #92: `Permission denied`), so the history lives in `$JENKINS_HOME/skilltest-history/`.
- System properties reach the forked workbench only through the explicit `<argLine>` in
  `tests/com.servoy.eclipse.opencode.skilltest.tests/pom.xml` (`integration` profile). A new
  `-Dservoy.skilltest.historyFile` must be added there, and a property default must be defined
  in the profile's `<properties>`.

### 2.3 Run-identity data available cheaply
- Agent: `SkillTestResult.getAgent()` (already persisted).
- Model: `SkillTestRunner.resolveModel(baseline)` (private static; sidecar `model` or golden
  `providerID/modelID`). The writer needs it, so it must be exposed (see 3.4).
- Plugin version: the skilltest bundle's `Bundle-Version` (`2026.3.1.qualifier`, resolved at
  runtime via `FrameworkUtil.getBundle(...)`, `null` outside OSGi).
- CI build: Jenkins env vars `BUILD_NUMBER`, `JOB_NAME`, `GIT_COMMIT` are inherited by the
  surefire fork.
- Skills version: not tracked anywhere today. An optional `-Dservoy.skilltest.skillsVersion` /
  env `SKILLTEST_SKILLS_VERSION` is read when present (CI can pass the skill4servoy commit).
  Otherwise it is omitted.

### 2.4 Git history
- `2bb609d` (SVY-21366): introduced `SkillTestPaths` / `SkillTestReporter`, the `reports/` layout,
  and the overwrite-only `last-results.json`.
- `b79c5b2` (SVY-21366): empties `$WORKSPACE/skilltest-reports` at the start of each CI build so a
  stale report is never published. **History must not live there.**
- `dd5f10a` (SVY-21366): per-build `kiro-state` that is deleted after the run, so it can't be a store.
- `373ea49` / `c879c1c` (SVY-21711): coverage report written best-effort to `outputDir` with a
  fixed name and archived. This is the precedent for a best-effort derived artefact that never fails the run.
- `2efe33e` (SVY-21660): metrics persisted additively. `RunMetrics` is reused as is.

## 3. Design

### 3.1 Storage format: `run-history.jsonl`
JSON Lines, UTF-8, one compact (non-indented) JSON object per line, `\n` terminated. Each completed
run appends **one line per baseline result**. All lines of the same run share a `runId`.

```json
{"v":1,"runId":"2026-11-24T03:12:45.123Z-ci-412","timestamp":"2026-11-24T03:12:45.123Z",
 "source":"ci","buildNumber":"412","job":"servoy-copilot-skilltest","commit":"ab12cd3",
 "pluginVersion":"2026.3.1.v20261124","skillsVersion":"9f8e7d6",
 "baselineId":"create-form-basic","status":"PASS","attempts":1,
 "trials":{"passed":3,"total":3},"agent":"Orchestrator","model":"kiro/auto",
 "metrics":{"toolCalls":14,"tokens":{"input":81234,"output":2210,"reasoning":0,"cacheRead":0,"cacheWrite":0},
            "kiro":{"requests":6,"estCredits":1.4},"creditsDelta":1.3}}
```
(The example is wrapped here for readability. On disk it is a single line.)

Field rules:
- `v`: schema version, `1`. Readers ignore unknown fields and skip lines with a higher major
  version they don't understand only when the required fields are missing.
- Required: `v`, `runId`, `timestamp` (ISO-8601 UTC instant), `source` (`ide` | `ci`),
  `baselineId`, `status` (`SkillTestResult.Status` name).
- Optional, **omitted when unknown** (never written as `null`/`0`, same convention as SVY-21660):
  `buildNumber`, `job`, `commit` (CI only), `pluginVersion`, `skillsVersion`, `agent`, `model`,
  `attempts`, `trials` (`passed` = `getPassCount()`, `total` = `getTrials().size()`, only when
  trials exist), `metrics`.
- `metrics` is a **compact subset** of `RunMetrics`: `toolCalls`, `tokens` (5 fields), `kiro`
  (`requests`, `estCredits`), `creditsDelta`. It leaves out `requestTokens`, `creditsBefore/After/Limit`,
  diffs, prompts, tool-call arguments/outputs, outcome nodes and JSUnit reports, which keeps lines
  small (well under 1 KB).
- **No secrets:** no prompt text, no tool arguments/outputs, no env values other than the
  explicit identity fields above, no Kiro key, no GENAI key. A unit test asserts this.
- `source`: `ci` when the writer is invoked by `SkillTestHeadlessRunner`, `ide` from the view.
  It is passed in explicitly by the caller and not guessed.
- `runId`: `<timestamp>-<source>[-<buildNumber>]`. It groups the per-baseline lines of one run.

Retention: **unbounded**, with no capping or rotation. At under 1 KB per line, years of nightly
runs over tens of baselines stay in the low-MB range.

### 3.2 Location resolution
New `SkillTestPaths.historyFile()`:
1. System property `servoy.skilltest.historyFile` if non-blank.
2. Otherwise env var `SERVOY_SKILLTEST_HISTORY_FILE` if non-blank.
3. Otherwise `new File(reportsRoot(), "run-history.jsonl")` (local default: git-ignored,
   survives restarts). Add constant `HISTORY_FILE = "run-history.jsonl"`.

Parent directories are created on write (`mkdirs`). The resolution logic lives in a
package-visible/static helper that takes the property/env values as parameters, so it is
unit-testable without mutating real env vars.

### 3.3 Shared writer: `RunHistory` (new class, package `com.servoy.eclipse.opencode.skilltest`)
- `record RunIdentity(String source, Instant timestamp, String buildNumber, String job, String commit, String pluginVersion, String skillsVersion)`,
  with factory methods `RunIdentity.ide()` and `RunIdentity.ci()`. They read `BUILD_NUMBER` / `JOB_NAME` /
  `GIT_COMMIT` from env, `servoy.skilltest.skillsVersion` (or env `SKILLTEST_SKILLS_VERSION`) and the bundle version. All
  lookups are null-safe, and there is an overload that takes a `Function<String,String>` env lookup so it can be tested.
- `static String toJsonLine(SkillTestResult r, RunIdentity id, String runId, String model)` is pure
  and unit-tested. It uses Jackson (already imported by `SkillTestReporter`), and its output contains
  no line breaks (Jackson escapes `\n` in strings).
- `static boolean append(File file, List<SkillTestResult> results, RunIdentity id, Function<String,String> modelByBaseline, Consumer<String> log)`:
  - Builds all lines for the run into one string, then writes them with a **single**
    `Files.write(..., CREATE, APPEND)` on a `FileChannel`, holding a `FileLock` for the duration (best-effort
    against a concurrent IDE+CI or two-IDE writer on the same file).
  - If the existing file is non-empty and does not end with `\n` (a previous write was cut off), it
    first writes a `\n`, so the new run starts on a fresh line and the broken fragment stays isolated
    on its own line, where the reader skips it.
  - **Never throws.** It catches `IOException | RuntimeException`, logs via `log`, and returns `false`. Null or empty
    results means nothing to write: no-op, return `true`.
- `static List<Entry> read(File file)` returns the parsed history in file order:
  - Missing file → empty list. It reads line by line and **skips** blank lines, lines that fail to parse,
    lines missing the required fields, and an unknown `status` (mapped to skip, not ERROR). A
    partially written or corrupt last line (or one in the middle) never fails the read.
  - `record Entry(String runId, Instant timestamp, String source, String buildNumber, String baselineId, Status status, Integer trialsPassed, Integer trialsTotal, String agent, String model, String pluginVersion, String skillsVersion, Long totalTokens, Integer toolCalls, Double creditsDelta)`.
- `static Map<String, Trend> trends(List<Entry> entries, int lastN)` is pure. It computes per baseline,
  over its most recent `lastN` entries (by file order, which is append/chronological order):
  - `runs` (count considered), `passes` (status `PASS`), `passRate` (`passes/runs`, with SKIPPED
    entries **excluded** from both numerator and denominator), and a compact `sparkline` string of
    the statuses oldest→newest (`✓` PASS, `✗` FAIL, `!` ERROR, for example `✓✓✗✓✓`), plus
    `avgTokens` / `lastTokens` when known (the token trend is shown in the detail panel; see 3.5).
  - `record Trend(int runs, int passes, double passRate, String sparkline, Long avgTokens, Long lastTokens)`
    with a `label()` such as `"80% (4/5) ✓✓✗✓✓"`. If there are no non-skipped runs, the label is `"—"`.
  - The default `lastN` is **10** (constant `RunHistory.DEFAULT_TREND_RUNS`). The value actually
    used by the view is **configurable** (see 3.5a); `trends` itself just takes `lastN` as a
    parameter (values `< 1` are clamped to 1).

### 3.4 Model per baseline
`SkillTestRunner.resolveModel(Baseline)` changes from `private static` to package-visible `static`
(or public, as needed by `headless`). That is a behaviour-preserving visibility change in our own
code, needed so both callers can record the model. Both callers build a
`Function<String,String> modelByBaseline` from the baselines they ran. An unknown model means the field is omitted.

### 3.5 Call sites
**IDE (`SkillTestView` run job).** Right after the existing `SkillTestReporter.writeRun(...)` try/catch,
and outside it, so a `writeRun` IOException does not skip history:
```java
RunHistory.append(SkillTestPaths.historyFile(), results, RunHistory.RunIdentity.ide(),
        modelByBaseline, SkillTestView.this::log);
```
Then it reloads the trend cache (see below) before `asyncRefresh()`.

The task says the writer is "called by SkillTestView (via the SkillTestReporter.writeRun path)". The
implementation can either call `RunHistory.append` directly next to `writeRun`, or add an overload
`SkillTestReporter.writeRun(results, markdown, identity, modelByBaseline, log)` that writes history
after last-results/markdown. Either way the history write must happen even if the Markdown write
fails, and must never turn into a thrown exception. Prefer the direct call next to `writeRun` for
clarity.

**CI (`SkillTestHeadlessRunner.runSkillTests`).** After the final JUnit report and coverage (step
6b), before the empty-results early return and before the exit-code computation:
```java
RunHistory.append(SkillTestPaths.historyFile(), results, RunHistory.RunIdentity.ci(),
        modelByBaseline, this::output);
```
It doesn't touch `exitCode`. Additionally, when the history file is **outside** `outputDir`, copy it
best-effort to `outputDir/run-history.jsonl` (`Files.copy(..., REPLACE_EXISTING)`, failure logged and
ignored), so the existing `skilltest-reports/` archive picks up a per-build snapshot. The interim
reports (`writeInterimReport`) do **not** write history. Only a completed run does.

Note: a run killed mid-way (Maven/Jenkins timeout) writes no history line for that run. That is
accepted, since Jenkins' JUnit trend still records the interim XML.

**View trend display.**
- `SkillTestView` keeps `Map<String, RunHistory.Trend> trends`, loaded on view creation (next to
  `loadLastResults()`) and after every run via `RunHistory.trends(RunHistory.read(SkillTestPaths.historyFile()), DEFAULT_TREND_RUNS)`.
  The read runs inside the job / off the UI thread for the post-run reload. The initial load at
  `createPartControl` is acceptable synchronously (the file is small), but it must never throw.
- New table column **"Trend"** (width ~150), inserted after "Last result": `trend.label()` or
  `"—"` when there is no history for the baseline.
- The detail panel (`SkillTestHtml`) gets a small "History (last N runs)" section for the selected
  baseline: a table of timestamp, source, status, trials, model, tokens, credits Δ, newest first.
  This is where the token trend appears (the ticket's "later tokens/credits"). The HTML is built by a
  pure static method so it is unit-testable, and all values are HTML-escaped like the existing detail rendering.
- Only **local** history is read. There is no download of CI history.

### 3.5a Configurable trend window
- The number of runs the trend covers ("last N") is a **user preference**, default
  `RunHistory.DEFAULT_TREND_RUNS` (10), allowed range **1..1000**.
- Stored in the skilltest bundle's instance-scope preferences
  (`InstanceScope.INSTANCE.getNode(<skilltest bundle symbolic name>)`, key
  `skilltest.trendRuns`), so it survives IDE restarts. Flush on change; a flush/read failure
  is logged and falls back to the default, never throws.
- Set from the Skill Test view's **view menu** (drop-down) action "Trend window (last N runs)...",
  which opens a JFace `InputDialog` pre-filled with the current value and an `IInputValidator`
  rejecting non-integers and values outside 1..1000. On OK: store, recompute the trend map from
  the already-read history (no re-read needed, or re-read off the UI thread), refresh the table
  and detail panel.
- The "Trend" column header shows the window, e.g. `Trend (last 10)`, and the detail-panel
  section title uses the same N ("History (last N runs)").
- Parsing/clamping/validation lives in a pure static helper (e.g.
  `RunHistory.parseTrendRuns(String raw)` → `Integer` or null, and `clampTrendRuns(int)`), so it is
  plain-JUnit testable; the preference access is behind a small overridable seam in the view.
- The headless CI runner does not use this preference (it only writes history).

### 3.6 CI wiring (`Jenkinsfile.skilltest` + test pom)
- `options { disableConcurrentBuilds() }` added next to `timeout`/`timestamps` (single writer on
  the agent file).
- In the run `sh` block: `mkdir -p "$JENKINS_HOME/skilltest-history"` and pass
  `-Dservoy.skilltest.historyFile=$JENKINS_HOME/skilltest-history/run-history.jsonl`, plus
  `-Dservoy.skilltest.skillsVersion="$(git -C "$WORKSPACE/skill4servoy" rev-parse --short HEAD)"`
  (best-effort; empty is fine).
- `tests/com.servoy.eclipse.opencode.skilltest.tests/pom.xml`, `integration` profile: add the property
  defaults `<servoy.skilltest.historyFile></servoy.skilltest.historyFile>` and
  `<servoy.skilltest.skillsVersion></servoy.skilltest.skillsVersion>`, and append
  `-Dservoy.skilltest.historyFile=${servoy.skilltest.historyFile} -Dservoy.skilltest.skillsVersion=${servoy.skilltest.skillsVersion}`
  to the tycho-surefire `<argLine>`. An empty value means the local default (3.2 treats blank as unset).
- `archiveArtifacts`: add `skilltest-reports/run-history.jsonl` (the per-build copy from 3.5).
- The agent dir is not created by the code on CI beyond `mkdirs`. The pipeline's `mkdir -p` makes
  a missing `/data` mount visible early. If the dir is not writable, the writer logs and the run
  still succeeds.
- The comment block explains why it is not under `$WORKSPACE`/`skilltest-reports` (wiped per build, b79c5b2) and
  why it is not Copy Artifact chaining or the baselines repo (human decision on this case).

### 3.6a Trend alerts: `TrendAlerts` (new, pure Java, same package)
Evaluated by the headless runner after the history append, over the **full** history file
(CI source only is not required; the rules apply to whatever the file holds, which on CI is CI runs).
Per baseline, using its entries in file (chronological) order, SKIPPED entries ignored, window
`N` = `RunHistory.DEFAULT_TREND_RUNS` (10) unless overridden:

| Rule id | Fires when (defaults) | Override property |
|---|---|---|
| `REGRESSION` | the latest run is FAIL/ERROR and the previous **3** runs were all PASS | `servoy.skilltest.alert.regressionRuns` |
| `PASS_RATE_DROP` | pass rate of the last N runs is **≥ 20** percentage points below the N runs before them (both windows must be full) | `servoy.skilltest.alert.passRateDropPoints` |
| `FLAKY` | **≥ 3** pass↔non-pass flips within the last N runs | `servoy.skilltest.alert.flakyFlips` |
| `COST_RISE` | the latest run exceeds the **baseline's own budget** for a metric (see below); tolerance **30%** by default | `servoy.skilltest.alert.costRiseDefaultPercent` |

**`COST_RISE` is budget-driven.** Each baseline can already declare soft budgets in its sidecar
(`Baseline.budgets()`, SVY-21660, edited via `BudgetEditorDialog`): one per metric
(`tokens`, `toolCalls`, `credits`), with an absolute `max` and/or a `baselineValue` + `tolerancePct`.
For every metric of the **current run's** history entry, `TrendAlerts` judges it like this:
1. **The baseline declares a budget for that metric** → use it, with the same semantics as
   `BudgetEvaluator`: over `max` fires; otherwise over `baselineValue × (1 + tolerance/100)` fires,
   where `tolerance` = the budget's `tolerancePct`, or the **default 30%** when the budget sets a
   `baselineValue` but no `tolerancePct`.
2. **No budget for that metric** → fall back to the history: the latest value is ≥ the default
   30% above the median of the previous N entries that have it (needs ≥ 3 such entries; otherwise
   no judgement for that metric).
- Metric mapping from the history entry: `tokens` = total tokens, `toolCalls` = `toolCalls`,
  `credits` = **per-session `kiro.estCredits`, rounded** (the same figure `BudgetEvaluator` uses,
  not the account-wide `creditsDelta`, which over-reports on a shared account). So `RunHistory.Entry`
  also carries `estCredits` (it is already in the JSON line's `metrics.kiro`). A metric whose value
  is absent in the entry is skipped, never alerted.
- The message names the metric, value, limit and source, e.g.
  `tokens 512000 exceeds budget 390111 +30% (+31%)` or
  `toolCalls 14 is 40% above the median of the last 10 runs (10)`.
- The default tolerance is overridable with `servoy.skilltest.alert.costRiseDefaultPercent`; a
  per-baseline `tolerancePct` always wins over the default.
- The budgets come from the baselines the headless runner already loaded
  (`Map<String, List<Baseline.Budget>>` by baseline id, passed into `evaluate`). The history file
  does **not** store budgets, so a budget change takes effect on the next evaluation.
- `BudgetEvaluator` itself is **unchanged** (its tolerance still defaults to 0 for the existing
  WARN-only JUnit/IDE budget warnings); the 30% default applies to the alert only. Reuse its logic
  where possible rather than duplicating it (e.g. a small package-visible helper taking the
  default tolerance as a parameter), as long as the existing warning output stays identical.

Window override: `servoy.skilltest.alert.window`. All overrides are read via a
`Function<String,String>` lookup (testable); blank/invalid → default, never throws.

- Alerts are only evaluated for baselines that have an entry **in the current run** (`runId` of
  this run), so a baseline that wasn't run tonight never alerts, and a night without a build
  (pollSCM only starts on changes) is simply not in the history — gaps never alert.
- Each alert carries a **likely cause** when the latest entry's `model`, `skillsVersion` or
  `pluginVersion` differs from the previous entry's (e.g. "model changed kiro/a → kiro/b").
- `record Alert(String baselineId, String rule, String message, String likelyCause)`;
  `static List<Alert> evaluate(List<RunHistory.Entry> all, String currentRunId,
  Map<String, List<Baseline.Budget>> budgetsByBaseline, AlertConfig cfg)` is pure.
- `static boolean writeAlertsJson(File outFile, List<Alert> alerts, Consumer<String> log)`: writes
  `alerts.json` (`{"runId":…, "count":n, "alerts":[…]}`), **always** written (count 0 when clean)
  so the pipeline can read it deterministically. Best-effort, never throws.
- No secrets in alerts (only ids, rule names, numbers, model/version strings).

### 3.6b Trend report: `TrendReportHtml` (new, pure Java, same package)
- `static String render(List<RunHistory.Entry> all, List<TrendAlerts.Alert> alerts, int window)`
  returns the HTML document. All values HTML-escaped. Light styling consistent with `coverage.html`.
- **CSP-safe by construction** (Jenkins serves artifacts with a strict Content-Security-Policy,
  default `sandbox; default-src 'none'; img-src 'self'; style-src 'self';`):
  - **No JavaScript at all**: no `<script>`, no `on*=` event attributes, no `javascript:` URLs.
  - **No inline CSS**: no `<style>` block **and no `style="..."` attributes** (both are blocked by
    `style-src 'self'` without `'unsafe-inline'`).
  - Styling comes from one **external stylesheet** `skilltest-report.css`, linked with a **relative**
    `<link rel="stylesheet" href="skilltest-report.css">`, so it is same-origin wherever the page is served
    (Jenkins artifact view, `/data/www` publish, local file) and is allowed by `style-src 'self'`.
    Elements are styled through `class` attributes only.
  - Charts are **inline `<svg>` markup** in the page (an inline SVG element is not a fetched
    resource, so `img-src` does not apply). SVG colours/strokes use **presentation attributes**
    (`fill`, `stroke`, `stroke-width`, …) or classes from `skilltest-report.css`, never `style=`. Tooltips use
    SVG `<title>` (works without JS).
  - No external fonts, images or other network resources.
  - The page stays readable unstyled (semantic tables/headings), so a missing `skilltest-report.css` degrades
    gracefully.
- `skilltest-report.css` is the shared stylesheet described in 3.6b-2 (also used by `coverage.html`).
  `static boolean writeReport(File outDir, String html, Consumer<String> log)` writes **both**
  `trend.html` and `skilltest-report.css` into `outDir`. Best-effort, never throws.
- The alert **email** body (3.6d) is not served by Jenkins and email clients generally ignore
  external stylesheets, so it uses plain, minimal HTML (simple table, no CSS dependency). It is not
  a copy of `trend.html`; it links to it.
- Content:
  1. Header: generated time, latest runId/build, number of runs and baselines in the history.
  2. **Alerts banner** at the top (red box listing each alert with baseline, rule, message, likely
     cause); a green "No trend alerts" line when empty.
  3. Summary table: one row per baseline — latest status, Trend label (`RunHistory.Trend.label()`
     over `window`), runs in history, last model.
  4. Per baseline: an inline SVG **pass-rate chart** (rolling pass rate over `window`, per run) and a
     **tokens / credits Δ chart** over all runs, x axis = run order with build-number labels where
     available, a vertical marker where `model` or `skillsVersion` changed (tooltip via SVG `<title>`).
     A baseline with < 2 runs shows a "not enough history" note instead of charts.
- Charts are produced by small pure helpers (point scaling / polyline building) so they are unit-testable.

### 3.6b-2 Shared report stylesheet + `coverage.html` CSP fix (folded in)
Today `coverage.html` (`CoverageReportHtml`, SVY-21711) renders unstyled in the Jenkins artifact
view: it moved its styling from a `<style>` block to `style="..."` attributes, but Jenkins' default
CSP (`style-src 'self'`, no `'unsafe-inline'`) blocks inline style attributes too. Fix it with the
same pattern as `trend.html`:
- **One shared stylesheet** `skilltest-report.css` (static resource in the skilltest bundle, e.g.
  `com/servoy/eclipse/opencode/skilltest/report/skilltest-report.css`, loaded via the class loader)
  used by **both** reports, so they look consistent. It holds the common base (body/font, header
  band, tables, badges, muted text, alert/notice boxes) plus report-specific rules (coverage
  not-covered/off-catalog blocks; trend charts/alerts banner).
- A small shared helper (e.g. `ReportCss`) with `static String linkTag()` → `<link rel="stylesheet"
  href="skilltest-report.css">` and `static boolean writeCss(Path dir, Consumer<String> log)` that
  copies the resource into the report's directory (overwrite, best-effort, never throws). Both
  report writers call it, so the CSS always sits next to the HTML.
- `CoverageReportHtml`: replace every `STYLE_*` `style="..."` attribute with a `class="..."`
  (class names mirror the current constants, e.g. `cov-header`, `cov-badge cov-badge-full`,
  `cov-badge-partial`, `cov-th`, `cov-td-item`, `cov-notcovered`, …), drop the `STYLE_*`
  constants, emit the `<link>` in `page(...)`, and make `write(...)` also write the stylesheet into
  `targetDir`. Rendered content/structure is otherwise unchanged (same text, sections, file names:
  `coverage.html` on CI, `coverage-<timestamp>.html` in-IDE). Update the class Javadoc's CSP note
  to the new approach (inline `style=` is also blocked; external same-origin CSS is allowed).
- In-IDE: the coverage report is only written to `reports/`, not rendered in the view's browser,
  so the stylesheet next to it is enough. The `SkillTestHtml` detail panel (rendered in the IDE
  browser via `setText`, not served by Jenkins) is **not** changed.
- Visual result must match the current intended look (green header band, green/amber badges, red
  not-covered block, muted grey secondary text), now actually applied on Jenkins.
- Pipeline: `archiveArtifacts` adds `skilltest-reports/skilltest-report.css`.
- Tests: update `CoverageReportHtmlTest`'s existing CSP test (currently asserts `style="` is
  **present**) to assert the opposite — no `style=`, no `<style`, no `<script`, exactly one relative
  `skilltest-report.css` link — and that the badge tests check classes instead of inline styles;
  add a test that `write(...)` puts a non-empty `skilltest-report.css` next to the HTML.
- `build.properties` of the skilltest bundle: make sure the `.css` resource under `src/` ends up in
  the built jar (resources under `src/` are copied by Tycho/PDE; verify, add to `bin.includes` if
  it lives outside `src/`).

### 3.6c Headless runner wiring (alerts + report)
In `SkillTestHeadlessRunner.runSkillTests`, right after the history append (3.5):
1. `entries = RunHistory.read(historyFile)`;
2. `alerts = TrendAlerts.evaluate(entries, currentRunId, AlertConfig.fromSystem())`;
3. write `outputDir/alerts.json` and `outputDir/trend.html`;
4. log one line per alert to the runner output.
`RunHistory.append` must therefore expose the `runId` it used (e.g. return it, or let the caller
build it via `RunHistory.runId(identity)` and pass it in). **None of this touches `exitCode`**; the
build result for alerts is decided by the pipeline (3.6d) from `alerts.json`. Any exception is
caught and logged. The view (IDE) does not evaluate alerts or write the report.

### 3.6d Pipeline: publish, UNSTABLE, email
In `Jenkinsfile.skilltest`:
- `archiveArtifacts`: also `skilltest-reports/trend.html, skilltest-reports/skilltest-report.css, skilltest-reports/alerts.json`
  (`skilltest-report.css` must sit next to `trend.html` in the artifacts so the relative link resolves).
- **No public publish.** `/data/www/latest/` is served publicly (`build.servoy.com/latest/`), so the
  report is **not** copied there. It is only available as a build artifact behind the Jenkins
  login; the stable "latest" link is
  `<JOB_URL>lastSuccessfulBuild/artifact/skilltest-reports/trend.html`.
- Read `alerts.json` (`readJSON` if Pipeline Utility Steps is available, otherwise a `grep`/`sed` on
  `"count":` in `sh`). If `count > 0`:
  - `unstable("Skill-test trend alerts: <count>")` → build UNSTABLE (yellow in Jenkins).
  - **No Teams post for trend alerts (trial period, until the alerts are tested).** The global
    `post { unstable { office365ConnectorSend ... } }` would otherwise fire, so it is guarded: the
    alert step sets a flag (e.g. `env.SKILLTEST_TREND_ALERTS = 'true'`), and the `unstable` Teams
    block only sends when the build is unstable for another reason (failed tests), i.e.
    `if (env.SKILLTEST_TREND_ALERTS != 'true' || currentBuild has test failures)` — simplest form:
    skip the Teams `unstable` post when the trend-alert flag is set and the junit step reported no
    failures. Controlled by a pipeline value `SKILLTEST_ALERT_TEAMS` (default `false`); setting it to
    `true` later re-enables Teams for trend alerts with no other change.
  - The `failure` and `fixed` Teams notifications are unchanged. Note: `fixed` (back to normal)
    may still post when a build goes from trend-alert UNSTABLE back to SUCCESS; accepted, or guard it
    the same way if it turns out noisy (implementer: guard it too if it is a one-line change).
  - Send an email with `emailext`: subject `[skilltest] <count> trend alert(s) - build #<n>`,
    HTML body listing the alerts (baseline, rule, message, likely cause) plus links to the build,
    `trend.html` artifact and the latest-report link (`<JOB_URL>lastSuccessfulBuild/artifact/...`, Jenkins login required). `mimeType: 'text/html'`.
  - If `emailext` is not available on the controller, fall back to the core `mail` step (plain text).
- **Recipients are configurable, never hardcoded in code**: a pipeline `environment` /
  `parameters` value `SKILLTEST_ALERT_EMAIL`, default `emera@servoy.com`. It is a
  **comma-separated list**, so more recipients can be added later by editing that one value (or
  setting the job parameter) — no code change. Empty value → no email (UNSTABLE still applies).
- This email is **only** for trend alerts. The existing build-failure / unstable / fixed Teams
  notifications are unchanged; no email is sent for ordinary build failures.
- If `alerts.json` is missing (runner crashed before writing it), do nothing alert-related; the
  existing junit/failure handling already covers that case.

### 3.7 Non-functional
- Best-effort everywhere: no history failure changes a PASS/FAIL, the exit code, or the view's
  ability to open.
- Backward compatible: `last-results.json`, `run-*.md`, `TEST-skilltest.xml` and `coverage.html`
  are unchanged. History is purely additive.
- Performance: append is O(run). The read is a full linear scan, which is fine for unbounded but small
  files. If the file ever grows big, tail-reading is a later optimization (out of scope).

## 4. Implementation plan
1. `SkillTestPaths`: add `HISTORY_FILE` constant, `historyFile()`, and a testable
   `resolveHistoryFile(String sysProp, String env, File reportsRoot)`. Update the class Javadoc layout
   diagram with `run-history.jsonl`.
2. New `RunHistory` class (bundle `com.servoy.eclipse.opencode.skilltest`, same package):
   `RunIdentity`, `Entry`, `Trend`, `toJsonLine`, `append`, `read`, `trends`, `DEFAULT_TREND_RUNS`,
   plus a pure `historyHtml(List<Entry>, String baselineId, int lastN)` (or put it in `SkillTestHtml`).
   Use `//$NON-NLS-1$` markers like the surrounding code.
3. `SkillTestRunner.resolveModel`: widen to package/public static (behaviour unchanged).
4. `SkillTestView`: append the history after the run, load/reload the trend map, add the "Trend" column, and add a
   history section in the detail panel.
5. `SkillTestHeadlessRunner.runSkillTests`: append the history after the final report and coverage, and do the
   best-effort copy into `outputDir`. The exit code is untouched.
6. `tests/com.servoy.eclipse.opencode.skilltest.tests/pom.xml`: property defaults + `argLine` entries.
7. `Jenkinsfile.skilltest`: `disableConcurrentBuilds()`, `mkdir -p $JENKINS_HOME/skilltest-history`,
   the two `-D` flags, and the archive glob entry.
6a. Shared `skilltest-report.css` + `ReportCss` helper; convert `CoverageReportHtml` from
    `style=` attributes to classes + external stylesheet, update `CoverageReportHtmlTest` (3.6b-2).
7a. New `TrendAlerts` (+ `AlertConfig`) and `TrendReportHtml` classes (3.6a/3.6b); wire them into
    `SkillTestHeadlessRunner` after the append (3.6c), exit code untouched.
7b. `Jenkinsfile.skilltest`: archive `trend.html`/`alerts.json` (no public publish), read `alerts.json`, `unstable(...)` + `emailext`
    (fallback `mail`) to `SKILLTEST_ALERT_EMAIL` (default `emera@servoy.com`, comma-separated) (3.6d).
8. Plain-JUnit tests in `tests/com.servoy.eclipse.opencode.skilltest.tests` (same package layout as the
   existing `RunMetricsTest` / `SkillTestReporterMetricsTest`, Jupiter, `@TempDir`). Register them in
   `AllSkillTestUnitTests` and in any explicit surefire include list in that pom, if present:
   - `RunHistoryTest`: line shape/required fields; unknown fields omitted (no `null`); one line per
     result with a shared runId; no prompt/diff/tool output/arguments/key in the line, even when the
     result carries them; append twice → both runs present in order; append to a file whose last line
     is truncated (no trailing `\n`) → the fragment is skipped and the new lines parse; corrupt middle line
     skipped; missing file → empty; unwritable target (a directory as the file path) → `append`
     returns false and does not throw; null/empty results → no-op.
   - `RunHistoryTrendTest`: pass rate over the last N (N cut-off honoured, older ignored); SKIPPED
     excluded; ERROR counted as non-pass; sparkline order oldest→newest; no history → `"—"`; token
     avg/last when known; multiple baselines kept separate.
   - `RunIdentity` tests via the injected env lookup: `ci()` with `BUILD_NUMBER`/`JOB_NAME`/`GIT_COMMIT` and
     without; `ide()` has no build fields; runId format.
   - `SkillTestPaths.resolveHistoryFile`: property beats env beats default; blank treated as unset.
   - History HTML: escapes values; newest first; limited to N.
   - Trend window: `parseTrendRuns` accepts 1..1000, rejects blank/non-numeric/0/negative/>1000;
     `clampTrendRuns` clamps; `trends` honours a non-default N.
   - `TrendAlertsTest`: each rule fires at its default threshold and not just below it
     (REGRESSION after 3 passes vs 2; PASS_RATE_DROP at 20 vs 19 points, only with two full windows;
     FLAKY at 3 vs 2 flips); COST_RISE per metric (tokens, toolCalls, credits separately):
     over a declared `max` fires; over `baselineValue` + the budget's own `tolerancePct` fires and
     the per-baseline tolerance beats the default; `baselineValue` without `tolerancePct` uses 30%
     (fires at 31%, not at 29%); no budget → median fallback at 30% vs 29%, needs ≥ 3 prior values;
     credits judged on rounded `estCredits`, not `creditsDelta`; absent metric skipped; the
     default-percent override applies only where no per-baseline tolerance is set;
     `BudgetEvaluator` output unchanged (existing tests stay green); SKIPPED ignored; baselines not in the current run never alert; likely cause filled
     on model/skillsVersion/pluginVersion change and absent otherwise; overrides applied; invalid
     override → default; `alerts.json` shape incl. `count:0` when clean; unwritable path → false, no throw.
   - `TrendReportHtmlTest`: CSP-safe output — no `<script`, no ` on[a-z]+=` attributes, no
     `javascript:`, no `<style`, no `style=` anywhere (incl. inside SVG), exactly one relative
     `<link rel="stylesheet" href="skilltest-report.css">`, no `http(s)://` resource references;
     `writeReport` writes both `trend.html` and a non-empty `skilltest-report.css` (resource found on the
     class path);
     values escaped; alerts banner present/absent; one section per baseline; < 2 runs → note
     instead of charts; change marker emitted on model change; chart helpers scale points correctly.
9. Compile loop (`eclipse-ide_getCompilationErrors`) and run the skilltest plain unit tests.
10. Update `AGENTS.md` / project-context test notes only if they enumerate the skilltest test classes
    (they currently don't list them, so likely no change).

## 5. Acceptance criteria
- [ ] Running baselines from the Skill Test view appends one JSON line per baseline to
      `<workspace>/servoy_ai_skilltests/reports/run-history.jsonl`, and earlier lines are preserved.
- [ ] The headless CI runner appends to the file named by `-Dservoy.skilltest.historyFile` (or
      env `SERVOY_SKILLTEST_HISTORY_FILE`, or the local default) using the same writer and format, with
      `source:"ci"` and `buildNumber` when `BUILD_NUMBER` is set.
- [ ] Each line contains run identity (timestamp, source, runId, and when available build number,
      job, commit, plugin version, skills version, agent, model), status, attempts/trials and the
      compact metrics. Unknown values are omitted, not written as null/0.
- [ ] No prompt text, diffs, tool arguments/outputs, or credentials appear in the file (covered by a test).
- [ ] A history write failure (unwritable path, IO error) is logged and never changes the run
      outcome, the CI exit code, or the view's results.
- [ ] The reader skips a partially written or corrupt line (last or middle) and still returns all
      valid entries. A new append after a truncated line starts on a fresh line.
- [ ] The trend window is configurable from the view menu (default 10, range 1..1000, invalid input
      rejected), persisted across IDE restarts, and changing it immediately updates the Trend column
      and detail panel.
- [ ] The Skill Test view shows a "Trend" column with the pass rate over the last N (default 10) runs per baseline
      (for example `80% (4/5) ✓✓✗✓✓`). It survives an IDE restart, comes from local history only, and
      updates after a run.
- [ ] The detail panel shows the selected baseline's recent history, including tokens and credits Δ.
- [ ] `Jenkinsfile.skilltest` uses `disableConcurrentBuilds()`, writes to
      `$JENKINS_HOME/skilltest-history/run-history.jsonl`, and archives a per-build copy as
      `skilltest-reports/run-history.jsonl`. The history accumulates across nightly builds.
- [ ] Each CI run writes `trend.html` + `skilltest-report.css` (inline SVG pass-rate and tokens/credits charts
      per baseline, alerts banner) and `alerts.json` to the output dir. The page has **no JavaScript
      and no inline CSS** (no `<style>`, no `style=`), styling only via the relative external
      `skilltest-report.css`, so it renders styled under Jenkins' CSP. All are archived as build artifacts only (Jenkins login required); nothing is published to the public `/latest/` site.
- [ ] The four alert rules (REGRESSION, PASS_RATE_DROP, FLAKY, COST_RISE) fire at their default
      thresholds (3 runs / 20 points / 3 flips / 30%; COST_RISE judged against each baseline's
      declared budget, 30% tolerance when none is set, median fallback when the baseline has no
      budget for that metric), overridable via `-D`, only for baselines in the
      current run, and name a likely cause when model/skills/plugin version changed.
- [ ] When `alerts.json` has `count > 0`, the build is marked UNSTABLE, **no Teams message is sent for
      the trend alerts** (unless `SKILLTEST_ALERT_TEAMS=true`; test-failure UNSTABLE still posts as today), and an HTML alert email is sent to `SKILLTEST_ALERT_EMAIL` (default `emera@servoy.com`,
      comma-separated list supported). No alert → no email, build result unchanged.
- [ ] Alert evaluation / report failures never change the runner's exit code.
- [ ] `coverage.html` renders styled in the Jenkins artifact view: it has no `style=`, `<style>` or
      script, links the shared relative `skilltest-report.css`, which is written next to it (CI and
      in-IDE) and archived; its content, sections and file names are unchanged.
- [ ] `last-results.json`, `run-*.md` and `TEST-skilltest.xml` output is unchanged.
- [ ] The new logic is covered by plain-JUnit tests (no workbench), and the workspace compiles with zero errors.

## 6. Out of scope
- Showing or downloading CI history in the IDE view.
- Copy Artifact chaining, or committing history to `servoy-ai-skilltest-baselines`.
- Retention caps, rotation or compaction (unbounded by decision).
- Charts or graphics in the IDE view beyond the text sparkline/label and the history table
  (charts are in the CI `trend.html` only).
- Alert evaluation, report generation or email from the IDE view (CI only).
- Email for ordinary build failures (Teams already covers those).
- A database / Grafana dashboard (decided: file-based, Option A).
- Backing up `$JENKINS_HOME/skilltest-history` (an infra concern).
- History lines for runs killed before completion.

## 7. Open questions
| Question | Owner | Status |
|----------|-------|--------|
| Is `/data` writable by the Jenkins user on every agent that can pick up this job (`agent any`)? If the job can land on different agents, history splits per agent. Pin the job to a label if needed. | Infra / DevOps | open |
| Should `skillsVersion` on CI be the skill4servoy short commit (assumed), or a version from the skills zip? | PM / Tester | open (assumed commit) |
| Is a default trend window of 10 runs right, or should it be a view preference? | PM | resolved: view preference, default 10 (3.5a) |
| Should a cancelled IDE run (Stop) still append the baselines it finished? Assumed **yes**, the completed results are real. | PM | open (assumed yes) |
| Exact CSP header on build.servoy.com artifacts | Infra / Tester | resolved: read from `copilot_skilltest` #89 `coverage.html`: `sandbox allow-same-origin; default-src 'none'; img-src 'self'; style-src 'self'; media-src 'self';` (enforced; a looser Report-Only header is also sent, non-blocking) |
| Is local history lost on IDE restart? | PM | resolved: no — per-workspace file in `reports/`, nothing in the bundle deletes it; lost only with the workspace itself, `git clean -fdx`, or in throwaway PDE-test workspaces. `-Dservoy.skilltest.historyFile` can point elsewhere. |
| `coverage.html` renders unstyled on Jenkins | PM | resolved: confirmed by user, fixed in this case via the shared external stylesheet (3.6b-2) |
| Are the `emailext` (Email Extension) and Pipeline Utility Steps (`readJSON`) plugins installed on build.servoy.com? Spec has fallbacks (`mail`, `sh` grep). | Infra / DevOps | open |
| Storage / reporting approach | PM | resolved: file-based JSONL + CI-generated report + alerts (Option A), no DB |
| Alert channel and recipients | PM | resolved: email + UNSTABLE, **no Teams for trend alerts until tested** (`SKILLTEST_ALERT_TEAMS=false`); `SKILLTEST_ALERT_EMAIL`, default `emera@servoy.com`, comma-separated for more later |
| Alert rules / thresholds | PM | resolved: defaults (3 runs / 20 points / 3 flips), `-D` overridable; COST_RISE uses each baseline's budget, 30% default tolerance |
| COST_RISE for a baseline with **no** budget for a metric: median-of-history fallback (assumed) or no cost alert at all? | PM | resolved: median fallback at 30% |
