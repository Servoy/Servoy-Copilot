# Spec: SVY-21711 — Skill-test coverage report (skills / agents / MCP tools)

## 1. Goal

Produce a **cumulative coverage report** for the Servoy AI skill-test suite that
aggregates, across all the baseline runs in one suite execution, three usage
dimensions:

1. **skills** actually loaded,
2. **agents** actually dispatched (including sub-agents), and
3. **MCP tools** actually called,

and flags, against the full skill4servoy **pack catalog**, the **NOT-COVERED-YET**
set in each dimension — the skills, agents and tools the pack offers that no run
in the suite ever drove, with a per-baseline breakdown so a gap traces back to the
baseline that should have covered it.

The report is a **human-readable HTML page** rendered in the same visual style as
the existing per-run report (the `SkillTestHtml` inline-CSS look) and emitted into
the existing `reports/` directory alongside `last-results.json`.

This answers a cross-run question ("what does our suite collectively exercise?"),
distinct from the per-run engine of SVY-21366 ("did this one baseline reproduce its
baseline?") and complementary to the SVY-21659 prompt library whose reach it
measures.

## 2. Background

### 2.1 This is an aggregation + denominator layer, not a new capture subsystem

The triage (`docs/SVY-21711-triage.md`) and the approved approach establish that
**no new capture mechanism is needed**. The data the three dimensions require is
already durably present in each run's opencode session, which the harness already
reads back after idle:

- `SkillTestRunner.runBaseline` calls `SessionExporter.export(sessionId)` (and falls
  back to `OpencodeHttpClient.getMessages`) once a run goes idle.
- `SessionTranscript.fromExport` walks the export/message tree and extracts every
  tool part into an `McpToolCall` (`server`, `tool`, `arguments`, `output`) via
  `collectFromParts` / `extractToolCall`.
- `isToolPart` matches any part whose type contains `"tool"` or that carries a
  `tool`/`name` field. In opencode this means the per-run tool-call list already
  contains, as first-class entries:
  - **MCP tool calls** — e.g. `eclipse-ide_readProjectResource`,
    `servoy-form_getFormLayout`; `McpToolCall.splitToolName` already splits these
    into `server` + `tool`.
  - **skill loads** — the `skill` tool (its `arguments` carry the skill id), so a
    skill load is observable as a tool call named `skill`.
  - **sub-agent dispatches** — the `task` tool (its `arguments` carry the dispatched
    agent name), so an agent dispatch is observable as a tool call named `task`.
- The **builder agent** of each run is already known: `SkillTestRunner.resolveAgent`
  computes the `effectiveAgent`, and `SessionTranscript.getAgent()` records the
  agent from the export `info.agent`.

So SVY-21711 is built on top of the existing `SessionTranscript` / `McpToolCall`
machinery and the per-run results — it classifies and aggregates that captured data
rather than introducing a parallel pipeline.

### 2.2 The pack catalog (the denominator) is read from the extracted skills zip

The NOT-COVERED-YET set is meaningful only against the full skill4servoy catalog
(all skills, all agents, and the MCP tools the pack offers). That catalog is **not
in this repository**; it arrives at runtime as `SERVOY_SKILLS_ZIP` and is extracted
by `SkillsZipExtractor.extractToConfigDir` into the opencode config directory
`~/.servoy/opencode/` (`Path.of(user.home, ".servoy", "opencode")`, the same dir
`OpencodeFolderCreatorJob` writes `opencode.json` into). The report reads the
catalog from that extracted tree.

Catalog sources (confirmed against a real extracted pack, SVY-21711 local run):

- **skills** — one per `skills/<id>/` directory (opencode skill folders, **plural**
  `skills/`, not `skill/`). The catalog skill set = the set of `<id>` directory
  names under `skills/`. (Observed real ids: `servoy-orchestrator`,
  `servoy-agent-shared`, `servoy-database`, `servoy-developer`, `servoy-e2etester`,
  `servoy-platform`, `servoy-reviewer`, `servoy-security-reviewer`,
  `servoy-unittester`, `skilltest-authoring`, `skilltest-expectations`.)
- **agents** — one per `agents/<name>.md` (opencode agent markdown files, **plural**
  `agents/`, not `agent/`). The catalog agent set = the set of `<name>` basenames
  under `agents/`. The active `opencode.json` `agent` block is a **secondary**
  source merged in (it adds the built-in `build`/`plan` and any config-only agents).
  (Observed real agents: `Orchestrator`, `Developer`, `Reviewer`, `E2E-Tester`,
  `JSUnit-Tester`, `Database-Researcher`, `Researcher`, `Security-Reviewer`,
  `Judge`, `Baseline-Author`.)
- **MCP tools** — the denominator is the set of **`server_tool`** identities the
  Servoy Copilot project itself defines, read by reflecting the
  `com.servoy.eclipse.developer.mcp` bundle (which the skilltest bundle already
  `Require-Bundle`s): `McpServerBuiltins.BUILT_IN_SERVER_CLASSES` lists every
  `@McpServer`-annotated server class; each class's `name()` is the server and each
  of its `@Tool(name=...)` methods is a tool. This yields a **real per-tool-name
  denominator** (e.g. `servoy-workspace_listProjects`, `servoy-form_getFormLayout`,
  …) so NOT-COVERED-YET at tool granularity is meaningful — the user confirmed "we
  know the tool names from the Copilot project." The `opencode.json` `mcp` block is
  still read as a **fallback/augment** for server names so a server present in config
  but not reflectable still contributes its server (with no tools) rather than
  vanishing.

#### MCP-tool classification and the built-in exclusion (SVY-21711 correction)

The previous run misclassified. The corrected rules:

- A tool call's identity is `server_tool` using `McpToolCall.splitToolName` (it
  already handles both `server_tool` and `server.tool`). A call is **MCP** only when
  it resolves to a `server` that is in the MCP catalog (the reflected servers + the
  `mcp`-block servers). Coverage is counted **per `server_tool` identity**; an
  observed `servoy-workspace_listProjects` marks that exact catalog tool covered.
