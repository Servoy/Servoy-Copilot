# SVY-21284 review summary: Investigate how to run integration tests faster

**Risk: MODERATE.** Mostly test infrastructure (plainjunit/integrationpde split, Copy-suffixed utils, per-branch PDE launchers, Jenkins integration stages, test-mode gates, condition waits, workspace reuse). The driver is that servoy-eclipse renamed public `FormSpecGenerator.specExists(String)` and its path getters in an exported package without keeping the old names. Older Copilot builds would hit `NoSuchMethodError` on newer Developers. This was asked on the ticket.

Reviewed by Diana Bunaciu on 2026-11-24. Resolved (Fixed).

## Scope
- Servoy-Copilot `master`: 41 commits, 23db384..6b89347. The key ones are 636c52d, 1132e96, 1f3dd4f, 1399142.
- servoy-eclipse: 66 commits (cherry-picks on master/release/lts_2026). The key ones are 1088eaefbe / 2995c88fee, 98dccd6836, 0cf972fde4, e3bca921bd.
- servoy-client: 15 commits (14b3dd0fb `Utils.getURLContent` overload, 39d3cfbad JUnit 6).
- server: 2 commits (agent docs).

## Manual test plan
**Verifying the change**
1. servoy-eclipse Jenkins (master, release): the Integration Tests stage runs cypress/ngclient.ui/ui/model/designer tests with plausible counts. Note its duration against the ~20 min baseline.
2. Servoy-Copilot Jenkins: the Integration Tests stage runs `developer.mcp.tests` and `opencode.tests`, and failures turn the build UNSTABLE/FAILED.
3. Locally, run the `developer.mcp.tests` integrationpde package with `PDEIntegrationTestsBaseCopy-<branch>.launch`. The workbench boots once, in about 500 s.

**Regression checks** (normal Developer, not under test)
1. On a clean workspace with an NG/Titanium solution, node extraction and the Titanium build run and the NG client opens.
2. The default PostgreSQL dump/install dialog still appears when applicable.
3. A mismatched j2db.jar / app-server version still gives the warning, and `lib/version.txt` is written.
4. A second Developer on the same port shows "Another developer is probably running". With nothing answering, the "check your startup" warning is shown.
5. A solution saved by a newer Servoy still gives the "activated, but with errors ... newer version" warning.
6. The current Copilot `showFormInBrowser` / `testForm` / `showAndTest` work on main-solution and module forms.
7. An old Copilot (before 2026-10-13) on Developer 2026.3.2+ running `testForm` checks the `NoSuchMethodError` compatibility question.
8. On a timed-out partial run, `runJSUnitTests` / `runTestMethod` output starts with `Error - Timed out while running!`.
9. The Servoy AI view starts and `opencode.json` has the expected MCP endpoints.

## Possible improvements / follow-ups
- **specExists compatibility** (asked on the ticket): if old-Copilot/new-Developer can happen, keep `specExists(String)`, `getSpecFilePath(String)` and `getSetupFilePath(String)` as `@Deprecated` delegates in `com.servoy.eclipse.cypress.services.FormSpecGenerator`.
- **Test not running on servoy-eclipse master/release**: `com.servoy.eclipse.tests` is no longer a module there (98dccd6836), but the lts merge 2433ff4a0e added `TestServoyFormBuilderFormComponentGuard` to it. Move the test to `model.tests`/`designer.tests` and delete the stale directory.
- **lts_2026**: `ChromiumWrapperScreenshotTest` is outside the `plainjunit`/`integrationpde` includes and never runs (not re-verified).
- **Launcher drift**: `PDEIntegrationTestsBaseCopy-release.launch` selects both junit-jupiter-api 5.14.4 and 6.1.3, which is the precondition for the `Namespace.getParts()` gotcha. It also differs from the servoy-eclipse release base in bundle selections. servoy-eclipse bases have `automaticValidate=true`, against the AGENTS.md rule.
- **API surface**: 1f3dd4f exports all `developer.mcp` packages (including `.auth`) without restriction. Consider `x-friends`/`x-internal`. `McpConfigWriter.testProvidersOverride` is now a `public static volatile` test hook in an exported package.
- **Investigation outcome**: only a local 800+ s → 500 s number is recorded. There is no Jenkins before/after time, and no written finding on why the committed-workspace idea was not used.

## Release target
Fix Version `2026.9.0`: MISMATCH (partial), accepted by the reviewer. Tag 2026.9.0 has 13/66 servoy-eclipse and 8/15 servoy-client commits. The rest are on the intended branches: release (2026.9.1) and master (2026.12.0). Only the Jira Fix Version field is stale.
