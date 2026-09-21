# Spec: SVY-21414 — MCP: runJsUnitTests doesn't work correctly when running ALL

## 1. Goal
Make the MCP `runJsUnitTests` tool wait until a JSUnit run has actually finished before
reporting results, so that a large suite (e.g. 630 tests) is reported in full instead of
returning after the first incremental batch of results (72 tests). Today the runner returns
as soon as any results appear, causing "All 72 test(s) passed!" while ~558 tests are still
running in the IDE — which makes the AI agent move on or re-run, breaking its loop. The same
premature-return also produces an observed secondary symptom: on an `ALL` run the runner
terminates the still-running launch and the subsequent re-run activates a different (test)
solution. Fixing the wait condition removes both the wrong count and the spurious
reactivation.

## 2. Background

### 2.1 Where the bug lives
`JSUnitRunnerService.waitForSessionByLaunch(ILaunch, long)`
(`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/JSUnitRunnerService.java`)
polls the DLTK testing model, correlating the `ITestRunSession` by launch, and
returns the session **as soon as the child count becomes greater than zero**:

```java
// Results have been bridged into the session.
if (found[0] != null && childCount[0] > 0)
{
    return new RunResult(found[0], true);
}
```

For a large run, DLTK bridges results into the session **incrementally** as tests complete.
The first non-empty snapshot the loop observes (72 tests) is treated as the finished run:
the method returns `finishedBeforeTimeout=true` and `formatResults()` counts
whatever children exist at that instant. The remaining tests keep running unseen. Because the
run is declared finished long before the timeout, the "Timed out while running — partial
results" path never triggers, so no warning is emitted either.

Small scopes work by coincidence: a single scope/form produces its handful of results in one
bridge batch that lands essentially atomically, so "first non-empty snapshot" equals the final
result set. The race only manifests when results arrive in multiple batches — i.e. large
suites, exactly the `ALL` case.

### 2.2 Why the current condition exists (SVY-21241)
The `childCount > 0` condition was introduced by commit **d2084b1** ("SVY-21241 wait for
JSUnit session children, not premature completed state [ai]"). It fixed the *opposite*
premature return: a freshly-created DLTK session reports `progressState=COMPLETED` with 0
children (an empty run is "100% done"), so the earlier code returned an empty result. SVY-21241
replaced the "completed + 0 children" check with a "children > 0" check. That was correct for
small/empty runs but over-corrected — it dropped the completion check entirely, so it now
returns on the *first* batch of a multi-batch run. The proper fix combines **both** signals:
"children present AND completed". Commit **b03c73e** on the same case only stripped diagnostics;
**a4b299e** (SVY-21284) is unrelated to the wait logic.

### 2.3 Available completion signals — and why progress state is the WRONG one
`org.eclipse.dltk.testing.model.ITestElement.ProgressState` (`NOT_STARTED / RUNNING /
STOPPED / COMPLETED`) is exposed via `ITestRunSession.getProgressState()`, and a first
attempt used "children present AND `COMPLETED`/`STOPPED`" as the terminal condition. **A
diagnosed, logged run proved this is wrong** (see §3.5): the Servoy JSUnit bridge
`ScriptUnitTestRunNotifier` fires `testTerminated` — which flips the DLTK session's
`fIsRunning=false`, i.e. `COMPLETED` — at the end of **each sub-suite batch**, not once at
the end of the whole run. So `COMPLETED` reads true mid-run (observed: 243 of 667, and again
the run reported partial while the IDE kept going). A second attempt keyed off
`launch.isTerminated()`, which is also wrong: the launch is only terminated by
`runForTarget`'s own `finally` **after** the wait returns, so `isTerminated()` stayed `false`
for 20+ seconds after the run had actually finished (logged), making the wait run to timeout
and return partial.

The signal that IS reliable — and is what the Script Unit Test view itself shows as
"Runs: started/total" — is the DLTK session's **started vs. total test counters**
(`getStartedCount()` / `getTotalCount()` on the internal
`org.eclipse.dltk.internal.testing.model.TestRunSession`). `total` is known up front and only
grows as the suite tree is bridged in, while `started` climbs to meet it, so
`started >= total` (with `total > 0`) becomes true only at the true end of the whole run and
never false-triggers between batches. These counters are on the internal impl, not the
`ITestRunSession` interface, so they are read reflectively to avoid a hard restriction
dependency.

### 2.4 Solution-reactivation symptom (same root cause)
`runForTarget` terminates the launch in its `finally` block (`launch.terminate()`). Combined
with the premature return, this produces the "another solution gets activated" symptom
observed on an `ALL` run:

1. `ALL` launches the correct target — `new TestTarget(activeSolution)`.
2. `waitForSessionByLaunch` returns early on the first 72-test batch (or the run reaches the
   timeout), and the `finally` block terminates the still-running smart-client launch mid-run.
3. The premature/partial result drives the agent to re-invoke the tool. On the re-run,
   `JSUnitLaunchConfigurationDelegate.prepareForLaunch` (~lines 73–91) compares the launched
   configuration's expected active solution against the *current* active solution; on a
   mismatch it shows "Cannot run target … Running current active solution" and falls back to
   `TestTarget.activeProjectTarget()` — the reactivation the user saw.

This path is only reached because the first run was cut short and re-triggered. Reporting an
`ALL` run only once it has truly completed removes the premature return, the spurious re-run,
and therefore the reactivation. **No separate change to the launch-config / active-solution
logic is in scope** — it is fixed transitively by the wait-condition change.

## 3. Design

### 3.1 Terminal condition in the poll loop — started/total counters
`waitForSessionByLaunch` returns `new RunResult(session, true)` only when **both** hold:

1. **Children have appeared** — `childCount > 0`. Preserves the SVY-21241 fix by never
   returning on a freshly-created empty session (which reports `total = 0`).
2. **Every planned test has reported a result** — `totalCount > 0 && startedCount >=
   totalCount`, read from the DLTK session's `getStartedCount()` / `getTotalCount()` (the same
   numbers the Script Unit Test view shows as "Runs: started/total").

