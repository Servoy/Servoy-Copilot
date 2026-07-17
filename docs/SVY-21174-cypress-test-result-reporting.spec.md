# Spec: SVY-21174 — Cypress test result reporting (JUnit-style runner view)

## 1. Goal

Provide a JUnit-style test runner view in Servoy Developer that displays Cypress test results (both form tests and E2E tests) with real-time progress, per-test pass/fail status, and a failure detail pane. This replaces scrolling through raw console output with a structured, navigable results UI — similar to Eclipse's built-in JUnit view.

## 2. Background

### 2.1 Current state

The Cypress testing feature (SVY-21170, SVY-21169) already works:

- `RunCypressFormTestHandler` runs a single form's `.spec.cy.js` via `FormSpecRunner.runSpec()`.
- `RunAllCypressFormTestsHandler` iterates all form tests, counts pass/fail, and writes results to the "Cypress Form Tests" `MessageConsole`.
- `FormSpecRunner.runSpec()` / `runE2ESpec()` return raw strings with pass/fail determined by checking for `"All tests passed"`.
- `CypressTestDiscoveryService` discovers test types (FORM vs E2E) and enumerates form tests via `discoverAllTestForms()`.
- Results are unstructured text dumped into the Console view.

**Directory structure (post SVY-21169/SVY-21171 restructure):**
- Form tests: `jenkins-custom/e2e-test-scripts/cypress/cy-form/<formName>.spec.cy.js`
- Form specs (setUp/tearDown): `jenkins-custom/e2e-test-scripts/cypress/cy-form-spec/<formName>.spec.json`
- E2E tests: `jenkins-custom/e2e-test-scripts/cypress/e2e/<solutionName>/<testName>.cy.js` (or `.cy.ts`)

### 2.2 Limitations

- No tree-based view showing test status per form (like JUnit runner).
- No visual progress bar during "run all" execution.
- No structured failure details — developer must scroll through raw output.
- E2E tests and form tests use the same reporting path but have no unified results UI.

### 2.3 Architecture context

The `com.servoy.eclipse.developer.mcp` bundle has access to:
- `org.eclipse.ui.console` — Console view APIs (used today)
- `org.eclipse.jface` — TreeViewer, ProgressBar for custom views
- `org.eclipse.ui` — ViewPart, perspective extensions
- `CypressTestDiscoveryService` — discovers test type (FORM vs E2E)
- `CypressFormTestTarget` — adapter providing form name and test form list
- `FormSpecRunner` — executes Cypress processes

## 3. Design

### 3.1 Cypress Test Results View (JUnit-style)

A new Eclipse ViewPart: **"Cypress Test Results"** (id: `com.servoy.eclipse.developer.mcp.views.cypressTestResults`).

**Layout (modelled on Eclipse JUnit view):**

```
┌─────────────────────────────────────────────────────────────┐
│ [▶ Re-run] [▶▶ Form] [▶▶ E2E] [■ Stop] [✕ Clear] [⚠ Filter] [📋 History] │
│ Runs: 12/15, Failures: 2, Errors: 1  ██████░░░░ 80%        │
├─────────────────────────────────────────────────────────────┤
│  ✓ loginForm                   FORM    1.2s                 │  ← table
│  ✓ dashboardForm               FORM    0.8s                 │
│  ✗ orderForm                   E2E     3.1s   Timed out...  │
│  ◌ customerForm                E2E     —                    │  (pending)
│  ⟳ invoiceForm                 E2E     —                    │  (running)
├─────────────────────────────────────────────────────────────┤
│ [Raw Output / Detail Pane]                                  │
│ AssertionError: Expected element '.order-total' to have     │
│ text '100.00', but the text was '0.00'                      │
│ at Context.eval (orderForm.spec.cy.js:23:5)                 │
└─────────────────────────────────────────────────────────────┘
```