- opencode **built-in/core tools are excluded from the MCP dimension entirely**:
  `read`, `write`, `edit`, `bash`/`shell`, `glob`, `grep`, `list`, `webfetch`,
  `websearch`, `execute`, `question`, `patch`, `todoread`/`todowrite`, and the
  dispatch/skill tools below. They are not MCP tools and must not appear as
  off-catalog MCP noise (the broken report listed `read`, `execute` there).
- **skills** are the `skill` tool calls (and `skills`/`load`-style equivalents) — id
  from args; classified into the **skills** dimension, never MCP.
- **agent dispatch** is observed from both the `task` **and `subagent`** tools (the
  real pack uses `subagent`); the dispatched agent name comes from the args
  (`subagent_type`/`agent`/`subagentType`/`agentType`), and the builder
  `effectiveAgent` is always added. Classified into the **agents** dimension, never
  MCP.

Each source is probed defensively — an absent catalog source degrades to an empty
set for that dimension (the report shows 0 covered / 0 total for it) rather than
failing the whole report, mirroring `SkillTestPaths`' tolerant fallbacks. The
reflection of `McpServerBuiltins` is wrapped so that if developer.mcp is not
resolvable the MCP denominator falls back to the `opencode.json` servers + observed.

### 2.3 Sub-agent child sessions

An Orchestrator baseline fans out into child sub-agent sessions
(`OpencodeHttpClient`). The current export parse reads a **single** session's
messages and does not walk child sub-agent sessions, so sub-agent *tool usage* is
partially invisible. v1 captures:

- **agent dispatch** — fully, from the `task` tool-calls on the parent session
  (the dispatched agent is in the `task` call's arguments), so "which agents ran"
  is complete without walking children.
- **MCP tools called inside a sub-agent** — recorded only insofar as the export
  includes child content. Walking child sessions to attribute tool calls to the
  sub-agent is noted as a v2 refinement (see Open questions).

### 2.4 Aggregation window and output location

- **Window**: cumulative across a whole suite execution — aggregate over all the
  baselines run in that execution (`SkillTestRunner.runAll` / the headless
  `runSkillTests` loop), not a single run or latest-per-scenario.
- **Output (two write targets, because the CI artifact and the IDE report dirs differ):**
  - **CI / headless** — the coverage HTML must land in the headless runner's
    `configuration.getOutputDir()` (in CI this is `-Dservoy.skilltest.outputDir`
    = `$WORKSPACE/skilltest-reports`), the **same** directory `JUnitXmlReporter`
    writes `TEST-skilltest.xml` into. This is the directory `Jenkinsfile.skilltest`
    already archives (`archiveArtifacts artifacts: 'skilltest-logs/**,
    skilltest-reports/**'`, §205–230). `SkillTestReporter.reportsRoot()`
    (`<workspace>/servoy_ai_skilltests/reports`) is **NOT** in that archive glob, so
    writing the coverage HTML only there would make it invisible on Jenkins. Writing
    it into `outputDir` means it appears as a Jenkins build artifact with **no
    Jenkinsfile change required** — the existing `skilltest-reports/**` glob picks it up.
  - **In-IDE** — the in-view run path additionally writes it to
    `SkillTestPaths.reportsRoot()` next to `last-results.json` and `run-<timestamp>.md`,
    following the same derived/git-ignored convention, so an in-IDE "Run all" also
    drops a coverage report locally.
- **Filename**: `coverage-<timestamp>.html` (same `<timestamp>` convention as the
  existing `run-<timestamp>.md`).

## 3. Design

### 3.1 Observed-usage extraction — `CoverageObservation`

A small immutable value type capturing, per baseline, what that baseline's run
exercised:

```
record CoverageObservation(
    String baselineId,
    Set<String> skills,     // skill ids loaded (from `skill` tool-calls)
    Set<String> agents,     // agents that ran: builder effectiveAgent + task-dispatched agents
    Set<String> mcpTools)   // fully-qualified tool identities (server.tool or tool)
```

Built by a new `CoverageExtractor` that takes the per-baseline inputs the runner
already has:

- the `SessionTranscript` (or its `actualCalls` list on `SkillTestResult`),
- the resolved `effectiveAgent`.

Classification of each `McpToolCall`:

- `tool == "skill"` → add the skill id (from `arguments`, e.g. `id`/`skill`/`name`)
  to `skills`. If the id is not in the arguments, record the raw skill tool call so
  the dimension is still non-empty.
- `tool == "task"` → add the dispatched agent name (from `arguments`, e.g.
  `subagent_type`/`agent`/`subagentType`) to `agents`.
- otherwise → add `server.tool` (or bare `tool` when `server` is null) to
  `mcpTools`.

The builder `effectiveAgent` is always added to `agents`.

Argument-key probing for skill id and dispatched-agent name is best-effort and
tolerant (opencode arg shapes vary), mirroring `SessionTranscript`'s defensive style.

### 3.2 Catalog reader — `PackCatalog`

```
record PackCatalog(
    Set<String> skills,     // from skill/<id>/
    Set<String> agents,     // from agent/<name>.md
    Set<String> mcpTools)   // from opencode.json mcp servers (+ observed, see 2.2)
```

A `PackCatalogReader` resolves the extracted config dir (default
`~/.servoy/opencode/`, overridable via the `servoy.skilltest.configDir` system
property for tests/CI) and reads:

- skills from the `skills/` subtree (plural),
- agents from the `agents/` subtree (plural), augmented with the `opencode.json`
  `agent` block keys,
- **MCP tools** as `server_tool` identities by reflecting
  `com.servoy.eclipse.developer.mcp.McpServerBuiltins.BUILT_IN_SERVER_CLASSES`
  (`@McpServer.name()` × each `@Tool.name()`), with the `opencode.json` `mcp` server
  keys folded in as a fallback for server names.

Each read is independently guarded; a missing source yields an empty set for its
dimension. The reflection is wrapped in try/catch (and tolerant of a missing
developer.mcp class), so the reader never throws on a malformed catalog or an
unresolvable bundle.

### 3.3 Aggregator — `CoverageReport`

Folds the list of `CoverageObservation` across the whole suite and diffs against the
`PackCatalog`:

For each dimension (skills, agents, mcpTools):

- **covered** = union of all observations' entries for that dimension that are also
  in the catalog,
- **not-covered-yet** = catalog − observed,
- **off-catalog/extra** = observed − catalog (items a run drove that the catalog did
  not list — surfaced as an informational "unexpected" bucket, not a failure),
- **per-item attribution** = for each covered catalog item, the set of baseline ids
  that exercised it (so a reader sees which scenario covered it),
- **per-baseline breakdown** = for each baseline, what it uniquely contributed.

`CoverageReport` exposes per-dimension `coveredCount` / `catalogCount` /
`percentage` so the HTML header can show a coverage summary line per dimension.

### 3.4 HTML rendering — `CoverageReportHtml`

A new `CoverageReportHtml.render(CoverageReport)` that produces a self-contained
HTML document in the **same style** as `SkillTestHtml`: reuse the same inline CSS
palette (`.header`, `.s-pass`/`.s-fail` greens/reds, `.section-title`,
`details`/`summary` collapsibles, `.oc-props`-style tables, `.muted`). Keep the
CSS literally consistent with `SkillTestHtml.CSS` (extract the shared palette or
copy it) so the two reports look like one family.

Page structure:

- **Header** — "Skill-test coverage" + the suite timestamp + three summary badges:
  `Skills N/M`, `Agents N/M`, `MCP tools N/M` (green when 100%, amber otherwise),
  matching the status-colour convention.
- **Per-dimension sections** (skills, agents, MCP tools), each a `<details>`:
  - a **covered** table: item → count → the baseline ids that exercised it,
  - a **NOT COVERED YET** list (red-accented, expanded by default when non-empty):
    the catalog items no baseline drove,
  - an optional **off-catalog** list (muted) for observed-but-not-in-catalog items.
- **Per-baseline breakdown** — a collapsible table: baseline id → skills/agents/tools
  it exercised, so a gap can be traced to the scenario responsible.

All text HTML-escaped via the same `esc` approach as `SkillTestHtml`.

### 3.5 Wiring into the suite run

The report is emitted once per suite execution, after all baselines have run,
wherever the suite results are already persisted:

- **Headless/CI path** — `SkillTestHeadlessRunner.runSkillTests`, after it builds the
  `results` list and writes the JUnit XML (into `configuration.getOutputDir()`),
  additionally:
  1. builds a `CoverageObservation` per baseline (from the kept `actualCalls` and the
     resolved agent),
  2. reads the `PackCatalog`,
  3. aggregates into a `CoverageReport`,
  4. writes `coverage-<timestamp>.html` **into the same `outputDir`** as the JUnit XML,
     so the CI run's coverage report is archived by Jenkins alongside
     `TEST-skilltest.xml` (§2.4). This write is best-effort: a failure to produce the
     coverage report logs and is swallowed — it must never fail the suite run or change
     the exit code.
- **In-view path** — the same emission after `SkillTestReporter.writeRun`, writing to
  `SkillTestPaths.reportsRoot()`, so an in-IDE "Run all" also drops a coverage report
  (view wiring mirrors where the Markdown run report is written today).

### 3.5.1 Jenkins artifact

`Jenkinsfile.skilltest` already archives the headless `outputDir`:

```
archiveArtifacts artifacts: 'skilltest-logs/**, skilltest-reports/**',
                 allowEmptyArchive: true, fingerprint: false
```

Because the coverage HTML is written into that same `skilltest-reports` directory
(§2.4 CI path), it becomes a downloadable Jenkins build artifact automatically. **No
change to `Jenkinsfile.skilltest` is required** for the report to appear on Jenkins.
(If a human later wants it archived under a distinct name or fingerprinted, the glob
can be narrowed — out of scope here.)

To make per-baseline observations available after the run, the simplest v1 is to
collect `CoverageObservation`s as each baseline finishes (the runner already holds
`lastActualCalls`/`effectiveAgent` at that point); expose them either by returning
them alongside results or by reading them back from the persisted `actualCalls` in
`last-results.json` (which `SkillTestReporter` already writes). Prefer reading from
the already-persisted `actualCalls` so the coverage step is a pure post-processor
over `last-results.json` + the catalog, with no change to the hot run loop. The
builder agent per baseline is also persisted for this (add an `agent` field to the
persisted result if not already present — small additive change to
`SkillTestReporter.writeLastResults` / `loadLastResults`).

### 3.6 No change to the pass/fail verdict

Coverage is **informational**. It never changes a baseline's PASS/FAIL/ERROR status
or the suite exit code. It is a reporting artifact only.

## 4. Implementation plan

All files under `bundles/com.servoy.eclipse.opencode.skilltest/src/com/servoy/eclipse/opencode/skilltest/`
unless noted. Tests under `bundles/com.servoy.eclipse.opencode.tests/` in the
`...plainjunit` package (pure classification/aggregation/reader/HTML logic needs no
OSGi).

1. **`CoverageObservation`** (new record) — per-baseline observed skills/agents/tools.
2. **`CoverageExtractor`** (new) — `observe(baselineId, List<McpToolCall> actualCalls,
   String effectiveAgent) → CoverageObservation`. Houses the `skill`/`task`/MCP-tool
   classification and tolerant argument-key probing for skill id + dispatched-agent.
3. **`PackCatalog`** (new record) + **`PackCatalogReader`** (new) — resolve the
   extracted config dir (default `~/.servoy/opencode/`, override via system property),
   read skills from `skill/`, agents from `agent/`, MCP server names from
   `opencode.json`; each guarded, never throwing.
4. **`CoverageReport`** (new) — aggregate `List<CoverageObservation>` + `PackCatalog`
   into per-dimension covered / not-covered-yet / off-catalog / per-item attribution /
   per-baseline breakdown, with `coveredCount`/`catalogCount`/`percentage` accessors.
5. **`CoverageReportHtml`** (new) — `render(CoverageReport) → String`, reusing the
   `SkillTestHtml` CSS palette and `esc`; sections per 3.4. Factor the shared CSS into
   a small constant reused by both (or duplicate verbatim) so the look stays unified.
6. **`CoverageReportHtml`** (or `SkillTestReporter`) — a `writeCoverage(CoverageReport,
   File/Path targetDir)` that writes `coverage-<timestamp>.html` into the **given**
   directory (so the caller chooses `outputDir` in CI vs `reportsRoot()` in-IDE); persist
   the builder `agent` per result in `SkillTestReporter.writeLastResults`/`loadLastResults`
   (additive) so the coverage step can read it back.
7. **`SkillTestHeadlessRunner.runSkillTests`** — after the JUnit XML write, build the
   observations (over the kept `actualCalls` + resolved agents), read the catalog,
   aggregate, and write the coverage HTML into **`configuration.getOutputDir()`** (the
   same dir as the JUnit XML, so Jenkins archives it — §2.4/§3.5.1). Wrap in try/catch so
   it never fails the run.
8. **In-view run path** — call the same coverage emission right after the Markdown run
   report is written, targeting `SkillTestPaths.reportsRoot()`.
9. **`Jenkinsfile.skilltest`** — **no change required**: the existing
   `archiveArtifacts artifacts: 'skilltest-logs/**, skilltest-reports/**'` already covers
   the `outputDir` the coverage HTML is written into. Verify (do not break) that glob.
10. **Tests** (`...plainjunit`):
   - `CoverageExtractorTest` — a `skill` call → skill dimension; a `task` call → agent
     dimension; an ordinary MCP call → tool dimension; builder agent always included;
     tolerant arg-key probing; missing ids degrade gracefully.
   - `PackCatalogReaderTest` — a temp config dir with `skill/<id>/SKILL.md`,
     `agent/<n>.md`, `opencode.json` → correct sets; missing sources → empty sets, no
     throw.
   - `CoverageReportTest` — covered / not-covered-yet / off-catalog math; per-item
     attribution to baseline ids; per-baseline breakdown; percentages.
   - `CoverageReportHtmlTest` — output contains the three dimension sections, a
     NOT-COVERED-YET block when gaps exist, is HTML-escaped, and is a complete
     `<!DOCTYPE html>` document.

## 5. Acceptance criteria

- [ ] After a full suite execution, a `coverage-<timestamp>.html` file is written: in
      CI/headless into the headless `outputDir` (`skilltest-reports/`), and in-IDE into
      `servoy_ai_skilltests/reports/` next to `last-results.json`.
- [ ] The CI coverage report appears as a **Jenkins build artifact** via the existing
      `archiveArtifacts 'skilltest-logs/**, skilltest-reports/**'` glob in
      `Jenkinsfile.skilltest`, with no Jenkinsfile change needed.
- [ ] The report shows, for each of **skills**, **agents** and **MCP tools**, the
      covered set with a usage count and the baseline ids that exercised each item.
- [ ] The report shows a **NOT-COVERED-YET** set per dimension = pack catalog − observed,
      read from the extracted `SERVOY_SKILLS_ZIP` tree under `~/.servoy/opencode/`.
- [ ] **Skill loads** (the `skill` tool) and **sub-agent dispatches** (the `task` tool,
      by dispatched agent) are counted as first-class coverage dimensions alongside MCP
      tools; the builder agent of each run is included.
- [ ] Aggregation is **cumulative across the whole suite** (all baselines in the run),
      not a single run or latest-per-scenario.
- [ ] The HTML matches the existing run report's visual style (shared `SkillTestHtml`
      CSS palette: header colours, collapsible `details`, tables, muted text).
