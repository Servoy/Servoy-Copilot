# Spec: SVY-21102 — Generate Cypress E2E tests using TiNG data-cy selectors against the running NGClient

## 1. Goal

Bring the Servoy AI Copilot Cypress E2E and form-testing infrastructure to full parity between the
Java MCP tool layer (`com.servoy.eclipse.developer.mcp`) and the AI skill layer (`skill4servoy`).
Two concrete gaps existed in the Java tool layer: the default E2E file extension and the scaffolded
`cypress.config.js` spec pattern. Additionally, two method renames improve clarity.

## 2. Background

### 2.1 What is already done

The `com.servoy.eclipse.developer.mcp` plugin contains a comprehensive Cypress testing subsystem:

| Tool | Class | Purpose |
|------|-------|---------|
| `generateFormSpec` | `ServoyTestingServer` → `FormSpecGenerator` | Reads `.frm` file, derives `data-cy` selectors, writes `*.spec.cy.js` to `jenkins-custom/e2e-test-scripts/cypress/cy-form/` |
| `testForm` / `showAndTest` | `ServoyTestingServer` → `FormSpecRunner.runFormCypressTests()` | Runs form-level Cypress tests |
| `generateCypressE2ETest` | `ServoyTestingServer` | Reads navigation graph, generates `*.cy.ts` by default in `jenkins-custom/e2e-test-scripts/cypress/e2e/<solutionName>/`; scaffolds `cypress.config.js` and `cypress/support/` |
| `testE2E` / `showAndTestE2E` | `ServoyTestingServer` → `FormSpecRunner.runE2ECypressTests()` | Runs full E2E Cypress tests |
| `listE2ETests` | `ServoyTestingServer` | Lists all discovered E2E test files |
| `screenshotForm` | `ServoyTestingServer` → `FormPreviewService` | Takes full-page screenshot via headless Cypress |
| `checkNGClientStatus` | `ServoyTestingServer` → `FormPreviewService` | Checks whether the NG client web server is running |

Additionally, the following are confirmed fully working:

- **`?svy_testmode=true`** in all generated URLs — `FormNavigationGraphService.java:511` already
  produces `/solution/<name>/index.html?svy_testmode=true`, covered by `FormNavigationGraphServiceTest` (lines 697–702).
- **`cypress.config.ts` preference when running** — `FormSpecRunner.java` already prefers
  `cypress.config.ts` over `cypress.config.js` when running tests.
- **E2E output path** — `jenkins-custom/e2e-test-scripts/cypress/e2e/<solutionName>/` is the
  correct Servoy convention and is consistently hardcoded across `ServoyTestingServer`,
  `FormSpecRunner`, and `CypressTestDiscoveryService`. No change needed.
- **Login support** — `CypressLoginSupport`, `cy.login()` command generation.
- **IDE UI** — `CypressTestResultsView`, `RunAllCypressFormTestsHandler`, `RunAllE2ETestsHandler`,
  `CypressTestSessionManager`.
- **Helper discovery** — `discoverCypressHelpers()` reads `cypress/support/` and passes to AI.
- **`baseUrl` resolution** — auto-detected from the live Servoy server port at generation time and
  baked into `cypress.config.js`. No AGENTS.md override needed.

### 2.2 Gaps that were fixed

#### Gap A — Default E2E output extension was `.cy.js`, not `.cy.ts`

The `E2E-Tester` skill (`skill4servoy/.opencode/agents/E2E-Tester.md`) exclusively writes `.cy.ts`
files. The Java default was `.cy.js`, creating a mismatch when the AI agent tried to run or discover
files. `FormSpecRunner` and `CypressTestDiscoveryService` already handled both extensions — only the
generator default needed updating.

Note: form-level specs (`generateFormSpec`) use `*.spec.cy.js` and were not changed — changing them
would orphan existing files in customer projects.

#### Gap B — Scaffolded `cypress.config.js` `specPattern` was missing `*.cy.ts`

The old pattern `'**/*.{cy.js,spec.js,test.js}'` did not include `cy.ts`. When the Java tool
scaffolded a new `cypress.config.js` and the AI then wrote `.cy.ts` E2E files, Cypress would not
discover them. `FormSpecRunner.ensureCypressConfig()` already used `'**/*.cy.{js,ts}'` — only the
scaffold in `ServoyTestingServer.generateCypressE2ETest()` was missing it.

