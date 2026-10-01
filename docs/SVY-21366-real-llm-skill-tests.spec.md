# Spec: SVY-21366 — Automate Servoy AI skills testing (session-export golden regression)

> **Update (2026-09, post-implementation):** several early design choices changed once the
> feature was exercised against the real embedded server. The material changes, each detailed in
> the sections below, are:
> - **Separate internal-only plugin.** The runner/view no longer live *inside*
>   `com.servoy.eclipse.opencode`. They live in a new bundle
>   `com.servoy.eclipse.opencode.skilltest` (+ test fragment
>   `com.servoy.eclipse.opencode.skilltest.tests`), shipped through its own internal feature
>   `com.servoy.eclipse.opencode.skilltest.feature`. It is **deliberately excluded from the
>   customer feature** (`com.servoy.eclipse.servoypilot.feature`), so customers who install Servoy
>   AI Copilot never receive it; only employees/CI install the internal feature. See §2.6/§3.1.
> - **Async prompt + poll, no per-attempt timeout.** A blocking `POST …/message` cannot wait out
>   an Orchestrator baseline that spawns child sub-agent sessions, and the JDK HTTP client's
>   default HTTP/2 stalls the opencode server. The replay now uses `POST …/prompt_async` +
>   polling `GET /session/status` until idle, over an **HTTP/1.1**-pinned client, with **no
>   timeout** budget and a cooperative **Stop**. See §2.5/§3.3.
> - **Agent/model taken from the golden export.** Replays reuse the recorded `info.agent` /
>   `info.model` (e.g. `Orchestrator`, `kiro/claude-sonnet-5`) rather than server defaults. See §3.3.
> - **Record-baseline removed from this plugin.** Exporting/recording a session is the opencode
>   view's responsibility; the skill-test side only imports/hand-authors baselines and runs them.
>   See §3.7/§3.8.
> - **Workspace setup rethought.** The git-based per-attempt reset (`git checkout`/delete) is
>   abandoned. The model is a **clean workspace into which the fixture solution is materialized** as
>   replay setup (natural on Jenkins). See §3.6/§7.
> - **Setup now supports three sources — `git`, `folder`, and `empty` — implemented headless.**
>   Before each baseline the runner **cleans** the baseline's solution project (deletes the project
>   *and* its on-disk content) and then materializes the declared initial state: `git` clones a repo
>   ref and imports its projects; `folder` imports projects from a local folder; **`empty` creates a
>   fresh empty solution via the MCP `servoy-dev/createSolution` tool** (activated). See §3.6/§3.11.
> - **The headless runner CANNOT be a pure `-application`: MCP requires the Eclipse workbench.**
>   This reverses the §3.10 "dedicated headless `-application`, OSGi-context MCP fallback" decision.
>   Empirically (MCP probe, 2026-09), a bare `-application` does **not** start a workbench, and with
>   no workbench the MCP tool servlets **never register** (`/dev_mcp/servoy-*` → HTTP 404). MCP only
>   comes up when the runner is launched as the **product/workbench** (`-product
>   com.servoy.eclipse.core.ide`), with the run triggered from an `org.eclipse.ui.startup`
>   (`SkillTestStartup`, gated by `-Dservoy.skilltest.run=true`) once the workbench — and therefore
>   core + app server + MCP — is up. Because the skill test genuinely needs the Servoy MCP tools,
>   this is mandatory, and it forces a **display**: Linux CI must run under **Xvfb**; local Windows
>   Jenkins cannot use the session-0 `LocalSystem` service (no window station) and needs an
>   **interactive-session agent**. See §3.10 (rewritten) and §3.12.

## 1. Goal
Give the Servoy AI team an automated, repeatable way to verify that the Servoy AI
**skills and prompt wiring** still produce the correct behaviour after a change. The core
idea: a recorded **opencode session export** is the *golden fixture* (baseline). A test takes
the original prompt from that export, re-runs it against the **current** skills through the
existing embedded opencode / "Servoy AI" server (real LLM), exports the new session, and
asserts the run **produces the same result** — i.e. the same MCP tool calls with equivalent
arguments as the baseline export. Non-deterministic model text is never compared; only the MCP
tool-call outcome is. A small number of retries per baseline absorbs model variability. The
library of baselines starts small and grows over time; baselines can be activated, deactivated,
updated, and re-recorded.

## 2. Background

### 2.1 Ticket history and the abandoned record/replay approach
`SVY-21366` is the follow-up to the research spike `SVY-21245`, which produced an uncommitted
record/replay mechanism that captured the LLM↔opencode event stream and replayed it
positionally. After team discussion (Vid Marian, 2026-08-24) that approach was rejected: pure
replay only exercises tool wiring, not whether the LLM correctly interprets prompts and skills.
The agreed replacement — bounding this spec — is a **real-LLM test that runs inside Developer**.
This spec keeps the real LLM in the loop: the export is used as the *baseline to compare against*
and as the *source of the prompt to replay*, but the actual run each time goes through the live
current skills. Rene van Veen's comment describes the manual workflow this automates: opencode's
`/export` yields a full session JSON/Markdown including MCP calls with args and outputs; he
extracts the same data manually from `opencode.db` and, after ~three runs, judges the output
"good". The related ticket `SVY-21374` ("export function in servoy voor opencode session(s)")
is **Closed**, confirming a native export path is available.

### 2.2 The mental model: a golden-file / snapshot regression test
This is a snapshot test where **the snapshot is an opencode session export** and the comparison
is over MCP tool calls, not text:

- **Record once:** run a prompt against a known-good version of the skills, `opencode export`
  the session, review it, and commit it as the *baseline* (golden) for that scenario.
- **Test many times:** re-send the baseline's prompt to the current skills, export the new
  session, and check the new run's MCP tool calls match the baseline's. "Pass" = *same result*.
- **Refresh when intended:** if a skill change deliberately changes behaviour, re-record the
  baseline (a reviewed, explicit action) rather than hand-editing expectations.

The regression signal is exactly what the team wants: an *unintended* skill/prompt change that
alters tool-call behaviour makes a current run diverge from its baseline and fails the test.

### 2.3 The JSUnit runner as the design template
Servoy already has a proven "launch it, wait for it, collect + format results" pattern in
`JSUnitRunnerService`
(`bundles/com.servoy.eclipse.developer.mcp/.../services/JSUnitRunnerService.java`). This runner
is modelled on it, one layer up (LLM instead of SmartClient) and with a golden comparison:

| JSUnit runner concept | Skill-test runner analogue |
|---|---|
| `TestTarget` (solution / module / form) | `Baseline` — a stored session export (prompt + recorded tool calls) |
| `RunJSUnitHandler` launch → `ILaunch` | `POST /session` + `POST /session/:id/message` on the embedded server |
| DLTK `ITestRunSession` populated with children | new session transcript from `opencode export` |
| `waitForSessionByLaunch(...)` polling | wait for message response / poll session, per-baseline timeout |
| assertions embedded in the JS test | comparison of new transcript vs the baseline export |
| `ITestCaseElement` PASS/FAIL/ERROR + `FailureTrace` | per-baseline result + a diff of baseline vs actual tool calls |
| `formatResults(...)` | `formatResults(...)` → Markdown to the view / console |
| launch in `RUN_MODE`, terminate in `finally` | create session, `DELETE /session/:id` (abort on timeout) in `finally` |

Differences JSUnit does not have: **non-determinism** (retries, §3.5) and **baselines as
data/fixtures** rather than assertions in code (§3.6), plus a **baseline capture** mode (§3.7).

### 2.4 How the embedded opencode server is launched today
The `com.servoy.eclipse.opencode` bundle already owns the embedded server lifecycle; the runner
reuses it rather than building a new LLM driver:

- `OpencodeFolderCreatorJob` installs/updates `opencode-ai`, extracts the skills zip into
  `~/.servoy/opencode/`, merges MCP + provider config into `opencode.json`, then schedules
  `RunOpencodeCommand`.
- `RunOpencodeCommand` finds a free port from `4096` (`findFreePort`) and launches
  `npm exec -- opencode serve --port <port> --hostname 127.0.0.1`
  (`RunOpencodeCommand.java:108`), injecting the Servoy XDG env (`buildServoyXdgEnv`), `PWD`,
  `GENAI_API_KEY`, and MCP env. A daemon watchdog polls `http://127.0.0.1:<port>/` and calls
  `Activator.serverStarted(port)`.
- `Activator` exposes `ensureServerStarting()`, `waitForServer(long timeoutMs)`,
  `getServerPort()`, `isServerReady()` (via `OpencodeServerState`), and `stopServer()`. Setup
  requires both `GENAI_API_KEY` and `SERVOY_SKILLS_ZIP` system properties.
- `OpenCodeView` already calls `ensureServerStarting()` and, via `startUrlSwitcherThread()`,
  `waitForServer(120_000)` + `getServerPort()` before pointing its browser at the server. The
  runner reuses exactly this handshake.

### 2.5 opencode server HTTP API and native export
`opencode serve` exposes an OpenAPI 3.1 server (spec at `/doc`). The embedded server was upgraded
to **opencode 2.x** (`@opencode/cli ~2.0.x`), which reshaped the API; `OpencodeHttpClient` targets
the 2.x surface:

- All routes are under an **`/api`** prefix, and responses are wrapped in a **`{ "data": … }`**
  envelope that the client unwraps.