- [ ] A per-baseline breakdown lets a gap be traced to the scenario that should have
      covered it.
- [ ] A missing/empty catalog source degrades to an empty dimension (0/0) rather than
      failing the report; coverage never changes any baseline's PASS/FAIL or the suite
      exit code.
- [ ] New `...plainjunit` tests cover extraction, catalog reading, aggregation and HTML
      rendering, and pass under the plain JUnit runner.
- [ ] Zero compilation errors (`eclipse-ide_getCompilationErrors`) before commit.

## 6. Out of scope

- A new live event-bus/SSE capture mechanism — explicitly not needed (approved approach);
  usage is sourced from the data the session already holds and the harness already reads.
- Walking child sub-agent sessions to attribute **MCP tool calls** to the specific
  sub-agent (v2 refinement; v1 attributes agent dispatch from the parent's `task` calls
  and tool calls from whatever the export includes).
- Per-agent-that-called-it MCP-tool granularity (an allowed-but-never-called-for-this-
  agent finer gap) — v1 is per-tool-name; see Open questions.
- A machine-readable per-agent permitted-tool manifest (not shipped in the zip today).
- Non-HTML renderings (JSON/Markdown coverage), the Servoy AI view coverage tab, and a
  dedicated CI artifact beyond the file in `reports/`.
