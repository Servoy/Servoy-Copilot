# Spec: SVY-21169 — Form Navigation Tree

## 1. Goal

Give the AI a complete map of the solution's form navigation structure — which forms contain which other forms, and how to reach any form from the main form. This enables:

- **E2E flow test generation** — AI generates Cypress scripts that navigate through the real application (clicking tabs, buttons, etc.) rather than using isolated `?formpreview=` URLs
- **Contextual bug fixing** — when fixing a bug on a specific form, AI knows how to reach that form in the running app to verify the fix
- **Understanding user problem reports** — when a user says "I have a problem on the orders screen", AI can map that to the actual form and understand its context within the application
- **Solution comprehension** — AI understands how the solution is structured from a navigation perspective, how forms relate to each other, and what UI path a user takes through the application

## 2. Background

### 2.1 Current state

The existing `FormSpecGenerator` generates isolated Cypress test specs for individual forms using `cy.visit('?formpreview=<formName>&svy_testmode=true')`. This bypasses the real application navigation entirely — it directly renders a single form in isolation without login, without the solution's main form, and without the real container hierarchy.

Real Cypress E2E tests work very differently:
1. Visit the **solution start URL**: `cy.visit('/solution/solutionName/index.html?svy_testmode=true')`
2. **Login** if required (type credentials, click submit)
3. **Navigate** to target forms by clicking UI elements — tabs, buttons, grid rows
4. **Verify** the target form loaded by asserting `data-cy` selectors on the new form

For example, a real E2E test from the Servoy test suite:
```javascript
cy.visit('/solution/test_webcomponents/index.html?svy_testmode=true');
cy.get('[data-cy="login.btn_login"]').should('have.text', 'Login');
cy.get("[data-cy='login.user']").click().type('admin');
cy.get("[data-cy='login.pass']").click().type('admin');
cy.get("[data-cy='login.btn_login']").click();
cy.get("[data-cy='mainForm.next']").click();          // navigate to next form
cy.get("[data-cy='testFC.btn_visible']").should('be.visible'); // verify new form
```

The current `FormSpecGenerator` approach is useful for isolated component smoke-tests but fails for:
- End-to-end user flow testing (real user journeys through the app)
- Understanding how a form fits into the larger application navigation
- Determining what UI interactions are needed to make a form visible/reachable

### 2.2 Form containment mechanisms in Servoy

Forms can be nested/shown in other forms through several mechanisms:

| Mechanism | Design-time? | How it works |
|---|---|---|
| **TabPanel** (with tabs) | Yes | `TabPanel` persist has `Tab` children, each referencing a form (by name) and optionally a relation |
| **Tabless panel** | Yes | `TabPanel` with `tabOrientation = HIDE (-1)` — shows one form at a time, no visible tabs |
| **Split pane** | Yes | `TabPanel` with `tabOrientation = SPLIT_HORIZONTAL (-2)` or `SPLIT_VERTICAL (-3)` — two panels |
| **Accordion** | Yes | `TabPanel` with `tabOrientation = ACCORDION_PANEL (-4)` |
| **WebComponent with form-typed properties** | Yes | Any `WebComponent` property whose type is `"form"` in the component's `.spec` file — see section 2.3 |
| **Navigator** | Yes | `Form.navigatorID` references another form used as sidebar navigator |
| **Script: form property assignment** | No (runtime) | JS code like `elements.myTabless.containedForm = forms.someForm` |
| **Script: window.show** | No (runtime) | `application.createWindow(...).show(forms.someForm)` — opens a form in a dialog/window |
| **Script: showFormPopup** | No (runtime) | `plugins.window.showFormPopup(...)` — opens a form as a popup |
| **Script: navigateToForm** | No (runtime) | Any call ending in `navigateToForm(forms.someForm)` — matches regardless of scope chain prefix (e.g. `scopes.navigation.navigateToForm(...)`, `myModule.navigateToForm(...)`, bare `navigateToForm(...)`). Pattern: `navigateToForm\s*\(\s*forms\.(\w+)` |
| **Script: JSForm.NAMES reference** | No (runtime) | `JSForm.NAMES.someForm` used anywhere in code — always a form reference regardless of context. Covers `navigateToForm(JSForm.NAMES.X)`, `someVar = JSForm.NAMES.X`, standalone `JSForm.NAMES.appDetails`, etc. Pattern: `JSForm\.NAMES\.(\w+)` |
| **Script: newForm** | No (runtime) | `solutionModel.newForm(...)` — creates a form dynamically in script; these forms can then be shown in containers or dialogs |

