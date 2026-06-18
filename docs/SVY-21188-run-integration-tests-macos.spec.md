# Spec: SVY-21188 - Run integration tests on macOS

## 1. Goal

Make the full integration test suite (`AllDeveloperMcpIntegrationTests`) run reliably on macOS. On macOS, SWT/Cocoa requires all UI-related calls to originate from the main thread (thread 0), unlike Windows where this is not enforced. Additionally, tighten ~27 tests that use permissive OR-chain assertions which cannot distinguish a genuine success from an early error.

## 2. Background

### 2.1 macOS main-thread constraint

On macOS (Cocoa), the first thread of the process **must** be the thread that drives the SWT event loop. The PDE JUnit test runner normally executes test methods on a non-UI thread (when `run_in_ui_thread=false`), but many integration tests call into code paths that eventually execute `Display.syncExec()` or `Display.asyncExec()`. If the SWT `Display` was never created on thread 0, those calls deadlock or crash with a native Cocoa exception.

The existing `AllDeveloperMcpIntegrationTests_mac.launch` already sets:
- `-Djava.awt.headless=true`
- `run_in_ui_thread=false`
- `ATTR_USE_START_ON_FIRST_THREAD=true`

The Windows launch (`AllDeveloperMcpIntegrationTests.launch`) uses:
- `run_in_ui_thread=false`
- `ATTR_USE_START_ON_FIRST_THREAD=true`
- No `-Djava.awt.headless=true`

The key helper for UI-thread safety is `ServoyRunnerTestBase.runOnBackgroundThread()` which detects whether the current thread is the SWT UI thread and pumps events accordingly. This works on Windows but may need adjustments on macOS where the Display must be created on thread 0.

### 2.2 Integration test architecture

All 12 integration test classes live in `com.servoy.eclipse.developer.mcp.integration`:

| Class | Test count | Requires App Server | Requires NG Client |
|---|---|---|---|
| `AddTestMethodIntegrationTest` | 22 | No (uses mock project) | No |
| `CreateTestFileIntegrationTest` | 14 | No (uses mock project) | No |
| `CypressConsoleUtilIntegrationTest` | 11 | No | No (uses IConsoleManager) |
| `CypressFormTestingIntegrationTest` | 47 | Yes | Yes (Cypress tests) |
| `JSUnitRunnerIntegrationTest` | 12 | Yes | Yes (headless client) |
| `JSUnitRunnerGroupedTest` | 20 | Yes | Yes (headless client) |
| `JSUnitRunnerLayer4Test` | 22 | Yes | Yes (headless client) |
| `PersistDuplicateIntegrationTest` | 21 | Yes | No |
| `RenamePersistIntegrationTest` | 29 | Yes | No |
| `ServoyDevServerIntegrationTest` | 12 | No (reflection tests) | No |
| `ServoyIdeServerIntegrationTest` | 5 | No (reflection tests) | No |
| `ShowFormInBrowserIntegrationTest` | 10 | Yes | Yes |

**Total: ~225 tests across 12 classes.**

### 2.3 The permissive assertion problem

An audit found ~27 tests that use OR-chains including `"Error"` in their success assertions. Example:

```java
assertTrue("Should return results",
    result.contains("passed") || result.contains("failed") || result.contains("Error"));
```

This means if the tool crashes and returns `"Error: NPE at line 42"`, the test still passes. These tests provide false confidence and must be tightened.

### 2.4 Affected classes with permissive assertions

| Class | Suspicious test count |
|---|---|
| `CypressFormTestingIntegrationTest` | 10 |
| `RenamePersistIntegrationTest` | 5 |
| `ServoyIdeServerIntegrationTest` | 4 |
| `JSUnitRunnerIntegrationTest` | 3 |
| `ShowFormInBrowserIntegrationTest` | 3 |
| `ServoyDevServerIntegrationTest` | 2 |

## 3. Design

### 3.1 macOS launch configuration fixes

The `_mac` launch configuration needs to ensure:

1. **`-XstartOnFirstThread`** is passed as a VM argument (not just `ATTR_USE_START_ON_FIRST_THREAD`). On macOS, Eclipse's PDE launcher should add this automatically when `ATTR_USE_START_ON_FIRST_THREAD=true`, but this must be verified.

2. **The Servoy application_server.dir** should use an absolute path to `testresources`, matching the Windows launch config pattern: `-Dservoy.application_server.dir=<workspace>/tests/com.servoy.eclipse.developer.mcp.tests/testresources`. The mac launch currently uses `${servoy_install}/application_server` which requires an Eclipse variable to be configured; change it to use the same testresources-based approach as Windows.

