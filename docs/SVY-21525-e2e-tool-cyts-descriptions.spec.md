# Spec: SVY-21525 — MCP E2E tool descriptions must mention both `.cy.js` and `.cy.ts`

## 1. Goal

The `listE2ETests`, `testE2E` and `showAndTestE2E` MCP tool descriptions in
`ServoyTestingServer.java` tell AI agents the E2E runner only works with `.cy.js` spec
files, while the sibling `generateCypressE2ETest` tool writes `.cy.ts` files and the
underlying runner (`FormSpecRunner.runE2ECypressTests`) actually resolves *both*
extensions. Read literally, the descriptions say the runner can never find the spec its
own sibling generates, which pushes agents to bypass the runner and shell out to bash.
This change aligns the five `@Tool` / `@ToolParam` `description` strings with the real
runtime behaviour (both `.cy.js` and `.cy.ts`) and adds a regression test so the wording
cannot silently drift back.

## 2. Background

### 2.1 The affected tool descriptions

`ServoyTestingServer` exposes a family of Cypress E2E tools. Five `description` strings
mention only `.cy.js`:

| Tool | Location | Current text (`.cy.js` only) |
|------|----------|------------------------------|
| `listE2ETests` | line 303, `@Tool` | `"Lists all Cypress E2E test files (.cy.js) in …"` |
| `testE2E` | line 359, `@Tool` | `"Looks for a .cy.js file in jenkins-custom/…"` |
| `testE2E` | line 363, `@ToolParam targetForm` | `"Looks for <formName>.cy.js in the E2E directory."` |
| `showAndTestE2E` | line 379, `@Tool` | `"Looks for a .cy.js file in jenkins-custom/…"` |
| `showAndTestE2E` | line 383, `@ToolParam targetForm` | `"Looks for <formName>.cy.js in the E2E directory."` |

### 2.2 The runtime already supports both extensions

The runtime is correct; only the docs are wrong (the ticket confirms this: "The defect is
more in the docs, because the tool will find the cy.ts; he just doesn't know it based on
the docs"):

- `FormSpecRunner.runE2ECypressTests()` (in `com.servoy.eclipse.cypress`, `servoy-eclipse`
  repo) resolves specs in order: exact target-form path → `<targetForm>.cy.js` →
  `<targetForm>.cy.ts` → recursive walk matching either extension.
- `listE2ETests` itself already *filters* both extensions at line 325
  (`endsWith(".cy.js") || endsWith(".cy.ts")`) and strips both when deriving the form name
  (lines 341–347) — so its own description contradicts its own code.
- `generateCypressE2ETest` defaults its output to `.cy.ts` (`resolveE2EFileName`) and its
  description already mentions `.cy.ts`.

### 2.3 How this drift happened (git history)

- Commit `b3aa83d86` (Johan Compagner, 2026-08-12, SVY-21339) bulk-rewrote `@ToolParam`
  annotations across all MCP servers and carried over the original `.cy.js`-only wording
  on `testE2E` / `showAndTestE2E` without updating it for the `.cy.ts` support.
- Commit `f51f46bf4e` (lvostinar, 2026-08-14) updated `FormSpecRunner.runE2ECypressTests`
  in `servoy-eclipse` to resolve both `.cy.js` and `.cy.ts`. The Servoy-Copilot
  descriptions were never updated to match.

### 2.4 Existing test precedent

`ServoyTestingServerTest` (plain JUnit unit test, `tests/com.servoy.eclipse.developer.mcp.tests`)
already asserts, via reflection over the `@Tool` / `@ToolParam` annotations, that
`generateCypressE2ETest`'s description and its `outputFileName` param mention `.cy.ts`
(lines 790–808). The new regression test should follow that exact pattern (`findToolMethod`,
`getAnnotation(Tool.class).description()`, `getParameters()[i].getAnnotation(ToolParam.class)`).

## 3. Design

### 3.1 Description wording

Change the five string literals to mention both extensions. Keep the phrasing minimal and
consistent so the regression test can assert on both substrings:

- `listE2ETests` `@Tool`: `"(.cy.js)"` → `"(.cy.js / .cy.ts)"`.
- `testE2E` `@Tool`: `"Looks for a .cy.js file in …"` → `"Looks for a .cy.js or .cy.ts file in …"`.
- `testE2E` `@ToolParam targetForm`: `"Looks for <formName>.cy.js in the E2E directory."` →
  `"Looks for <formName>.cy.js or <formName>.cy.ts in the E2E directory."`.
- `showAndTestE2E` `@Tool`: same change as `testE2E` `@Tool`.
- `showAndTestE2E` `@ToolParam targetForm`: same change as `testE2E` `@ToolParam`.

No code/behaviour change — only the annotation `description` text. This is a documentation
fix with zero runtime risk.

### 3.2 Regression test

Add tests to `ServoyTestingServerTest` symmetric to the existing
`generateCypressE2ETest` description tests, asserting the three E2E tools' descriptions (and
the two `targetForm` params) mention both `.cy.js` and `.cy.ts`. This locks the wording so a
future bulk annotation rewrite cannot silently drop `.cy.ts` again.

