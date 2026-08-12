# Triage Report â SVY-21323

**Verdict:** PROCEED

## Reported problem

Two issues with Cypress form tests:

1. **License exhaustion on re-run:** Running `testForm`/`showAndTest` multiple times causes `com.servoy.j2db.ApplicationException: No more licenses available`. Stale form-preview clients are not invalidated before launching new ones.

2. **Form name collision across solutions:** Spec files stored by form name alone (no solution namespace) cause conflicts when two solutions have forms with the same name. `generateFormSpec` says "already exists" and does nothing.

This triage focuses on issue #1 (license exhaustion), per user context: "we should shutdown the client after the tests are finished."

## Root-cause assessment

The flow is:
1. `ServoyTestingServer.testForm()` / `showAndTest()` calls `ensureTestingMode()` then `specRunner.runFormCypressTests(formName, headless)` (`ServoyTestingServer.java:225,247`)
2. `FormSpecRunner.runFormCypressTests()` launches Cypress which opens a browser connecting to `http://localhost:{port}/solution/{solutionName}/index.html?formpreview={formName}` (`FormSpecRunner.java:204-352`)
3. This `?formpreview=` request creates a `FormPreviewNGClient` instance (extends `NGClient`) which registers itself with the application server and consumes a license slot (`FormPreviewNGClient.java:42-112`)
4. The `FormPreviewNGClient` sets `userUid = "formpreview_user"` (`FormPreviewNGClient.java:109`)
5. When Cypress exits, the browser process terminates but **the server-side NG client is never explicitly shut down**. It remains registered until WebSocket inactivity timeout eventually cleans it up (which may not happen quickly enough).
6. On the next invocation, a new `FormPreviewNGClient` is created while the stale one still holds a license slot â licenses exhausted.

The root cause is clear: `FormSpecRunner.runFormCypressTests()` has no cleanup step to shut down the form-preview client after the Cypress process completes.

## Ticket premise check

The ticket correctly identifies that the `testForm`/`showAndTest` tooling should handle invalidating previous preview sessions. The user context confirms the fix: "shutdown the client after the tests are finished." This is the right approach â a code change is needed in the MCP plugin.

## Approaches considered

1. **Shut down form-preview clients after each test run in `FormSpecRunner.runFormCypressTests()`** â After `process.waitFor()` returns, iterate registered clients via `ApplicationServerRegistry.get()`, find those with `clientInfo.getUserUid() == "formpreview_user"`, and call `shutDown()` on them.
   - Pros: Fixes the root cause at the common entry point; all callers (`testForm`, `showAndTest`, `RunAllCypressFormTestsHandler`, `HeadlessFormTestExecutor`) benefit. Clients identified by the well-known `"formpreview_user"` UID.
   - Cons: Need access to `IClientManager` from within `FormSpecRunner` (should be available via `ApplicationServerRegistry`).

2. **Shut down clients in `ServoyTestingServer.testForm()`/`showAndTest()` after calling `specRunner.runFormCypressTests()`** â Same logic but at the tool method level.
   - Pros: Simpler, contained to the MCP tool layer.
   - Cons: Does not cover other callers (`RunAllCypressFormTestsHandler`, `RunCypressFormTestHandler`, `HeadlessFormTestExecutor`). Code duplication.

3. **Shut down stale clients *before* launching a new test (invalidate-on-entry)** â In `ensureTestingMode()` or at the top of `runFormCypressTests()`, kill any existing `formpreview_user` clients before starting the new one.
   - Pros: Also handles the case where a previous run crashed without cleanup.
   - Cons: Could interfere with a legitimate concurrent preview in another tool invocation (unlikely in practice for form tests, but worth noting).

4. **No code change** â Wait for WebSocket inactivity timeout to clean up clients.
   - Pros: No risk of regressions.
   - Cons: Does not fix the issue; timeout may be too long or not trigger at all in Developer mode. Users hit the license wall repeatedly.

## Recommendation

**Recommended approach: Combination of #1 and #3** â Shut down `formpreview_user` clients both before launching a new test (defensive cleanup of stale leftovers) AND after the Cypress process completes (immediate cleanup). The primary cleanup point should be `FormSpecRunner.runFormCypressTests()` since it is the common path for all callers.

Implementation sketch:
- Add a private method `shutdownFormPreviewClients()` in `FormSpecRunner`
- It finds all registered clients with `userUid == "formpreview_user"` via `ApplicationServerRegistry.get().getClientManager()` (or the `ISessionManager`/`IClientManager` available in the Developer runtime)
- Call it after `process.waitFor()` returns (whether success, failure, or timeout)
- Optionally also call it before starting the Cypress process for defensive cleanup

## Git history findings

The relevant files were introduced/modified in these commits:
- `FormSpecRunner.java`: `c88782d` (SVY-21102), `b65979d` (SVY-21173), `7973427` (SVY-21273) â no prior explicit client shutdown logic was ever added.
- `FormPreviewNGClient.java`: Introduced around 2026.6 â always sets `"formpreview_user"` as UID, providing a reliable identification mechanism for cleanup.
- The headless runner (`CypressFormTestRunner.java`) registers a `WebsocketSessionFactory` that creates `FormPreviewNGClient` instances and documents the license exhaustion problem in comments (line 186), but never implemented shutdown after tests.