**Components:**
- **Toolbar**: Re-run Last (disabled when no previous results), Run All Form Tests, Run All E2E Tests, Stop, Clear, Filter Failures Only (checkbox), Test Run History... buttons. A counters label ("Runs: X/Y, Failures: Z, Errors: E").
- **Progress bar**: Green when all passing, turns blue on first failure, turns red on first error (like JUnit).
- **Test list** (`TableViewer`): One row per test (form or E2E). Columns: status icon, test name, type, duration, **Video** (clickable "▶ play" when a video exists), **Screenshot** (clickable "▦ view" when a screenshot exists), error summary.
- **Detail pane** (bottom `SashForm`): Shows full Cypress output for the selected test in a read-only `StyledText`.
- **Context menu** on table rows: "Re-run Test" (runs the single selected test), "Open Test File" (opens the spec file in editor), "Copy Error to Clipboard", "Copy Full Output to Clipboard", and (for failed tests with media) "Open Video" / "Open Screenshot".

**Status icons per row:**
| State | Icon | Description |
|-------|------|-------------|
| Pending | ○ grey circle | Not yet started |
| Running | ⟳ spinner | Currently executing |
| Passed | ✓ green checkmark | All assertions passed |
| Failed | ✗ blue X | Cypress ran but assertions failed |
| Error | ✗ red X | Test couldn't complete (timeout, crash, missing file, exception) |

**Interactions:**
- Double-click a row → opens the `.spec.cy.js` (or `.cy.js`/`.cy.ts`) test file in editor.
- Right-click a row → context menu (see Components above).
- Click a **Video** / **Screenshot** cell → opens that media in the Eclipse internal browser (renders both `.mp4` and `.png`), falling back to the OS default application.
- Tests update in real-time as each test completes during "run all".
- View auto-opens when a test run starts.

### 3.2 Structured result model

```java
package com.servoy.eclipse.developer.mcp.actions;

public record CypressTestResult(
    String testName,         // form name or E2E test name
    TestType testType,       // FORM or E2E
    TestStatus status,       // PENDING, RUNNING, PASSED, FAILED, ERROR
    String errorSummary,     // first failure/error line (null if passed)
    String rawOutput,        // full Cypress stdout
    long durationMs,         // execution time
    String videoPath,        // preserved video path (null if none)
    String screenshotPath    // preserved screenshot path (null if none)
) {
    public enum TestStatus { PENDING, RUNNING, PASSED, FAILED, ERROR }
    public enum TestType { FORM, E2E }

    // Convenience constructor without media paths (videoPath/screenshotPath = null)
    // plus hasVideo() / hasScreenshot() helpers.
}
```

**Status determination logic** (from `FormSpecRunner` output):
- `PASSED` — result contains `"All tests passed"`
- `FAILED` — result contains `"Some tests failed"` (Cypress ran, assertions failed)
- `ERROR` — result starts with `"Error:"` or `"Error running spec:"` or `"Error running E2E spec:"` (timeout, crash, exception)

### 3.3 Test session manager

A `CypressTestSessionManager` (singleton) coordinates between handlers and the view:

- `startSession(List<String> testNames, TestType type)` — stops any running session first, initializes results as PENDING, creates a history entry immediately, notifies view.
- `updateResult(String testName, CypressTestResult result)` — updates a single test, updates the active history entry, notifies view.
- `markRunning(String testName, TestType type)` — marks a test as running, updates history.
- `stop()` — sets running=false AND kills the active Cypress process via `FormSpecRunner.cancel()`.
- `setActiveRunner(FormSpecRunner runner)` — registers the runner so stop can kill the process.
- `getResults()` — returns current session state.
- Fires `PropertyChangeEvent` so the view refreshes per-test (real-time updates).

### 3.4 Test run history

The session manager maintains an in-memory history (up to 50 entries) of all test runs since Developer was opened:

