# Spec: SVY-21187 — Create Integration Tests for the MCP Tools

## 1. Goal

Answer the concrete question driving this ticket run: **"Is there anything else that needs to be tested? JUnit or JUnit Plug-in tests?"**

Most of the coverage gaps identified in the first pass of this ticket have since been closed by committed test classes. This revision re-audits **every** MCP server and service against the current test tree and reports only the **remaining** gaps, classifying each as a plain **JUnit unit test** or a **JUnit Plug-in (integration) test**.

## 2. Background

### 2.1 Architecture

The MCP server bundle `com.servoy.eclipse.developer.mcp` exposes IDE operations as tools via `@McpServer` classes in `.../servers`. Each `@Tool`-annotated method is a callable tool. Business logic lives in `.../services`. The test fragment `com.servoy.eclipse.developer.mcp.tests` has two categories:

- **Plain JUnit** (`src/test/java/.../servers/`, `.../services/`, `.../actions/`, and top-level): no OSGi container or Eclipse workspace. Tests annotations, parameter validation, pure logic, error paths via reflection/mocking. Run with `eclipse-ide_runClassTests`.
- **JUnit Plug-in / integration** (`src/test/java/.../integration/`): require a running Eclipse workbench + Servoy App Server. Use `ResourcesPlugin`, `ServoyModelManager`, `Display`, real projects, real DB connections. Run with `eclipse-pde_runJUnitPluginTestClass`. Base class: `ServoyRunnerTestBase`.

### 2.2 Classification criteria

A tool needs a **JUnit Plug-in (integration)** test if it touches `ResourcesPlugin`/`IProject`/`IFile`, `ServoyModelManager`/`ServoyModel`/persistence, DB servers (`IServerManagerInternal`), the UI thread (`Display`), an active solution, DLTK, or EGit/JGit.

A tool needs only a **plain JUnit** test if it is pure logic (UUID, time, string/format/regex) testable with reflection or simple mocking.

## 3. Design — Current Coverage & Remaining Gaps

### 3.1 What now EXISTS (since the first pass)

The following integration classes are committed and wired into the `AllDeveloperMcpIntegrationTests` suite, closing the bulk of the originally-planned work:

`ServoyCoderServerIntegrationTest` (23), `CreateArtifactsIntegrationTest` (13 — createForm/createRelation/createValueList), `CreateSolutionIntegrationTest` (10 — createSolution/activateSolution), `DatabaseToolsIntegrationTest` (26), `SecurityToolsIntegrationTest` (20), `DocumentationToolsIntegrationTest` (17), `ValidationToolsIntegrationTest` (18 — getTarget/validate/validateFormat/validateFormElementFormat/syncDbiWithDatabase), `ServoyGitServerIntegrationTest` (11 — full git workflow), `ServoyI18nServerIntegrationTest` (12), `ServoyIdeServerWorkspaceIntegrationTest` (21), `CodeAnalysisIntegrationTest` (18 — findReferences/getTypeHierarchy/getMethodCallHierarchy/executeQuickFix), `ContextServerHistoryIntegrationTest` (7), `ServoyMediaServerIntegrationTest` (10), `ServoySolutionServiceIntegrationTest` (19 — findForm), `CodeContextServiceIntegrationTest` (13). Plus the pre-existing testing/cypress/jsunit/persist integration suites. `TimeServerTest` (14) and `FormatValidatorServiceTest` (42) cover the plain-JUnit items.

### 3.2 Coverage-gap table (per server — remaining gaps only)

