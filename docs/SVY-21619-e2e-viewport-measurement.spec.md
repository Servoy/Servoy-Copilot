# SVY-21619 — E2E-Tester viewport measurement: anti-false-negative reporting + opt-in responsive checks

## 1. Goal

Make the Cypress form/E2E tooling incapable of producing the class of false negative seen in run 007, where the E2E-Tester set `cy.viewport(480, 900)` but the form's container actually measured ~892px, the tool reported the cards side-by-side, and the agent then escalated a **non-existent** "svy-respform does not narrow to the viewport" platform limitation. Ground truth: the form stacks correctly at 480px.

Two complementary problems caused that outcome, and this spec fixes both at the tooling level:

1. **The measurement never reported the width it actually ran at.** Nothing in the returned output told the reader what `window.innerWidth` or the rendered container width was, so an unapplied viewport (viewport requested, layout measured at a different width) was indistinguishable from a real platform bug.
2. **There was no way to request a responsive cross-check at specific widths**, and no assertion that the requested viewport was actually applied before measuring.

The fix is a **hybrid**:

- **Always-on (no flag, every run):** both Cypress runners surface, in their returned output, the measured `window.innerWidth` **and** the rendered root-container's measured width, read **after layout settles** — for every run, responsive or not. This is the core anti-false-negative change: run 007's trap (assuming the viewport was applied when it wasn't) exists on every Cypress run regardless of responsiveness.
- **Opt-in `viewportWidths` parameter** on both generators: when supplied, the generated spec adds, per width, a block that sets the viewport, **asserts** `window.innerWidth` equals the requested width (failing loudly with both numbers if not — the thing run 007 never checked), waits for layout to settle, then reads and logs the chosen container's measured width so the container-vs-viewport cross-check is visible in the output.
- A **reusable Cypress custom command** (`cy.measureAtViewport(selector, width, height)`) scaffolded into `cypress/support/commands.js`, so the measure + settle + verify logic lives in exactly one place and both generated-spec styles call it.

## 2. Background

### 2.1 Two independent pipelines measure form layout — do not confuse them

There are two separate ways a form's layout gets measured in this repo. This spec touches **only the second**.

| Pipeline | Where | When it measures | Status re: this spec |
|---|---|---|---|
| **getFormLayout** design-time tool | `com.servoy.eclipse.developer.mcp` (ServoyFormServer / getFormLayout) | At design time, against the editing flattened solution | **Already satisfies the measurement asks** via SVY-21591 / SVY-21614, and already has a regression test `FormLayoutResponsiveStackingIntegrationTest`. **Related/complementary only — NOT changed here.** |
| **Cypress generators + runners** | `com.servoy.eclipse.cypress` (FormSpecGenerator, FormSpecModelEmitter, FormSpecRunner) and `com.servoy.eclipse.developer.mcp` (ServoyTestingServer tools, FormNavigationGraphService) | At runtime, in a real headless browser against the running NG client | **This is the pipeline this spec changes.** |

Run 007's false negative came from the **runtime Cypress** pipeline, not from getFormLayout.

### 2.2 Correction of the earlier triage — the E2E tooling IS in this repo

An earlier triage wrongly concluded the E2E tooling was "not in this repo" and lived in `jenkins-custom/e2e-test-scripts`. **That is incorrect.** `jenkins-custom/e2e-test-scripts/` is only the **output directory** where generated Cypress specs, `cypress.config.js`, and `cypress/support/` are written at runtime. The Java code that **generates and runs** those specs lives in:

- bundle **`com.servoy.eclipse.cypress`** — `FormSpecGenerator`, `FormSpecModelEmitter`, `FormSpecRunner` (plus `CypressLoginSupport`, `CypressToolchain`), and
- bundle **`com.servoy.eclipse.developer.mcp`** — `ServoyTestingServer` (the MCP tools `generateFormSpec`, `generateCypressE2ETest`, `testForm`, `testE2E`) and `FormNavigationGraphService` (the E2E navigation spec template).

So this is a real **PROCEED** with a code change in this repository.

### 2.3 What the code does today (as read from source)

- **`ServoyTestingServer.generateFormSpec(formName)`** — one param; delegates to `generateSpecWithModel(formName)` → `FormSpecGenerator.generateSpec(formName, model)`.
- **`ServoyTestingServer.generateCypressE2ETest(...)`** — nine params today (`targetForm`, `scenario`, `fromForm`, `outputFileName`, `baseUrl`, `loginUrl`, `testUsername`, `testPassword`, `loginSuccessSelector`). It builds the nav path, calls `FormNavigationGraphService.generateCypressTestContent(...)`, writes the `.cy.ts` under `jenkins-custom/e2e-test-scripts/cypress/e2e/<solutionName>/`, and **scaffolds** `cypress.config.js`, `cypress/support/commands.js` (currently just a comment stub) and `cypress/support/e2e.js` (`import './commands';`) when missing.
- **`FormSpecGenerator.generateSpec(formName, model)`** — writes `<solution>.<form>.spec.cy.js` (via `FormSpecModelEmitter.emit(formUrl, model)` when a model is present, else the legacy `generateCypressSpecContent(metadata)` fallback) plus a setup `.spec.js`.
- **`FormSpecModelEmitter.emit(formUrl, model)`** — the **deterministic** emitter: sorted elements/handlers, `describe` / `beforeEach` (`cy.visit`) / `it` blocks, `cy.get('[data-cy=...]')`. Determinism (sorted, no timestamps, no random) is a stated contract of this class.
- **`FormNavigationGraphService.generateCypressTestContent(...)`** — the E2E nav spec template: header comments, `describe`, `beforeEach` with `cy.visit(relativeUrl)`, an `it` with the navigation `click` steps and a `// --- Assertions --- // TODO` block.
- **`FormSpecRunner.runFormCypressTests(formName, headless, timeout, extraArgs)`** and **`runE2ECypressTests(targetForm, headless)`** — both build a `cypress run` command, stream merged stdout into a `StringBuilder output`, and return it wrapped in a results string (`"**Form Spec Results: ...**\n\n... \n\n" + rawOutput`). On failure they call `preserveArtifacts(rawOutput, name)`, which **appends marker lines** (`PRESERVED_VIDEO_MARKER` + path, `PRESERVED_SCREENSHOT_MARKER` + path) to the returned text. **This marker-append pattern is the precedent this spec reuses** for hoisting measurement lines.

## 3. Design

### 3.1 Always-on measurement reporting via a stable marker + runner extraction

**Goal:** every Cypress run — form or E2E, responsive or not — returns the measured `window.innerWidth` and the rendered root container's measured width, read after layout settles.

**Mechanism (mirrors the existing `PRESERVED_*` marker pattern):**

1. Every generated spec logs the measurement with `cy.log(...)` using a **stable marker prefix**, e.g.
   `SVY_MEASURE innerWidth=<n> container=<selector> containerWidth=<n> [requested=<n>]`.
   `cy.log` output appears in Cypress stdout, which both runners already capture into `rawOutput`.
2. Each runner gains a small private helper, **`hoistMeasurementMarkers(String rawOutput)`**, that scans `rawOutput` for lines containing the `SVY_MEASURE` marker and prepends a compact, human-readable **"Measured layout"** summary block to the returned result (above the raw log), so the numbers are visible without hunting through Cypress noise. This mirrors how `preserveArtifacts` surfaces `PRESERVED_*` markers — but runs on **every** run (pass or fail), not only on failure.
3. The marker constant lives next to the existing `PRESERVED_VIDEO_MARKER` / `PRESERVED_SCREENSHOT_MARKER` constants in `FormSpecRunner` (e.g. `SVY_MEASURE_MARKER = "SVY_MEASURE "`). The emitters reference the **same literal string** (generators are in `com.servoy.eclipse.cypress` alongside the runner, so share the constant; `FormNavigationGraphService` in `com.servoy.eclipse.developer.mcp` uses the identical literal — document the coupling in a comment on both sides).

**Always-on "default" measurement** (when `viewportWidths` is not supplied): the generated spec, inside its existing load/visibility `it` block (after the form exists), reads `window.innerWidth` and the root container's measured width at the **current/default viewport** and logs one `SVY_MEASURE` line. It does **not** assert anything about the width — it only reports — so behaviour is otherwise unchanged from today.

### 3.2 Opt-in `viewportWidths` parameter on both generators

A new optional parameter, comma-separated pixel widths (e.g. `"375,1024"`):

- **`ServoyTestingServer.generateFormSpec`** gains a 2nd `@ToolParam(..., required = false)` `viewportWidths`.
- **`ServoyTestingServer.generateCypressE2ETest`** gains a **10th** `@ToolParam(..., required = false)` `viewportWidths` (after `loginSuccessSelector`).

Parsing/validation (shared helper, see plan): split on comma, trim, parse to positive ints, drop blanks, dedupe while preserving order. Invalid tokens → the tool returns an `Error:` string naming the offending token (do not silently skip). Empty/null → behave as today plus §3.1 always-on reporting.

When one or more widths are supplied, the generated spec appends **one measurement block per width** (a dedicated `it(...)` for form specs; appended steps/`it` for the E2E template) that:

1. sets the viewport to that width (height = a fixed default, e.g. 900, unless the generator already carries a height),
2. **asserts** `window.innerWidth` **equals the requested width**, failing loudly with **both numbers** in the message if not (the check run 007 never did),
3. waits for layout to **settle** (§3.3 — not a blind sleep),
4. reads the chosen container's measured width and logs a `SVY_MEASURE ... requested=<width>` line so the container-vs-viewport cross-check lands in the output.

All of this is delegated to the shared custom command (§3.4), so the emitted block is a thin, deterministic call.

### 3.3 Settle strategy — wait for the box to stabilize, NOT a fixed sleep

The settle step must **not** be `cy.wait(<ms>)`. It must poll the target element's bounding box (`getBoundingClientRect()` width/height, or `[0].offsetWidth`) and resolve only once the box is **unchanged across consecutive samples** (e.g. two or three polls spaced a short interval apart, within Cypress's default command timeout, failing with a clear message if it never stabilizes). This removes both halves of the run-007 trap: measuring before layout reflowed, and depending on a magic sleep that is too short on slow CI.