- **History entries are created immediately** when a session starts (not deferred to completion).
- Each `HistoryEntry` stores: label, timestamp, and a live-updating list of results.
- The entry's `toString()` shows: `"HH:mm:ss - E2E Tests (10 tests) (5 passed, 5 failed)"` with `"[running]"` suffix if still in progress.
- A "Test Run History..." toolbar button opens a `ListDialog` where the user can select a previous run to view.
- Selecting a history entry restores its results into the view (read-only, not re-running).
- History is in-memory only — resets when Developer restarts.

### 3.5 Re-run behavior

- **Re-run Last** toolbar button: re-runs the exact same test names from the current session (not re-discovering). For single tests uses `RunSingleTestHandler`, for multi-test FORM uses `RunAllCypressFormTestsHandler.runFormTests(names)`, for multi-test E2E uses `RunAllE2ETestsHandler(testNames)`. Disabled when no results exist.
- **Re-run Failed** toolbar button: collects only FAILED/ERROR tests and re-runs them, respecting test type (FORM vs E2E).
- **Re-run Test** context menu: runs just the selected test via `RunSingleTestHandler`.
- **Starting a new run while one is in progress**: automatically stops the previous run (kills the Cypress process) before starting the new one.

`RunAllCypressFormTestsHandler.runFormTests(List<String>)` accepts an explicit form list, or discovers all forms when null. This lets re-run reuse the exact set. Both form and E2E runs register the runner via `setActiveRunner` so Stop can kill the process.

### 3.5.1 Artifact preservation

Cypress wipes the `results`/`videos`/`screenshots` folders on each run, so on a failed test `FormSpecRunner.preserveArtifacts()` copies the media to a stable per-run folder `cypress/preserved-artifacts/<testName>-<timestamp>/` so it survives until Developer is closed.

- **Disk-based discovery** (not console-text parsing): Cypress wraps media paths across multiple lines in its console box, so the runner instead scans `cypress/videos` for `<spec>.mp4` and `cypress/screenshots` recursively for a matching `.png` (preferring a `(failed)` screenshot).
- After copying, it appends single-line markers to the returned output — `[Preserved Video] <path>` and `[Preserved Screenshot] <path>` — which `CypressOutputParser.extractVideoPath()` / `extractScreenshotPath()` read back reliably (constants `PRESERVED_VIDEO_MARKER` / `PRESERVED_SCREENSHOT_MARKER`).
- These paths populate `CypressTestResult.videoPath` / `screenshotPath`, driving the clickable Video/Screenshot columns.
- **Startup cleanup**: `cleanPreservedArtifactsOnce()` runs once per JVM to delete artifacts left from a previous Developer session, keeping the current session's media.
- Preservation failures are non-fatal.

### 3.5.3 Enabling video & screenshots per run

Rather than editing the (version-controlled) `cypress.config.ts`/`.js`, both `runSpec` and `runE2ESpec` pass `--config video=true,screenshotOnRunFailure=true` on the Cypress command line. This forces media capture for that run only and never mutates the repo config.

### 3.5.4 Project-local Cypress bootstrap

Before running, `ensureProjectCypressInstalled()` checks whether the `e2e-test-scripts` repo manages its own Cypress (has a `package.json`) but hasn't been installed (`node_modules/.bin/cypress` missing). If so, it runs `npm install` in that directory so the project-local Cypress is available. If there is no `package.json`, or Cypress is already present, it does nothing and the bundled `.metadata` Cypress fallback is used. `resolveLocalCypressCmd()` centralizes locating the local binary (shared by form and E2E paths).

### 3.5.2 UI polish

- **Row coloring**: cached `Color` fields (green/blue/red) applied as row backgrounds, disposed with the view.
- **Sortable columns**: clicking a column header toggles asc/desc sort by name, type, duration, or status.
- **Filter text box**: a search field above the table filters visible tests by name substring.
- **Tab progress**: the view content description shows "(completed/total)" during a run and a result summary when done.
- **Clipboard**: context menu can copy the error summary or the full raw output.

### 3.6 Stop / Cancel behavior