3. **Platform-specific bundles**: The mac launch correctly deselects `com.servoy.eclipse.nodejs.win32.win32.x86_64` and selects `com.servoy.eclipse.nodejs.macosx.cocoa.aarch64`. Verify SWT cocoa bundle is included.

### 3.2 `ServoyRunnerTestBase` enhancements for macOS

The `runOnBackgroundThread()` method already handles the UI-thread case by pumping events. On macOS, the issue is that `Display.getDefault()` may not yet exist if the test runner starts before the workbench initializes. The method should:

1. Handle `Display.getDefault() == null` gracefully (treat as non-UI thread, just join).
2. Add a timeout log message if the background thread is still alive past the deadline so CI logs show where hangs occur.

### 3.3 Tightening permissive assertions

The general pattern to fix is:

**Before (permissive):**
```java
assertTrue("msg", result.contains("success") || result.contains("Error"));
```

**After (strict, success-path test):**
```java
assertFalse("Should not start with Error: " + result, result.startsWith("Error"));
assertTrue("Should contain success indicator", result.contains("success"));
```

**After (strict, error-path test):**
```java
assertTrue("Should be an error", result.startsWith("Error"));
assertTrue("Should mention specific reason", result.contains("required"));
```

Each suspicious test falls into one of two categories:
- **Success-path tests** : the operation should succeed; remove `"Error"` from the OR-chain and assert the absence of error.
- **Error-path tests** : the operation should fail with a specific message; assert the error starts with `"Error"` AND contains a specific substring.

### 3.4 Test granularity: run a single test, a class, or all

The ticket explicitly requires support for:
1. Running a **single selected test method** on macOS
2. Running a **selected test class** on macOS
3. Running **all tests** via the `AllDeveloperMcpIntegrationTests_mac.launch` configuration

This is already supported by the PDE JUnit launcher (right-click -> Run As -> JUnit Plug-in Test). The `_mac` launch provides the "all" scenario. For individual classes, developers can right-click and run, inheriting the product/VM args from the workspace launch configuration. No additional launch configs are needed per-class.

## 4. Implementation plan

1. **Update `AllDeveloperMcpIntegrationTests_mac.launch`**:
   - Change `-Dservoy.application_server.dir` to use the absolute path to `testresources` (same as Windows launch).
   - Verify `-XstartOnFirstThread` is handled by `ATTR_USE_START_ON_FIRST_THREAD=true` (it is, for PDE launches).
   - Ensure `com.equo.chromium.cef.cocoa.macosx.aarch64` is correctly referenced (already present).
   - Add `-Dorg.eclipse.swt.internal.cocoa.allowMainThreadCalls=true` to VM args if needed for SWT access from non-main threads during tests.

2. **Enhance `ServoyRunnerTestBase.runOnBackgroundThread()`**:
   - Add null-safety for `Display.getDefault()` returning null on macOS before workbench init.
   - Add a `System.err.println` timeout warning when the background thread exceeds the deadline.

3. **Tighten assertions in `CypressFormTestingIntegrationTest`** (10 tests):
   - `testTestForm_runsAndReturnsResults` : replace with `assertFalse(result.startsWith("Error"))` + `assertTrue(result.contains("passed") || result.contains("failed"))`
   - `testTestForm_autoGeneratesSpecIfMissing` : already strict (checks file existence), no change needed
   - `testGenerateFormSpec_runCypressDirectly` : same as testTestForm_runsAndReturnsResults
   - `testCypress_buttonClickUpdatesLabel` : same pattern
   - `testCypress_generatedSpecPassesForButtonLabelForm` : assert `result.contains("passed")`
   - `testGenerateFormSpec_returnsSuccessMessage` : remove `"already exist"` branch (spec was just deleted), assert `result.contains("Created")`
   - `testScreenshotForm_validForm_returnsResult` : `assertFalse(result.startsWith("Error"))` + `assertTrue(result.contains(".png"))`
   - `testCreateTestFile_validParams_createsFile` : `assertFalse(result.startsWith("Error"))` + `assertTrue(result.contains("Created"))`
   - `testAddTestMethod_validParams_addsMethod` : `assertFalse(result.startsWith("Error"))` + `assertTrue(result.contains("Added") || result.contains("Updated"))`
   - `testGenerateFormSpec_validForm_generatesFiles` : `assertTrue(result.contains("Created"))`

4. **Tighten assertions in `ServoyIdeServerIntegrationTest`** (4 tests):
   - `testGetClassOutline_unknownScript_returnsNotFound` : `assertTrue(result.contains("not found"))`
   - `testGetMethodSource_nullName_throws` : expect `RuntimeException` or `result.startsWith("Error")` + `result.contains("required")`; remove catch-all approach
   - `testGetMethodSource_nullMethodNames_returnsError` : `assertTrue(result.startsWith("Error"))` + content check
   - `testGetFilteredSource_nullName_throws` : same as nullName_throws above