### 3.4 Shared custom command `cy.measureAtViewport(selector, width, height)`

Scaffolded into `jenkins-custom/e2e-test-scripts/cypress/support/commands.js` (today a comment stub). The command:

- sets the viewport (`cy.viewport(width, height)`),
- asserts `window.innerWidth === width`, throwing with both numbers on mismatch (**fail loud**, §3.2 step 2),
- waits for the element's box to stabilize (§3.3),
- reads `innerWidth` and the element's measured width,
- `cy.log`s the `SVY_MEASURE` marker line (§3.1), and
- **yields** `{ innerWidth, containerWidth }` so a caller can chain further assertions.

A companion **measure-only** path (no viewport change, no equality assertion) backs the §3.1 always-on default-viewport reporting — either a second tiny command (e.g. `cy.measureContainer(selector)`) or a `measureAtViewport` call guarded to skip the viewport set/assert when width is omitted. Pick one and keep it consistent across both generators.

**Scaffolding rules:** `generateCypressE2ETest` already creates `commands.js` only when missing. The command must be **merged in without clobbering** an existing `commands.js` (which may already hold `cy.login()` from the auth path): append the command (idempotently — skip if a `Cypress.Commands.add('measureAtViewport'` already present) rather than overwriting. The form-spec generator path (`FormSpecGenerator`) must trigger the same scaffolding so form specs can call the command even when `generateCypressE2ETest` was never run.