The stop button (and implicit stop on new session start):
1. Sets `running = false` — prevents the next test from starting in multi-test runs.
2. Calls `FormSpecRunner.cancel()` — forcibly destroys the active Cypress OS process immediately.

`FormSpecRunner` tracks the active `Process` in a volatile field and provides a `cancel()` method that calls `process.destroyForcibly()`.

### 3.7 Single test execution

`RunSingleTestHandler` runs a single test by name and type:
- Creates a 1-test session via the session manager.
- Runs `FormSpecRunner.runE2ESpec()` or `runSpec()` depending on test type.
- Reports result to session manager.
- Used by "Re-run Test" context menu and "Re-run Last" when session had a single test.

### 3.8 Integration with existing handlers

Both `RunCypressFormTestHandler` and `RunAllCypressFormTestsHandler` are updated to:
1. Open/reveal the Cypress Test Results view at the start of a run.
2. Call `CypressTestSessionManager.startSession(...)` with the list of tests.
3. Register the runner via `setActiveRunner(runner)`.
4. After each test completes, parse the result and call `updateResult(...)`.
5. Clear the active runner in a `finally` block.
6. The existing console output continues unchanged (backward compatible).

For single-form runs, the session has just one entry.

### 3.9 Output parsing

A `CypressOutputParser` utility extracts:
- Pass/fail/error status:
  - `"All tests passed"` → PASSED
  - `"Some tests failed"` → FAILED (assertion failure)
  - Starts with `"Error:"` → ERROR (infrastructure/setup problem)
- Duration (wall-clock time: measured from process start to process exit)
- Error summary: for FAILED tests it scans for the real assertion line (`AssertionError`, `CypressError`, `Timed out`, `expected…`), skipping Cypress box-drawing separators and boilerplate; for ERROR state it returns the first meaningful error line.
- Media paths: `extractVideoPath()` / `extractScreenshotPath()` read the `[Preserved Video]` / `[Preserved Screenshot]` marker lines appended by `preserveArtifacts()` (see 3.5.1).

### 3.10 Console coexistence

The existing "Cypress Form Tests" console continues to work exactly as before. The new results view is complementary — structured navigation vs. raw log.

## 4. Implementation plan

1. **Create `CypressTestResult` record** in `c.s.e.d.mcp.actions` — status enum, test type enum, immutable result data.

2. **Create `CypressOutputParser`** in `c.s.e.d.mcp.services` — static methods to extract error summary and duration from raw Cypress output.

3. **Create `CypressTestSessionManager`** in `c.s.e.d.mcp.actions` — holds current session results, fires property-change events for the view. Includes in-memory history (up to 50 runs), auto-stop on new session, active runner tracking.

4. **Create `CypressTestResultsView` ViewPart** in `c.s.e.d.mcp.views` — toolbar (Re-run Last, Run All Form Tests, Run All E2E Tests, Stop, Clear, Filter toggle, History), progress bar, TableViewer with context menu ("Re-run Test", "Open Test File"), detail pane. Listens to session manager for real-time updates.

5. **Create `RunSingleTestHandler`** in `c.s.e.d.mcp.actions` — runs a single test by name and type, reports to session manager. Used by context menu and re-run logic.

6. **Register the view in `plugin.xml`** — `org.eclipse.ui.views` extension point, category "Servoy".

7. **Add `discoverAllE2ETests()` to `CypressTestDiscoveryService`** — walks `cypress/e2e/<solutionName>/` recursively and returns all `.cy.js`/`.cy.ts` test names (analogous to existing `discoverAllTestForms()`).

8. **Create `RunAllE2ETestsHandler`** in `c.s.e.d.mcp.actions` — new command handler that uses `discoverAllE2ETests()` to find tests (or accepts explicit test names for re-run), runs each via `FormSpecRunner.runE2ESpec()`, and reports results to the session manager.