### 2.3 Component .spec files and form-typed properties

Every Servoy NG component has a `.spec` file that defines its properties, types, handlers, and APIs. Properties with `"type": "form"` indicate that the component can display/contain a form. The property name is **not** fixed — different components use different names:

| Property Name | Component | Spec file |
|---|---|---|
| `containedForm` | bootstrapcomponents-tabpanel | `tabpanel/tabpanel.spec` (in `types.tab`) |
| `containedForm` | bootstrapcomponents-tablesspanel | `tablesspanel/tablesspanel.spec` (model) |
| `containedForm` | servoyextra-sidenav | `sidenav/sidenav.spec` (model) |
| `headerForm` | servoyextra-sidenav | `sidenav/sidenav.spec` (model) |
| `footerForm` | servoyextra-sidenav | `sidenav/sidenav.spec` (model) |
| `formName` | servoyextra-sidenav | `sidenav/sidenav.spec` (in `types.MenuItem`) |
| `form` | servoyextra-collapse | `collapse/collapse.spec` (in `types.collapsible` and `types.card`) |
| `containsFormId` | servoyextra-splitpane | `splitpane/splitpane.spec` (in `types.pane`) |
| `editForm` | aggrid-groupingtable | `groupingtable/groupingtable.spec` (in `types.column`) |

The component specs live in the NG packages installed in the solution. At design time, the spec JSON is accessible via the Servoy model's web package manager. The graph builder must iterate all properties of all WebComponents and check against the component spec whether any property has `"type": "form"` — it cannot just look for a hardcoded property name.

### 2.4 Existing relevant APIs

- `TabPanel.getTabs()` — iterates `Tab` persists (each has `.getContainsFormID()` and `.getRelationName()`)
- `WebComponent.getProperties()` — all property values on the component instance
- `WebComponentSpecProvider` / package manager — resolves component spec definitions including property types
- `FlattenedSolution.getFormHierarchy(Form)` — inheritance hierarchy (not navigation)
- `Form.getNavigatorID()` — navigator form reference
- `BasicFormController.getFormContext()` — runtime-only context (dataset: containername, formname, tabpanel/beanname, tabname, tabindex)
- `FlattenedSolution.getForms(boolean)` — all forms in the solution
- `ServoyModelFinder.getServoyModel().getFlattenedSolution()` — access point from Eclipse

### 2.5 Runtime `getFormContext()` dataset format

The existing Servoy `controller.getFormContext()` API returns a dataset with columns:
`[containername(1), formname(2), tabpanel/splitpane/accordion/beanname(3), tabname(4), tabindex(5), tabindex1based(6)]`

Rows go from mainform (row 1) → parent (row 2) → current form (row 3). This is the runtime equivalent of what we need to build statically.

## 3. Design

### 3.1 Navigation graph model

A directed graph where:
- **Nodes** = forms (identified by name)
- **Edges** = "form A can show form B", annotated with:
  - `containerName`: the element name of the container in form A (e.g., `"tabs_1"`)
  - `containerType`: `"tabpanel"` | `"tabless"` | `"splitpane"` | `"accordion"` | `"formcomponent"` | `"navigator"` | `"script"`
  - `tabName`: for tabpanels, the name of the specific tab (enables `cy.get('[data-cy="tabName"]').click()`)
  - `tabIndex`: 0-based position
  - `relationName`: if the tab uses a relation (affects foundset)
  - `trigger`: for script-based edges, the element/method that causes the navigation (e.g., `"button_1.onAction"`)
  - `confidence`: `"static"` (design-time, certain) or `"dynamic"` (script analysis, possible)

### 3.2 Graph building — Phase 1 (static/design-time)

Iterate all forms in the active flattened solution. For each form:

