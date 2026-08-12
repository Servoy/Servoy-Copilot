# Spec: SVY-21323 â Shutdown formpreview clients & fix form name collisions

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
5. When Cypress exits, the browser process terminates but the server-side NG client is never explicitly shut down â it remains registered until WebSocket inactivity timeout eventually cleans it up

### 2.2 License exhaustion impact

On subsequent invocations, a new `FormPreviewNGClient` is created while the stale one still holds a license slot. After a few re-runs, all license slots are exhausted and the test cannot proceed.

### 2.3 Form name collision issue

Spec files are stored in flat directories keyed only by form name:
- `{workspace}/jenkins-custom/e2e-test-scripts/cypress/cy-form/{formName}.spec.cy.js`
- `{workspace}/jenkins-custom/e2e-test-scripts/cypress/cy-form-spec/{formName}.spec.js`

When two solutions have forms with the same name, `generateFormSpec` checks `Files.exists(cySpecPath)` and returns "already exists" â the second solution's form can never get a spec generated.

### 2.4 Callers of runFormCypressTests

All callers benefit from cleanup in `FormSpecRunner` since it is the common path:
- `ServoyTestingServer.testForm()` / `showAndTest()`
- `RunCypressFormTestsHandler` (run all forms)
- `RunCypressFormTestHandler` (run single form)
- `HeadlessFormTestExecutor` / `CypressFormTestRunner` (headless CI)

### 2.5 Git history

- `FormSpecRunner.java`: introduced in `c88782d` (SVY-21102), evolved in `b65979d` (SVY-21173) and `7973427` (SVY-21273) â no client shutdown logic was ever added.
- `FormPreviewNGClient.java`: always sets `"formpreview_user"` as UID, providing a reliable identification mechanism.
- `FormSpecGenerator.java`: stores spec files by form name only â no solution namespace was ever introduced.

## 3. Design

### 3.1 New private method: `shutdownFormPreviewClients()`

Add a private method in `FormSpecRunner` that finds all registered clients whose `clientInfo.getUserUid()` equals `"formpreview_user"` and shuts them down.

The bundle already depends on `j2db_server`, `servoy_shared`, and `servoy_ngclient`, so the following API path is available:

```java
private void shutdownFormPreviewClients()
{
    try
    {
        if (!ApplicationServerRegistry.exists()) return;
        ApplicationServer as = (ApplicationServer)ApplicationServerRegistry.get();
        ClientHost clientHost = as.getClientHost();
        if (clientHost == null) return;
        Map<String, ClientProxy> clients = clientHost.getClients();
        IClientManagerInternal clientManager =
            ((IDataServerInternal)as.getDataServer()).getClientManager();
        for (ClientProxy cp : clients.values())
        {
            if (FORMPREVIEW_USER_UID.equals(cp.getClientInfo().getUserUid()))
            {
                IClientInternal client = clientManager.getClient(cp.getClientInfo().getClientId());
                if (client instanceof NGClient)
                {
                    ((NGClient)client).shutDown(true);
                }
            }
        }
    }
    catch (Exception e)
    {
        // best-effort cleanup - log but don't fail the test run
    }
}
```

Key design decisions:
- **Use `ApplicationServer.getClientHost().getClients()`** - follows the established pattern in `ClientsServlet`, `ServerAccessProvider`, and `ClientsBean`.
- **Cast to `ApplicationServer`** - safe because the MCP plugin always runs inside Servoy Developer which uses this concrete class. The bundle already has `j2db_server` on its classpath.
- **`shutDown(true)` (force)** - ensures cleanup even if the client is mid-request. These are test preview clients with no user data at risk.
- **Catch all exceptions** - this is best-effort cleanup; a failure here must not break the test run itself.

### 3.2 Call sites within `FormSpecRunner.runFormCypressTests()`

1. **Before launching Cypress** (defensive cleanup of stale leftovers from crashed runs):
   - Call `shutdownFormPreviewClients()` after validating the spec file exists but before building the Cypress command.

2. **After Cypress process completes** (immediate cleanup):
   - Call `shutdownFormPreviewClients()` in a `finally` block that executes after `process.waitFor()` returns - whether the process succeeded, failed, or timed out.

### 3.3 Constant for the user UID