The decision is extracted into a pure package-private helper
`isTerminalRun(int childCount, int startedCount, int totalCount)` so it can be unit-tested
without a workbench. The counter reads happen inside the existing
`Display.getDefault().syncExec(...)` snapshot (the DLTK model is read on the UI thread), via a
small reflective `readIntNoThrow(session, "getStartedCount"/"getTotalCount")` helper, because
the counters live on the internal `TestRunSession`, not the `ITestRunSession` interface.

### 3.2 Timeout fallback (unchanged behaviour)
Keep the existing fallback: if the deadline passes before the terminal condition is met, read
the current session one last time and return `new RunResult(fallback, false)`. The
`finishedBeforeTimeout=false` value flows into the existing partial-results reporting in
`runTests` and `runTestMethod`, so a genuinely-slow or stuck run honestly reports
"Error - Timed out while running! Partial results follow:" instead of silently
under-reporting. The `effectiveTimeout` floor (`Math.max(timeoutMs, 30_000L)`) and
`POLL_INTERVAL_MS` stay as they are. (Slow environment-dependent tests — e.g. AWS/CRM calls to
unreachable hosts — can still push a run past the timeout; that is a test-content concern,
not a runner-logic one.)

### 3.3 Signals deliberately NOT used
- **`ProgressState.COMPLETED`** — flips true at the end of each sub-suite batch
  (`ScriptUnitTestRunNotifier` fires `testTerminated` per notifier), so it reads terminal
  mid-run and returns a partial count. Rejected.
- **`launch.isTerminated()`** — the launch is only terminated by `runForTarget`'s own
  `finally`, after the wait returns; it stays `false` for the entire run (logged: 20+ s of
  `launchTerminated=false` after `started=658/total=658`), so waiting on it just runs to the
  timeout. Rejected.
- **`STOPPED`** — a user-cancelled run stops progressing; the timeout fallback already returns
  the partial results collected so far, so no special `STOPPED` handling is needed in the
  terminal condition.

### 3.5 How the signal was chosen (diagnosis)
The `COMPLETED` and `launch.isTerminated()` attempts each failed on the real `cloudSync_test`
suite (658 tests). Diagnostic logging in the poll loop captured the true timeline: `total`
was 658 throughout while `started` climbed 23 → 243 → 512 → 613 → 658, and the run was truly
finished (32 failures, matching the IDE view) exactly when `started` reached 658 —
`progressState` only settled to `COMPLETED` at that same point and `launchTerminated` was
still `false` 20 s later. `started >= total` is therefore the correct end-of-run signal. The
temporary diagnostic log line is removed once verified.

## 4. Implementation plan