- The `/api` routes require **basic auth**: opencode 2.x secures the server with
  `OPENCODE_SERVER_PASSWORD` (generated per launch). The client reads it from
  `com.servoy.eclipse.opencode.Activator.getServerPassword()` and sends
  `Authorization: Basic opencode:<password>` on every request (the scheme the Angular BFF uses);
  with no password set it sends no header.
- `POST /api/session` → session (`id` under `data`).
- `POST /api/session/:id/prompt` (body `{ text, delivery, agent?, model?, … }`) — a single
  top-level `text` field (not a `parts` array), and **`delivery` is `"queue"`**
  (`Session.Inbox.Delivery` = `["steer","queue"]`), which queues the turn and returns without
  holding the connection for the whole run.
- **Completion is detected with `POST /api/experimental/session/:id/wait`** (blocks server-side,
  `204` when idle). opencode 2.x has no `time.completed` field and no `/session/status` map, so the
  old poll-until-idle approach cannot work; the client calls `wait` in short (15 s) loops, checking
  the cancel supplier and the stall guard between calls (`503` = still busy → resets the guard).
- Export is `GET /api/experimental/session/:id/export`; messages
  `GET /api/session/:id/message`; interrupt `POST /api/session/:id/interrupt` (was `/abort`).
- `model`, when sent, is still an object `{ "providerID", "modelID" }`; the client splits a
  `"provider/model"` spec and omits it when unset.

**Transport facts that still hold:** the JDK `HttpClient` must be pinned to **HTTP/1.1** (its
default HTTP/2 upgrade stalls the server on a POST-with-body until timeout; HTTP/1.1 returns in
ms), and the server binds `127.0.0.1` only.

**Transcript parsing follows the 2.x message shape.** A v2 message is
`{ id, type, text?, content:[…] }` — tool calls live in `content` as
`{ type:"tool", name, state:{ status, input, content:[{type:"text",text}] } }`, not in a `parts`
array. `SessionTranscript` reads `content` (falling back to `parts` for pre-2.x), takes the user
prompt from the top-level `text`, maps the tool `name`, and flattens the tool state's `content[]`
text blocks into one output string — mirroring the webui's `mapV2Message`/`normalizeToolState`,
the reference for how OpenChamber itself exports a session. Tool calls come from the export JSON
(primary) with the live message list as a fallback. Known upstream quirk (opencode #12130): the
export may prefix a status line to stdout, producing invalid JSON — the parser strips a leading
non-JSON line.

**OpenChamber's own MCP client (2.x) vs. the Eclipse MCP server.** OpenChamber 2.0.x bundles
OpenCode 2.0.18, whose MCP client sends a newer `capabilities.elicitation.form.applyDefaults` field
on `initialize`. The Eclipse AssistAI HTTP MCP server uses an older MCP Java SDK that rejects
unknown fields (`-32600 Invalid message format`), so after an OpenChamber auto-update the
`eclipse-*` MCP servers can fail to connect until the server-side SDK is bumped (or set to ignore
unknown properties). This affects the IDE's tool connectivity, not the skill-test runner's HTTP
client.

**Interactive baselines cannot replay unattended.** If the recorded session contains a `question`
(or permission) tool call the headless replay blocks on it forever; such a baseline must be
re-recorded with a fully-specified prompt. The poll loop's cooperative Stop (§3.3) and the stall
guard (§3.5) are the escapes when one slips through.

**Interactive baselines cannot replay unattended.** If the recorded session contains a `question`
(or permission) tool call — the agent paused to ask the user — a headless replay will block on it
forever. Such a baseline must be re-recorded with a fully-specified prompt so the agent never
asks; the poll loop's cooperative Stop (§3.3) is the escape hatch when one slips through.

### 2.6 Existing test infrastructure and where the runner code belongs
- `com.servoy.eclipse.opencode.tests` — a **fragment** of `com.servoy.eclipse.opencode` (plain
  JUnit today; shares the host bundle's package/classpath, so it can call package-visible host
  lifecycle like `Activator.ensureServerStarting()`).
- `com.servoy.eclipse.developer.mcp.tests` — a fragment of `developer.mcp` that already runs
  **PDE integration tests inside a Developer workbench**; `AbstractIntegrationTest` activates a
  Servoy solution and disables node/titanium build via
  `Activator.setNodeExtractionAndTitaniumBuildDisabled(true)`. This is the template for a
  workbench test.

**Decision (§3.1) — revised.** The runner engine and view were *initially* placed inside
`com.servoy.eclipse.opencode`, but that bundle **ships to customers** (it is listed in
`com.servoy.eclipse.servoypilot.feature`). Skill testing is an **internal-only** tool for a few
employees and Jenkins, so it was moved into its own bundle **`com.servoy.eclipse.opencode.skilltest`**
with an internal-only feature; the customer feature is left untouched. The bundle depends on
`com.servoy.eclipse.opencode` (`Require-Bundle`) for the server lifecycle and the export helper.
The **JUnit integration test** lives in the fragment
**`com.servoy.eclipse.opencode.skilltest.tests`** (Fragment-Host = the skilltest bundle) and runs
via the PDE plug-in test launcher. See §3.1 for the full module layout and the customer-visibility
guarantee.

## 3. Design

### 3.1 Module layout: a separate internal-only plugin (kept out of the customer feature)
The runner, view, and tests live in a **dedicated internal bundle**, not inside the shipped
`com.servoy.eclipse.opencode`. Customer visibility is controlled purely by **feature membership**:
the customer feature `com.servoy.eclipse.servoypilot.feature` lists only
`com.servoy.eclipse.developer.mcp` and `com.servoy.eclipse.opencode`; because the skill-test
bundle is **not** in that feature, a customer who installs Servoy AI Copilot never receives it. A
separate **internal** feature publishes it to employees/Jenkins.

Modules (all in the `Servoy-Copilot` repo, wired as reactor `<module>`s in the root pom):

- **`bundles/com.servoy.eclipse.opencode.skilltest`** — the internal plugin. `Require-Bundle:
  com.servoy.eclipse.opencode` (server lifecycle via `Activator`; export via
  `RunOpencodeCommand.exportSession`). Package `…​.skilltest`:
  - `SkillTestRunner` — the `JSUnitRunnerService` analogue: replays a `Baseline` prompt, polls to
    completion, exports, compares with retries, returns a `SkillTestResult`. API:
    `runBaseline(Baseline[, cancelled])`, `runAll(List<Baseline>[, cancelled])`,
    `formatResults(...)`. (No `recordBaseline` — see §3.7.)
  - `OpencodeHttpClient` — thin `java.net.http.HttpClient` (pinned **HTTP/1.1**) + Jackson wrapper:
    `createSession`, `promptAsync(sessionId, prompt, agent, model)`,
    `getStatus(sessionId)`, `waitForCompletion(sessionId, pollInterval[, cancelled])`,
    `getMessages(sessionId)`, `abort`, `deleteSession`.
  - `SessionExporter` — delegates to `RunOpencodeCommand.exportSession(dir, sessionId)` in the
    opencode bundle (which owns the `ngclient.ui` npm-command dependency), strips the leading
    non-JSON line, parses into `SessionTranscript`. The skilltest bundle does **not** depend on
    `ngclient.ui` itself.
  - `SessionTranscript` / `McpToolCall`; also parses `info.agent`, `info.model`, `info.directory`.
  - `Baseline` (+ sidecar, §3.6); `BaselineLoader`; `TranscriptComparator` (§3.4); `SkillTestResult`.
  - `SkillTestView`, `OpenSkillTestViewHandler`, `SkillTestPaths`, `SkillTestReporter`,
    `SkillTestHtml`.
  - `plugin.xml` registers the view **and contributes the "open skill tests" toolbar button back
    into the Servoy AI view** via a `menuContribution` (so the shipped `OpenCodeView` holds **no**
    reference to skilltest — avoiding a dependency cycle and a dead button for customers).
- **`tests/com.servoy.eclipse.opencode.skilltest.tests`** — `Fragment-Host` = the skilltest
  bundle; the `@TestFactory` driver + moved unit/scenario tests + committed seed baselines.
- **`features/com.servoy.eclipse.opencode.skilltest.feature`** — the internal feature listing the
  skilltest bundle; published to an internal update site only.

> **On the shared opencode bundle:** the only code added there is
> `RunOpencodeCommand.exportSession(File, String)` (the `opencode export` invocation moved down so
> the internal bundle need not depend on `ngclient.ui`). This is a pragmatic seam and may move
> again if export later becomes a service or an HTTP-API call.

The engine sits in a bundle (not only the fragment) because the same runner is reused by the view
in §3.8 — the JSUnit shared-runner pattern, now in an internal bundle.

### 3.2 Opt-in gating (keep normal builds green and free)
Slow, token-costing, non-deterministic → **opt-in and disabled by default**. The JUnit factory
runs baselines only when a guard property is present (e.g. `-Dservoy.ai.skilltests=true`) **and**
both `GENAI_API_KEY` and `SERVOY_SKILLS_ZIP` are configured; otherwise every dynamic test is
skipped via `Assumptions.assumeTrue(...)`, keeping normal builds green and free. The in-view
runner (§3.8) is manual and needs no guard.

### 3.3 Test lifecycle, async replay, and server reuse
`AbstractSkillScenarioTest`:
1. `@BeforeAll`: assume guard + required properties; activate a Servoy test solution; ensure the
   embedded server is ready via `Activator.getInstance().ensureServerStarting()` →
   `waitForServer(timeoutMs)` → `getServerPort()`.
2. Per baseline (`SkillTestRunner.runBaseline`):
   - **Setup**: bring the workspace to the golden's starting state (import the fixture solution(s)
     into a clean workspace — §3.6).
   - **Resolve agent/model**: use the baseline sidecar values if set, otherwise fall back to the
     `info.agent` / `info.model` recorded **in the golden export** (e.g. `Orchestrator`,
     `kiro/claude-sonnet-5`). Replaying with the server default silently diverges, so this
     fallback is essential.
   - Create a fresh session; **fire the prompt asynchronously** (`POST /session/:id/prompt_async`)
     — never the blocking `…/message`.
   - **Poll `GET /session/status` until idle** (`waitForCompletion`, ~2 s interval). There is
     **no per-attempt timeout**: an Orchestrator run legitimately takes minutes; a fixed budget was
     the original source of spurious "timed out" failures. A short startup-grace avoids a false
     "idle" before the run is seen as `busy`.
   - Export the new session (`opencode export`); fall back to the `GET …/message` list.
   - Compare new tool calls to the baseline's (§3.4). Retry on mismatch (§3.5).
   - `finally`: `DELETE /session/:id`.
