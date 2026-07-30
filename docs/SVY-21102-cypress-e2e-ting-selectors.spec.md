# Spec: SVY-21102 — Generate Cypress E2E tests using TiNG data-cy selectors against the running NGClient

## 1. Goal

Bring the Servoy AI Copilot Cypress E2E and form-testing infrastructure to full parity between the
Java MCP tool layer (`com.servoy.eclipse.developer.mcp`) and the AI skill layer (`skill4servoy`).
Two concrete gaps exist in the Java tool layer: the default E2E file extension and the scaffolded
`cypress.config.js` spec pattern. Additionally, two method renames improve clarity.

## 2. Background

### 2.1 What is already done

The `com.servoy.eclipse.developer.mcp` plugin contains a comprehensive Cypress testing subsystem:

| Tool | Class | Purpose |
|------|-------|---------|
| `generateFormSpec` | `ServoyTestingServer` → `FormSpecGenerator` | Reads `.frm` file, derives `data-cy` selectors, writes `*.spec.cy.js` to `jenkins-custom/e2e-test-scripts/cypress/cy-form/` |
| `testForm` / `showAndTest` | `ServoyTestingServer` → `FormSpecRunner.runSpec()` | Runs form-level Cypress tests |
| `generateCypressE2ETest` | `ServoyTestingServer` | Reads navigation graph, generates `*.cy.js` (or caller-specified `*.cy.ts`) in `jenkins-custom/e2e-test-scripts/cypress/e2e/<solutionName>/`; scaffolds `cypress.config.js` and `cypress/support/` |
| `testE2E` / `showAndTestE2E` | `ServoyTestingServer` → `FormSpecRunner.runE2ESpec()` | Runs full E2E Cypress tests |
| `listE2ETests` | `ServoyTestingServer` | Lists all discovered E2E test files |
| `screenshotForm` | `ServoyTestingServer` → `FormPreviewService` | Takes full-page screenshot via headless Cypress |
| `checkNGClientStatus` | `ServoyTestingServer` → `FormPreviewService` | Checks whether the NG client web server is running |

Additionally, the following are confirmed fully working:

- **`?svy_testmode=true`** in all generated URLs — `FormNavigationGraphService.java:511` already
  produces `/solution/<name>/index.html?svy_testmode=true`, covered by `FormNavigationGraphServiceTest` (lines 697–702).
- **`cypress.config.ts` preference when running** — `FormSpecRunner.java:229-231` and `:520-522`
  already prefer `cypress.config.ts` over `cypress.config.js` when running tests.
- **E2E output path** — `jenkins-custom/e2e-test-scripts/cypress/e2e/<solutionName>/` is the
  correct Servoy convention and is consistently hardcoded across `ServoyTestingServer`,
  `FormSpecRunner`, and `CypressTestDiscoveryService`. No change needed.
- **Login support** — `CypressLoginSupport`, `cy.login()` command generation.
- **IDE UI** — `CypressTestResultsView`, `RunAllCypressFormTestsHandler`, `RunAllE2ETestsHandler`,
  `CypressTestSessionManager`.
- **Helper discovery** — `discoverCypressHelpers()` reads `cypress/support/` and passes to AI.
- **`baseUrl` resolution** — auto-detected from the live Servoy server port at generation time and
  baked into `cypress.config.js`. No AGENTS.md override needed.

### 2.2 What gaps remain

#### Gap A — Default E2E output extension is `.cy.js`, not `.cy.ts`

`ServoyTestingServer.java:791-793`:
```java
String fileName = (outputFileName != null && !outputFileName.isBlank()) ? outputFileName
        : targetForm + ".cy.js";
if (!fileName.endsWith(".cy.js") && !fileName.endsWith(".cy.ts"))
    fileName = fileName.replaceAll("\\.js$|\\.ts$", "") + ".cy.js";
```

The `E2E-Tester` skill (`skill4servoy/.opencode/agents/E2E-Tester.md`) exclusively writes `.cy.ts`
files. The Java default produces `.cy.js`, creating a mismatch when the AI agent tries to run or
discover files. `FormSpecRunner.runE2ESpec()` and `CypressTestDiscoveryService` already handle both
`.cy.js` and `.cy.ts` — only the generator default needs updating.

Note: form-level specs (`generateFormSpec`) use `*.spec.cy.js` and must **not** change — changing
them would orphan existing files in customer projects.

#### Gap B — Scaffolded `cypress.config.js` `specPattern` misses `*.cy.ts`