9. **Register "Run All E2E Tests" context menu action** in `plugin.xml` — `org.eclipse.ui.menus` contribution to Solution Explorer, enabled at solution level (same pattern as "Run All Cypress Form Tests").

10. **Update `RunCypressFormTestHandler`** — start session (1 test), parse result, update session, reveal view.

11. **Update `RunAllCypressFormTestsHandler`** — start session (N tests), update each test as it completes, reveal view.

12. **Add `cancel()` to `FormSpecRunner`** — tracks active process, provides cancellation support.

13. **Export/keep internal** — the `views` package stays internal (not exported).

## 5. Acceptance criteria

- [x] A "Cypress Test Results" view is available under Window > Show View > Servoy.
- [x] When a single Cypress form test runs, the view opens showing one entry that transitions from RUNNING → PASSED/FAILED/ERROR.
- [x] When "Run All Cypress Form Tests" executes, the view shows all forms with real-time status updates as each completes.
- [x] "Run All E2E Tests" context menu action is available on the solution in Solution Explorer.
- [x] E2E Cypress tests report results into the same view as form tests.
- [x] The progress bar is green when all tests pass, turns blue on assertion failures, and turns red on errors.
- [x] The counters label shows "Runs: X/Y, Failures: Z, Errors: E" updating in real-time.
- [x] Selecting a failed or errored test shows its Cypress output/error message in the detail pane.
- [x] Double-clicking a row opens the corresponding test file in the editor.
- [x] The existing "Cypress Form Tests" console still receives raw output (backward compatible).
- [x] The toolbar has Re-run Last, Run All Form Tests, Run All E2E Tests, Stop, Clear, Filter Failures Only, and Test Run History buttons.
- [x] The view supports filtering to show only failures/errors.
- [x] Duration is measured as wall-clock time per test.
- [x] Right-click context menu on test rows with "Re-run Test" and "Open Test File" actions.
- [x] "Re-run Test" runs only the selected single test (not all tests).
- [x] "Re-run Last" re-runs the exact same test names from the last session (not re-discovering).
- [x] "Re-run Last" is disabled when no test results exist.
- [x] Test Run History stores all sessions since Developer was opened (in-memory, up to 50).
- [x] History entries are created immediately when a run starts and update live as results come in.
- [x] Selecting a history entry restores those results in the view.
- [x] Stop button kills the active Cypress process immediately (not just preventing the next test).
- [x] Starting a new run while one is in progress automatically stops the previous run.
- [x] "Re-run Failed" toolbar button re-runs only the failed/errored tests (handles FORM and E2E correctly).
- [x] Row background coloring: green for passed, blue for failed, red for errored.
- [x] Sortable columns (click headers to sort by name, type, duration, status).
- [x] Filter text box to filter visible tests by name.
- [x] Context menu "Copy Error to Clipboard" and "Copy Full Output to Clipboard".
- [x] Context menu "Open Video" / "Open Screenshot" for failed tests (media preserved before next run wipes it).
- [x] Clickable Video and Screenshot columns open the media in the Eclipse internal browser.
- [x] Preserved artifacts survive until Developer is closed; stale artifacts from a previous session are cleaned once at startup.
- [x] Video/screenshots are forced per-run via `--config` without modifying the repo's cypress config.
- [x] Project-local Cypress is npm-installed automatically when the repo manages its own but isn't bootstrapped.
- [x] Error column shows the real assertion/error line, not Cypress separator boxes or boilerplate.
- [x] View tab shows progress ("(5/10)") while running and result summary when done.
- [x] Stop works for both form and E2E runs.

## 6. Out of scope

- Problem markers on form resources.
- Parsing individual `it()` blocks within a Cypress spec — reporting is per-form/per-test-file level.
- CI/CD integration or JUnit XML export.
- Persisting test results across IDE sessions (history is in-memory only).
- Custom ANSI color rendering in the detail pane.
- Custom view icon (uses default Eclipse view icon).

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| — | — | — |