### 3.5 Determinism

`FormSpecModelEmitter` output must stay deterministic (sorted, no timestamps, no random). The viewport blocks are emitted in the **given order of `viewportWidths`** after dedupe; the marker strings are fixed literals; no clocks or randomness enter the generated text. This preserves the emitter's stated contract and keeps the existing golden-output style tests stable.

## 4. Implementation plan (ordered, file-by-file)

> No new runtime dependencies: this is pure string/JS generation plus Cypress, which is already present. MANIFEST deps come from the target platform, not the plugin pom — no manifest changes expected. Any new test is JUnit 6 (Jupiter).

1. **`com.servoy.eclipse.cypress` — `FormSpecRunner`**
   - Add constant `SVY_MEASURE_MARKER` next to `PRESERVED_VIDEO_MARKER` / `PRESERVED_SCREENSHOT_MARKER`.
   - Add private helper `hoistMeasurementMarkers(String rawOutput)` → returns a short "Measured layout" summary block extracted from `SVY_MEASURE` lines (empty string when none).
   - In **both** `runFormCypressTests(formName, headless, timeoutSeconds, extraArgs)` and `runE2ECypressTests(targetForm, headless)`: after `rawOutput` is finalized (pass **and** fail branches), prepend `hoistMeasurementMarkers(rawOutput)` to the returned result string. Keep the existing `preserveArtifacts(...)` call on the fail branch unchanged.