3. `@AfterAll`: leave a shared server running for reuse (only stop one the base class started).

**Cooperative Stop.** Because there is no timeout, a run is stoppable. `runAll`/`runBaseline`/
`waitForCompletion` take a `BooleanSupplier cancelled`; the view's **Stop** toolbar button calls
`job.cancel()`, whose monitor is the cancel supplier. On cancel the poll loop **aborts the
in-flight opencode session** and halts within one poll interval; remaining baselines are skipped;
partial results are still reported.

> **Update (2026-09, outcome-authoritative verification):** "same result" is now judged on the
> **real Servoy persists** the run produced, not on the transcript, whenever a baseline declares
> expected outcomes. This was driven by a concrete false failure: a `create customerDetail form`
> baseline produced the *identical* outcome (a CSS-position form bound to `db:/example_data/customers`
> with fields on `companyname`/`contactname`) but was marked FAIL because the transcript comparison
> penalised the model for (a) rephrasing the `task` sub-agent **prompt** — free-text prose — and (b)
> taking a slightly different tool path (an extra `getTableInfo`/`getFileInfo`). Two changes address
> this:
> - **Prose arguments are never compared.** `TranscriptComparator` now ignores free-text argument
>   names (`prompt`, `description`, `message`, `title`, `text`, `content`, …) unless a baseline pins
>   one via `argOverrides`. This is the stopgap that stops equivalent runs failing on rephrasing.
> - **Outcome assertions are authoritative (`expect.persists`).** A baseline may declare the Servoy
>   persists it must produce; when present, the runner verifies them against the **active solution's
>   in-memory persist model** (`PersistOutcomeChecker` over `Solution`/`IPersist`) and **skips the
>   transcript comparison entirely** — precedence resolved as "outcome-authoritative when
>   `expect.persists` present". This is immune to tool reordering, extra exploration calls, sub-agent
>   delegation, and prose, because it asks *"does the right persist exist with the right properties?"*
>   not *"were the right tools called?"*. See §3.4a.

### 3.4 Comparison model — "same result" as MCP tool-call equivalence (transcript fallback)
When a baseline declares **no** expected outcomes, verification falls back to comparing the MCP tool
calls in the two transcripts (baseline vs current run), tolerant of model variation:

- **Tool set match**: every tool the baseline called (by `server` + `tool` name) must appear in
  the current run at least as often. Order is **not** compared by default (models legitimately
  reorder); a baseline may opt into ordered comparison.
- **Argument equivalence**: for each baseline call, its arguments must match the corresponding
  current call. Default matching is *tolerant*: string compare is case-insensitive and
  whitespace-normalised; list arguments compare as sets (order-independent); arguments the
  baseline did not constrain are ignored. A baseline may mark specific args for **exact** compare
  or supply a matcher (`equals`/`contains`/`containsAll`/`regex`/`present`) via its sidecar
  (§3.6) to override tolerance where precision matters.
- **No unexpected destructive calls**: by default, extra tool calls in the current run do not
  fail it (the model "sometimes does more", per Rene), but a baseline may declare a
  `forbidden` list (e.g. `delete_form`) or set `strict:true` to fail on any tool not in the
  baseline.
- Prose, reasoning, durations, and subagent structure are never compared.

`TranscriptComparator` returns a structured diff; the formatter renders baseline-vs-actual tool
calls and the offending arguments (the `formatSingleMethodResult` analogue), using the
best/last attempt. **Free-text/prose arguments (`prompt`, `description`, `message`, `title`,
`text`, `content`, `reason`, `summary`, `instructions`, `comment`, `explanation`) are ignored by
default** and only compared when a baseline names them in `argOverrides`.

### 3.4a Outcome model — "same result" as real Servoy persists (authoritative)
The primary, authoritative definition of "same result" is over the **real in-memory Servoy persist
tree** of the active solution after the run, not over the transcript. A baseline declares expected
outcomes in an `expect.persists` block; **when present, the runner uses ONLY this check and skips the
transcript comparison entirely** (§3.3, precedence: outcome-authoritative when `expect.persists`
present). This is what the `create-customerdetail-form-with-data-bindings` baseline uses today, and
it is why an equivalent run that reorders tools, delegates to a sub-agent, explores with extra
read-only calls, or rephrases a sub-agent prompt still passes: the check asks *"does the right persist
exist with the right properties?"*, not *"were the right tools called?"*.

**Where the check runs in the lifecycle.** After a replay attempt goes idle (`SkillTestRunner`), if
`baseline.hasExpectedOutcomes()` the runner resolves the **active** `ServoyProject` from the Servoy
model, takes its **editing `Solution`** (`getActiveProject().getEditingSolution()`), logs
`verifying outcomes against active solution '<name>'`, and hands it to `PersistOutcomeChecker`. The
run therefore reads the same in-memory model the IDE edits — no `.frm`/`.val` file parsing, no
workspace re-read. A missing active solution/editing solution is itself a failure (a run that
produced nothing to check).

- **`OutcomeAssertion`** (record) — a persist that must **exist** (default) or must **NOT** exist
  (`exists:false`), identified by `kind` (a `PersistKind` mapped to an `IRepositoryConstants` type id;
  `OTHER` matches any kind), an optional `name` (case-insensitive; `null` = match purely by
  properties, used for child components identified by e.g. `dataProviderID`), an optional `scope`
  (best-effort, compared against a `scopeName` property for globals), a map of property matchers
  (`props`), and nested `children` matched recursively against the parent's own children.
- **`PersistKind`** — the Servoy-characteristic surface (form, valuelist, relation, menu, media,
  scriptmethod, scriptvariable, calculation, aggregate, component, field, graphicalcomponent,
  layoutcontainer, …) resolved to type ids, so a match is `persist.getTypeID() == kind.typeId()`.
- **`PersistOutcomeChecker`** — walks the solution generically. Root persists are
  `childrenOf(solution)`; each assertion is matched in the current candidate pool (root pool at the
  top level, the matched parent's children when recursing) by kind → name/scope → (for name-less
  assertions) property matchers. It never needs a per-type checker: children come from
  `ISupportChilds.getAllObjects()` and names from `ISupportName.getName()` / the `name` property.
- **Property resolution is multi-source (the hard-won part).** A Servoy persist holds a property in
  several different places, so `readProperty` tries them in order and returns the first non-null:
  1. the explicitly-set **flat properties map** (`AbstractBase.getPropertiesMap()`);
  2. **`AbstractBase.getProperty(...)`** (content-spec properties + defaults);
  3. a **JavaBean getter** (`getX`/`isX`, via reflection) — this is where typed custom-property
     accessors such as `Form.getUseCssPosition()` and `Form.isResponsiveLayout()` live, which the
     flat map does **not** expose (this is exactly why the `useCssPosition` assertion resolves
     correctly);
  4. the **`customProperties`** bag directly (`getCustomProperty(new String[]{name})`);
  5. the **flattened component JSON** (`getFlattenedJson().opt(name)`) — where web components keep
     model properties such as `dataProviderID`/`text`.
  Reflection is used for (3)–(5) so the skilltest bundle does not hard-depend on every persist
  subtype or on the component-JSON API.
- **Property matchers** reuse the same `ArgMatcher` grammar as the transcript `argOverrides`:
  tolerant by default (case-insensitive, whitespace-normalised, list-as-set), with
  `exact`/`equals`/`contains`/`containsAll`/`regex`/`present` overrides.
- **Inference placeholders are inert.** An inferred, still-unedited component `typeName` (the
  `OutcomeInference.TYPE_NAME_PLACEHOLDER` sentinel) is **skipped** rather than asserted, so a
  baseline passes on the real, meaningful properties (`dataProviderID`, …) until the user fills in a
  concrete type. It is reported in the result as `(placeholder - not checked)`.
- **Structured result, not just a diff.** `check(...)` returns a `Result(match, diff, nodes)` where
  `nodes` is a tree of `Node(label, found, pass, message, props, children)` and each `props` entry is
  a `PropCheck(property, expected, actual, pass)`. This lets a UI render an expandable,
  per-assertion, expected-vs-actual breakdown **even on PASS** (used by `SkillTestHtml` in the view
  and by `JUnitXmlReporter`), while `diff` is the flat text for the console / Markdown report.