- Historical trend/coverage-over-time across multiple suite executions.

## 7. Open questions

| Question | Owner | Status |
|---|---|---|
| MCP-tool denominator: with no per-server tool manifest in the zip, v1 scopes the tool catalog to "configured MCP servers + tool names observed across the suite". Is that acceptable for v1, or should the report enumerate tools from the running opencode server's advertised tool list instead? | Reporter / Product | Open — v1 assumption documented (§2.2) |
| Finer MCP-tool granularity (per agent-that-called-it) — deferred to v2. Confirm per-tool-name is sufficient for v1. | Reporter / Product | Open — v1 = per-tool-name (approved) |
| Exact opencode argument keys for the skill id (on the `skill` tool) and the dispatched agent (on the `task` tool). Implementer probes the likely keys tolerantly; a sample export would let the keys be pinned. | Implementer | Open — tolerant multi-key probe assumed |
| Skills zip layout: confirm the extracted catalog really uses `skill/<id>/` and `agent/<name>.md`. If the pack ships a different layout, the `PackCatalogReader` paths adjust accordingly. | Implementer | Open — standard opencode layout assumed |
| Whether to also surface the coverage report in the Servoy AI skill-test view (a tab/link) in this iteration or leave it as a file-only artifact. | Product | Open — file-only in v1 |
