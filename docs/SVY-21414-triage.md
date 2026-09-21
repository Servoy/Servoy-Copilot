# Triage Report — SVY-21414

**Verdict:** PROCEED

## Reported problem
When running a large JSUnit suite (630 tests in the reporter's case) via the MCP
`runJsUnitTests` tool with scope `ALL`, the runner returns far too early and reports
only 72 passing tests, even though all tests are observed running to completion inside
the IDE (in under 30s, well within the 120s timeout). The premature/partial result
("All 72 test(s) passed!") makes the AI agent believe the run finished, so it moves on
or re-runs, leading to a broken loop. A single scope or single form always works
correctly and the runner waits until done.

**Additional observed symptom (same root cause):** during an `ALL` run the user also
saw the runner run some tests up to the timeout and then **activate another (test)
solution** — an unexpected solution reactivation. This is not a second bug: it is a
downstream consequence of the premature-return / mid-run termination described below
(see "Reactivation symptom" under root-cause assessment).

## Root-cause assessment
The bug is in `JSUnitRunnerService.waitForSessionByLaunch(...)`
(`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/JSUnitRunnerService.java`).

The polling loop correlates the DLTK `ITestRunSession` by launch, then returns the
session **as soon as the child count becomes greater than zero**:

```java
// Results have been bridged into the session.
if (found[0] != null && childCount[0] > 0)
{
    return new RunResult(found[0], true);
}
```

For a large run, DLTK bridges test results into the session **incrementally** as they
complete. The first non-empty snapshot the loop observes (72 tests) is treated as the
finished run: the method returns `finishedBeforeTimeout=true` and `formatResults()`
counts whatever children exist at that instant — 72 — and prints
"All 72 test(s) passed!". The remaining ~558 tests keep running in the IDE, unseen.

The loop never consults the session's completion signal. `ITestElement.ProgressState`
(decompiled) has `NOT_STARTED / RUNNING / STOPPED / COMPLETED`, and
`ITestRunSession`/`ITestElement` expose `getProgressState()`. The correct terminal
condition is "session progress state is COMPLETED (or STOPPED)", not "child count > 0".

Why small scopes work: a single scope/form produces its handful of results in one bridge
batch that lands essentially atomically, so "first non-empty snapshot" happens to equal
"final result set". The race only manifests when results arrive in multiple batches,
i.e. large suites — exactly the ALL case.

This also explains the symptom that partial-timeout handling never triggers: the run is
declared finished (`finishedBeforeTimeout=true`) long before the timeout, so the
"Timed out while running" path is never taken and no warning is emitted.

### Reactivation symptom (same root cause)
`runForTarget` terminates the launch in its `finally` block (`launch.terminate()`).
Combined with the premature return, this produces the "another solution gets activated"
symptom the user observed on an `ALL` run:

1. `ALL` launches the correct target — `new TestTarget(activeSolution)`.
2. `waitForSessionByLaunch` returns early on the first 72-test batch (or the run reaches
   the timeout), and the `finally` block terminates the still-running smart-client launch
   mid-run.
3. The premature/partial result drives the agent to re-invoke the tool. On the re-run,
   `JSUnitLaunchConfigurationDelegate.prepareForLaunch` compares the launched
   configuration's expected active solution against the *current* active solution
   (`prepareForLaunch`, ~lines 73–91 of `JSUnitLaunchConfigurationDelegate.java`). When
   they mismatch it shows "Cannot run target … Running current active solution" and
   **falls back to `TestTarget.activeProjectTarget()`** — the reactivation the user saw.

The mismatch/fallback path is only reached because the first run was cut short and
re-triggered. Fixing the terminal condition so an `ALL` run is reported only once it has
truly completed removes the premature return, the spurious re-run, and therefore the
reactivation. No separate fix to the reactivation/launch-config logic is required.

## Ticket premise check
The ticket reports the symptom without proposing a specific code solution ("What is
wrong?"), so there is no premise to overturn. The reporter's own diagnosis — that the
runner "doesn't wait" for large runs — is accurate and matches the root cause. The
later-observed reactivation is confirmed (by the user) to be the same root cause, not a
separate defect. No mistaken direction to correct here.

## Approaches considered
1. **Wait for session completion, not first-children (recommended)** — Change the
   terminal condition in `waitForSessionByLaunch` to require the session's
   `getProgressState() == COMPLETED` (also accept `STOPPED`), rather than
   `childCount > 0`. Keep waiting for children to appear first (to avoid the
   "freshly-created empty session reports COMPLETED with 0 children" trap that
   SVY-21241 fixed), then additionally require the progress state to reach COMPLETED
   before returning success. On timeout, fall back to returning the current session
   with `finishedBeforeTimeout=false` (partial results path already exists).
   - Pros: Directly fixes the race; small, localized change; reuses existing
     partial-result reporting; preserves the SVY-21241 fix (still don't return on an
     empty just-created session); removes the premature-return that triggers the
     re-run and therefore the solution reactivation symptom.
   - Cons: Must confirm DLTK actually flips the state to COMPLETED at the end of a real
     run (verify against the running session), and handle the known quirk that an empty
     session also reads COMPLETED — hence the "children present AND completed" guard.

2. **Stability/quiescence heuristic (count stops changing for N polls)** — Return once
   the child count has been stable across several consecutive poll intervals.
   - Pros: Doesn't depend on trusting the progress state.
   - Cons: Heuristic and timing-fragile; a slow test or a pause between batches could
     trip an early return again; adds tunable magic numbers. Inferior to reading the
     real completion signal.

3. **Correlate with launch termination** — Wait until the underlying `ILaunch`
   terminates (the runner already terminates it in `finally`) before reading results.
   - Pros: Uses a concrete lifecycle signal.
   - Cons: Smart-client JSUnit launch lifecycle vs. DLTK result bridging may not line up
     cleanly; the client can stay alive after results are in, or terminate paths may
     differ; riskier than reading the DLTK progress state directly.

4. **No code change needed** — Not viable. This is a genuine correctness bug in Servoy
   MCP code (wrong terminal condition in the poll loop). There is no built-in tool
   behaviour that already produces a correct full-suite result through this path.
   - Pros: none.
   - Cons: Leaves a Critical, fix-versioned defect that actively breaks the AI test loop
     and causes spurious solution reactivation.

## Recommendation
Fix `waitForSessionByLaunch` so it only reports success once the whole run has finished,
keeping the existing timeout fallback (return the session with `finishedBeforeTimeout=false`
so the "Timed out while running — partial results" path reports honestly). This also resolves
the observed solution-reactivation symptom, since that is caused by the premature return +
mid-run termination + agent re-run chain, not by the launch-config/active-solution logic.

> **Implementation note — the "completed progress state" signal (Approach 1) was tried and
> rejected.** A live, logged run of the real `cloudSync_test` suite (658 tests) proved that
> `getProgressState()` reaches `COMPLETED` at the end of **each sub-suite batch**, not once at
> the true end (the Servoy bridge `ScriptUnitTestRunNotifier` fires `testTerminated` per
> notifier). "children + COMPLETED" therefore returned a mid-run partial (243/667). A second
> attempt keying off `launch.isTerminated()` also failed — the launch is only terminated by the
> runner's own `finally` after the wait returns, so it stayed `false` for 20+ s after the run
> finished, making the wait time out. **The signal that works is the DLTK session's
> `startedCount` vs `totalCount`** (the same "Runs: started/total" the Script Unit Test view
> shows): terminal = children present AND `startedCount >= totalCount` (with `totalCount > 0`).
> In the logged run, `total` was 658 throughout while `started` climbed to 658 exactly at the
> true end. See the spec §3.1/§3.3/§3.5 for the final design. Alternative 2 (quiescence
> heuristic) was not needed; Alternative 3 (launch termination) is the rejected second attempt.

## Git history findings
The current `childCount > 0` condition was introduced by commit **d2084b1**
("SVY-21241 wait for JSUnit session children, not premature completed state [ai]",
2026-07-24). That change fixed the *opposite* premature-return: a freshly-created
session reports `progressState=COMPLETED` with 0 children, so the old code returned an
empty result. SVY-21241 replaced the "completed + 0 children" check with a
"children > 0" check. That fix was correct for small/empty runs but over-corrected —
it dropped the completion check entirely, so it now returns on the *first* batch of a
multi-batch run. The proper fix keeps the "children present" guard (still needed to not
regress SVY-21241) and adds the real whole-run signal: `startedCount >= totalCount`
(NOT the progress state — see the Recommendation note above for why). Commit **b03c73e** on
the same case only stripped diagnostics; **a4b299e** (SVY-21284) is unrelated to the wait
logic.

---

## Follow-up — `ALL` must not descend into modules

**Verdict:** PROCEED

### Reported problem
Running `runJsUnitTests` with scope `ALL` runs the active solution's tests and then keeps
going into the solution's **modules**. When those modules have no tests (the reporter's
case), the runner launches empty per-module runs and the agent loop stalls. The user's
expectation: "run all tests of the solution" should run only the active solution's own
tests, not its modules.

### Root-cause assessment
The `ALL`/`null` branch of `JSUnitRunnerService.runTests` resolved to a single
`new TestTarget(activeSolution)`. A `TestTarget` built from a `Solution` with no
module-to-test means "the whole active solution", which
`SolutionJSUnitSuiteCodeBuilder.appendSolutionTestCode` treats as the full *flattened*
solution: it recurses through `Solution.getReferencedModules(...)` and adds a
"Module tests" suite for every referenced module. So one `ALL` target inherently walked the
module tree — there was no per-target way to confine it to just the main solution.

This is distinct from the §1–7 completion-wait bug but lives in the same method and the same
bundle, so it is captured here as a follow-up rather than a separate ticket.

### Approaches considered
1. **Fan out `ALL` over the main solution's own scopes and forms** — one narrow `TestTarget` per
   scope/form of the active solution, combined via `formatGroupedResults`.
   - Pros: no change to the shared servoy-eclipse engine.
   - Cons: **rejected after a live run.** Produces one JSUnit launch/session *per scope/form*
     (fragmented "Test Runs" list) instead of the single unified session the classic "Run all"
     gives, and multiplies the smart-client cold-start N times — on a ~45-scope solution it eats
     the whole suite time budget. Also needs an ad-hoc "skip empty scopes" guard to avoid spurious
     `testSystemInitFailed ("no solution?")` suites. Fundamentally the wrong shape.
2. **Engine flag: single-session solution run that excludes modules (adopted)** — Add an
   `excludeModules` flag to `TestTarget` (new constructor + getter + optional serialisation token,
   default `false`) and honour it in `SolutionJSUnitSuiteCodeBuilder.appendSolutionTestCode` by
   skipping the module-inspection block. The MCP runner resolves `ALL`/`null` to a single
   `TestTarget(activeSolution, true)` and renders it with the single-session `formatResults`.
   - Pros: one launch / one session (matches classic "Run all" minus modules); no cold-start
     multiplication; no empty-scope guard needed. The flag defaults to `false`, so the JSUnit view,
     the classic run action, headless/CI and mobile exporters are behaviourally unchanged.
   - Cons: touches the shared `com.servoy.eclipse.model` engine — mitigated by the default-false
     opt-in and by serialisation that tolerates the flag's absence.

### Recommendation
Proceed with **Approach 2** (engine flag). Earlier guidance leaned away from touching the shared
engine, but the fan-out alternative proved unacceptable in practice (fragmented sessions, N cold
starts). The opt-in flag confines the behaviour change to callers that explicitly request it —
only the MCP runner's `ALL` path — leaving every existing caller unchanged.

### Scope note
All JSUnit-runner behaviour is in `com.servoy.eclipse.developer.mcp`. The
`com.servoy.eclipse.servoypilot` bundle is no longer used and is intentionally left untouched.