- **Sidecar shape** (`expect.persists`), the live customerDetail scenario:

  ```jsonc
  "expect": {
    "persists": [
      { "kind": "form", "name": "customerDetail",
        "props": { "dataSource": "db:/example_data/customers", "useCssPosition": true },
        "children": [
          { "kind": "component",
            "props": { "typeName": "bootstrapcomponents-textbox", "dataProviderID": "companyname" } },
          { "kind": "component",
            "props": { "typeName": "bootstrapcomponents-textbox", "dataProviderID": "contactname" } }
        ] }
    ]
  }
  ```

- **Inference at import (`OutcomeInference`).** Because expected outcomes should not be hand-authored,
  importing an export **infers** a starting `expect.persists` set — workspace-independently, since the
  golden workspace is usually gone by import time. It draws only on the export: persist **skeletons**
  from every `task` output's `Changed files:` list (the orchestrator export has no direct write-tool
  calls — the sub-agent did the work — so this list is the source of paths → kind+name), plus
  property **suggestions** from the prompt (datasource `db:/…`, layout mode, quoted dataprovider
  names). The inferred set pre-fills the editor.
- **Assertion editor (Option C, `PersistAssertionEditorDialog` + `PersistNodeDialog`).** Import ends
  with a full editor pre-populated with the inferred assertions for the user to **confirm/edit**
  (add/remove/edit persists, their property matchers, and nested children) before the baseline is
  written; the same editor is reachable from the view's **Edit expected outcomes…** action to revise
  an existing baseline (`BaselineLoader.writeExpected`).