`ServoyTestingServer.java:826`:
```java
"    specPattern: '**/*.{cy.js,spec.js,test.js}',\n"
```

`*.cy.ts` is absent. When the Java tool scaffolds a new `cypress.config.js` and the AI then writes
`.cy.ts` E2E files, Cypress will not discover them.

Note: `FormSpecRunner.ensureCypressConfig()` (line 369) already uses `'**/*.cy.{js,ts}'` — only
the scaffold in `ServoyTestingServer.generateCypressE2ETest()` is missing it.

#### Gap C — Method names are unclear

`FormSpecRunner` has two public test-running methods whose names don't clearly convey what kind of
test they run:

| Current name | Callers | What it runs |
|---|---|---|
| `runSpec` | 11 (5 production, 6 test) | Form-level Cypress tests (`*.spec.cy.js` in `cypress/cy-form/`) |
| `runE2ESpec` | 5 (all production) | Full E2E Cypress tests (`*.cy.js`/`*.cy.ts` in `cypress/e2e/<solution>/`) |

Rename both to make the distinction explicit:
- `runSpec` → `runFormCypressTests`
- `runE2ESpec` → `runE2ECypressTests`

## 3. Design

### 3.1 Change default E2E file extension to `.cy.ts` (Gap A)

In `ServoyTestingServer.generateCypressE2ETest()` (`ServoyTestingServer.java:791-793`):

```java
String fileName = (outputFileName != null && !outputFileName.isBlank()) ? outputFileName
        : targetForm + ".cy.ts";
if (!fileName.endsWith(".cy.js") && !fileName.endsWith(".cy.ts"))
    fileName = fileName.replaceAll("\\.js$|\\.ts$", "") + ".cy.ts";
```

### 3.2 Add `*.cy.ts` to scaffolded `specPattern` (Gap B)

In `ServoyTestingServer.generateCypressE2ETest()` (`ServoyTestingServer.java:826`):

```java
"    specPattern: '**/*.{cy.js,cy.ts,spec.cy.js,spec.js,spec.ts,test.js,test.ts}',\n"
```

This covers both the existing `*.spec.cy.js` form specs and the new `*.cy.ts` E2E specs from one
`cypress.config.js`.

### 3.3 Rename `runSpec` and `runE2ESpec` (Gap C)

- `FormSpecRunner.runSpec(String, boolean)` and `runSpec(String, boolean, int, String)` →
  `runFormCypressTests`
- `FormSpecRunner.runE2ESpec(String, boolean)` → `runE2ECypressTests`

Update all callers:
- Production: `ServoyTestingServer.java` (4 call sites), `RunAllCypressFormTestsHandler.java`,
  `RunAllE2ETestsHandler.java`, `RunCypressFormTestHandler.java`, `RunSingleTestHandler.java`
- Tests: `CypressFormTestingIntegrationTest.java` (4 call sites), `ShowFormInBrowserIntegrationTest.java`,
  `FormSpecRunnerTest.java`

## 4. Implementation plan

1. **Change default E2E extension to `.cy.ts`** — `ServoyTestingServer.java:791-793` (two-line change).

2. **Update `specPattern` in scaffolded `cypress.config.js`** — `ServoyTestingServer.java:826` (one-line change).

3. **Rename `runSpec` → `runFormCypressTests`** in `FormSpecRunner.java` and all 11 callers.

4. **Rename `runE2ESpec` → `runE2ECypressTests`** in `FormSpecRunner.java` and all 5 callers.

5. **Update unit tests** — Fix any existing tests asserting the default E2E file name ends with
   `.cy.js` (update to `.cy.ts`). Verify all Cypress-related tests still pass.

## 5. Acceptance criteria

- [ ] `generateCypressE2ETest` produces a `.cy.ts` file by default (without the caller specifying `outputFileName`).
- [ ] The scaffolded `cypress.config.js` `specPattern` includes `*.cy.ts` so Cypress discovers TypeScript E2E specs alongside existing `*.spec.cy.js` form specs.
- [ ] `FormSpecRunner` has methods named `runFormCypressTests` and `runE2ECypressTests`; the old names `runSpec` and `runE2ESpec` no longer exist.
- [ ] All existing unit and integration tests for Cypress-related classes continue to pass.

## 6. Out of scope

- Moving Cypress infrastructure to a dedicated `com.servoy.eclipse.cypress` plugin (tracked in SVY-21296).
- Adding a live-DOM `getFormDomSelectors` tool.
- Changing form-level spec extension from `*.spec.cy.js`.
- Visual regression testing, multi-solution batching, CI/CD pipeline integration.