| Server | Tools | Unit test | Integration test | Remaining gap |
|---|---|---|---|---|
| **MemoryServer** | think, completionMeta | ✓ MemoryServerTest | n/a (pure logic) | **None** |
| **TimeServer** | getCurrentTime, convertTimeZone | ✓ TimeServerTest | n/a (pure logic) | **None** |
| **ServoyCoderServer** | 10 file ops | ✓ | ✓ ServoyCoderServerIntegrationTest | **None** |
| **ServoyContextServer** | 6 | ✓ | ✓ ContextServerHistoryIntegrationTest | **None** |
| **ServoyGitServer** | 14 | ✓ | ✓ ServoyGitServerIntegrationTest | **None** |
| **ServoyI18nServer** | 3 | ✓ ServoyI18nServerTest | ✓ ServoyI18nServerIntegrationTest | **None** (but unit class orphaned — see 3.4) |
| **ServoyMediaServer** | 1 | ✓ | ✓ ServoyMediaServerIntegrationTest | **None** |
| **ServoyWpmServer** | 8 | ✓ | ✓ ServoyWpmServerIntegrationTest | searchPackages / installPackage / getAvailableWebPackages — **out of scope** (network) |
| **ServoyDevServer** | ~35 | ✓ ServoyDevServerTest | ✓ (7 integration classes) | **Menu tools (8)** — see 3.3 |
| **ServoyIdeServer** | ~26 | ✓ ServoyIdeServerTest | ✓ (3 integration classes) | **6 read/nav tools + searchAndReplace + openProject** — see 3.3 |
| **ServoyTestingServer** | ~20 | ✓ ServoyTestingServerTest | ✓ (many) | **E2E tools (3)** — see 3.3 |

### 3.3 The remaining gaps (this is the deliverable)

#### Gap A — ServoyDevServer **Menu tools** → JUnit Plug-in (integration)

`listMenus`, `getMenuStructure`, `createMenu`, `createMenuItem`, `updateMenu`, `updateMenuItem`, `deleteMenu`, `deleteMenuItem` have only **unit-level annotation/null-arg tests** (`ServoyDevServerTest` lines ~731–977). `MenuService` calls `ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject()` and manipulates `Menu`/`MenuItem` persists → **needs a real active solution → JUnit Plug-in test.** No integration coverage exists today. **Highest-value remaining gap.**

#### Gap B — ServoyIdeServer read/navigation tools → JUnit Plug-in (integration)

No integration test calls these (only unit-level checks exist):

| Tool | Backing service / API | Test type |
|---|---|---|
| `getSource` | `ServoyScriptResolver` (DLTK) | Plug-in |
| `getFileOutline` | `FileStructureService.analyzeFile(IFile)` | Plug-in |
| `readFunction` | `FileStructureService` | Plug-in |
| `readFileRanges` | `ProjectService` → `IFile.getContents()` | Plug-in |
| `readFileContext` | `ProjectService` → `IFile.getContents()` | Plug-in |
| `searchAndReplace` | Eclipse text search + `IFile` write | Plug-in |
| `openProject` | `ResourcesPlugin` project import | Plug-in |
| `getConsoleOutput` | Eclipse console manager | Plug-in (borderline — output non-deterministic) |

`getCurrentlyOpenedFile` and `getEditorSelection` remain **out of scope** (require interactive UI editor state, non-deterministic in a test runner).

#### Gap C — ServoyTestingServer **E2E tools** → mixed

| Tool | Backing | Test type | Note |
|---|---|---|---|
| `listE2ETests` | walks `jenkins-custom/e2e-test-scripts` via `ResourcesPlugin` workspace root | Plug-in | Cheap, deterministic — **worth adding** |
| `testE2E` | Cypress headless run | Plug-in | Heavy but mirrors already-tested `testForm` (1 real run acceptable) |
| `showAndTestE2E` | Cypress headed run | — | **Out of scope** — opens a visible browser |
| `getNavigationPath` | multi-form nav graph | Plug-in (optional) | Unit-covered by `NavigationGraphTest`; an integration test with a real multi-form solution would harden it but is low priority |

### 3.4 Test-hygiene finding — orphaned unit test classes

Several committed **plain-JUnit** classes are **not referenced by either aggregate suite** (`AllDeveloperMcpTests` / `AllDeveloperMcpIntegrationTests`), so a suite-driven CI run will silently skip them:

- `ServoyI18nServerTest`
- `CypressTestDiscoveryServiceTest`
- `FormatValidatorServiceTest`
- `FormNavigationGraphServiceTest`
- `FormPreviewServiceTest`
- `GitServiceDiffTest`
- `NavigationGraphTest`
- `PersistDuplicateServiceTest`
- `actions/*` (`CypressConsoleUtilTest`, `CypressEditorInputPropertyTesterTest`, `CypressTestAdapterFactoryTest`, `CypressTestPropertyTesterTest`, `RunAllCypressFormTestsHandlerTest`, `RunCypressFormTestHandlerTest`)