5. **Tighten assertions in `ShowFormInBrowserIntegrationTest`** (3 tests):
   - `testShowFormInBrowser_nullForm_returnsError` : `assertTrue(result.startsWith("Error"))`
   - `testShowFormInBrowser_emptyString_returnsUrl` : assert `result.contains("formpreview=")` and `assertFalse(result.startsWith("Error"))` (the test name indicates a URL is expected)
   - `testCheckNGClientStatus_returnsInfo` : `assertFalse(result.startsWith("Error"))` + `assertTrue(result.contains("running") || result.contains("URL"))`

6. **Tighten assertions in `RenamePersistIntegrationTest`** (5 tests):
   - `testRenameForm_viaTool` : `assertTrue(result.contains("successfully") || result.contains("Renamed"))` (drop `"Error"`)
   - `testRenamePersist_unsupportedType_returnsError` : `assertTrue(result.startsWith("Error"))` + `assertTrue(result.contains("Unsupported"))`
   - `testRenamePersist_sameName_returnsError` : `assertTrue(result.startsWith("Error"))` + `assertTrue(result.contains("same"))`
   - `testRenamePersist_nullOldName_returnsError` : `assertTrue(result.startsWith("Error"))` + `assertTrue(result.contains("required") || result.contains("oldName"))`
   - `testRenamePersist_nullNewName_returnsError` : `assertTrue(result.startsWith("Error"))` + `assertTrue(result.contains("required") || result.contains("newName"))`

7. **Tighten assertions in `JSUnitRunnerIntegrationTest`** (3 tests):
   - `testActiveSolution_runAll_returnsNonNullResult` : add `assertFalse(result.startsWith("Error"))`
   - `testActiveSolution_runModules_doesNotCrash` : add `assertFalse(result.startsWith("Error"))`
   - `testActiveSolution_runForms_doesNotCrash` : add `assertFalse(result.startsWith("Error"))`
   - `testActiveSolution_runNullScope_producesValidOutput` : remove `result.startsWith("Error")` from accepted set; keep `result.contains("| Passed") || result.contains("No ")` as valid outcomes

8. **Tighten assertions in `ServoyDevServerIntegrationTest`** (2 tests):
   - `testResolveIdentifierType_nullIdentifier_returnsError` : remove catch block; `assertTrue(result.startsWith("Error"))` + `assertTrue(result.contains("required"))`
   - `testResolveIdentifierType_unknownForm_returnsNotFound` : remove catch block; `assertTrue(result.contains("not found"))`

9. **Verify on macOS**: Run the full suite using `AllDeveloperMcpIntegrationTests_mac.launch` on a macOS machine and confirm all tests pass.

## 5. Acceptance criteria

- [ ] `AllDeveloperMcpIntegrationTests` passes on macOS (Apple Silicon) using the `_mac` launch config
- [ ] Individual test classes can be launched independently on macOS via right-click -> Run As -> JUnit Plug-in Test
- [ ] Individual test methods can be run in isolation on macOS
- [ ] All 27 previously-permissive assertions are tightened to distinguish success from error
- [ ] No regressions on Windows : the Windows launch config continues to work unchanged
- [ ] `CypressFormTestingIntegrationTest.testCypressConfigIsGenerated` failure is documented (requires npm/Cypress setup) and does not block the rest of the suite
- [ ] `ServoyRunnerTestBase.runOnBackgroundThread()` handles null `Display` gracefully

## 6. Out of scope

- Installing Cypress / npm on the macOS CI agent (Cypress tests may still skip/fail if not installed)
- Changing the test architecture to avoid PDE JUnit Plugin Test launches
- Adding new integration tests
- Fixing tests that fail due to missing Servoy application server (these are environment-dependent)

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should `testShowFormInBrowser_emptyString_returnsUrl` expect a URL or an error for empty-string input? | Diana | resolved - expect URL (test name says "returnsUrl", assert `formpreview=` present) |
| Do we need a CI pipeline for macOS integration tests, or is local developer verification sufficient for now? | Diana | resolved - local verification for now |
| Should the `_mac` launch config use a variable for `servoy_install` or resolve from Eclipse installation? | Diana | resolved - use absolute path to testresources (same as Windows) |
| For `testActiveSolution_runNullScope_producesValidOutput`, should the `"Error"` branch truly be removed given null scope maps to ALL and may legitimately error on environments without test methods? | Diana | resolved - remove Error branch; valid outcomes are markdown table or "no tests" notice |