2. **`com.servoy.eclipse.cypress` — shared parse/validate helper**
   - Add a small static helper (e.g. `ViewportWidths.parse(String csv)` in a new tiny util class, or a private static method reused by the generator) that turns `"375,1024"` into an ordered, deduped `List<Integer>` and reports invalid tokens. Shared by both tool methods so parsing/validation is identical.

3. **`com.servoy.eclipse.cypress` — `FormSpecModelEmitter`**
   - Add an overload/param carrying the parsed viewport widths (e.g. `emit(String formUrl, FormSpecModel model, List<Integer> viewportWidths)`), keeping the existing `emit(formUrl, model)` as a 2-arg delegate (widths = empty) so current callers/tests are untouched.
   - In the load/visibility `it` block: emit the always-on default-viewport `SVY_MEASURE` measure-only call (§3.1).
   - When widths non-empty: append one deterministic `it('stacks/responds at <w>px', ...)` per width calling `cy.measureAtViewport(<rootSelector>, <w>, <height>)` + the requested-width log (§3.2/§3.4).
   - Keep sorting/determinism; widths emitted in given order.

4. **`com.servoy.eclipse.cypress` — `FormSpecGenerator`**
   - `generateSpec(formName, model)` → add an overload `generateSpec(formName, model, List<Integer> viewportWidths)`; thread widths into `FormSpecModelEmitter.emit(...)`. Keep the 2-arg and 1-arg forms as delegates.
   - Apply the same always-on default measurement + opt-in blocks in the **legacy fallback** `generateCypressSpecContent(metadata)` (model == null path), so both generation paths behave consistently.
   - Ensure the `cypress/support/commands.js` scaffold (with `measureAtViewport`) is written/merged from this path too (factor the scaffold into a shared helper — see step 6).

5. **`com.servoy.eclipse.developer.mcp` — `FormNavigationGraphService.generateCypressTestContent(...)`**
   - Add the parsed `List<Integer> viewportWidths` param (overload; keep existing signature as delegate with empty list).
   - After the existing nav `it` block: emit the always-on default-viewport `SVY_MEASURE` measure-only line against the target form's root container, and, when widths non-empty, one measurement block per width calling `cy.measureAtViewport(...)` (§3.2). Use the **same** `SVY_MEASURE` literal as the cypress bundle (comment the coupling).

6. **`com.servoy.eclipse.developer.mcp` — `ServoyTestingServer`**
   - `generateFormSpec`: add `@ToolParam(name = "viewportWidths", required = false)`; parse via the shared helper; pass widths down to `generateSpecWithModel` → `FormSpecGenerator.generateSpec(..., widths)`. Update the `@Tool` description to document the new param and the always-on reporting.
   - `generateCypressE2ETest`: add the **10th** `@ToolParam(name = "viewportWidths", required = false)` after `loginSuccessSelector`; parse; pass to `FormNavigationGraphService.generateCypressTestContent(...)`.
   - Factor the `commands.js` / support scaffolding so the `measureAtViewport` command is **merged in idempotently** (not overwriting `cy.login()`), and make the form-spec path reuse the same scaffolding helper (shared util or a method on the cypress bundle). Update the `@Tool` description accordingly.
   - `testForm` / `testE2E`: no signature change — they inherit the always-on reporting because it comes from the runner + generated spec. (If a caller should be able to request responsive widths at **run** time without regenerating, that is an open question, §7.)

7. **Post-change verification loop** (mandatory per repo rules)
   - `eclipse-ide_getCompilationErrors` after each bundle's edits; apply safe quick fixes; re-check to zero errors. Address top-two-severity SpotBugs in touched code.

## 5. Tests

Extend the existing test classes (JUnit 6 Jupiter; plain tests where no workbench is needed, integration where the NG client/app server is):

- **`com.servoy.eclipse.cypress.plainjunit.services.FormSpecGeneratorTest`** / emitter tests — assert that:
  - default generation emits exactly one always-on `SVY_MEASURE` measure-only call and no viewport-set/assert;
  - `viewportWidths="375,1024"` emits one block per width, each calling `cy.measureAtViewport(<root>, <w>, <h>)`, asserting `window.innerWidth === <w>`, and logging `requested=<w>`;
  - output remains deterministic (same input → byte-identical output; widths in given order; dedup works);
  - invalid `viewportWidths` token → `Error:` naming the token.
