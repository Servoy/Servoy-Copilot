# Spec: SVY-21170 — Run Cypress Form Test context menu action

## 1. Goal

Add "Run Cypress Form Test" and "Run All Cypress Form Tests" context menu actions to the Servoy Developer Solution Explorer. These actions allow developers to execute Cypress form tests directly from the IDE without needing to invoke them through the AI/MCP layer, providing a streamlined testing workflow for form-level UI testing.

## 2. Background

### 2.1 Existing Cypress form testing infrastructure (SVY-21025)

The `com.servoy.eclipse.developer.mcp` bundle already contains the core Cypress form testing logic:

- **`FormSpecGenerator`** — generates `.spec.cy.js` files in `{project}/medias/tests/` and `.spec.js` setup files in `{project}/forms/`. Provides `specExists(formName)` to check if a form has an associated test and `getSpecFilePath(formName)` to resolve the file path.
- **`FormSpecRunner`** — orchestrates Cypress execution via `runSpec(formName, headless)`. Handles Cypress installation, config generation, process spawning, and result collection.
- **`ServoyTestingServer`** — exposes these as MCP tools (`testForm`, `showAndTest`, `generateFormSpec`).

### 2.2 Test file conventions

- Cypress spec files: `{solutionProject}/medias/tests/{formName}.spec.cy.js`
- Setup scripts: `{solutionProject}/forms/{formName}.spec.js`
- The naming convention ties test files to forms via `formName` (the Servoy form name).
- SVY-21171 may move files out of `medias/` in the future; discovery logic must be abstracted.

### 2.3 Existing context menu pattern (JSUnit reference)

The `com.servoy.eclipse.jsunit` plugin implements a similar "Run JS Unit Test(s)" context menu using:
1. `org.eclipse.ui.commands` — command definitions
2. `org.eclipse.ui.handlers` — handler implementations (extending `AbstractHandler`)
3. `org.eclipse.ui.menus` — menu contributions with `visibleWhen` expressions
4. `org.eclipse.core.runtime.adapters` — adapter factory to adapt `SimpleUserNode` to a test target interface
5. `org.eclipse.core.expressions.definitions` — reusable enablement expressions

The handler obtains the selection from the Solution Explorer (`SimpleUserNode`), adapts it via `Platform.getAdapterManager()`, and delegates to the test execution logic.

### 2.4 Plugin dependency situation

`com.servoy.eclipse.developer.mcp` already depends on `com.servoy.eclipse.ui`, `com.servoy.eclipse.model`, `org.eclipse.ui`, and `org.eclipse.core.runtime`, and imports `com.servoy.eclipse.ui.views.solutionexplorer.actions`. All dependencies needed for context menu contributions are already satisfied.

## 3. Design

### 3.1 Test discovery service

Create a `CypressTestDiscoveryService` class (in `com.servoy.eclipse.developer.mcp.services`) that encapsulates all test file discovery logic:

- `boolean hasTest(String formName)` — checks if a specific form has a `.spec.cy.js` file (delegates to `FormSpecGenerator.specExists`)
- `List<String> discoverAllTestForms()` — scans the `medias/tests/` directory for all `*.spec.cy.js` files and extracts form names from filenames
- `boolean hasAnyTest()` — returns true if at least one Cypress form test exists in the active solution

This abstraction isolates the file-location convention (currently `medias/tests/`) so that SVY-21171 can change it without impacting the menu infrastructure.

For performance (context menu enablement must be fast), the initial implementation uses a simple filesystem check (`Files.exists` for single-form, `Files.list` with filter for solution-level). No caching is needed initially since these are cheap I/O operations on local filesystem. If performance becomes an issue, a file-watcher-based cache can be added later.

### 3.2 Command and handler architecture

Two Eclipse commands:

| Command ID | Label | Scope |
|---|---|---|
| `com.servoy.eclipse.developer.mcp.commands.runCypressFormTest` | Run Cypress Form Test | Single form node |
| `com.servoy.eclipse.developer.mcp.commands.runAllCypressFormTests` | Run All Cypress Form Tests | Solution node, Forms node |

Two handler classes:

- **`RunCypressFormTestHandler`** — extracts form name from `SimpleUserNode` selection, calls `FormSpecRunner.runSpec(formName, true)`
- **`RunAllCypressFormTestsHandler`** — uses `CypressTestDiscoveryService.discoverAllTestForms()`, iterates and calls `FormSpecRunner.runSpec` for each form

Both handlers extend `AbstractHandler` and run execution in an Eclipse `Job` to avoid blocking the UI thread.

### 3.3 Adapter factory for enablement

Create a `CypressTestAdapterFactory` implementing `IAdapterFactory` that adapts `SimpleUserNode` to a new `CypressFormTestTarget` interface:

- For `UserNodeType.FORM`: adapts if `CypressTestDiscoveryService.hasTest(formName)` returns true
- For `UserNodeType.SOLUTION`, `UserNodeType.SOLUTION_ITEM`, `UserNodeType.FORMS`: adapts if `CypressTestDiscoveryService.hasAnyTest()` returns true

Register via `org.eclipse.core.runtime.adapters` extension point with `adaptableType="com.servoy.eclipse.ui.node.SimpleUserNode"`.

