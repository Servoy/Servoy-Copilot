# Review Summary — SVY-21591: getFormLayout cannot render at a requested viewport width

**Risk: MODERATE.** The new viewport path (resize, correct width, verify width) was never executed: the author's integration tests were not run, and the acceptance criterion "375px-wide screenshot" is not asserted anywhere.

Reviewed: Servoy-Copilot, `c5c031c` (SVY-21591 getFormLayout: render at a requested viewport width/height), 3 files, +444/-106. Follow-ups SVY-21614 / SVY-21619 were out of scope.

## Manual test plan

**Verifying the fix**
1. With a form that has a 640px breakpoint rule, call `getFormLayout(form, viewportWidth=375)`. The layout should be the narrow one and `window.width` should be 375 (±2px).
2. Repeat at 1281, 992, 768 and 767. The layout should differ across the breakpoints, and 768 must not render as 769.
3. Call with `screenshot=true, viewportWidth=375`. The PNG in `screenshotFile` should be 375px wide.
4. Call at 1281, then 375, then with no viewport. The last call must come back at the default width, not 375.
5. Pass `viewportWidth=100`, `5000` and `abc`. Each should return a clear error.
6. Pass `viewportHeight=700` and check `window.height`.

**Regression checks**
1. Call with no viewport parameters and note `window.width` and `viewport.w`. The default was about 1281 and is now 1280.
2. Fire three calls in parallel at different viewports on a large form. None should fail with "Another getFormLayout render is still in progress".
3. Call the same form twice in a row. The second result should reflect its current state.
4. Switch quickly between two forms. Each result should be for the form requested.
5. Check that FormPreview and ShowFormInBrowser are unaffected.

**Automated:** `FormLayoutToolIntegrationTest` in `com.servoy.eclipse.developer.mcp.tests` (PDE launcher `PDEIntegrationTestsBaseCopy-lts_2026` or `-release`). It needs a built Angular app in the test workspace.

## Possible improvements / follow-ups
- **Parallel calls:** the render lock wait uses the same timeout as the render (15s default), so healthy parallel calls can fail with "still in progress". The tool description tells agents to call at 1281/768/375. Consider a longer lock wait or documenting "call sequentially".
- **Default render:** alignment now runs on every call and a failed alignment is silent unless `viewportWidth` was passed. Decide whether to verify the width on default calls too.
- **Default width:** exactly 1280 now, while the issue and a test comment say 1281. Confirm this is intended.
- **`widthDelta`:** it can keep accumulating if the browser ignores resizes. Consider clamping it (for example ±8px) or resetting it on a failed align.
- **Height:** `viewportHeight` is applied but never verified.
- **Tests:** `validateViewport` and `parseViewport` have no plain-JUnit coverage. The two input-validation tests are in the integration class but do not render.
- **Unverified:** whether `/formtemplate` ignores the unique `svyRender=<ms>` query parameter (it lives in other repos), and the behaviour of an abandoned stuck `evaluate` on the SWT display thread.

## Release target
Fix Version 2026.12 (unreleased): MATCH, with a caveat. servoy-client master reads 2026.12.0 and `c5c031c` is on Servoy-Copilot origin/master. Servoy-Copilot's manifest reads 2026.3.1.qualifier, so confirm master is the line that ships with 2026.12.