> **Verified end-to-end (2026-09, Jenkins build #24/#25).** The `create-customerdetail-form-with-data-bindings`
> baseline runs green on CI via the outcome check: the runner creates an empty `aiTestSolution`
> (`precondition.source: empty` — see §3.11; the baseline **must** carry `precondition.solution` or
> `SolutionSetup.createEmptySolution` throws "'empty' source requires 'precondition.solution'"),
> replays the prompt (agent `Orchestrator`, model `kiro/claude-sonnet-5`), then asserts the resulting
> `customerDetail` form (with `dataSource`, `useCssPosition`) and its two bound text components against
> the live persist tree. On import, `BaselineLoader.starterSidecar` now seeds this `precondition`
> block (default solution `aiTestSolution`, `source:empty`) so imported baselines are runnable without
> hand-editing.

### 3.4b Behavioural tier — JSUnit verification (baseline-owned tests)
Structure (`expect.persists`) and code text cannot prove that JavaScript the skill produced actually
**runs and behaves**. When a baseline needs behavioural verification it may declare a `verify.jsunit`
block; after the replay and the `expect.persists` check, the runner injects the baseline's JSUnit
test(s) into the active solution, runs them, and folds their pass/fail into the baseline result.

**The tests belong to the BASELINE, not the solution.** This is the central design point:

- A `folder`/`git` fixture *could* ship its own `test_*` methods, but an **`empty`-source baseline**
  (created fresh via `createSolution`) has no test scope — and empty-source baselines are exactly the
  ones that add JavaScript we want to verify. Tying tests to the fixture also couples them to the
  solution rather than to the baseline that owns the expectation.
- The engine constraint (`JSUnitRunnerService`, §3.4c) is that it can only run `test_*` methods that
  exist in the **active solution's** scopes/forms — there is no "run a loose `.js` file" entry point.
- **Resolution:** the verify scripts are **stored in the baseline folder** and **injected into the
  active solution as a post-run setup step**, uniformly for `empty`, `folder`, and `git` sources. The
  solution is only where they are temporarily planted to be runnable; they are discarded when the
  workspace is cleaned before the next baseline.

**Sidecar shape** (`verify.jsunit`):

```jsonc
"verify": {
  "jsunit": {
    "scripts": [ "verify/scopes/skilltest_verify.js" ], // baseline-relative files to inject
    "scope": "skilltest_verify",                          // scope/form to run after injection
    "method": null,                                        // optional single test_ method
    "timeoutSeconds": 180,
    "required": true                                       // false = report failures but don't fail the baseline
  }
}
```

- `scripts` are copied from the baseline folder into the active solution **after** the skill run (so
  the skill neither sees nor depends on the test harness). A global-scope script is written as
  `<scopeName>.js` **in the solution project ROOT** — that is where Servoy stores a global scope; an
  earlier attempt wrote it under a `scopes/` subfolder, which is NOT a recognized scope location, so
  the Servoy builder never registered it and JSUnit found no tests ("no solution?"). After writing,
  an incremental build + a join on the build jobs lets the builder parse the file into the in-memory
  solution model (so the scope and its `test_` methods exist, and show up under **Scopes**) before
  the JSUnit launch reads that model.
- `scope`/`method` then drive `JSUnitRunnerService`. With **no `scripts`**, this degrades to "run
  tests already present in the folder/git solution" — the simpler case.
- **Name-tolerance discipline (mandatory):** the injected tests must query the model/runtime rather
  than hardcode LLM-chosen element names, exactly as `expect.persists` does, or model naming variance
  fails the test for the wrong reason.

**Precedence / result folding.** The baseline result is `PASS` only when the outcome check **and** the
JSUnit run pass (JSUnit runs after, and only if, the baseline declares it). A `verify.jsunit` with
`required:false` reports JSUnit failures in the result but does not fail the baseline. The JSUnit
Markdown report is attached to `SkillTestResult` (`jsUnitReport` + `jsUnitPass`), rendered as a
collapsible section in the view, and folded into the JUnit XML.

**The JSUnit SmartClient must be able to boot the solution.** The JSUnit engine starts a headless
SmartClient and loads the active solution; if the solution cannot load — e.g. a form bound to
`db:/example_data/customers` whose table metadata is absent — the client never initializes and
Servoy's own `RunClientTests.cleanUpAfterPrepare` throws `getClientInfo() is null`, surfaced as a
modal dialog that hangs headless/Xvfb CI. Two mitigations: (a) the CI workspace's resources project
ships `.dbi` metadata for the tables the baselines bind to (so datasource bindings resolve and the
client boots); (b) the verify run is wrapped so a SmartClient start failure is reported as a JSUnit
FAIL rather than crashing (the NPE originates on a DLTK job thread outside that wrapper, so the
durable fix is a null-guard in Servoy's `RunClientTests` — tracked upstream). After the run the view
re-activates itself (`bringToTop`) so the DLTK "Script Unit Test" view does not stay on top.

### 3.4c Reusing `JSUnitRunnerService` (the behavioural engine)
The behavioural tier reuses the proven `JSUnitRunnerService`
(`com.servoy.eclipse.developer.mcp.services`) rather than a new JSUnit driver — the same "launch →
wait → format" pattern §2.3 modelled the whole runner on, one layer down:

- API: `runTests(scopeOrAll, timeoutSeconds)` (`"ALL"`/`"MODULES"`/`"FORMS"`/scope/form) and
  `runTestMethod(method, scopeOrAll, timeoutSeconds)`, both returning a formatted Markdown result.
- Internally it builds a `TestTarget` from the active `Solution`/scope/form, launches via
  `RunJSUnitHandler.findSmartClientTestLaunchConfiguration(target)` in `RUN_MODE`, polls the DLTK
  `ITestRunSession` (`waitForSessionByLaunch`), formats, and terminates the launch in a `finally`.
- It requires the runtime the skill-test runner already provides: **workbench up, app server up, the
  fixture solution activated** (§3.10/§3.11). So the wiring is small.
- **Threading:** `JSUnitRunnerService` formats via `Display.getDefault().syncExec`, so it must be
  invoked on the runner's **background Job thread** (never the UI thread), mirroring the developer.mcp
  integration tests' `runOnBackgroundThread` (`JSUnitRunnerIntegrationTest` / `…Layer4Test`).
- **Dependency:** the skilltest bundle adds `com.servoy.eclipse.developer.mcp` to `Require-Bundle`
  (both are internal, workbench-only bundles); `JSUnitRunnerService`'s DLTK-testing +
  `RunJSUnitHandler` dependencies are already present in the launched product, so no new
  target-platform units.
- **Caveats:** a JSUnit launch is a cold SmartClient start (seconds) on top of the minutes-long LLM
  replay, and datasource-bound tests need a working DB in the fixture — so the tier stays **opt-in per
  baseline**.

> **Status:** **implemented** (2026-09). `expect.persists` remains the default/authoritative check;
> `verify.jsunit` is an additive opt-in tier. Implementation: `Baseline.JsUnitVerify` (sidecar record),
> `BaselineLoader.parseJsUnitVerify`, and **`JsUnitVerifier`** (injects the baseline's `scripts` into the
> active solution project via the resources API, then calls `JSUnitRunnerService.runTests`/`runTestMethod`
> and parses the Markdown for pass/fail). `SkillTestRunner` runs it after the outcome/transcript check and
> ANDs a `required` JSUnit result into the baseline pass/fail; the report is carried on
> `SkillTestResult` (`withJsUnit`), rendered as a collapsible "JSUnit verification — PASS/FAIL" section by
> `SkillTestHtml`, and folded into the JUnit XML (`<failure>` on fail, `<system-out>` on pass) by
> `JUnitXmlReporter`. The skilltest bundle gained `Require-Bundle: com.servoy.eclipse.developer.mcp`.

### 3.5 Non-determinism handling (retries)
Each baseline declares `maxAttempts` (suite default **3**), editable per baseline in the view
("Set max attempts…" context action; shown in an **Attempts** column; persisted to the sidecar via
`BaselineLoader.setMaxAttempts`). Following the skills guidance *"run 3-5 trials per prompt and look
at the distribution, not one pass/fail"*, the runner now **runs ALL configured trials** (it no
longer stops at the first pass) and reports the distribution, e.g. `2/3 trials passed`. The overall
result is **PASS only when every trial matches**; it fails if any trial diverges. Each trial is
recorded as a `SkillTestResult.Trial` (its own pass flag, outcome-check tree, JSUnit report and
tool calls); the view renders a collapsible **Trial N — PASS/FAIL** section per trial, and the
per-trial data is persisted so the distribution survives an IDE restart. The stall guard
(§2.5, default **1 hour**, `-Dservoy.skilltest.stallTimeoutSeconds`) aborts a trial that makes no
progress.

### 3.6 Baseline fixtures and the growing library
A **baseline is data**, so the library grows and is curated without recompiling. Baselines live in
two roots (see §3.9 for the full layout): committed **seed** fixtures under
`com.servoy.eclipse.opencode.tests/baselines/<id>/` (used by the automated suite / CI), and the
**workspace** store `<workspace>/servoy_ai_skilltests/baselines/<id>/` where the interactive view
imports and records them. Each baseline is a folder holding a golden export plus a sidecar:

- `export.json` — the committed **golden** `opencode export` output (the source of both the
  prompt to replay and the expected tool calls). This is produced by the record step (§3.7), not
  hand-written.
- `baseline.json` — a small sidecar the team edits, describing how to run and compare:

```jsonc
{
  "id": "create-valuelist-basic",
  "title": "Create a value list with values one, two, three",
  "active": true,                 // deactivate without deleting
  "export": "export.json",        // the golden transcript
  "promptOverride": null,          // optional; default = the prompt extracted from export.json
  "agent": "build",               // optional; default from the export
  "model": null,                   // optional; default from the export / suite config
  "maxAttempts": 3,
  "precondition": {
    "solution": "aiTestSolution",       // solution to end up active (also the project cleaned first)
    "source": { "type": "empty" }        // how to materialize the start state: empty | git | folder
    // "source": { "type": "git", "location": "https://…/fixtures.git", "ref": "main" }
    // "source": { "type": "folder", "location": "C:\\fixtures\\aiTestSolution" }
  },
  "compare": {
    "ordered": false,
    "strict": false,
    "forbidden": [ { "server": "servoy-ide", "tool": "delete_form" } ],
    "argOverrides": {              // optional per-arg precision, else tolerant defaults apply
      "servoy-coder.create_valuelist": { "values": { "containsAll": ["one","two","three"] } }
    }
  }
}
```

`BaselineLoader` reads each `baselines/*/baseline.json`, filters `active == true`, and pairs it
with its `export.json`. Deactivating = `active:false`; updating behaviour = re-record
`export.json`; adding = drop in a new folder. The prompt and the expected tool calls come from
`export.json`; the sidecar only tunes replay/compare.

> **`timeoutSeconds` removed.** The replay polls to completion with no per-attempt time budget
> (§3.3), so the sidecar no longer carries a timeout.

**Workspace setup — the `git init` / git-reset approach is abandoned.** An early implementation
reset the fixture per attempt by shelling `git checkout HEAD -- <path>` and deleting untracked
files, driven by a `precondition.delete` / `precondition.restore` manifest. This was rejected: it
assumes the run happens inside a git checkout, is destructive to unrelated working-tree changes,
and does not fit CI. The agreed model instead is **start from a clean solution project and
materialize the baseline's declared initial state as replay setup** (§3.11).

The sidecar expresses setup as **`precondition.source`** (how to materialize the starting state) +
**`precondition.solution`** (the solution to end up active), not a git delete/restore manifest.
`source.type` is one of `git`, `folder`, or `empty` (§3.11).

### 3.7 Recording and refreshing a baseline — done by the opencode view, not here
The golden `export.json` is a real `opencode export`. Producing it is the **opencode view's**
responsibility (it owns the session and should offer export); the skill-test plugin does **not**
record sessions itself. The earlier in-plugin `recordBaseline` / `recordSession` flow was
**removed** — it depended on the opencode view exposing the current session id, which an upstream
change (the Angular chat UI / reverted session-tracking) removed, and recording belongs with the
session owner anyway.

A baseline therefore enters the library one of two ways:
- **Import export…** — pick an `opencode export` JSON from disk; `BaselineLoader.importExport`
  validates it, extracts the prompt, and writes `<root>/<id>/{export.json, baseline.json}`.
- **Hand-author / commit** a `baseline.json` (+ `export.json`) folder directly.

Re-recording an *intended* behaviour change means producing a new export (via the opencode view)
and re-importing it, then reviewing the diff in the PR.

### 3.8 Wiring into the IDE: a dedicated "Servoy AI Skill Tests" view
Rather than only logging to a console, the runner is surfaced in a **dedicated Eclipse view**,
`SkillTestView` (registered under the `com.servoy.eclipse.ui` category in the **skilltest bundle's**
`plugin.xml`, id `com.servoy.eclipse.opencode.skilltest.SkillTestView`). The button that opens it
is contributed **from the skilltest bundle** into the Servoy AI view toolbar via a
`menuContribution` + command handler (`OpenSkillTestViewHandler`); the shipped `OpenCodeView` holds
no reference to it, so the button is present only when the internal bundle is installed.

Layout (a `SashForm`):
- **Baselines table** (top): one row per baseline discovered under the workspace baselines root
  (§3.9), showing `id`, `title`, `active`, and **last result** (PASS/FAIL/ERROR/— from the most
  recent run).
- **Result detail panel** (bottom): for the selected baseline, its **status + attempts + the
  tool-call diff** produced by `TranscriptComparator` (or the error message / "no differences" on
  pass).

Toolbar actions:
- **Import export…** — a `FileDialog` picks an opencode `export.json` from disk;
  `BaselineLoader.importExport(file, root, id, title)` validates it, extracts the prompt, and
  writes `<root>/<id>/{export.json, baseline.json}`. This is the primary way baselines enter the
  library. (There is **no** Record action — recording is the opencode view's job, §3.7.)
- **Run** — runs all active baselines on a background `Job`, populates the table's last-result
  column and the detail panel, and **persists results** (§3.9). Disabled while a run is in progress.
- **Stop** — cancels the in-progress run (cooperative; aborts the in-flight session, §3.3).
  Enabled only while running.
- **Delete baseline…** — removes the selected baseline's folder from disk (also on the table's
  right-click context menu, guarded by a confirm dialog).
- **Refresh** — re-reads baselines from disk.

Rules:
- Actions run `SkillTestRunner` on a background `Job` (never the UI thread), reusing
  `Activator.getServerPort()` — no new server, no new browser. UI updates marshal back via
  `Display.asyncExec`.
- Results are also echoed to the existing **"Servoy AI Console"** (`Activator.logToConsole`) as
  formatted Markdown, preserving the JSUnit-style text report.
- Browser-abstraction rule (AGENTS.md): the runner talks to opencode over HTTP directly and does
  **not** touch `IBrowser`/Chromium. Any future in-page rendering must go through `IBrowser`.

This mirrors JSUnit: one shared runner service, invoked from both automated tests and an IDE
surface — here a table + detail view instead of only a console dump.

### 3.9 Storage layout in the workspace (JaCoCo-style: committed fixtures vs. derived reports)
Baselines and results live **under the Servoy workspace root**, not under `~/.servoy/`, so they
travel with the workspace and can be version-controlled. A `SkillTestPaths` helper resolves the
root via `ResourcesPlugin.getWorkspace().getRoot().getLocation()` (e.g.
`C:\Users\merae\servoy_workspace_2026_9`), falling back to `~/.servoy/opencode/skilltests` only
when no Eclipse workspace is available (plain unit tests):

```
<workspace>/servoy_ai_skilltests/
├── baselines/                     # committed fixtures — the "source of truth"
│   └── <id>/
│       ├── export.json            # golden opencode session export
│       └── baseline.json          # sidecar (id, title, active, compare rules, …)
└── reports/                       # derived, regenerable — git-ignore (like target/)
    ├── last-results.json          # canonical machine-readable results, keyed by baseline id
    └── run-<timestamp>.md         # human-readable Markdown report, one per run (history)
```

The split follows **EclEmma/JaCoCo**: JaCoCo keeps the canonical result as a small data file
(`jacoco.exec`) and treats HTML/XML/CSV as **derived, regenerable** views written to build output
(`target/`), never into `src/`, and does not commit them.

- **`baselines/`** are the committed fixtures (analogue of source/config): added, deactivated
  (`active:false`), updated, or re-recorded, and reviewed in PRs.
- **`reports/`** are transient derived output (analogue of `target/site/jacoco/`): **`reports/`
  is git-ignored** and regenerable by re-running.
  - `last-results.json` is the **canonical** result (the `jacoco.exec` analogue): written after
    every Run and **reloaded on view open** so the table's last-result column survives a Developer
    restart without re-running.
  - `run-<timestamp>.md` is the **derived human report** (the HTML-report analogue): the existing
    `formatResults` Markdown, one timestamped file kept per run for history.
  - Optional future **`run-<timestamp>.xml` / `.csv`** for CI consumption, mirroring
    `jacoco.xml` / `jacoco.csv` (deferred).

Because the workspace may itself be a git repo, `SkillTestPaths` drops a self-contained
`reports/.gitignore` (`*`) when it first creates the folder, so reports are ignored without
depending on any outer repo's ignore file. `SkillTestReporter` owns writing `last-results.json` +
the timestamped Markdown and reloading the canonical JSON. The seed fixtures under `com.servoy.eclipse.opencode.tests/baselines/` remain the
CI/automated-suite source; the workspace `baselines/` root is the interactive/import/record store.

### 3.11 Per-baseline setup: clean solution + three materialization sources
`SolutionSetup.prepare(baseline)` runs before each baseline and brings the workspace to the
golden's declared starting state in two steps:

1. **Clean.** If `precondition.solution` is set, delete that solution's project **and its on-disk
   content** (`deleteProjectWithContent`) so the run starts from a known-empty state and a later
   import is not blocked by leftover files.
   The source (type + git URL/ref or folder + solution name) is editable per baseline in the view
   ("Edit test solution source…" context action → `PreconditionEditorDialog`), persisted via
   `BaselineLoader.writePrecondition`.
2. **Materialize** according to `precondition.source.type`:
   - **`git`** — clone with **JGit** (not the git CLI) into `<workspace>/.skilltest-src/<repo>@<ref>`,
     using **EGit's credentials provider** (resolved reflectively, its internal class) so HTTPS auth
     reuses the IDE's secure store and no interactive username/tty prompt is needed (the CLI's
     prompt was the "could not read Username for https://github.com" / "User cancelled dialog"
     failure on CI). If the checkout already exists from a prior trial it is **reverted to a pristine
     ref state** (`reset --hard` + `clean -dfx`) rather than re-cloned, discarding all local changes
     including newly-added/untracked files. Then import the solution and activate.
   - **`folder`** — import the Servoy project(s) under `source.location` (guarded so it is not the
     live workspace), then activate.
   - **Import is solution-scoped** (`importSolutionProjects`): rather than importing every project in
     a (mono-)repo, it indexes all `.project` dirs under the source, then imports only the declared
     `precondition.solution` plus the projects it transitively references (its resources project and
     modules, via each `.project`'s referenced/dynamic references). Referenced projects absent from
     the source are skipped (resolved from the workspace/target if needed).
   - **`empty`** — create a fresh, empty solution named `precondition.solution` via the **MCP
     `servoy-dev/createSolution` tool** (with `activate=true`). This is why the runner must have MCP
     available (§3.10): the empty-source path calls a Servoy MCP tool over HTTP
     (`McpToolClient` → `POST …/dev_mcp/servoy-dev`, the same streamable-HTTP client the runner and
     the MCP probe use). There is no workbench-free "create empty solution" path, so `empty` depends
     on the workbench-hosted MCP servers being up. **`empty` therefore requires
     `precondition.solution`** (the name of the solution to create) — `SolutionSetup.createEmptySolution`
     throws `'empty' source requires 'precondition.solution'` when it is absent, which is exactly the
     failure that took down Jenkins build #23 for a baseline whose sidecar had no `precondition` block.
     Two safeguards followed: `BaselineLoader.starterSidecar` now seeds `precondition: { solution:
     "aiTestSolution", source: { type: "empty" } }` into every **imported** baseline, and any
     hand-authored baseline using `empty` must carry a `precondition.solution`.

**All three sources activate via the workbench-native MCP `activateSolution` tool** (the same path
`createSolution` uses). The runner runs inside the workbench (it needs MCP + JSUnit), so it must
NOT activate through the headless `ExportServoyModel`: that installs/expects a
`WorkspaceUserManager`, but a running workbench has a `SwitchableEclipseUserManager` (installed by
the JSUnit infrastructure), and mixing them throws "SwitchableEclipseUserManager cannot be cast to
WorkspaceUserManager".

The view run (not just the headless CI runner) performs this per-baseline setup before each
replay, so an interactive run also starts from a genuinely clean solution rather than whatever the
previous run left behind.

### 3.10 Running on Jenkins (headless)
The suite runs on CI as a **headless Eclipse application** launched by Maven, mirroring the two
existing Servoy CI jobs (`servoy_test`'s `Jenkinsfile.jsunit` and `Jenkinsfile.e2e`): **Maven →
maven-antrun → a headless `-application` runner → JUnit XML → Jenkins `junit`**. The Servoy product
(developer install + application server + **AI plugin**) is materialized by Maven exactly as
`servoy_test/pom.xml` already does (it downloads `servoy-aiplugin.zip` into
`application_server/plugins`).

**Decision (revised 2026-09): launch the Servoy Developer PRODUCT/WORKBENCH; the run fires from an
`org.eclipse.ui.startup` hook. A pure `-application` is NOT viable, because MCP needs the
workbench.** The spec originally chose a dedicated headless `-application` (`SkillTestHeadlessRunner
extends AbstractWorkspaceExporter`) that hand-bootstrapped the app server and relied on the MCP
servlets registering through an OSGi-context fallback with "no workbench needed". **Direct
measurement disproved that fallback.**

**What the MCP probe established (2026-09).** A small diagnostic (`McpProbeApplication` +
`McpProbeStartup`, kept in the skilltest bundle) launched Servoy three ways and probed
`http://127.0.0.1:<webPort>/dev_mcp/servoy-*`:

- **Bare `-application` (no workbench):** the app server + Tomcat come up, but the MCP tool servlets
  **never register** — every `/dev_mcp/servoy-*` probe returns **HTTP 404**. `PlatformUI.isWorkbenchRunning()`
  is `false`. The "register MCP via OSGi context without a workbench" path does not actually happen
  for these servlets.
- **Product/workbench launch (`-product com.servoy.eclipse.core.ide`), UI *not* disabled:** the
  workbench starts, and with it core → app server → **MCP servlets register**. A bare `GET` returns
  **HTTP 400** (the servlet is present but rejects a non-MCP request), and a **full MCP handshake**
  (streamable-HTTP `initialize` + `tools/list`, with the session bearer token) **succeeds** —
  `servoy-dev` reports **42 tools**, `servoy-ide` **28**. This is the only configuration in which the
  agent actually has the Servoy tools.
- **`ModelUtils.setUIDisabled(true)`** likewise yields no workbench → no MCP.

**Why MCP needs the workbench.** The MCP servers are built by **E4 dependency injection**
(`ContextInjectionFactory.make` of `ServoyIdeServer`, needing services such as `CodeAnalysisService`)
and register as servlets tied to the **workbench/E4 context**. When Servoy is launched as a bare
`-application`, no workbench and no E4 workbench context exist, the injection has nothing to resolve
against, `McpServerBuiltins.createServerInstances` does not produce servers, and nothing registers on
Tomcat — hence the 404s. Starting the **workbench application** creates that context, so the servers
instantiate and register. In short: **MCP registration is a workbench/E4-hosted concern, not a plain
Tomcat concern**, so "MCP without a workbench" is not achievable with the current developer.mcp
design. Since the skill test's entire value is exercising the **real Servoy MCP tools**, the runner
must run where those tools exist — inside the workbench.

**How the runner is launched now.**
- **Launch the product/workbench**, not a bare app:
  `-product com.servoy.eclipse.core.ide -noSplash -data <workspace>` with the full Servoy bundle set
  (crucially including `com.servoy.eclipse.developer.mcp`). Do **not** pass `-Djava.awt.headless=true`
  — the workbench uses **SWT**, which needs a real display (headless AWT does not help and can hurt).
- The run is triggered by **`SkillTestStartup`** (an `org.eclipse.ui.startup` `IStartup` in the
  skilltest bundle), gated by **`-Dservoy.skilltest.run=true`** so opening Developer normally never
  starts a run. Once the workbench is up, it runs the baselines on a background thread via
  `SkillTestHeadlessRunner.runInWorkbench()`, then **stops the opencode server and shuts down the app
  server** (to free the node port 4096 and the web-server port 8183) and **halts** the JVM with the
  exit code. `SkillTestHeadlessRunner.runInWorkbench()` **waits** for the workbench-started app
  server (it no longer hand-bootstraps one) and does **not** disable the UI. Configuration is passed
  as `-Dservoy.skilltest.*` system properties (`as`, `outputDir`, `cloudUser`, `cloudPass`), read by
  `SkillTestArgumentChest.fromSystemProperties()`. The old bare-app `start(IApplicationContext)` entry
  now refuses to run and points at the workbench launch.
- **Cleanup matters because `halt()` skips shutdown hooks.** `SkillTestStartup` explicitly stops
  opencode (`opencode.Activator.stopServer()`, which kills the node process tree) and the app server
  (`ApplicationServerRegistry.doNativeShutdown()`) before `halt()`, otherwise orphaned `node`/opencode
  processes keep port 4096 (forcing the next run to a different port) and Tomcat keeps 8183 (a
  `BindException` next run).

**MCP client transport gotcha (cross-OSGi ServiceLoader).** When the runner calls MCP directly (the
`empty`-source `createSolution`, and the probe), the MCP SDK's `ServiceLoader` discovery of its JSON
mapper / schema validator **does not work across OSGi bundles** (`No McpJsonMapperSupplier` /
`No JsonSchemaValidatorSupplier available`). The client must therefore be handed concrete instances:
`HttpClientStreamableHttpTransport.builder(base).jsonMapper(new JacksonMcpJsonMapper(om))` and
`McpClient.sync(transport).jsonSchemaValidator(new DefaultJsonSchemaValidator(om))`. The skilltest
bundle imports the needed `io.modelcontextprotocol.*` packages (client, transport, common, json,
json.jackson2, json.schema, json.schema.jackson2, spec, util) plus `reactor.core.publisher`. Auth:
the client sends `Authorization: Bearer <Activator.SESSION_AUTH_TOKEN>` (read reflectively from the
developer.mcp bundle); internal `@servoy.com` logins bypass the check anyway.

**Xvfb is now the execution host, not a safety net.** Because the workbench needs a display, Linux CI
**must** run the launch under **Xvfb** (`xvfb-run`), as `Jenkinsfile.jsunit`/`e2e` do. This is a
reversal of the earlier "Xvfb is only belt-and-suspenders" note. The runtime cost is negligible next
to minutes of LLM latency.

**JUnit output.** The runner writes JUnit XML (one `<testcase>` per baseline; `<failure>` carries the
tool-call diff) to `-Dservoy.skilltest.outputDir` and sets an exit code (`0` pass / `1` failed / `2`
infra), reusing the Cypress `JUnitXmlReporter` shape. **Publishing on Jenkins:** the report is written
under the Servoy **workspace** (outside the Jenkins job workspace), and the `junit` step only matches
globs **relative to the job workspace**, so the pipeline copies `TEST-*.xml` into the job workspace
before `junit` publishes it.

**Authentication — headless Servoy Cloud login (no interactive dialog, no separate kiro login).**
The opencode/kiro credentials are **issued by the Servoy Cloud login**, not hardcoded. In Developer,
`ServoyLoginDialog.getLoginToken(user, pass)` POSTs Basic-auth to the Cloud "crowd" endpoint and, on
`200`, receives a JSON `{ token, svy_ai_key, skill_endpoint }`, from which it sets:
- `GENAI_API_KEY` = `svy_ai_key` (the provider key opencode/kiro uses), and
- `SERVOY_SKILLS_ZIP` = `SERVOY_API_BASE + skill_endpoint + "?loginToken=" + token` (the skills zip
  URL, itself authenticated with the login token).

CI reproduces **only the HTTP call** (the dialog is UI; the request is a plain `HttpClient` POST), so
no display is needed for auth:
1. Jenkins holds a **Servoy Cloud service-account** username/password as credentials.
2. A pre-launch step (Maven/antrun, or the runner itself given `-user`/`-pass`) POSTs Basic-auth to
   `${servoy.api.url:-https://middleware-prod.unifiedui.servoy-cloud.eu}/servoy-service/rest_ws/api/developer_auth/getAuthToken`
   with headers `Authorization: Basic …`, `Accept: application/json`, `servoyVersion`, `os`.
3. It parses `svy_ai_key` and `skill_endpoint`+`token` from the response and passes
   `-DGENAI_API_KEY=…` and `-DSERVOY_SKILLS_ZIP=…` to the launcher (note: the opencode bundle reads
   these via **`System.getProperty`**, so they must be `-D` JVM args, not just environment vars).

The `?loginToken=` on the skills-zip URL indicates these tokens are session-bound, so **CI logs in
immediately before each run** to obtain fresh credentials rather than caching a static key.

### 3.12 Deploying to the CI product, and the Windows session-0 wall
**Install via the p2 update site, not a raw-jar overlay.** The skilltest / opencode / developer.mcp
bundles must reach the CI product **through a proper p2 install** from the built update sites
(`repository.site_aiplugin` for opencode + developer.mcp + the customer feature; `repository.site_skilltest`
for the internal skilltest feature). A quick "copy the SNAPSHOT jars into `plugins/` and patch
`bundles.info`" overlay was tried and **fails**: the bundles land unresolved (`Unable to resolve
plug-in "com.servoy.eclipse.opencode"`, `… .skilltest`), so their views/classes/icons never load
("Could not create the view: com.servoy.eclipse.opencode.OpenCodeView"). p2 resolves the
Import-Package/version constraints against the target platform; a raw copy does not. Two overlay
foot-guns also surfaced and are worth recording: (a) PowerShell 5.1 `Set-Content -Encoding UTF8`
writes a **UTF-8 BOM** that corrupts the first line of `bundles.info` and breaks
`simpleconfigurator` at startup (`FrameworkEvent ERROR`) — write it **without** a BOM; (b) after any
bundle change, clear the product's `configuration/org.eclipse.osgi` cache so the framework re-reads
`plugins/`.

**Local Windows Jenkins runs in session 0 and cannot open a display.** The default Jenkins Windows
service runs as **`LocalSystem`** in **session 0** with **no window station** (`DesktopInteract=False`).
Since the run now launches the **SWT workbench** (§3.10), it cannot start there — SWT cannot create a
`Display` in session 0. A diagnostic stage confirmed the job runs as `nt authority\system` / session 0
while the interactive desktop is session 1. Therefore local Windows Jenkins must use an
**interactive-session agent**: create a permanent node (label `desktop`) with an **inbound** launch
method and start its `agent.jar` **from a logged-in desktop session** (session 1), pinning the job with
`agent { label 'desktop' }`. Notes: start the agent with a **Java 17+** JRE (the modern agent jar is
compiled for class-file 61; the bundled Servoy JRE or a JDK 21 works — the old PATH Java 8 gives
`UnsupportedClassVersionError`). This session-0 limitation is Windows-specific; the intended CI target
is **Linux + Xvfb**, where the workbench runs headless on the agent without an interactive login.

**Entry points — Maven (primary) or npm (optional).** The runner is a headless application; Maven or
npm only *launch* it:
- **Maven (primary):** a profile (e.g. `-Prun_skill_test`) whose `maven-antrun` step performs the
  Cloud login, then launches the application with `-data/-s/-o` + the `-D` credentials, then Jenkins
  reads the JUnit XML. This is self-contained (Maven also materializes the product) and matches the
  existing jobs.
- **npm (optional):** an npm `script` that invokes the same launcher (login + `-application` call),
  useful if the fixtures repo is node-centric. npm does **not** materialize the product, so it still
  relies on the install being present (via Maven or a download step). Both entry points emit the same
  JUnit XML.

**Jenkins wiring (sketch) — product/workbench launch under a display.** The job launches the
installed Servoy Developer **product** (which already contains the skilltest + developer.mcp bundles,
installed from the p2 site — see §3.12), triggers the run via `-Dservoy.skilltest.run=true`, and
publishes the copied JUnit XML. On **Linux** wrap the launch in `xvfb-run`; on **Windows** it must run
on an **interactive-session agent** (§3.12):
```groovy
// Linux CI (sketch)
wrap([$class: 'Xvfb', installationName: 'xvfb', autoDisplayName: true]) {
  sh '''
    "$DEVELOPER_DIR/servoy_developer" -product com.servoy.eclipse.core.ide -noSplash -consolelog \
      -Dservoy.skilltest.run=true \
      "-Dservoy.skilltest.as=$APP_SERVER_DIR" \
      "-Dservoy.skilltest.outputDir=$WORKSPACE_DIR/servoy_ai_skilltests/reports" \
      "-Dservoy.skilltest.cloudUser=$CLOUD_USER" "-Dservoy.skilltest.cloudPass=$CLOUD_PASS" \
      -data "$WORKSPACE_DIR"
  '''
}
post { always {
  // report is under the Servoy workspace (outside the job workspace): copy in, then publish
  sh 'mkdir -p reports && cp "$WORKSPACE_DIR"/servoy_ai_skilltests/reports/TEST-*.xml reports/ || true'
  junit allowEmptyResults: true, testResults: 'reports/TEST-*.xml'
}}
```
(The repo's `Jenkinsfile.skilltest` is the Windows/`bat` equivalent, pinned to an interactive
`desktop` agent.)

**Open prerequisites (ops, not code):** a Servoy Cloud **service account** usable non-interactively;
confirmation that `svy_ai_key`/`loginToken` live long enough for a run (hence login-per-run); and
ownership of that account's **LLM token budget** by the AI team. A **separate repo** may host the
skill-test fixtures + CI job (baselines, fixture solution(s), `Jenkinsfile`, a `pom.xml` that
materializes the product + AI plugin), kept distinct from the internal plugin in `Servoy-Copilot`.

## 4. Implementation plan

1. **Internal bundle `com.servoy.eclipse.opencode.skilltest`** (`Require-Bundle:
   com.servoy.eclipse.opencode`): `OpencodeHttpClient` (HTTP/1.1-pinned; `promptAsync`,
   `getStatus`, `waitForCompletion`, `getMessages`), `SessionExporter` (delegates to
   `RunOpencodeCommand.exportSession`), `SessionTranscript` (+ agent/model/directory),
   `McpToolCall`, `TranscriptComparator` (+ `ArgMatcher`), `Baseline`, `BaselineLoader`,
   `SkillTestResult`, `SkillTestRunner` (`runBaseline`/`runAll` with a `cancelled` supplier,
   `formatResults`), `SkillTestView`, `OpenSkillTestViewHandler`, `SkillTestPaths`,
   `SkillTestReporter`, `SkillTestHtml`, and `plugin.xml` (view + toolbar `menuContribution`).
2. **Export helper in the shipped bundle:** add `RunOpencodeCommand.exportSession(File, String)`
   so the internal bundle exports without depending on `ngclient.ui`. Confirm the host handshake
   (`ensureServerStarting`/`waitForServer`/`getServerPort`/`isServerReady`) is reachable.
3. **Internal feature `com.servoy.eclipse.opencode.skilltest.feature`** listing only the skilltest
   bundle, published to an internal update site. **Do not add the bundle to the customer
   `servoypilot.feature`** — this is the customer-visibility guarantee.
4. **Test fragment `com.servoy.eclipse.opencode.skilltest.tests`** (Fragment-Host = skilltest):
   `AbstractSkillScenarioTest` (guards + solution activation + server handshake) and
   `SkillScenarioTests` (`@TestFactory` → one dynamic test per active baseline); moved unit tests +
   seed `baselines/`.
5. **Root pom:** add the two bundles + the feature as reactor `<module>`s.
6. **Workspace setup:** implement clean-workspace + `precondition.importSolutions` import for CI;
   settle the local story (§7).
7. **Seed baselines:** import/commit a small initial set under `baselines/` (non-interactive
   prompts — no `question` tool call).
8. **Suite config:** defaults `maxAttempts=3`, tolerant compare, agent/model from the export;
   overridable via `baseline.json` and system properties.
9. **Docs & verify:** running the suite (`eclipse-pde_runJUnitPluginTestClass` +
   `-Dservoy.ai.skilltests=true` + keys), importing baselines, the in-view actions;
   `getCompilationErrors` clean; suite **skipped** when the guard is off.

**Status:** items 1–5 are implemented; 6 (workspace import setup) and 7 (seed baselines) are the
main remaining work, plus a full reactor build and a guarded end-to-end run.

## 5. Acceptance criteria
- [ ] A recorded `opencode export` is the golden baseline: the runner extracts the prompt from it
      and re-runs that prompt against the current skills through the existing embedded server.
- [ ] A baseline **passes when the current run produces the same result**. When it declares
      `expect.persists`, "same result" is judged **authoritatively against the active solution's
      in-memory persist tree** (`PersistOutcomeChecker`, §3.4a) and the transcript comparison is
      skipped; otherwise it falls back to the same MCP tool calls with equivalent arguments under a
      tolerant comparison. Text/prose is never compared.
- [ ] The outcome check resolves the active editing `Solution`, matches each `OutcomeAssertion`
      generically (kind → name/scope → property matchers) with children walked recursively, reads
      properties through the multi-source fallback (flat map → `getProperty` → bean getter →
      `customProperties` → flattened JSON, so typed flags like `useCssPosition` resolve), skips
      unedited inference placeholders, and returns a structured expected-vs-actual tree rendered even
      on PASS (view + JUnit XML). `expect.persists` is inferred at import and confirmed/edited in the
      assertion editor.
- [ ] The reusable `SkillTestRunner` (HTTP client, exporter, transcript model, comparator,
      baseline loader, formatter) lives in the **internal** bundle
      `com.servoy.eclipse.opencode.skilltest`, modelled on `JSUnitRunnerService`, and does not
      touch `IBrowser`/Chromium.
- [ ] The internal bundle is **excluded from the customer feature**
      (`com.servoy.eclipse.servoypilot.feature` lists only mcp + opencode) and shipped only via the
      internal `…​.skilltest.feature`, so customers installing Servoy AI Copilot never receive it.
- [ ] The replay uses **async prompt + poll-to-idle** (`prompt_async` + `GET /session/status`)
      over an **HTTP/1.1**-pinned client, with **no per-attempt timeout**, and reuses the golden's
      recorded `agent`/`model`. A **Stop** action cancels an in-progress run.
- [ ] Per-baseline setup cleans the solution project + content and materializes the start state from
      `precondition.source` = **`git` / `folder` / `empty`**; `empty` creates the solution via the MCP
      `servoy-dev/createSolution` tool. All three work headless (product/workbench launch).
- [ ] The CI runner launches the **product/workbench** (MCP requires the workbench — a bare
      `-application` gives HTTP 404 on `/dev_mcp/servoy-*`), triggered by an `org.eclipse.ui.startup`
      hook (`-Dservoy.skilltest.run=true`), waits for the app server, runs, then stops opencode + the
      app server and halts with an exit code. Linux CI runs it under **Xvfb**.
- [ ] Transcripts are captured via native `opencode export`, with the `GET …/message` list as
      fallback.
- [ ] Comparison supports tolerant defaults plus per-arg overrides, `forbidden`, `ordered`, and
      `strict`; retries (default 3) pass a baseline if any attempt matches.
- [ ] Baselines are data (`export.json` + `baseline.json`) that can be added, deactivated
      (`active:false`), updated, and re-imported; a `@TestFactory` emits one test per active
      baseline. Recording is done by the opencode view (export), not by this plugin.
- [ ] The runner is wired into a dedicated **Servoy AI Skill Tests** view (baselines table with a
      last-result column + a result-detail panel showing status/attempts/diff; Import export / Run /
      Stop / Delete / Refresh actions) on a background Job, also reporting to the Servoy AI Console.
- [ ] Baselines and results live **under the workspace root** (`<workspace>/servoy_ai_skilltests/`),
      not `~/.servoy/`; committed `baselines/` vs. git-ignored, regenerable `reports/`
      (JaCoCo-style), with `last-results.json` as canonical data reloaded on view open and a
      timestamped Markdown report kept per run.
- [ ] The JUnit suite is opt-in: skipped (green, no cost) unless the guard property + keys are set.
- [ ] At least two seed baselines are recorded, committed, and documented.
- [ ] Workspace compiles with zero errors; existing opencode-fragment plain-JUnit tests pass.

## 6. Out of scope
- Resurrecting the record/replay of the LLM↔opencode event stream from SVY-21245 (rejected).
- **Mocking the LLM** from recorded responses on each run (would defeat testing prompt/skill
  interpretation — the run must go through the live current skills; the export is only the
  baseline to compare against).
- Building a native Servoy end-user function to extract sessions from `opencode.db` (SVY-21374
  already delivered export).
- Rendering results **inside** the embedded opencode web page (needs `IBrowser` plumbing);
  results go to the Servoy AI Console for now.
- Comparing model reasoning text, durations, or subagent structure.
- Recording/exporting sessions from the skill-test plugin (delegated to the opencode view).
- Replaying **interactive** baselines (those with a `question`/permission tool call) unattended.
- Shipping the skill-test plugin to customers (internal feature only).
- Full CI pipeline wiring, dashboards, or cost-reporting beyond a soft attempt cap.
- Changing the shipped skills or prompts themselves.

## 7. Open questions
| Question | Owner | Status | Proposed default |
|----------|-------|--------|------------------|
| Which model + credentials should CI/nightly use, and who owns the token budget? | Servoy AI team (Vid/Rene) | open | Model from the export/skills-zip `opencode.json`; a dedicated CI `GENAI_API_KEY` owned by the AI team. |
| Per-run cost ceiling / total-attempt cap? | Servoy AI team | open | Soft cap ≤ (active baselines × `maxAttempts`); `maxAttempts=3`. |
| How often should the suite run — every build, nightly, or on-demand? | Servoy AI team / build owner | open | On-demand (view action) + nightly; never on every PR build. |
| Initial seed baselines, and who curates/re-records the library? | Rene van Veen (skills owner) | open | Seed: "create a value list one/two/three" and "add a button to a form"; Rene records/curates. |
| How tolerant should the default argument comparison be (e.g. is set-equality for lists right, is case-insensitive right)? | Servoy AI team | open | Tolerant defaults (case-insensitive, whitespace-normalised, list-as-set), with per-arg exact overrides in `baseline.json`. |
| How is the workspace brought to the golden's starting state (git-reset is abandoned)? | Servoy AI team | **resolved (implemented)** | Per-baseline: **clean the solution project + content**, then materialize `precondition.source` = **`git`** (clone+import), **`folder`** (import), or **`empty`** (MCP `createSolution`). No git init/reset. §3.11. |
| What is the **local** setup story, given a developer's workspace must not be wiped? | Servoy AI team | **resolved (approach)** | Use a **dedicated skill-test workspace** (`-data`), never the developer's live one; the setup only ever deletes the baseline's own solution project. |
| Concrete headless **solution import** mechanism for setup? | Servoy AI team | **resolved (implemented)** | `git`/`folder` import via `org.eclipse.core.resources` (create+open) + activate through the headless Servoy model; `empty` via the MCP `servoy-dev/createSolution` tool. §3.11. |
| Where should interactive baselines and run reports live? | Servoy AI team | **resolved** | Under the **workspace root** `<workspace>/servoy_ai_skilltests/` (not `~/.servoy/`): committed `baselines/`, git-ignored `reports/` (JaCoCo-style). Bundled seeds under the tests fragment remain the CI source. |
| Should run reports be committed or treated as derived output? | Servoy AI team | **resolved** | Derived/regenerable → **git-ignore `reports/`** (like JaCoCo's `target/site`). `last-results.json` is canonical machine-readable data; timestamped Markdown is the human report; XML/CSV for CI deferred. |
| Default `maxAttempts`; per-attempt timeout? | Servoy AI team | **resolved** | `maxAttempts=3`. **No per-attempt timeout** — the replay polls to idle (runs take minutes); a cooperative **Stop** replaces the timeout. |
| Jenkins integration for the internal suite? | build owner / AI team | **resolved (approach)** | Install the bundles into the product via the **p2 site** (not a raw-jar overlay); launch the **product/workbench** with `-Dservoy.skilltest.run=true`; copy `TEST-*.xml` into the job workspace for `junit`. **Linux+Xvfb** is the target; local **Windows** needs an interactive-session agent (session 0 has no display). §3.10/§3.12. |
| Must the CI runner be a pure headless `-application`? | — | **resolved (reversed)** | **No.** MCP servers require the workbench/E4 context; a bare `-application` never registers `/dev_mcp/servoy-*` (HTTP 404). The runner launches the **product/workbench** and fires from an `org.eclipse.ui.startup` hook. §3.10. |
| Multiple attempts per baseline? | Servoy AI team | **temporarily disabled** | Pinned to **1 attempt** (retry loop commented) because the 2nd attempt hangs; restore §3.5 once single runs are stable. |
