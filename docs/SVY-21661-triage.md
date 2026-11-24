# Triage Report — SVY-21661

**Verdict:** PROCEED

## Reported problem
`[skilltest] Trend / history reporting` (Task, follow-up of SVY-21366, pairs with SVY-21660).
Skill-test results only exist per run, so a skill that degrades slowly across model or skill
updates goes unnoticed. The ticket asks for:
- a compact, append-only run history;
- a per-baseline trend (pass rate over the last N runs, later tokens/credits);
- the history kept in the git-ignored `reports/` folder (canonical data separate from the rendered view);
- the trend shown in the Skill Test view and/or a small report, tied into the nightly job;
- done when the view shows pass-rate history per baseline and history survives IDE restarts
  and **accumulates across nightly runs**.

User context: *"where could we keep the data about runs (usage)? this should work locally and on CI"*.
So the main open question is where to store the history, in both environments.

## Root-cause assessment
This is a missing feature, not a bug. The current persistence cannot hold history, and on CI it
does not persist anything at all.

**Local (Skill Test view)**
- `SkillTestPaths` puts everything under `<workspace>/servoy_ai_skilltests/`. `reports/` is
  derived output and gets its own `.gitignore` (`*`) on creation.
- `SkillTestReporter.writeRun` (called only from `SkillTestView`, line ~501) **overwrites**
  `reports/last-results.json` and adds a `run-<timestamp>.md`. The JSON is a snapshot (one entry
  per baseline, latest run only). The Markdown files are history in name only, since nothing
  parses them back. `loadLastResults()` restores only the last-result column.
- The SVY-21660 metrics (tokens, Kiro requests/estCredits, creditsDelta, ...) are already in
  `last-results.json`, so a history record can reuse them.

**CI (`Jenkinsfile.skilltest`, nightly `pollSCM('H 3 * * *')`)**
- `SkillTestHeadlessRunner` never calls `SkillTestReporter`. It writes only
  `TEST-skilltest.xml` (`JUnitXmlReporter`) and `coverage.html` into
  `-Dservoy.skilltest.outputDir=$WORKSPACE/skilltest-reports`. **No `last-results.json` is
  written on CI.**
- That dir is deleted at the start of every build (`rm -rf "$WORKSPACE/skilltest-reports"`,
  commit b79c5b2, done deliberately so a stale report is never published).
- The workbench workspace (and so `SkillTestPaths.reportsRoot()`) is tycho-surefire's
  `target/work/data`, which `mvn clean verify` wipes every build.
- The Kiro state dir (`$WORKSPACE/kiro-state`) is also recreated and deleted per build
  (dd5f10a). It holds the key, so it can't serve as a store either.
- So nothing survives between CI builds except what Jenkins archives (`archiveArtifacts`:
  `workbench.log`, `TEST-skilltest.xml`, `coverage.html`, `opencode-server.log`) and what the
  `junit` step records.
- Jenkins already gives a partial trend for free. The `junit` step ingests one `<testcase>` per
  baseline (`classname="skilltest.<id>"`), so the job's *Test Result Trend* and each test case's
  *History* page already show pass/fail per baseline over builds. Metrics only appear in
  `<system-out>`, so token/credit trends are **not** charted.

