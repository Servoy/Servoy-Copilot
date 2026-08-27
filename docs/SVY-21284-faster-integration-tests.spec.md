# Spec: SVY-21284 — Faster and more stable integration tests

## 1. Goal

Speed up the `com.servoy.eclipse.developer.mcp.tests` integration test suite so that it
runs in a fraction of its previous time, eliminate random failures caused by
unconditional `Thread.sleep` calls, and reduce boilerplate code across all integration
test classes by extracting shared infrastructure into a common base class hierarchy.

## 2. Background

### 2.1 Previous state

The integration tests in `com.servoy.eclipse.developer.mcp.tests` had several
structural problems that made them slow and brittle:

- **Unconditional sleeps.** Almost every test class contained calls like
  `Thread.sleep(5_000)` or `Thread.sleep(30_000)` to wait for asynchronous workspace
  or solution events. When the event arrived early the sleep wasted time; when it
  arrived late the test still timed out, causing random failures.
- **Node/npm builds during test runs.** `NodeFolderCreatorJob` was running (triggering
  `npm install` / `ng build` cycles) for test classes that had no need for the node
  folder. This added tens of seconds per test class.
- **Duplicated boilerplate.** Every integration test class individually implemented
  app-server waiting, solution workspace setup, project activation, workspace build
  job draining, and file-writing helpers. There were subtle variations between classes
  that caused additional instability.
- **Wrong Tomcat working directory.** The PDE launch configuration pointed
  `servoy.application_server.dir` at a workspace-relative `testresources/` folder
  instead of a real Servoy install, so Tomcat started with an incomplete resource
  directory and spilled files into the git-tracked project tree.
- **Stale/incorrect bundle list.** The launch config referenced version-pinned bundle
  IDs (`bcprov*1.85.2`, `commons-codec*1.22.1`) and now-removed bundles
  (`org.apache.commons.logging`, `com.servoy.eclipse.servoypilot.assistenttests`
  in wrong position) that caused resolution failures or class-version conflicts.

### 2.2 Architecture of the test infrastructure

The integration tests are PDE plug-in tests that run inside a live Eclipse workbench
with a connected Servoy Application Server. Test classes extend a hierarchy:

```
TestUtilitiesClass          (workspace/solution setup, pumpEventsUntil, file helpers)
  └─ AbstractIntegrationTest  (NodeFolderCreatorJob toggle via @BeforeClass/@AfterClass)
       └─ ServoyRunnerTestBase  (JSUnit runner helpers, Markdown result parsers)
            └─ concrete test class
```

Prior to this work, each test class was a standalone subclass of either nothing or a
minimal `ServoyRunnerTestBase` that duplicated most of `TestUtilitiesClass`'s logic.

### 2.3 `JSUnitRunnerService` timeout handling

`JSUnitRunnerService.runForTarget` previously returned a raw `ITestRunSession` (or
`null` on timeout). This made it impossible to distinguish the two timeout cases:

1. The session never appeared — the smart client never started (true timeout; `null`).
2. The session appeared and tests started, but the run did not finish within the
   ceiling — partial results are available.

Both cases returned `null`, causing callers to discard partial results and report
only "timed out".

---

## 3. Design

### 3.1 `TestUtilitiesClass` — shared workspace infrastructure

A new class `TestUtilitiesClass` in
`com.servoy.eclipse.developer.mcp.integration` replaces all duplicated workspace
helpers across test classes. It provides:

| Method | Responsibility |
|--------|---------------|
| `waitForAppServer()` | Polls `ApplicationServerRegistry.exists()` for up to 15 s; caches the boolean result so subsequent calls return immediately |
| `ensureTestSolutionInWorkspace(BiConsumer)` | Creates or opens a minimal synthetic Servoy solution and resources project in the workspace |
| `ensureSolutionInWorkspace(...)` (static) | Low-level version, reusable without an instance |
| `ensureActiveProject()` | Activates the test solution via `ServoyModelManager`; waits with `pumpEventsUntil` until `getSolution()` and `getEditingSolution()` are non-null |
| `pumpEventsUntil(long, Runnable)` | Spin-loop that drives the SWT event queue (or `Thread.sleep` off the UI thread) until a `Runnable` of assertions passes or the deadline expires, then runs the assertions one final time (fail-fast) |
| `waitForWorkspaceBuildJobs()` | Drains `FAMILY_AUTO_BUILD` and `FAMILY_MANUAL_BUILD` via `IJobManager.join`, wrapped in `pumpEventsUntil` |
| `writeProjectFile(...)` | Creates or updates a file in a workspace project, creating parent folders as needed |
| `writeProjectFileInWorkspaceRun(...)` | Runs the above inside an `IWorkspaceRunnable` |