1. **TabPanels**: Iterate `form.getTabPanels()`. For each TabPanel, iterate its tabs. Each tab's `containsFormID` resolves to a form name → create an edge. The edge's `tabName` enables Cypress to click the correct tab (e.g., `cy.get('[data-cy="formName.tabName"]').click()`).
2. **WebComponents with form-typed properties**: For each `WebComponent` child, resolve its component spec (`.spec` file) from the web package manager. Iterate all properties defined in the spec. For any property where `"type": "form"`, read the property value from the component instance — if it references a form → create an edge. This handles `containedForm`, `headerForm`, `footerForm`, `formName`, `form`, `containsFormId`, `editForm`, and any future form-typed property without hardcoding names.
3. **Navigator**: If `form.getNavigatorID()` references a form → create a reverse edge (the navigator is *shown inside* this form, not the other way around). Also note which forms *use* this navigator form.

### 3.3 Graph building — Phase 2 (script analysis)

Scan JavaScript files in the solution for patterns that set form references dynamically:

1. **Form property assignments**: Regex scan for `elements.<name>.<property> = forms.<formName>` or `elements.<name>.<property> = '<formName>'` — this covers `containedForm`, `headerForm`, or any form-typed property being set at runtime.
2. **tabIndex assignments**: `elements.<tabpanel>.tabIndex = ...` (indicates switching visible tab, but tabs are already known from Phase 1)
3. **Window/dialog/popup calls**: `application.createWindow(...).show(forms.<name>)` and `plugins.window.showFormPopup(forms.<name>, ...)` — these produce edges with `containerType: "dialog"` or `"popup"`. The dialog/popup form itself may contain further nested forms (tabpanels, form-typed components) which are discovered recursively in Phase 1.
4. **`navigateToForm(forms.<name>)`**: Matches any call ending in `navigateToForm(forms.<name>)` regardless of the scope chain prefix — e.g. `scopes.navigation.navigateToForm(forms.X)`, `myNav.navigateToForm(forms.X)`, bare `navigateToForm(forms.X)`. Regex: `navigateToForm\s*\(\s*forms\.(\w+)`. Produces an edge with `containerType: "navigation"`.
5. **`JSForm.NAMES.<name>`**: Matches anywhere `JSForm.NAMES.<name>` appears in code — it is always a form reference regardless of context. Covers `navigateToForm(JSForm.NAMES.X)`, `someVar = JSForm.NAMES.X`, `JSForm.NAMES.appDetails` as a standalone expression, etc. Regex: `JSForm\.NAMES\.(\w+)`. Produces an edge with `containerType: "navigation"`.

Script context: `extractFormContext` maps each `.js` file to its source context. Files under `forms/` map to their form name. All other `.js` files (scope scripts, root-level files) fall back to the file's base name — this ensures `navigateToForm` calls in scope scripts like `navigation.js` or `appReportsBaseWidget.js` are captured. Spec/test files (`*.spec.cy.js`, `test_*.js`) are excluded.

These produce edges with `confidence: "dynamic"` and `trigger` indicating the script file and method.

### 3.4 Path finding

Given a target form name, compute the shortest path from the solution's first form (configured in solution properties as `firstFormID`) to the target form using BFS on the navigation tree. The graph starts **after login** — the authenticator/login form is not part of the navigation tree. Return the path as an ordered list of navigation steps.

Note: The structure is essentially a **tree** rooted at the first form (forms nested inside other forms via containers). Cycles are unlikely but BFS handles them safely. Forms shown via `showFormInDialog` are reachable but require a script trigger rather than a static UI click.

### 3.5 MCP tool API

Add two new tools to `ServoyTestingServer`:

#### `getFormNavigationGraph`
- **Parameters**: `formName` (optional — if provided, returns only the subgraph relevant to reaching this form; if omitted, returns the full graph)
- **Returns**: JSON with:
  - `mainForm`: the entry-point form name (solution start form)
  - `graph`: array of edges `{from, to, containerName, containerType, propertyName, tabName, tabIndex, relationName, trigger, confidence}`
  - `pathTo` (if `formName` specified): ordered array of steps from mainForm to target

#### `getNavigationPath`
- **Parameters**: `targetForm` (required), `fromForm` (optional, defaults to main form)
- **Returns**: JSON array of navigation steps, each with enough detail for a Cypress script generator to produce the corresponding `cy.get('[data-cy="..."]').click()` commands.

### 3.6 Directory structure

All Cypress test artifacts live under `{workspace}/jenkins-custom/e2e-test-scripts/cypress/`:

```
jenkins-custom/e2e-test-scripts/
├── cypress.config.js              ← auto-scaffolded by generateCypressE2ETest
├── cypress/
│   ├── e2e/<solutionName>/        ← E2E navigation tests (multi-form flows)
│   │   ├── dialogform1.cy.js
│   │   └── dialogform2.cy.js
│   ├── cy-form/                   ← Single-form Cypress UI tests (.spec.cy.js)
│   │   └── orderDetails.spec.cy.js
│   ├── cy-form-spec/              ← Form setUp/tearDown specs (.spec.js)
│   │   └── orderDetails.spec.js
│   ├── support/
│   │   ├── commands.js
│   │   └── e2e.js
│   ├── screenshots/
│   └── videos/
```

- **`e2e/<solutionName>/`** — E2E tests generated by `generateCypressE2ETest`. These navigate through the real application (login → click tabs → open dialogs). One subdirectory per solution.
- **`cy-form/`** — Single-form tests generated by `generateFormSpec`. These use `?formpreview=<formName>&svy_testmode=true` to render one form in isolation.
- **`cy-form-spec/`** — Servoy setUp/tearDown scope files for form tests (with DLTK code completion support).

### 3.7 Testing mode and `data-cy` attributes

Servoy NG client renders `data-cy="formName.elementName"` attributes on DOM elements **only when testing mode is enabled**. There are two mechanisms:

1. **URL parameter** (preferred for Cypress): `?svy_testmode=true` appended to the page URL. This enables testing mode per-session at page load time.
2. **Server property**: `servoy.ngclient.testingMode=true` in `servoy.properties`. This enables it globally but may not take effect for already-running client sessions.

The generated E2E tests use the URL parameter approach:
```javascript
cy.visit('/solution/mySolution/index.html?svy_testmode=true');
```

The generated form tests also include it:
```javascript
cy.visit('solution/mySolution/index.html?formpreview=myForm&svy_testmode=true');
```

### 3.8 Cypress script integration — E2E tests

The `generateCypressE2ETest` tool produces navigation-based E2E tests using `FormNavigationGraphService.generateCypressTestContent()`:

1. Visit the **solution start URL**: `cy.visit('/solution/<solutionName>/index.html?svy_testmode=true')`
2. For each step in the navigation path, perform the appropriate Cypress action based on `containerType`:
   - **Tab/tabless/accordion click**: `cy.get('[data-cy="parentForm.tabName"]').click()`
   - **Dialog/popup trigger**: `cy.get('[data-cy="parentForm.buttonName"]').click()` + wait for dialog
   - **Generic click**: `cy.get('[data-cy="parentForm.elementName"]').click()`
3. If no selector is available for an edge, a `// TODO` comment is inserted for manual resolution
4. An **assertions** section is scaffolded with TODO comments for the scenario

The AI agent can then read the generated file, run it via `testE2E`, and iteratively fix selectors based on screenshots and error messages.

### 3.9 Custom command discovery

When `generateCypressE2ETest` produces a test, it also scans the `cypress/support/` directory for existing custom Cypress commands (e.g., `commands.js`, `helpers.js`). If any files contain real code (not just scaffold comments), their content is appended to the tool output:

```
--- Available Cypress custom commands (from cypress/support/) ---
Use these in the generated test instead of writing raw commands:

// --- commands.js ---
Cypress.Commands.add('login', (username, password) => { ... });
Cypress.Commands.add('clickAgGridRow', (selector, rowIndex) => { ... });
Cypress.Commands.add('openPopupMenu', (trigger, menuItem) => { ... });
```

This allows the AI to **reuse existing helpers** (login flows, ag-grid interactions, popup menu handling, etc.) rather than generating raw Cypress commands. The AI sees the available custom commands and can call `cy.login('admin', 'admin')` instead of manually typing credentials, or use `cy.clickAgGridRow(...)` instead of complex grid selectors.

Files excluded from discovery:
- `e2e.js` / `e2e.ts` (just imports `./commands`)
- Files containing only comments (the default scaffold)

### 3.10 Cypress script integration — Form tests

The `generateFormSpec` tool produces isolated single-form tests using `FormSpecGenerator.generateCypressSpecContent()`:

1. Visit the **form preview URL**: `cy.visit('solution/<solutionName>/index.html?formpreview=<formName>&svy_testmode=true')`
2. Wait for the form to render (timeout 30s on `[data-cy^="formName."]`)
3. Assert no errors (`.svy-error, .error-overlay` should not exist)
4. Verify visibility of up to 8 named elements
5. Verify buttons are visible and enabled

### 3.11 MCP Tools

| Tool | Purpose | Output location |
|------|---------|-----------------|
| `generateCypressE2ETest` | Generate multi-form navigation test scaffold | `cypress/e2e/<solutionName>/<fileName>.cy.js` |
| `testE2E` | Run E2E spec headless | — |
| `showAndTestE2E` | Run E2E spec in visible browser | — |
| `listE2ETests` | List all E2E test files | — |
| `generateFormSpec` | Generate single-form test | `cypress/cy-form/<formName>.spec.cy.js` + `cypress/cy-form-spec/<formName>.spec.js` |
| `testForm` | Run single-form spec headless (auto-generates if missing) | — |
| `showAndTestForm` | Run single-form spec in visible browser | — |

### 3.12 Workspace file access

The `jenkins-custom` directory lives at the Eclipse workspace root level but is **not** an Eclipse project. To allow the AI agent to read and edit generated test files, `WorkspaceService.readProjectResource` and `CodeEditingService.replaceFileContent` include a fallback that resolves paths from the workspace root when the "project name" does not match any Eclipse project.

### 3.13 Output format example

```json
{
  "mainForm": "main_form",
  "pathTo": "order_detail",
  "steps": [
    {
      "form": "main_form",
      "action": "click_tab",
      "container": "tabs_main",
      "containerType": "tabpanel",
      "propertyName": "containedForm",
      "tabName": "Orders",
      "tabIndex": 1,
      "targetForm": "orders_list",
      "cypressSelector": "[data-cy=\"main_form.Orders\"]"
    },
    {
      "form": "orders_list",
      "action": "click_element",
      "container": "detail_panel",
      "containerType": "tabless",
      "propertyName": "containedForm",
      "trigger": "button_view_detail.onAction",
      "confidence": "dynamic",
      "targetForm": "order_detail",
      "cypressSelector": "[data-cy=\"orders_list.button_view_detail\"]"
    }
  ]
}
```

## 4. Implementation plan

1. **Create `FormNavigationGraphService`** in `com.servoy.eclipse.developer.mcp.services`:
   - Method `buildStaticGraph(FlattenedSolution fs)` — Phase 1 graph from TabPanels, WebComponents (inspecting component .spec for form-typed properties), navigators
   - Method `augmentWithScriptAnalysis(FlattenedSolution fs, NavigationGraph graph)` — Phase 2 script scanning
   - Method `findPath(NavigationGraph graph, String fromForm, String toForm)` — BFS/shortest path
   - Inner classes: `NavigationGraph`, `NavigationEdge`, `NavigationStep`

2. **Create `NavigationGraph` model classes** in `com.servoy.eclipse.developer.mcp.services`:
   - `NavigationGraph` — holds adjacency list, provides `getEdgesFrom(form)`, `getEdgesTo(form)`, `findPath(...)`
   - `NavigationEdge` — from, to, containerName, containerType, propertyName, tabName, tabIndex, relationName, trigger, confidence

3. **WebComponent form-property discovery**: For each `WebComponent` on a form, resolve its component spec via the web package manager / `WebComponentSpecProvider`. Iterate all declared properties. For any property with `"type": "form"`, read the component instance's value for that property. If it resolves to a form UUID or name → create an edge with `propertyName` set to the actual property name (e.g., `"headerForm"`, `"containedForm"`, etc.).

3. **Add MCP tools** to `ServoyTestingServer`:
   - `getFormNavigationGraph(String formName)` — builds graph, optionally filters to subgraph, returns JSON
   - `getNavigationPath(String targetForm, String fromForm)` — returns path steps as JSON

4. **Extend `FormSpecGenerator`** (optional, Phase 2):
   - Add `generateNavigationHelper(List<NavigationStep> path)` method that produces Cypress commands for each step
   - Update `generateCypressSpecContent()` to accept an optional navigation path parameter
   - Use solution start URL pattern: `cy.visit('/solution/solutionName/index.html?svy_testmode=true')` instead of `?formpreview=`