- **`com.servoy.eclipse.cypress.plainjunit.services.FormSpecRunnerTest`** — feed a fake `rawOutput` containing `SVY_MEASURE` lines and assert `hoistMeasurementMarkers` extracts a summary that contains the innerWidth and container width; assert extraction runs on both pass and fail branches; assert no marker lines → no summary block and output otherwise unchanged.
- **`com.servoy.eclipse.developer.mcp.plainjunit.servers.ServoyTestingServerTest`** — assert both tool methods expose the new `viewportWidths` `@ToolParam` (required=false), parse CSV, and reject invalid tokens; assert `generateCypressE2ETest`'s nav template carries the always-on measure line and per-width blocks when widths are set.
- **`com.servoy.eclipse.developer.mcp.integrationpde.CypressFormTestingIntegrationTest`** — end-to-end: generate a spec with `viewportWidths`, run it against the running NG client, and assert the returned output contains the measured innerWidth and container width (and, for a known narrow width, the hoisted summary). Runs via `eclipse-pde_runJUnitPluginTests` with launcher `PDEIntegrationTestsBaseCopy-release` on Windows.

## 6. Acceptance criteria

- [ ] `generateCypressE2ETest` with `viewportWidths="375,1024"` emits one block **per width** that sets the viewport, asserts `window.innerWidth` equals the requested width, and logs the measured container width.
- [ ] `generateFormSpec` with `viewportWidths="375,1024"` does the same for a single-form spec (both the model-emitter path and the legacy fallback path).
- [ ] A form/E2E run's **returned output contains the measured `window.innerWidth` and the container width even without `viewportWidths`** (always-on default-viewport reporting), on both pass and fail.
- [ ] When the applied `window.innerWidth` ≠ the requested width, the generated spec **fails with both numbers in the message**.
- [ ] The settle step waits for the element's bounding box to stabilize; there is **no fixed `cy.wait(<ms>)`** sleep in the measurement path.
- [ ] `cy.measureAtViewport(selector, width, height)` exists in scaffolded `cypress/support/commands.js`, is the single place the measure/settle/verify logic lives, yields `{ innerWidth, containerWidth }`, and is **not** clobbered when a `cy.login()` command already exists in that file.
- [ ] `FormSpecModelEmitter` output stays deterministic (byte-identical for identical input; widths in given order; dedupe).
- [ ] Both runners surface `SVY_MEASURE` marker lines into a readable "Measured layout" summary in the returned result, reusing the `PRESERVED_*` marker precedent.
- [ ] Zero compilation errors; no new top-two-severity SpotBugs in touched code; no new runtime dependency and no MANIFEST change.
- [ ] Plain + integration tests above pass.

## 7. Out of scope

- **Agent/skill prompt guidance** ("cross-check a surprising platform-limitation conclusion before escalating; measure both narrow and wide") lives in the **`servoy-e2etester` skill** shipped via the skills zip, **not** in these Java bundles. It is not changed by this spec. (Documented here so the reader knows where that half of the run-007 lesson belongs.)
- **The design-time `getFormLayout` tool** (`com.servoy.eclipse.developer.mcp`, ServoyFormServer) — already satisfies the measurement asks via SVY-21591 / SVY-21614 and has `FormLayoutResponsiveStackingIntegrationTest`. Related/complementary, not touched here.

## 8. Open questions — RESOLVED (decisions are binding on the coder)

1. **Root-container selector — DECIDED: the coder picks per the rendered DOM.** There is no single hardcoded selector mandated. The emitters must target the form's rendered root container as it actually appears in the DOM; `.svy-form` is the safe baseline (it is what the getFormLayout read keys off and is present in every rendered form), but the coder may choose a more specific `[data-cy]`/page container if the live DOM shows it is the element that actually tracks the viewport. Keep the chosen selector a single named constant/parameter so it is easy to adjust.
2. **Run-time responsive request — DECIDED: generators only.** `testForm` / `testE2E` keep their current signatures (no `viewportWidths`). Responsive widths come from regenerating the spec, then running it.
3. **Default height — DECIDED: carry the generator's existing viewport height.** A width-only `viewportWidths` entry reuses whatever viewport height the generator already uses (not a new fixed 900 constant).
4. **Marker format — ACCEPTED as-is:** `SVY_MEASURE innerWidth=.. container=.. containerWidth=.. requested=..` is the stable contract for the runner extractor.
5. **Settle tolerance — DECIDED: 3 samples × 100ms** within Cypress's default command timeout; fail with a clear message if the box never stabilizes.