The key change from the old scattered code is that **all waiting is done via
`pumpEventsUntil`**, not `Thread.sleep`. The wait ends as soon as the condition is
true, and always drives the SWT event loop to avoid deadlock on the UI thread.

### 3.2 `AbstractIntegrationTest` — NodeFolderCreatorJob guard

A new class `AbstractIntegrationTest extends TestUtilitiesClass` wraps
`NodeFolderCreatorJob.setDisabled(true/false)` in JUnit 4 `@BeforeClass` /
`@AfterClass` hooks so that the npm/node build cycle is suppressed for every test
class that does not explicitly need it.

Test classes that *do* need the node folder (e.g. `CypressFormTestingIntegrationTest`)
override `disableNodeFolderCreatorJob()` and call `setDisabled(false)` instead.

This is the primary driver behind removing the "tens of ng build jobs" cost observed
in the issue description.

The class is documented to use JUnit 4 lifecycle annotations (not JUnit 5) because the
integration suite still runs with JUnit 4. If it is migrated, the hooks must stay
`static` and `public`.

### 3.3 `AbstractIntegrationTestBaseTest` — unit tests for the guard

A new JUnit 5 (Jupiter) test class `AbstractIntegrationTestBaseTest` verifies the
`@BeforeClass`/`@AfterClass` hooks directly (no OSGi required). It:

- Saves and restores `NodeFolderCreatorJob.isDisabled()` in `@BeforeEach`/`@AfterEach`
  so tests are hermetic.
- Uses `@Nested` groups to separately test the disable hook, the restore hook, and the
  opt-out override pattern.

This class is added to `AllDeveloperMcpJupiterUnitTests` so it runs in the plain JUnit
suite.

### 3.4 `ServoyRunnerTestBase` — refactored to extend the hierarchy

`ServoyRunnerTestBase` previously duplicated app-server polling, file-writing, and
sleep-based waiting. It now:

- Extends `AbstractIntegrationTest` instead of declaring those helpers locally.
- Removes its own `appServerAvailableCache`, `writeProjectFile`, and all
  `Thread.sleep` calls.
- Exposes a constructor that forwards `testSolutionName` and `servoyResourcesProjectName`
  to `TestUtilitiesClass`.

### 3.5 Bulk refactoring of concrete integration test classes

All 20+ concrete integration test classes are refactored to:

1. Extend `AbstractIntegrationTest` (directly, or via `ServoyRunnerTestBase`).
2. Pass the solution name and resources project name to the base constructor.
3. Replace all `Thread.sleep(N)` calls with `pumpEventsUntil(N, assertions)`.
4. Remove duplicated local implementations of helpers now provided by the base classes.
5. Use `ensureTestSolutionInWorkspace` / `ensureActiveProject` from the base rather
   than re-implementing solution provisioning.

### 3.6 `RunResult` record — distinguishing timeout cases in `JSUnitRunnerService`

A new record `RunResult(ITestRunSession session, boolean finishedBeforeTimeout)` is
introduced in `com.servoy.eclipse.developer.mcp.services`. The two fields separate:

- `session == null` → the session never appeared; the smart client did not start.
- `session != null && !finishedBeforeTimeout` → tests started but the ceiling was hit;
  partial results are available.
- `session != null && finishedBeforeTimeout` → clean completion.

`JSUnitRunnerService` is updated throughout:

- `runForTarget` returns `RunResult` instead of `ITestRunSession`.
- `waitForSessionByLaunch` returns `RunResult`; the early-return path (session found
  with children) sets `finishedBeforeTimeout = true`; the fallback path sets it
  `false`.
- All callers (`runTests`, `runTestMethod`, `formatGroupedResults`) check
  `runResult.session()` for null and `runResult.finishedBeforeTimeout()` to prepend
  `"Error - Timed out while running! Partial results follow:\n"` when appropriate.
- Error messages distinguish "timed out before starting" from "timed out while running".

### 3.7 PDE launch configuration fixes (`AllDeveloperMcpIntegrationTests.launch`)