5. **Write unit tests** in `com.servoy.eclipse.developer.mcp.tests`:
   - `FormNavigationGraphServiceTest` — test graph building with mock forms/tabpanels/web components with various form-typed properties
   - `NavigationGraphTest` — test path finding (BFS correctness, cycles, unreachable forms)
   - Test script analysis regex patterns

6. **Register tool descriptions** — ensure the `@Tool` annotations on the new methods have clear descriptions mentioning Cypress and navigation context.

## 5. Acceptance criteria

- [x] AI can call `getFormNavigationGraph()` and receive a JSON graph of all form-to-form navigation edges in the active solution
- [x] AI can call `getNavigationPath("order_detail")` and receive an ordered list of steps from the first form to `order_detail`
- [x] Static edges (TabPanel tabs, WebComponent form-typed properties, navigators) are detected with `confidence: "static"`
- [x] WebComponent form-typed properties are discovered dynamically by inspecting component `.spec` files (not hardcoded property names) — handles `containedForm`, `headerForm`, `footerForm`, `formName`, `form`, `editForm`, etc.
- [x] Script-based edges (form property assignments, `showFormInDialog`) are detected with `confidence: "dynamic"` and include the trigger method
- [x] `showFormInDialog` edges use `containerType: "dialog"` and the dialog form's own children are recursively included in the tree
- [x] `navigateToForm(forms.X)` calls are detected regardless of scope chain prefix (e.g. `scopes.navigation.navigateToForm`, `myNav.navigateToForm`, bare call) and produce edges with `containerType: "navigation"` and `confidence: "dynamic"`
- [x] `JSForm.NAMES.X` references are detected anywhere in code (assignments, direct call arguments, standalone expressions) and produce edges with `containerType: "navigation"` and `confidence: "dynamic"`
- [x] `extractFormContext` falls back to the script file's base name for non-form scripts (scope files, root-level `.js`), so `navigateToForm` calls outside `forms/` are captured
- [x] Spec and test files (`*.spec.cy.js`, `test_*.js`) are excluded from `extractFormContext` to avoid noise
- [x] Path finding handles cycles without infinite loops
- [x] Path finding returns empty/error for truly unreachable forms
- [x] Each navigation step includes a suggested `cypressSelector` for the action needed to navigate
- [x] The graph is rebuilt on each tool call (no stale cache)
- [x] Tools are registered in `ServoyTestingServer`
- [x] Unit tests cover: graph building from tabpanels, graph building from WebComponents with diverse form-typed properties, path finding, script pattern detection
- [x] Tool output is valid JSON parseable by the AI for Cypress script generation
- [x] `generateCypressE2ETest` writes test files to `cypress/e2e/<solutionName>/` directory
- [x] `generateFormSpec` writes form tests to `cypress/cy-form/` and setup specs to `cypress/cy-form-spec/`
- [x] Generated E2E tests include `?svy_testmode=true` in the visit URL to enable `data-cy` attributes
- [x] Generated form tests include `?svy_testmode=true` via `?formpreview=<form>&svy_testmode=true`
- [x] AI agent can read/edit files in `jenkins-custom/` via workspace-root fallback in `readProjectResource` and `replaceFileContent`
- [x] `cypress.config.js` is auto-scaffolded by `generateCypressE2ETest` (without deprecated `experimentalStudio` option)
- [x] `generateCypressE2ETest` discovers existing custom commands in `cypress/support/` and includes them in the output so the AI can reuse helpers (login, ag-grid, popup menu, etc.)

## 6. Out of scope

- Solution model (multi-solution module graphs) — mentioned in ticket but explicitly deferred
- Login/authentication flow — handled by the authenticator; the navigation tree starts from the first form after login. The generated E2E tests include a `beforeEach` hook where login logic can be added manually.
- Performance optimization (caching the graph across tool calls) — rebuild on each call; can be optimized later if needed
- `controller.show()` calls — these are legacy and rarely used in NG solutions
- Forms created dynamically via `solutionModel.newForm()` — these exist only at runtime and cannot be discovered statically; they will not appear in the tree unless they are subsequently assigned to a container via a detectable script pattern

## 7. Open questions

*None — all resolved.*