#### Gap C — Method names were unclear

`FormSpecRunner` had two public test-running methods whose names did not clearly convey what kind of
test they ran. Renamed:
- `runSpec` → `runFormCypressTests` (2 overloads)
- `runE2ESpec` → `runE2ECypressTests`

## 3. Design

### 3.1 Default E2E file extension changed to `.cy.ts` (Gap A)

The filename-defaulting logic was extracted into a package-private static helper for testability:

```java
// ServoyTestingServer.java
static String resolveE2EFileName(String outputFileName, String targetForm) {
    String fileName = (outputFileName != null && !outputFileName.isBlank()) ? outputFileName
            : targetForm + ".cy.ts";
    if (!fileName.endsWith(".cy.js") && !fileName.endsWith(".cy.ts"))
        fileName = fileName.replaceAll("\\.js$|\\.ts$", "") + ".cy.ts";
    return fileName;
}
```

### 3.2 `specPattern` in scaffolded `cypress.config.js` (Gap B)

The specPattern was extracted into a package-private constant for testability and reuse:

```java
// ServoyTestingServer.java
static final String CYPRESS_E2E_SPEC_PATTERN =
    "**/*.{cy.js,cy.ts,spec.cy.js,spec.js,spec.ts,test.js,test.ts}";
```

This single pattern covers both the existing `*.spec.cy.js` form specs and the new `*.cy.ts` E2E
specs from one `cypress.config.js`.

### 3.3 Method renames (Gap C)

- `FormSpecRunner.runSpec(String, boolean)` and `runSpec(String, boolean, int, String)` →
  `runFormCypressTests`
- `FormSpecRunner.runE2ESpec(String, boolean)` → `runE2ECypressTests`

Updated callers:
- Production: `ServoyTestingServer.java` (4 call sites), `RunAllCypressFormTestsHandler.java`,
  `RunAllE2ETestsHandler.java`, `RunCypressFormTestHandler.java`, `RunSingleTestHandler.java`,
  `HeadlessFormTestExecutor.java`
- Tests: `CypressFormTestingIntegrationTest.java`, `ShowFormInBrowserIntegrationTest.java`,
  `FormSpecRunnerTest.java`, `RunAllCypressFormTestsHandlerTest.java`,
  `RunCypressFormTestHandlerTest.java`

## 4. Implementation plan

1. **Changed default E2E extension to `.cy.ts`** — extracted `resolveE2EFileName` helper in
   `ServoyTestingServer.java`.

2. **Updated `specPattern` in scaffolded `cypress.config.js`** — extracted `CYPRESS_E2E_SPEC_PATTERN`
   constant in `ServoyTestingServer.java`.

3. **Renamed `runSpec` → `runFormCypressTests`** in `FormSpecRunner.java` and all callers.

4. **Renamed `runE2ESpec` → `runE2ECypressTests`** in `FormSpecRunner.java` and all callers.

5. **Updated unit tests** — Fixed tests asserting the default E2E file name, added 14 new
   behavioral tests covering AC1 (`resolveE2EFileName` — 6 tests), AC2
   (`CYPRESS_E2E_SPEC_PATTERN` — 3 tests), and AC3 (`FormSpecRunnerTest` — 5 tests).

## 5. Acceptance criteria

- [x] `generateCypressE2ETest` produces a `.cy.ts` file by default (without the caller specifying `outputFileName`).
- [x] The scaffolded `cypress.config.js` `specPattern` includes `*.cy.ts` so Cypress discovers TypeScript E2E specs alongside existing `*.spec.cy.js` form specs.
- [x] `FormSpecRunner` has methods named `runFormCypressTests` and `runE2ECypressTests`; the old names `runSpec` and `runE2ESpec` no longer exist.
- [x] All existing unit and integration tests for Cypress-related classes continue to pass.

## 6. Out of scope

- Moving Cypress infrastructure to a dedicated `com.servoy.eclipse.cypress` plugin (tracked in SVY-21296).
- Adding a live-DOM `getFormDomSelectors` tool.
- Changing form-level spec extension from `*.spec.cy.js`.
- Visual regression testing, multi-solution batching, CI/CD pipeline integration.