This test class is a **plain JUnit unit test** (no workbench needed) — it reflects over
annotations only. It is already listed in the pom `<test>` headless block and the IDE unit
suite, so new methods on it need no registration changes.

## 4. Implementation plan

1. In `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/servers/ServoyTestingServer.java`, edit the five `description` strings per §3.1:
   - Line 303 `listE2ETests` `@Tool`: `(.cy.js)` → `(.cy.js / .cy.ts)`.
   - Line 359 `testE2E` `@Tool`: `a .cy.js file` → `a .cy.js or .cy.ts file`.
   - Line 363 `testE2E` `@ToolParam`: `<formName>.cy.js in the E2E directory.` → `<formName>.cy.js or <formName>.cy.ts in the E2E directory.`.
   - Line 379 `showAndTestE2E` `@Tool`: `a .cy.js file` → `a .cy.js or .cy.ts file`.
   - Line 383 `showAndTestE2E` `@ToolParam`: `<formName>.cy.js in the E2E directory.` → `<formName>.cy.js or <formName>.cy.ts in the E2E directory.`.
2. In `tests/com.servoy.eclipse.developer.mcp.tests/src/test/java/com/servoy/eclipse/developer/mcp/servers/ServoyTestingServerTest.java`, add regression tests (following the existing `testGenerateCypressE2ETest_descriptionMentionsCyTs` pattern):
   - `listE2ETests` `@Tool` description contains both `.cy.js` and `.cy.ts`.
   - `testE2E` `@Tool` description contains both; its `targetForm` `@ToolParam` contains both.
   - `showAndTestE2E` `@Tool` description contains both; its `targetForm` `@ToolParam` contains both.
3. Run `eclipse-ide_getCompilationErrors()` and ensure the bundle and test fragment are error-free.
4. Run `ServoyTestingServerTest` with `eclipse-ide_runClassTests` and confirm the new tests pass.

No MANIFEST, `build.properties`, pom, or extension-point changes are needed — this touches
only existing source files.

## 5. Acceptance criteria

- [ ] `listE2ETests` `@Tool` description mentions both `.cy.js` and `.cy.ts`.
- [ ] `testE2E` `@Tool` description and its `targetForm` `@ToolParam` description mention both `.cy.js` and `.cy.ts`.
- [ ] `showAndTestE2E` `@Tool` description and its `targetForm` `@ToolParam` description mention both `.cy.js` and `.cy.ts`.
- [ ] No runtime/behaviour change — only `description` string literals are edited.
- [ ] New regression tests in `ServoyTestingServerTest` assert each of the above and pass.
- [ ] The MCP bundle and the test fragment compile with zero errors.

## 6. Out of scope

- Any change to `FormSpecRunner` / `com.servoy.eclipse.cypress` runtime resolution (already correct).
- The `.spec.cy.js` wording on the single-form tools (`showFormInBrowser`, `testForm`, `showAndTestForm`, `generateCypressTest` — lines 183, 252, 279, 401), which describe `cy-form/` single-form specs, not the E2E `e2e/` specs this ticket targets.
- `generateCypressE2ETest`'s description (already correctly mentions `.cy.ts`).

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Preferred ordering/separator in the wording (`.cy.js / .cy.ts` vs `.cy.js or .cy.ts`) — this spec uses `/` for `listE2ETests` and `or` in prose; implementer may unify. | implementer | open |
