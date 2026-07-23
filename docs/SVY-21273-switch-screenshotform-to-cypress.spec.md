# Spec: SVY-21273 — Switch screenshotForm from Playwright to Cypress

## 1. Goal

Replace the Playwright-based screenshot mechanism in the `screenshotForm` MCP tool with Cypress, unifying all browser automation in the Servoy Copilot on a single technology stack. This eliminates the need for a separate Playwright installation (npm package + browser download), reduces disk footprint, and fixes the integration test `testScreenshotForm_validForm_returnsResult` in `CypressFormTestingIntegrationTest`.

## 2. Background

### 2.1 Current architecture

`FormPreviewService.screenshotForm()` (lines 130–244) performs these steps:

1. Validates form name, project, and form existence
2. Checks Eclipse error markers (`checkFormMarkers`)
3. Validates form property types (`validateFormProperties`)
4. Ensures Playwright is installed in `.metadata/.plugins/com.servoy.eclipse.copilot/playwright/` (npm install + `npx playwright install chromium`)
5. Generates a Node.js script using `require('playwright')` that launches headless Chromium, navigates to the form preview URL, waits, takes a screenshot, and captures console errors
6. Runs the script with bundled Node.js
7. Returns the screenshot path (or error/warning text)

### 2.2 Existing Cypress infrastructure

`FormSpecRunner` already manages a Cypress installation at `<workspaceRoot>/jenkins-custom/e2e-test-scripts/` with:
- `ensureCypressInstalled(Path)` — installs Cypress ^13.0.0 via npm if missing
- `ensureCypressConfig(Path, String)` — writes a `cypress.config.js` with baseUrl
- `getCypressDir()` — resolves the internal Cypress directory
- `getNodePath()` — finds the bundled Node.js from `com.servoy.eclipse.ngclient.ui`
- `resolveLocalCypressCmd(Path)` — resolves the local `cypress.cmd`/`cypress` binary

The `runSpec` and `runE2ESpec` methods demonstrate how to launch `cypress run` with proper PATH/NODE_PATH environment setup.

### 2.3 Cypress screenshots

Cypress has built-in screenshot support:
- `cy.screenshot('filename')` takes a screenshot of the current viewport
- `cy.screenshot('filename', { capture: 'fullPage' })` captures the full document
- Screenshots are stored by default in `cypress/screenshots/<specFileName>/filename.png`
- A custom `screenshotsFolder` can be configured in `cypress.config.js`

### 2.4 Git history

The Playwright-based `screenshotForm` was introduced in commit `ff5957d` (SVY-21025 — "be able to just show a form in the browser for unit testing that form"). It was enhanced with marker/property validation and error capture in commit `2ef47e8` (SVY-21195). Those validations must be preserved.

### 2.5 Integration test issue

The PDE test `testScreenshotForm_validForm_returnsResult` currently accepts a Playwright-related environment error (`"screenshot"`, `"navigate"`, or `"localhost:-1"`). In environments where Playwright's browser download fails or Chromium is not available (e.g. headless CI), the test reports a vague error. Switching to Cypress (which is already installed and verified by other tests in the same suite) resolves this.

## 3. Design

### 3.1 Replace Playwright logic with Cypress in `FormPreviewService.screenshotForm()`

The `screenshotForm` method retains its existing validation logic (steps 1–3: form name, project, markers, properties, port). The Playwright-specific code (steps 4–6) is replaced with a Cypress-based approach:

1. Ensure Cypress is installed (reuse `FormSpecRunner`'s installation or delegate to it)
2. Generate a temporary Cypress spec file (`_screenshot_<formName>.cy.js`) that:
   - Visits the form URL (`cy.visit(url)`)
   - Waits for rendering (`cy.wait(waitSeconds * 1000)`)
   - Takes a full-page screenshot (`cy.screenshot(filename, { capture: 'fullPage' })`)
   - Captures uncaught exceptions and console errors via `Cypress.on('uncaught:exception')` and `cy.on('window:before:load')`
3. Write a temp `cypress.config.js` (or reuse the one from `getCypressDir()`) with:
   - `baseUrl` pointing to `http://localhost:<port>`
   - `screenshotsFolder` pointing to a known screenshots directory
    - `video: true` (consistent with project convention)
   - `supportFile: false`
4. Run `cypress run --spec <tempSpec> --config-file <configPath>` headlessly
5. Parse output for errors, locate the screenshot file
6. Clean up the temp spec file (always, regardless of pass/fail)
7. Return the screenshot path or error text

### 3.2 Reuse FormSpecRunner's Cypress infrastructure

Rather than duplicating Cypress install logic in `FormPreviewService`, delegate to `FormSpecRunner`:
- Expose `getCypressDir()` as package-private or add a static helper method
- Alternatively, extract shared Cypress setup into a new utility class (e.g. `CypressEnvironment`) — but the simplest approach is to make `FormPreviewService` instantiate its own `FormSpecRunner` or call shared static methods

The recommended approach: make `FormSpecRunner`'s `getCypressDir()`, `ensureCypressInstalled(Path)`, and `getNodePath()` accessible (package-private or through a new shared static utility) so `FormPreviewService` can reuse them without duplication.

### 3.3 Remove Playwright dependencies

After the switch:
- Remove the `PLAYWRIGHT_DIR` constant, `PACKAGE_JSON_CONTENT` (playwright package.json), `getPlaywrightDir()`, and `ensurePlaywrightInstalled()` from `FormPreviewService`
- Remove the Node.js script generation that uses `require('playwright')`
- The Playwright directory in `.metadata/.plugins/com.servoy.eclipse.copilot/playwright/` will no longer be created or used

### 3.4 Cypress working directory and screenshot output location

Cypress config and specs are written to `<workspaceRoot>/jenkins-custom/e2e-test-scripts/` — the same directory used by `FormSpecRunner` for form-testing specs. Screenshots end up in the `screenshots/` subfolder under that directory.

This aligns with the existing test infrastructure and avoids the previous pattern of storing files in `.metadata/.plugins/`.

### 3.5 Console/runtime error capture

The current Playwright implementation captures:
- Browser console errors via Playwright's `page.on('console')` / `page.on('pageerror')`
- Server-side errors via `RuntimeErrorCapture` (log4j2 appender)

With Cypress:
- Server-side errors: continue using `RuntimeErrorCapture` around the Cypress run
- Browser console errors: Cypress captures uncaught exceptions automatically and fails the spec. Additionally, the spec can use `cy.on('window:console', ...)` or check Cypress output for error indicators
- The simplest approach: if Cypress exits non-zero, parse its stdout for error messages. Server-side errors are still captured via `RuntimeErrorCapture`.

### 3.6 Test updates

The integration test `testScreenshotForm_validForm_returnsResult` currently accepts errors mentioning "screenshot", "navigate", or "localhost:-1". After the switch:
- When the NG client is running: should return a valid `.png` path
- When the NG client is NOT running (port -1): should return an error mentioning "Tomcat" or "localhost:-1" (the pre-flight port check already handles this before Cypress is invoked)
- The test assertion for Playwright-specific errors (`"navigate"`) should be updated to match Cypress-relevant errors

## 4. Implementation plan

1. **Extract shared Cypress helpers** — Make `FormSpecRunner.getCypressDir()`, `ensureCypressInstalled(Path)`, and `getNodePath()` package-private (they are in the same package `com.servoy.eclipse.developer.mcp.services`).

2. **Rewrite `FormPreviewService.screenshotForm()`** — Replace lines 165–238 (Playwright script generation and execution) with:
   - Call `FormSpecRunner`'s `getCypressDir()` / `ensureCypressInstalled()`
   - Generate a temporary `.cy.js` spec file
   - Write/reuse a `cypress.config.js` in the Cypress dir
   - Run `cypress run --spec ... --config-file ...` headlessly
   - Parse output and locate the screenshot file

3. **Remove Playwright infrastructure** from `FormPreviewService`:
   - Delete `PLAYWRIGHT_DIR`, `PACKAGE_JSON_CONTENT`, `getPlaywrightDir()`, `ensurePlaywrightInstalled()`
   - Update the class Javadoc (references Playwright in the doc header)

4. **Update `CypressFormTestingIntegrationTest.testScreenshotForm_validForm_returnsResult()`** — Adjust the error-case assertions to match the Cypress-based error messages (the port-check error `"localhost:-1"` still applies, but `"navigate"` may no longer appear).

5. **Update `FormPreviewServiceTest`** — If there are unit tests asserting Playwright-specific behaviour (script generation, etc.), update or remove them to reflect the new Cypress approach.

6. **Verify all related tests pass** — Run `ServoyTestingServerTest`, `FormPreviewServiceTest`, `ShowFormInBrowserToolTest`, and the PDE integration tests.

## 5. Acceptance criteria

- [ ] `screenshotForm` tool uses Cypress (not Playwright) to take form screenshots
- [ ] No Playwright-related code remains in `FormPreviewService`
- [ ] `testScreenshotForm_validForm_returnsResult` passes in the PDE integration test suite
- [ ] `testScreenshotForm_nullForm_returnsError` and `testScreenshotForm_nonExistentForm_returnsError` continue to pass
- [ ] Marker validation tests (`testScreenshotForm_formWithMarkerErrors_*`) continue to pass unchanged
- [ ] The tool returns a valid `.png` file path when the NG client is running
- [ ] The tool returns a clear error message when the NG client is not running (port <= 0)
- [ ] Console/runtime errors are still detected and reported in the tool output
- [ ] No new Playwright directory is created in the workspace metadata
- [ ] The Cypress installation used by `screenshotForm` is the same one used by `runSpec`/`runE2ESpec` (no duplication)

## 6. Out of scope

- Removing old Playwright directories from existing workspaces (users who already have `.metadata/.plugins/com.servoy.eclipse.copilot/playwright/` can clean it up manually)
- Changing how `showFormInBrowser` works (it opens a browser for the user, not for screenshot capture)
- Adding new screenshot-related MCP tools
- Changing the `runSpec` or `runE2ESpec` tools in `FormSpecRunner`

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| _(none — all resolved)_ | — | — |

### Resolved decisions

- **Temporary Cypress spec cleanup:** Always clean up the temporary `.cy.js` spec file after the run completes, whether it passes or fails. The Cypress output (stdout/stderr) provides sufficient debugging information.
- **RuntimeErrorCapture:** Keep using `RuntimeErrorCapture` around the Cypress run. It captures server-side Java exceptions (e.g. from solution code triggered by the form load) that Cypress cannot see. Cypress only sees client-side browser errors.
- **`servoypilot` bundle:** Leave it as-is. That bundle is reference-only and no longer actively developed.