1. In `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/JSUnitRunnerService.java`,
   modify `waitForSessionByLaunch`:
   - Inside the `syncExec` snapshot, capture `childCount` plus the session's
     `getStartedCount()` / `getTotalCount()` (via a reflective `readIntNoThrow` helper, since
     they live on the internal `TestRunSession`, not the `ITestRunSession` interface).
   - Replace the success condition with `found[0] != null &&
     isTerminalRun(childCount, startedCount, totalCount)`.
   - Leave the deadline loop, poll interval, timeout floor, and the
     `new RunResult(fallback, false)` fallback path unchanged.
2. Add the pure helper `isTerminalRun(int childCount, int startedCount, int totalCount)` —
   returns `childCount > 0 && totalCount > 0 && startedCount >= totalCount` — and the reflective
   `readIntNoThrow(Object, String)` helper. Document why progress-state and launch-termination
   were rejected (§3.3).
3. Add unit tests in the `com.servoy.eclipse.developer.mcp.tests` fragment for `isTerminalRun`
   (`JSUnitRunnerServiceTerminalConditionTest`), covering: all-reported → terminal;
   partial/early batch (e.g. 243/658) → not terminal; `total = 0` freshly-created → not
   terminal; and 0-children → not terminal (SVY-21241 guard). Plain JUnit, no workbench.
4. Run the compilation self-check loop (`eclipse-ide_getCompilationErrors`) and fix any errors
   or blocking Spotbugs issues. Remove the temporary diagnostic `ServoyLog.logInfo` line from
   the poll loop once the fix is verified against a live large run.

## 5. Acceptance criteria
- [x] A JSUnit `ALL` run of a large suite reports the full count (verified: 658/658, 32 failed
      on `cloudSync_test`, matching the Script Unit Test view), not an incremental batch.
- [x] `waitForSessionByLaunch` returns `finishedBeforeTimeout=true` only once the session has
      children **and** `startedCount >= totalCount` (`totalCount > 0`).
- [x] A freshly-created empty session (`total = 0`) does **not** cause an early return — the
      SVY-21241 fix is preserved.
- [x] On timeout, the runner still returns the current session with
      `finishedBeforeTimeout=false`, and `runTests`/`runTestMethod` prepend the
      "Error - Timed out while running! Partial results follow:" message.
- [x] A single-scope / single-form run continues to report correctly (no regression).
- [x] An `ALL` run no longer triggers a spurious activation of another (test) solution, because
      it is no longer returned early and re-invoked mid-run.
- [x] Unit test: `isTerminalRun` returns success only for "children present AND
      `startedCount >= totalCount > 0`"; keeps polling for 0 children, for partial batches
      (243/658), and for `total = 0`. (`JSUnitRunnerServiceTerminalConditionTest`, 10 cases.)
- [x] Zero compilation errors and no new blocking Spotbugs issues in the modified file.

## 6. Out of scope
- Changing the `JSUnitLaunchConfigurationDelegate.prepareForLaunch` active-solution
  mismatch/fallback logic or the `runForTarget` `finally`-terminate — the reactivation symptom
  is resolved transitively by the wait-condition fix, not by altering these.
- Refactoring the `MODULES` / `FORMS` grouped-run paths themselves. (The §8 follow-up *reuses*
  the grouped-run machinery for the `ALL` fan-out but does not change the `MODULES` / `FORMS`
  logic.)
- Changing the result formatting (`formatResults` / `formatGroupedResults`) or the MCP tool's
  public signature.
- The reference-only `com.servoy.eclipse.servoypilot` bundle's own `JSUnitRunnerService`.
- Reworking the timeout ceiling / poll interval values.