| Area | Before | After |
|------|--------|-------|
| Test workspace location | `${workspace_loc}/../junit-workspace-integration` | `${workspace_loc}/../tests-mcp-developer-integration` |
| `servoy.application_server.dir` | `${workspace_loc:…/testresources}` (incomplete install) | `${servoy_install}/application_server/` (real install) |
| `servoy.junit.running` system property | absent | `-Dservoy.junit.running=true` |
| Bundle version pins | `bcprov*1.85.2`, `commons-codec*1.22.1` | Version-agnostic (`bcprov`, `commons-codec`) |
| Removed stale bundles | `org.apache.commons.logging`, duplicate entries | Cleaned up |
| Start-level corrections | Several bundles at wrong level | `org.apache.felix.scr@2`, `log4j@3`, `equinox.event@2`, `ui.tweaks@3`, `j2db_log4j@true` |
| Jasper bundle rename | `org.mortbay.jasper.apache-*` | `org.mortbay.jasper.mortbay-apache-*` |
| JUnit versions | 6.1.2 | 6.1.3 |
| Cypress bundle | missing | `com.servoy.eclipse.cypress@default:default` added |

---

## 4. Implementation plan

1. **Create `TestUtilitiesClass`** in
   `tests/…/mcp/integration/TestUtilitiesClass.java` with all shared workspace helpers
   and `pumpEventsUntil` / `waitForWorkspaceBuildJobs`.

2. **Create `AbstractIntegrationTest`** extending `TestUtilitiesClass`; add
   `@BeforeClass disableNodeFolderCreatorJob()` and `@AfterClass restoreNodeFolderCreatorJob()`.

3. **Create `AbstractIntegrationTestBaseTest`** (JUnit 5) and register it in
   `AllDeveloperMcpJupiterUnitTests`.

4. **Refactor `ServoyRunnerTestBase`** to extend `AbstractIntegrationTest`; remove
   all duplicated helpers; add forwarding constructor.

5. **Refactor all concrete integration test classes** to extend `AbstractIntegrationTest`
   (or `ServoyRunnerTestBase`), call base-class helpers, and remove all
   `Thread.sleep` waits.

6. **Create `RunResult` record** in `bundles/…/mcp/services/RunResult.java`.

7. **Update `JSUnitRunnerService`** to use `RunResult`: change return types of
   `runForTarget` and `waitForSessionByLaunch`, update all callers, add partial-results
   error prefix.

8. **Fix `AllDeveloperMcpIntegrationTests.launch`**: workspace location, VM arguments
   (`servoy_install`, `servoy.junit.running`), bundle list cleanup.

---

## 5. Acceptance criteria

- [ ] Integration test suite completes significantly faster than before (no
      unconditional `Thread.sleep` waits remaining in integration test classes).
- [ ] `NodeFolderCreatorJob` is disabled by default for all integration tests; the ng
      build cycle does not run unless a test explicitly opts in.
- [ ] `JSUnitRunnerService` distinguishes "timed out before starting" from "timed out
      while running" and returns partial results in the latter case.
- [ ] All boilerplate workspace/solution helpers are consolidated in `TestUtilitiesClass`;
      no duplication of `ensureSolutionInWorkspace`, `waitForAppServer`, or
      `pumpEventsUntil` across test classes.
- [ ] `AbstractIntegrationTestBaseTest` passes (JUnit 5, no OSGi required).
- [ ] `AllDeveloperMcpIntegrationTests.launch` points to the correct Servoy install
      directory (`${servoy_install}/application_server/`) and sets
      `-Dservoy.junit.running=true`.
- [ ] No Tomcat-generated files appear inside the git-tracked workspace directory.
- [ ] No compilation errors in the workspace after the changes.

---

## 6. Out of scope

- Migrating integration tests from JUnit 4 to JUnit 5 (the base class uses JUnit 4
  lifecycle annotations intentionally).
- Adding new integration test coverage beyond what was already present.
- Changes to non-integration test classes or to bundles other than
  `com.servoy.eclipse.developer.mcp` and `com.servoy.eclipse.developer.mcp.tests`.

---

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should `CypressFormTestingIntegrationTest`'s opt-out override be documented with a code comment explaining why it needs the node folder? | dev | resolved — comment added |
| Is `tests-mcp-developer-integration` the canonical directory name for the new PDE test workspace, or should it match a CI convention? | dev/CI | open |