**Conclusion:** "keep history in the git-ignored `reports/`" (the ticket's premise) works
locally, but on CI it gives nothing that "accumulates across nightly runs", because every place
the build writes to is wiped by design.

## Ticket premise check
- **Local:** the premise holds. `reports/` survives IDE restarts and is git-ignored. Appending
  to a new file there fits the existing JaCoCo-style split (canonical data vs rendered view).
- **CI:** the premise does **not** hold as written. `reports/` on CI lives inside `target/` and
  is cleaned. Changing the CI cleanup to keep the history would undo intentional
  stale-report fixes. CI history needs a store **outside the build's own disposable dirs**.
- The ticket also assumes the headless path produces the same canonical data as the view. It
  doesn't. The CI runner must start writing the history record itself, ideally through the same
  writer as the view.
- It's worth deciding whether Jenkins' built-in JUnit trend already covers the "pass-rate
  history" part on CI. It does for pass/fail per baseline, but not for metrics, and not inside
  the IDE view.

## Approaches considered

Common to every option below: one **record format** written by one shared writer that both
`SkillTestView` and `SkillTestHeadlessRunner` call. The format is an append-only JSON Lines file
(`run-history.jsonl`). Each run appends one line per baseline:
`{runId, timestamp, source: ide|ci, buildNumber?, baselineId, status, trials passed/total, attempts,
agent, model, plugin/skills version or commit, metrics{tokens, kiro, creditsDelta}}`.
The file stays compact (no diffs, tool-call outputs or prompts), and JSONL means an append never
rewrites the whole file and a partly written last line is easy to skip. The location comes from
`-Dservoy.skilltest.historyFile` (or env), with `reportsRoot()/run-history.jsonl` as the default.
Only the CI storage differs between options:

1. **Jenkins artifact chaining (copy previous build's history, append, re-archive)**. The
   pipeline fetches `run-history.jsonl` from the last completed build (Copy Artifact plugin:
   `copyArtifacts(projectName: env.JOB_NAME, selector: lastCompleted(), optional: true, filter: ...)`),
   passes it in via `-Dservoy.skilltest.historyFile`, the runner appends, and
   `archiveArtifacts` keeps the new file.
   - Pros: all data stays in Jenkins next to the build it describes. No new infra, works on any
     agent, survives a workspace wipe. Each build's artifact is a full history snapshot. The IDE
     view could later show CI history by downloading `lastCompletedBuild/artifact/...`.
   - Cons: needs the Copy Artifact plugin (**not verified** on build.servoy.com). History is
     lost if the build-discard policy deletes all older builds, or if a build dies before
     archiving (mitigated by `optional: true` plus a copy from the last *completed* build). The
     whole file is duplicated per build (small, since JSONL lines are compact).
2. **Persistent directory on the CI agent** (e.g. `/data/skilltest-history/run-history.jsonl`,
   next to the existing `/data/www/...` paths the job already relies on), also archived per
   build.
   - Pros: simplest pipeline change (one `-D` and one archive glob), with true append semantics.
   - Cons: depends on agent state (`agent any`, although the job already hard-depends on
     `/data/www` existing). Not backed up or versioned unless infra arranges it. Concurrent builds
     would need a lock (`options { disableConcurrentBuilds() }`).
3. **Commit history to the private `servoy-ai-skilltest-baselines` repo** (CI pushes the appended
   file to a `history` branch or folder).
   - Pros: versioned, backed up, and testers can see it next to their baselines. A local IDE
     could pull the same file.
   - Cons: CI needs write credentials and push logic, adds nightly noise commits, and risks push
     conflicts. Mixes generated data into a curated fixture repo, which goes against the "derived
     data is git-ignored" rule.
4. **Rely on Jenkins' built-in JUnit trend only** for CI, plus local JSONL only for the IDE.
   - Pros: no CI change, since pass/fail history per baseline already exists.
   - Cons: no token/credit trend (metrics live only in `<system-out>`), nothing machine-readable
     to feed the view or a report, so the nightly part of "done when" is only half met.
5. **No code change**.
   - Pros: zero effort. Jenkins already shows a pass/fail trend per baseline on CI.
   - Cons: no local history, no metric trend, and the view shows nothing over time. The ticket's
     "done when" is not met.

## Recommendation
**PROCEED** with the shared append-only `run-history.jsonl` writer, used by both the Skill Test
view and the headless runner:
- **Locally:** default to `<workspace>/servoy_ai_skilltests/reports/run-history.jsonl`. It is
  already git-ignored, survives restarts, and matches the ticket. The view's trend (pass rate
  over the last N runs per baseline, later tokens/credits) reads only this file.
- **On CI:** use **approach 1 (Jenkins artifact chaining)** as the primary store. Keep the file
  outside `skilltest-reports/`'s wipe (e.g. `$WORKSPACE/skilltest-history/`, re-seeded from the
  previous build's artifact each run), pass it via `-Dservoy.skilltest.historyFile`, and archive
  it. Optionally render a small `trend.html` next to `coverage.html` from the same file.
  Jenkins' own JUnit trend stays as a free extra.
- **Fallback:** if the Copy Artifact plugin is unavailable or not wanted, use approach 2 (a fixed
  agent dir under `/data/`, plus `disableConcurrentBuilds()`). The code change is the same
  either way, because the location is just the `historyFile` property.

Decisions the PM/spec phase must confirm with the user:
1. CI store: Jenkins artifact chaining (needs Copy Artifact plugin) or a fixed `/data/...` dir on
   the agent.
2. Should the IDE view also show CI history (downloaded from Jenkins), or only local runs?
3. Retention: cap the file (e.g. last N runs per baseline) or keep it unbounded.
4. Run identity fields: which "version" to record (plugin qualifier, skills-zip commit, model id)
   so a trend change can be attributed to a model or skill update.

Constraints for implementation: the history write must be best-effort and never fail a run or
change the exit code (same rule as `CoverageReportHtml.emitCoverage`). It must stay additive and
backward compatible next to `last-results.json`. No secrets (Kiro key, prompts with tokens) may
go into the file. All logic in the `skilltest` bundle, unit-tested in plain JUnit
(`opencode.skilltest.tests`).

## Git history findings
- `2bb609d` (SVY-21366): introduced `SkillTestPaths` / `SkillTestReporter` with the workspace
  `reports/` layout and the overwrite-only `last-results.json`.
- `b79c5b2` (SVY-21366): intentionally empties `$WORKSPACE/skilltest-reports` at the start of
  each CI build to avoid publishing stale reports, so CI history must not live there.
- `dd5f10a` (SVY-21366): per-build `kiro-state` dir, deleted after the run (holds the key), so it
  can't be a store either.
- `373ea49` / `c879c1c` (SVY-21711): coverage report written to `outputDir` with a fixed name and
  archived. This is the precedent for a best-effort, archived derived report on CI.
- `2efe33e` (SVY-21660): metrics persisted additively in `last-results.json` and in JUnit
  `<system-out>`. A history record can reuse `RunMetrics` as is.
- No dependency or target changes are needed. Jackson is already used by `SkillTestReporter`.
