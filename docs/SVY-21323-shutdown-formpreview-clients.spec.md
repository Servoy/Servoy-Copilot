# Spec: SVY-21323 — Shutdown formpreview clients & fix form name collisions

## 1. Goal

Fix two issues in Cypress form testing:
1. Prevent license exhaustion (`No more licenses available`) by explicitly shutting down `FormPreviewNGClient` instances after each test run completes and defensively before each new run starts.
2. Prevent form name collisions across solutions by namespacing spec files under a solution subdirectory.

## 2. Background

### 2.1 Current form test flow

1. `ServoyTestingServer.testForm()` / `showAndTest()` calls `FormSpecRunner.runFormCypressTests(formName, headless)`
2. `runFormCypressTests()` launches a Cypress process that opens `http://localhost:{port}/solution/{solutionName}/index.html?formpreview={formName}`
3. The `?formpreview=` request triggers creation of a `FormPreviewNGClient` instance (extends `NGClient`) which registers with the application server and consumes a license slot
4. The `FormPreviewNGClient` sets `userUid = "formpreview_user"` (`FormPreviewNGClient.java:109`)
5. When Cypress exits, the browser process terminates but the server-side NG client is never explicitly shut down — it remains registered until WebSocket inactivity timeout eventually cleans it up

### 2.2 License exhaustion impact

On subsequent invocations, a new `FormPreviewNGClient` is created while the stale one still holds a license slot. After a few re-runs, all license slots are exhausted and the test cannot proceed.

### 2.3 Form name collision issue

Spec files are stored in flat directories keyed only by form name:
- `{workspace}/jenkins-custom/e2e-test-scripts/cypress/cy-form/{formName}.spec.cy.js`
- `{workspace}/jenkins-custom/e2e-test-scripts/cypress/cy-form-spec/{formName}.spec.js`

When two solutions have forms with the same name, `generateFormSpec` checks `Files.exists(cySpecPath)` and returns "already exists" — the second solution's form can never get a spec generated.

### 2.4 Callers of runFormCypressTests

All callers benefit from cleanup in `FormSpecRunner` since it is the common path:
- `ServoyTestingServer.testForm()` / `showAndTest()`
- `RunCypressFormTestsHandler` (run all forms)
- `RunCypressFormTestHandler` (run single form)
- `HeadlessFormTestExecutor` / `CypressFormTestRunner` (headless CI)

### 2.5 Git history

- `FormSpecRunner.java`: introduced in `c88782d` (SVY-21102), evolved in `b65979d` (SVY-21173) and `7973427` (SVY-21273) — no client shutdown logic was ever added.
- `FormPreviewNGClient.java`: always sets `"formpreview_user"` as UID, providing a reliable identification mechanism.
- `FormSpecGenerator.java`: stores spec files by form name only — no solution namespace was ever introduced.

## 3. Design

### 3.1 New private method: `shutdownFormPreviewClients()`

Add a private method in `FormSpecRunner` that finds all registered clients whose `clientInfo.getUserUid()` equals `"formpreview_user"` and shuts them down.

### 3.2 Call sites within `FormSpecRunner.runFormCypressTests()`

1. **Before launching Cypress** (defensive cleanup of stale leftovers from crashed runs)
2. **After Cypress process completes** (immediate cleanup in a `finally` block)

### 3.3 Constant for the user UID

Extract `"formpreview_user"` as a private constant `FORMPREVIEW_USER_UID`.

### 3.4 Solution-prefixed spec file names in `FormSpecGenerator`

**New naming pattern:**
- `cy-form/{solutionName}.{formName}.spec.cy.js`
- `cy-form-spec/{solutionName}.{formName}.spec.js`

**Backward compatibility:** Existing spec files with the old naming are still discovered via `findExistingSpecFile`/`findExistingSetupFile` which check prefixed first, then legacy.

## 4. Implementation plan

1. Add constant `FORMPREVIEW_USER_UID` to `FormSpecRunner`
2. Add `shutdownFormPreviewClients()` method
3. Call it before launch and in finally block in `runFormCypressTests()`
4. In `FormSpecGenerator`: add solution-prefixed methods and update `generateSpec()`
5. Update `CypressTestDiscoveryService.hasFormSpec()` to check both patterns
6. Update `PersistRenameService.renameFormSpecFiles()` to use findExisting* methods
7. Add unit tests
8. Verify zero compilation errors

## 5. Acceptance criteria

- [ ] Running `testForm` twice in succession does not produce `No more licenses available`
- [ ] After Cypress exits, no `formpreview_user` clients remain registered
- [ ] If Cypress times out, the client is still cleaned up
- [ ] If cleanup itself fails, the test run still returns its normal result
- [ ] `generateFormSpec` for same-named forms in different solutions creates separate spec files
- [ ] Spec files named `{solutionName}.{formName}.spec.cy.js`
- [ ] Legacy spec files still found (backward compat)
- [ ] `CypressTestDiscoveryService` finds specs with both naming patterns
- [ ] No compilation errors introduced

## 6. Out of scope

- Changes to headless runner lifecycle
- License pool expansion

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should cleanup log when clients are found and shut down? | Implementer | open |