Extract `"formpreview_user"` as a private constant `FORMPREVIEW_USER_UID` at the top of `FormSpecRunner` to avoid magic string duplication.

### 3.4 Solution-prefixed spec file names in `FormSpecGenerator`

Include the solution name in the file name itself (no subdirectories):

**New naming pattern:**
- `cy-form/{solutionName}.{formName}.spec.cy.js`
- `cy-form-spec/{solutionName}.{formName}.spec.js`

**Changes to `FormSpecGenerator`:**

1. Add a `solutionName` parameter to path-building methods:
   - `specExists(String formName, String solutionName)`
   - `getSpecFilePath(String formName, String solutionName)`
   - `getSetupFilePath(String formName, String solutionName)`

2. Update `generateSpec()` to accept `solutionName` and build file names as `solutionName + "." + formName + extension`.

3. Update the "already exists" check (line 69-71) to use the solution-prefixed path.

**Callers to update:**
- `ServoyTestingServer.generateFormSpec()` â pass the active project's solution name
- `FormSpecRunner.runFormCypressTests()` â pass solution name when resolving spec paths
- `PersistRenameService.renameFormSpecFiles()` â already receives `ServoyProject`, extract solution name
- `CypressTestDiscoveryService` â unchanged (still scans the same flat directory; new file names are naturally discovered)

**Backward compatibility:** Existing spec files with the old naming (`{formName}.spec.cy.js`) are still discovered. The runner and discovery service check for both `{solutionName}.{formName}` and legacy `{formName}` patterns. New specs are always generated with the solution prefix.

## 4. Implementation plan

1. Add constant `private static final String FORMPREVIEW_USER_UID = "formpreview_user"` to `FormSpecRunner`.
2. Add private method `shutdownFormPreviewClients()` as described in Â§3.1.
3. In `FormSpecRunner.runFormCypressTests()`:
   - Call `shutdownFormPreviewClients()` after the spec-file-exists check for defensive pre-cleanup.
   - Wrap the process-launch-and-wait section in a try/finally, calling `shutdownFormPreviewClients()` in the finally block.
4. Add required imports for the shutdown logic.
5. In `FormSpecGenerator`:
   - Add `solutionName` parameter to `specExists()`, `getSpecFilePath()`, `getSetupFilePath()`
   - Update `generateSpec()` to accept `solutionName` and build file names as `solutionName + "." + formName + extension`
6. Update callers of `FormSpecGenerator`:
   - `ServoyTestingServer.generateFormSpec()` â pass active solution name
   - `FormSpecRunner.runFormCypressTests()` â pass solution name when checking spec existence
   - `PersistRenameService.renameFormSpecFiles()` â extract and pass solution name from `ServoyProject`
7. Add backward compatibility: runner checks for both `{solutionName}.{formName}` and legacy `{formName}` file patterns
8. Add/update unit tests for both changes
9. Verify compilation with `eclipse-ide_getCompilationErrors()`

## 5. Acceptance criteria

- [ ] Running `testForm` or `showAndTest` twice in succession does not produce `No more licenses available`
- [ ] After Cypress exits (pass or fail), no `formpreview_user` clients remain registered in the application server
- [ ] If Cypress times out and is force-destroyed, the client is still cleaned up
- [ ] If the cleanup method itself fails (e.g., app server not started), the test run still returns its normal result/error
- [ ] Pre-cleanup handles the case where a previous run crashed without cleanup (stale clients from earlier sessions are removed before starting)
- [ ] `generateFormSpec` for two forms with the same name in different solutions creates separate spec files
- [ ] Spec files are named `{solutionName}.{formName}.spec.cy.js` in the flat `cy-form/` directory
- [ ] Existing spec files with the old naming (`{formName}.spec.cy.js`) are still found and usable (backward compat)
- [ ] `CypressTestDiscoveryService` finds specs with both legacy and new naming patterns
- [ ] Existing tests continue to pass
- [ ] No compilation errors introduced

## 6. Out of scope

- Changes to the `CypressFormTestRunner` headless runner â it manages its own lifecycle separately; once `FormSpecRunner` cleans up, the headless runner benefits transitively
- License pool expansion or configuration changes

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should cleanup log a message when clients are found and shut down (for debugging)? | Implementer | open |