### 3.4 Menu contributions and visibility

Use `org.eclipse.ui.menus` extension point with `locationURI="popup:org.eclipse.ui.popup.any?before=additions"` (same pattern as JSUnit).

Menu locations:
- **Solution Explorer:** both "Run Cypress Form Test" (on form nodes) and "Run All Cypress Form Tests" (on solution/forms nodes)
- **Form Designer:** "Run Cypress Form Test" on the form editor's context menu, visible only when a test exists for the currently edited form

Visibility rules using `org.eclipse.core.expressions.definitions`:
- **Single form (Solution Explorer + Form Designer):** `visibleWhen` adapts selection to `CypressFormTestTarget` with a single-form check
- **Solution/Forms node:** `visibleWhen` adapts selection to `CypressFormTestTarget` with an any-test check

### 3.5 Execution and result reporting

- Execution runs in a background `Job` with progress reporting
- Console output is written to a dedicated Eclipse Console ("Cypress Form Tests")
- Tests always run in **headless** mode (no visible browser window) for speed and non-intrusiveness
- Exit code determines pass/fail status; a dialog or console marker indicates the result
- For "run all": tests execute sequentially using individual `FormSpecRunner.runSpec` calls (reuses shared logic, simpler than Cypress `--spec` glob) with aggregate results reported at the end
- Full result reporting integration with SVY-21174 is out of scope; this implementation provides console-based output

### 3.6 Error handling

- No active project → show error dialog "No active Servoy project"
- Spec file missing (single form) → should not happen (menu hidden), but guard with error message
- Cypress not installed → `FormSpecRunner` handles auto-install
- Node.js not available → error in console
- Test timeout → process killed, timeout message in console

## 4. Implementation plan

1. **Create `CypressTestDiscoveryService`** in `com.servoy.eclipse.developer.mcp/src/.../services/CypressTestDiscoveryService.java`
   - `hasTest(String formName)`, `discoverAllTestForms()`, `hasAnyTest()`
   - Delegates to `FormSpecGenerator` for path resolution

2. **Create `CypressFormTestTarget` interface** in `com.servoy.eclipse.developer.mcp/src/.../actions/CypressFormTestTarget.java`
   - `String getFormName()` (null for solution-level)
   - `boolean isSolutionLevel()`
   - `List<String> getTestFormNames()` (for run-all)

3. **Create `CypressTestAdapterFactory`** in `com.servoy.eclipse.developer.mcp/src/.../actions/CypressTestAdapterFactory.java`
   - Adapts `SimpleUserNode` to `CypressFormTestTarget`
   - Checks node type (FORM, SOLUTION, FORMS) and delegates to discovery service

4. **Create `RunCypressFormTestHandler`** in `com.servoy.eclipse.developer.mcp/src/.../actions/RunCypressFormTestHandler.java`
   - Extends `AbstractHandler`
   - Gets selection, adapts to target, runs `FormSpecRunner.runSpec` in a Job
   - Writes output to Eclipse Console

5. **Create `RunAllCypressFormTestsHandler`** in `com.servoy.eclipse.developer.mcp/src/.../actions/RunAllCypressFormTestsHandler.java`
   - Extends `AbstractHandler`
   - Gets solution-level target, iterates all test forms, runs each spec
   - Aggregates and reports results to console

6. **Update `plugin.xml`** in `com.servoy.eclipse.developer.mcp`:
   - Add command definitions (2 commands)
   - Add handler registrations (2 handlers)
   - Add menu contributions with `visibleWhen` expressions
   - Add adapter factory registration
   - Add core expression definitions for visibility

7. **Write integration tests** in `com.servoy.eclipse.developer.mcp.tests` (require PDE test launcher):
   - `CypressTestDiscoveryServiceTest` — test discovery logic with real workspace projects
   - `CypressTestAdapterFactoryTest` — test adaptation rules for different node types
   - `RunCypressFormTestHandlerTest` — test handler logic with live Eclipse workbench

## 5. Acceptance criteria

- [ ] Right-clicking a form node in Solution Explorer shows "Run Cypress Form Test" only when a `.spec.cy.js` file exists for that form
- [ ] Right-clicking a solution or Forms node shows "Run All Cypress Form Tests" only when at least one form has a Cypress test
- [ ] "Run Cypress Form Test" on a single form executes the Cypress spec and shows results in an Eclipse Console
- [ ] "Run All Cypress Form Tests" discovers all Cypress form tests and executes each, reporting aggregate results
- [ ] Menu actions do not appear when no Cypress tests exist
- [ ] Context menu enablement check completes in < 100ms (no noticeable UI delay)
- [ ] Execution runs in a background Job without blocking the UI
- [ ] Tests pass for the new handler and discovery service classes

## 6. Out of scope

- Rich test result reporting UI (covered by SVY-21174)
- Moving test files out of `medias/` (covered by SVY-21171)
- Headless execution product for CI/CD (covered by SVY-21173)
- Test generation from the context menu (already available via MCP `generateFormSpec` tool)
- Parallel test execution (Cypress runs tests sequentially)
- Caching/indexing of test file locations (premature optimization; filesystem checks are sufficient)

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| When SVY-21171 moves test files, will there be a migration tool or will existing tests need to be regenerated? | Product | open |