## 7. Open questions
| Question | Owner | Status |
|----------|-------|--------|
| ~~Confirm against a live large run that `getProgressState()` reaches `COMPLETED` at the true end.~~ **Resolved:** a logged live run showed `COMPLETED` (and every batch's `testTerminated`) fires per sub-suite, so progress state is NOT a reliable whole-run signal. The runner now keys off `startedCount >= totalCount` instead (§3.1, §3.3, §3.5). | Implementer | resolved |
| Slow environment-dependent tests (AWS/CRM calls to unreachable hosts) can still push a full run past the MCP timeout, yielding an honest partial result. Mocking those calls is test-content work, out of scope for this runner fix. | Reporter / test owner | noted |

---

## 8. Follow-up — `ALL` must not descend into modules

### 8.1 Reported behaviour
When `runJsUnitTests` is invoked with scope `ALL`, it runs the active solution's own tests
and then continues into every **module** of that solution. When the modules contain no tests
(the common case for the reporter), the runner spends its time launching empty per-module runs
and the agent loop stalls. Expected behaviour: **"run all tests of the solution" runs only the
active solution's own tests, never its modules.**

### 8.2 Root cause
`ALL` (and `null`) resolved to a single `new TestTarget(activeSolution)`. `TestTarget` with a
`Solution` and no module-to-test means "whole active solution", which
`SolutionJSUnitSuiteCodeBuilder.appendSolutionTestCode` interprets as *the full flattened
solution* — it recurses through `Solution.getReferencedModules(...)` and generates a
"Module tests" suite for every referenced module. So a single `ALL` target inherently walked
the module tree; there was no way to scope it to just the main solution through that one target.

### 8.3 Design — solved in the agent skill, NOT in the Java runner

> **Two Java approaches were tried and both reverted.** (1) A per-scope/per-form **fan-out**
> produced one JSUnit launch/session per scope/form (fragmented "Test Runs" list, N cold starts;
> on a ~45-scope solution it ate the whole time budget). (2) An engine-level **`excludeModules`
> flag** on `TestTarget` + `SolutionJSUnitSuiteCodeBuilder` (skip the module-inspection block for
> a flagged `SOLUTION` target) worked, but touched the shared `com.servoy.eclipse.model` engine
> used by the JSUnit view / classic run / headless-CI / mobile exporter. Both were backed out of
> the Java tree.

**The actual, shipped fix is in the agent skill, not the runner.** The observed symptom — a
"run all jsunit tests" request running *another* solution's tests (e.g. activating `svyCloud_test`
while `cloudSync_test` was active), which also tore down the running client mid-run — was the
**JSUnit-Tester agent switching the active solution** (`servoy-model_setTarget`) and/or the
orchestrator enumerating multiple `*_test` solutions. The runner faithfully tested whatever was
active. So the correct layer is the skill:

- `servoy-unittester` (SKILL.md rule 13 + `context/tooling.md` step 2): a bare "run all" is the
  **one currently-active solution, a single run** — never `setTarget` away, never enumerate other
  `*_test` solutions, never run a second solution after the first (which caused the
  `ClientInfo.clearUserInfo()` NPE / `ExitScriptException` teardown). If the active solution is not
  a `*_test` solution the target is ambiguous → return `Status: INPUT_REQUIRED`.
- `servoy-orchestrator` (`context/routes.md` #jsunit): on `INPUT_REQUIRED`, ask the user via the
  `question` tool (run active as-is, or switch), then re-dispatch once.

These skill changes are committed to the **`skill4servoy`** repo (commits `1e802c0` +
`3b1f6fd`), separate from this Java work, and take effect on skill install. Verified live: with
`cloudSync_test` active, "run all jsunit tests" runs that one solution as a single session (658
tests) with no solution switch.

**The Java runner therefore keeps only the §1–7 completion-wait fix.** `runTests`' `ALL`/`null`
branch uses the original `new TestTarget(activeSolution)` (whole flattened solution) and the
single-session `formatResults(...)`; it is the skill that guarantees only one solution is run per
request. The `TestTarget` / `SolutionJSUnitSuiteCodeBuilder` engine files are unchanged from
upstream.

### 8.4 Scope of the change (as shipped)
- **Agent skill (separate repo, `skill4servoy`):** `servoy-unittester/SKILL.md`,
  `servoy-unittester/context/tooling.md`, `servoy-orchestrator/context/routes.md` — the "run all =
  one active solution, ask when ambiguous" rule.
- **Java runner:** no §8-specific change beyond the §1–7 completion-wait fix. The engine
  (`TestTarget`, `SolutionJSUnitSuiteCodeBuilder`) is **not** modified.
- The reference-only `com.servoy.eclipse.servoypilot` bundle is **not** touched.

### 8.5 Acceptance criteria
- [x] A bare "run all jsunit tests" runs the **one currently-active solution** as a single JSUnit
      session and never switches to / enumerates another solution. (Verified: `cloudSync_test`
      active → 658-test single session, no switch.)
- [x] When the active solution is not a `*_test` solution, the tester returns `INPUT_REQUIRED` and
      the orchestrator asks the user instead of silently switching.
- [x] No mid-run client teardown (`ClientInfo.clearUserInfo()` NPE / `ExitScriptException`) from a
      second solution being activated during a run.
- [x] The engine (`TestTarget`, `SolutionJSUnitSuiteCodeBuilder`) is unchanged; JSUnit view /
      classic run / headless-CI / mobile exporter behaviour is unaffected.
- [x] Skill changes committed to `skill4servoy` (`1e802c0`, `3b1f6fd`) and verified live.