These should be added to `AllDeveloperMcpTests` (or a dedicated actions suite) so they actually run. This is a correctness gap in the test harness, independent of new tests.

## 4. Implementation Plan (remaining work, prioritized)

1. **`MenuToolsIntegrationTest`** (JUnit Plug-in) — activate a test solution, then:
   - `createMenu` → `listMenus` shows it; `getMenuStructure` returns it
   - `createMenuItem` under the menu; `updateMenu` / `updateMenuItem` mutate properties
   - `deleteMenuItem` then `deleteMenu`; verify removed from model
   - null/blank/duplicate/not-found error paths against the live model
   - Add to `AllDeveloperMcpIntegrationTests`.

2. **`ServoyIdeServerReadIntegrationTest`** (JUnit Plug-in) — with a test project:
   - `getSource` on a known Servoy scope/form scriptfile
   - `getFileOutline` + `readFunction` on a `.js` file with known functions
   - `readFileRanges` (correct line window) + `readFileContext` (windowed content)
   - `searchAndReplace` (replaces text, verify `IFile` content)
   - `openProject` (import a folder, verify project appears)
   - `getConsoleOutput` (smoke — non-null, no throw)
   - Add to `AllDeveloperMcpIntegrationTests`.

3. **`E2EToolsIntegrationTest`** (JUnit Plug-in):
   - `listE2ETests` — no-dir returns clear message; with a seeded `.cy.js` returns it
   - `testE2E` — one real headless run on a known form asserting passed/failed present (mirror `testForm` pattern)
   - Add to `AllDeveloperMcpIntegrationTests`.

4. **Wire orphaned unit tests into `AllDeveloperMcpTests`** (see 3.4). Verify each still passes via `eclipse-ide_runClassTests`.

## 5. Acceptance Criteria

- [ ] `MenuToolsIntegrationTest` covers all 8 menu tools (create/list/structure/update/delete for menu + item, plus error paths) and is in the integration suite.
- [ ] `ServoyIdeServerReadIntegrationTest` covers `getSource`, `getFileOutline`, `readFunction`, `readFileRanges`, `readFileContext`, `searchAndReplace`, `openProject`, `getConsoleOutput` and is in the integration suite.
- [ ] `E2EToolsIntegrationTest` covers `listE2ETests` (both branches) and one `testE2E` run and is in the integration suite.
- [ ] All orphaned unit test classes listed in 3.4 are referenced by an aggregate suite and pass.
- [ ] New plain JUnit tests pass with `eclipse-ide_runClassTests`; new plug-in tests pass with `eclipse-pde_runJUnitPluginTestClass`.
- [ ] Zero compilation errors introduced; no Spotbugs high/critical issues in new code.
- [ ] No `Assume`/`assumeTrue`/`assumeThat` — use only `assert*`. Tests verify real behaviour, not trivially green. Failing tests are analysed and discussed before any fix.

## 6. Out of Scope

- `getCurrentlyOpenedFile`, `getEditorSelection` — interactive UI editor state, non-deterministic.
- `showAndTestE2E`, `screenshotForm` — require a visible/headed NG client + browser.
- WPM `searchPackages`, `installPackage`, `getAvailableWebPackages` — hit the network.
- Inactive bundles (`servoypilot`, `langchain4j`, `knowledgebase`, `assistenttests`).
- Modifying production code or existing passing tests (except adding classes to suites).

## 7. Open Questions

| Question | Assumption |
|---|---|
| Should `testE2E` do a real Cypress run in CI, or only assert wiring? | Assume one real headless run, mirroring the accepted `testForm` integration test. Revisit if CI runtime is a concern. |
| Should orphaned unit tests go into the existing `AllDeveloperMcpTests` or a new `actions`/`services` suite? | Assume add to `AllDeveloperMcpTests`; split only if it grows unwieldy. |
| Is a `getNavigationPath` integration test (real multi-form solution) wanted now? | Assume deferred — unit coverage is adequate; low priority. |
