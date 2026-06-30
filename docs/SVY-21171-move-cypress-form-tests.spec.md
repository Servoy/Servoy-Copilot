# Spec: SVY-21171 — Move Cypress form test files out of the media folder

## 1. Goal

Move generated Cypress form test spec files (`*.spec.cy.js`) out of the
solution's `medias/tests/` folder and into a dedicated directory **outside** the
solution: `{workspace}/jenkins-custom/e2e-test-scripts/cypress/e2e-form/`.
Media files become part of the deployed/exported solution, so test artifacts
placed there leak into production. Relocating them keeps the test specs
associated with the workspace but excluded from any solution deployment, and
aligns form tests with the existing Servoy Cloud E2E directory structure
(`jenkins-custom/e2e-test-scripts/cypress/e2e/`) already used by
`generateCypressE2ETest`.

This is a follow-up to SVY-21025 (form testing infrastructure) and relates to
SVY-21170 (context-menu run actions). The location change must flow through the
generator, the runner, the discovery service, the rename service, and the
solution scaffolding.

## 2. Background

### 2.1 Current behaviour (SVY-21025)

`com.servoy.eclipse.developer.mcp` writes two artifacts per form:

| Artifact | Current location | Purpose |
|---|---|---|
| `{formName}.spec.cy.js` | `{solution}/medias/tests/` | Cypress UI assertions (`data-cy` selectors) |
| `{formName}.spec.js` | `{solution}/forms/` | Servoy `spec_setUp()`/`spec_tearDown()` scope file |

The Cypress spec is placed under `medias/` and a `.buildpath` exclusion
(`**/*.spec.cy.js`) keeps DLTK from parsing it. The problem: everything under
`medias/` is bundled into the deployed/exported solution, so the `.spec.cy.js`
files ship to production.

The `.spec.js` setup file is a genuine Servoy scope file (it has
`@properties` annotations and is read by a headless client at runtime). It is
**not** a deployment problem in the same way and is **out of scope** for this
ticket — only the `.spec.cy.js` Cypress artifact moves.

### 2.2 Key classes and the `medias/tests` coupling

`FormSpecGenerator` (`bundles/.../services/FormSpecGenerator.java`):
- `CYPRESS_TESTS_DIR = "medias/tests"` — the single constant that defines the location.
- `BUILDPATH_EXCLUSION_PATTERN = "**/*.spec.cy.js"` and `ensureBuildpathExclusion(...)` (currently unused/private) — only relevant while files live inside the solution.
- `generateSpec(formName)` — resolves `testsDir` against the **project** location, writes the `.spec.cy.js` there, then calls `project.refreshLocal(...)`.
- `specExists(formName)`, `getSpecFilePath(formName)`, `getFormsDir()` — all resolve against `project.getLocation()/medias/tests`.

`FormSpecRunner` (`bundles/.../services/FormSpecRunner.java`):
- `runSpec(formName, headless)` — gets the spec path from `FormSpecGenerator.getSpecFilePath`, ensures a Cypress install in `.metadata/.plugins/com.servoy.eclipse.developer.mcp/cypress/`, writes a `cypress.config.js` there (`specPattern: '**/*.spec.cy.js'`), and runs `npx cypress run --spec <path>` with the **solution project** as the working directory.
- `runE2ESpec(targetForm, headless)` — already runs E2E specs from `jenkins-custom/e2e-test-scripts/cypress/e2e/`, preferring a project-local Cypress binary in `e2e-test-scripts/node_modules/.bin`. This is the model to follow for the new form-spec location.

`CypressTestDiscoveryService` (`bundles/.../services/CypressTestDiscoveryService.java`):
- `hasTest`, `discoverAllTestForms`, `hasAnyTest` — all call `specGenerator.getFormsDir()` (i.e. `medias/tests`) to discover `*.spec.cy.js` files. Used by the SVY-21170 context-menu adapter/handlers.

`PersistRenameService` (`bundles/.../services/PersistRenameService.java`):
- `renameFormSpecFiles(old, new, project)` — moves both `forms/{old}.spec.js` and `medias/tests/{old}.spec.cy.js` when a form is renamed. The `.spec.cy.js` branch must point at the new location.

`ServoyDevServer` (`bundles/.../servers/ServoyDevServer.java`):
- Writes the solution `.buildpath` with `excluding=".stp/|medias/|**/*.spec.cy.js"` when scaffolding a solution.

`ServoyTestingServer` (`bundles/.../servers/ServoyTestingServer.java`):
- Tool descriptions for `generateFormSpec` mention `medias/tests/`. Descriptions must be updated to the new path.

### 2.3 The target directory already partly exists

`generateCypressE2ETest` writes navigation E2E tests to
`{workspace}/jenkins-custom/e2e-test-scripts/cypress/e2e/` and scaffolds
`cypress.config.js`, `cypress/support/commands.js`, and `cypress/support/e2e.js`
in `jenkins-custom/e2e-test-scripts/`. The form specs will live in a **sibling**
`e2e-form/` directory under the same `cypress/` root, reusing the same Cypress
project (config, node_modules, support files).

```
{workspace}/
  jenkins-custom/
    e2e-test-scripts/
      cypress.config.js          (shared, scaffolded by generateCypressE2ETest)
      cypress/
        e2e/                      (navigation E2E tests — existing)
        e2e-form/                 (form spec tests — NEW location for *.spec.cy.js)
        support/
        ...
```

### 2.4 Workspace vs. project root

The target is **workspace-relative**, not project-relative. `jenkins-custom`
lives directly in the Eclipse workspace directory
(`ResourcesPlugin.getWorkspace().getRoot().getLocation()`), the same anchor
`FormSpecRunner.runE2ESpec` and `ServoyTestingServer.generateCypressE2ETest`
already use. Because the new location is outside the solution, the
`.buildpath` exclusion and `ensureBuildpathExclusion` machinery are no longer
needed for the moved files.

### 2.5 Git history

`FormSpecGenerator` was introduced by SVY-21025 (commits `4b85c04`, `b8e58b7`,
`ecfb56b`, `96d45f9`). The `medias/tests` placement was an intentional choice in
SVY-21025 because `medias/` was already DLTK-excluded — but the deployment
side-effect was overlooked. This ticket reverses that placement decision; it
does not revert any other SVY-21025 behaviour (data-cy attributes, headless
setUp/tearDown, `.spec.js` scope files all stay).

## 3. Design

### 3.1 New form-spec location

Introduce a single source of truth for the Cypress form-spec directory,
resolved against the workspace root:

```
{workspace}/jenkins-custom/e2e-test-scripts/cypress/e2e-form/
```

Spec file name convention is unchanged: `{formName}.spec.cy.js`. Only the
directory changes (from solution-relative `medias/tests` to
workspace-relative `jenkins-custom/e2e-test-scripts/cypress/e2e-form`).

### 3.2 `FormSpecGenerator` changes

- Replace the `CYPRESS_TESTS_DIR = "medias/tests"` constant with a
  workspace-relative resolver, e.g. a private helper
  `Path resolveFormSpecDir()` that returns
  `workspaceRoot/jenkins-custom/e2e-test-scripts/cypress/e2e-form` (workspace
  root obtained from `ResourcesPlugin.getWorkspace().getRoot().getLocation()`).
- `generateSpec(formName)`:
  - Resolve `testsDir` via `resolveFormSpecDir()` instead of
    `project.getLocation()/medias/tests`.
  - `Files.createDirectories(testsDir)` (creates the `e2e-form` dir on demand).
  - Keep writing the `.spec.js` setup file in `{solution}/forms/` (unchanged).
  - The `project.refreshLocal(...)` call only needs to refresh the solution
    (for the `.spec.js`); the `e2e-form` directory is outside the workspace
    project tree, so no IResource refresh is required for the `.spec.cy.js`.
  - Update the user-facing result strings (currently
    `"Created: medias/tests/..."`) to the new relative path.
- `specExists(formName)`: check `resolveFormSpecDir()/{formName}.spec.cy.js`
  (the `.spec.js` existence check in `forms/` is unchanged).
- `getSpecFilePath(formName)`: resolve against `resolveFormSpecDir()`.
- `getFormsDir()`: return `resolveFormSpecDir()` (kept for
  `CypressTestDiscoveryService`; consider renaming to `getFormSpecDir()` for
  clarity, updating callers).
- Remove or neutralise `ensureBuildpathExclusion(...)` and
  `BUILDPATH_EXCLUSION_PATTERN` for the moved files, since the specs are no
  longer inside the solution. (The method is currently private and unused;
  removing it is safe. Leave the `.buildpath` exclusion in `ServoyDevServer`
  in place only if any `.spec.cy.js` could still be created under the
  solution — see 3.6 — otherwise remove that fragment too.)

### 3.3 `FormSpecRunner` — no change required

`runSpec(formName, headless)` resolves the spec path via
`specGenerator.getSpecFilePath(formName)` (`FormSpecRunner.java:57`) and runs it
with an **absolute** `--spec` path (`:93`) plus its own `cypress.config.js`
written into the `.metadata` Cypress dir (`:74`, `ensureCypressConfig` at `:148`).
The `baseUrl` is computed correctly for the running solution
(`http://localhost:{port}/solution/{solution}/index.html`, `:72`).

Because the runner takes the spec path straight from the generator and passes it
as an absolute `--spec`, **once `FormSpecGenerator.getSpecFilePath` returns the
`e2e-form/` path the runner follows automatically with no code change.** The
working directory (`:102`, currently the solution project) is irrelevant since
both `--spec` and `--config-file` are absolute. The runner keeps its bundled Node
+ `.metadata` Cypress install and its own config (with the solution `baseUrl`).
No shared `jenkins-custom` Cypress project, no `--config baseUrl=` override, and
no helper extraction are needed for this ticket.

### 3.4 `CypressTestDiscoveryService` changes

No behavioural change required if it continues to call
`FormSpecGenerator.getFormsDir()` / `getFormSpecDir()` — the discovery
automatically follows the generator's new location. Verify the three methods
(`hasTest`, `discoverAllTestForms`, `hasAnyTest`) work when the directory is the
workspace-level `e2e-form/`. Note: discovery is now **workspace-wide**, not
per-solution; if multiple solutions share the workspace, all their form specs
live in one `e2e-form/` directory. This is acceptable because the file name is
the form name and form names are unique within the active solution context the
discovery runs in. Document this in the service.

### 3.5 `PersistRenameService` changes

In `renameFormSpecFiles(old, new, project)`:
- The `forms/{old}.spec.js` → `forms/{new}.spec.js` move is unchanged (still a
  workspace `IFile` inside the solution).
- The `.spec.cy.js` file is now **outside** the workspace project tree, so it
  cannot be moved with `IFile.move(...)`. Replace that branch with a
  `java.nio.file.Files.move(...)` against the resolved `e2e-form/` paths
  (use `FormSpecGenerator.getSpecFilePath(old)` and `...(new)`), guarded by
  existence checks and a no-overwrite rule (mirror the current
  "don't overwrite if target exists" behaviour).

### 3.6 `ServoyDevServer` / `.buildpath`

The `**/*.spec.cy.js` fragment exists only because the specs used to live
**inside** the solution. DLTK parses every `.js` file in a Servoy solution, and
Cypress spec syntax made DLTK fail (the StackOverflow noted at
`FormSpecGenerator.java:25`), so the files had to be excluded from the build
path. Once the specs move outside the solution into `jenkins-custom/`, DLTK
never encounters them and the exclusion serves no purpose for new specs.

**Decision:** drop the `|**/*.spec.cy.js` fragment from the `ServoyDevServer`
scaffolding (`ServoyDevServer.java:160`) so new solutions get the clean
`excluding=".stp/|medias/"`. Also remove the now-dead `ensureBuildpathExclusion`
+ `BUILDPATH_EXCLUSION_PATTERN` machinery from `FormSpecGenerator` (3.2).

### 3.7 Tool descriptions (`ServoyTestingServer`)

Update the `generateFormSpec` tool description (and any other description
referencing `medias/tests/`) to state the new
`jenkins-custom/e2e-test-scripts/cypress/e2e-form/` location. `testForm`,
`showAndTest`, `showFormInBrowser` descriptions that mention `.spec.cy.js`
generally don't reference the directory and need only minor review.

### 3.8 Migration of existing specs

Existing `.spec.cy.js` files already sitting in `{solution}/medias/tests/` are
**not** auto-migrated. They require regeneration (run `generateFormSpec` /
`showFormInBrowser` again, which writes to the new `e2e-form/` location). There
is no runtime relocation and no migration command in this ticket — these test
artifacts are cheap to regenerate and a startup/relocation job adds risk for no
real benefit. Stray legacy files under `medias/tests/` are harmless leftovers
the developer can delete manually.

## 4. Implementation plan

1. **`FormSpecGenerator`**
   - Add `resolveFormSpecDir()` returning
     `workspaceRoot/jenkins-custom/e2e-test-scripts/cypress/e2e-form`.
   - Replace all `CYPRESS_TESTS_DIR`/`project.getLocation().../medias/tests`
     usages in `generateSpec`, `specExists`, `getSpecFilePath`, `getFormsDir`.
   - Update result/message strings to the new relative path.
   - Remove `ensureBuildpathExclusion` + `BUILDPATH_EXCLUSION_PATTERN` (unused
     after the move) or document why kept.

2. **`FormSpecRunner.runSpec`** — no change needed. It takes the spec path from
   `FormSpecGenerator.getSpecFilePath` and passes it as an absolute `--spec`, so
   it follows the generator's new location automatically (see 3.3). Verify only.

3. **`PersistRenameService.renameFormSpecFiles`**
   - Switch the `.spec.cy.js` move from `IFile.move` to `Files.move` against the
     resolved `e2e-form/` paths, with existence + no-overwrite guards.

4. **`ServoyDevServer`**
   - Drop the `|**/*.spec.cy.js` fragment from the scaffolded `.buildpath`
     (`ServoyDevServer.java:160`).

5. **`ServoyTestingServer`**
   - Update `generateFormSpec` (and any other) tool descriptions to reference
     `jenkins-custom/e2e-test-scripts/cypress/e2e-form/`.

6. **`CypressTestDiscoveryService`**
   - Verify it follows the generator's new dir; add a doc note about
     workspace-wide discovery. Rename `getFormsDir()` → `getFormSpecDir()` if
     done in step 1 (update callers).

7. **Update tests** (see section 5).

## 5. Test impact and plan

The location change breaks several existing assertions. Update them:

- **`CypressFormTestingIntegrationTest`** (PDE):
  - `testGenerateFormSpec_specInMediasTests` — rename and assert the path now
    contains `jenkins-custom`, `e2e-test-scripts`, `cypress`, `e2e-form`
    instead of `medias/tests`.
  - `testCypress_buttonClickUpdatesLabel` and any test that manually writes to
    `activeProject.../medias/tests` (line ~430, ~445) — write to the resolved
    `e2e-form/` dir instead.
  - The `.buildpath` literal at line ~1021 — update if `ServoyDevServer`
    scaffolding changes.
- **`ShowFormInBrowserIntegrationTest`** (PDE): line ~228/~246 write to
  `medias/tests` — update to `e2e-form/`.
- **`RenamePersistIntegrationTest`** (PDE): lines ~371–395 assert
  `medias/tests/{form}.spec.cy.js` before/after rename — update to the new
  location; note the file is now outside the project, so use
  `java.nio.file` checks rather than `IFile.exists()`. Update the `.buildpath`
  literal at line ~789 if scaffolding changes.
- **`CypressTestDiscoveryServiceTest`** (plain JUnit): uses a `tempDir` and
  reflection to inject the spec dir — verify the injection point still matches
  the refactored generator (it sets the `specGenerator` field). Should remain
  green; adjust if the dir-resolver method name changes.
- **`FormSpecGeneratorTest`** / **`FormSpecRunnerTest`** (plain JUnit): reflection
  presence checks for method names. Update if `getFormsDir`→`getFormSpecDir`
  or `ensureBuildpathExclusion` is removed.
- **`CodeAnalysisIntegrationTest`**, **`PersistDuplicateIntegrationTest`**:
  contain the `.buildpath` literal with `**/*.spec.cy.js` — update only if the
  scaffolding fragment is removed.
- **`RenamePersistIntegrationTest` / `RunCypressFormTestHandlerTest` /
  `CypressTestAdapterFactoryTest` / `CypressEditorInputPropertyTesterTest` /
  `RunAllCypressFormTestsHandlerTest`**: create `*.spec.cy.js` files in a
  `tempDir`; verify the dir they target matches the new resolver injection.

Run plain JUnit suites with `eclipse-ide_runClassTests`; run the PDE
integration tests (`CypressFormTestingIntegrationTest`,
`ShowFormInBrowserIntegrationTest`, `RenamePersistIntegrationTest`) with the PDE
launcher.

## 6. Acceptance criteria

- [ ] `generateFormSpec` writes `{formName}.spec.cy.js` to
  `{workspace}/jenkins-custom/e2e-test-scripts/cypress/e2e-form/`, never under
  the solution's `medias/`.
- [ ] The `.spec.js` setup file is still written to `{solution}/forms/`
  (unchanged).
- [ ] `FormSpecRunner.runSpec` finds and runs the moved spec against the running
  solution (form-preview `baseUrl`), returning pass/fail output.
- [ ] Renaming a form moves the `e2e-form/{old}.spec.cy.js` to
  `e2e-form/{new}.spec.cy.js`.
- [ ] `CypressTestDiscoveryService` discovers tests from the new location
  (context-menu enablement from SVY-21170 still works).
- [ ] No newly generated `.spec.cy.js` ends up inside a solution / deployed
  artifact; exporting a solution does not include any form Cypress spec.
- [ ] Tool descriptions reference the new location.
- [ ] All updated unit and integration tests pass; zero compilation errors.

## 7. Out of scope

- Moving the `.spec.js` Servoy setUp/tearDown scope file (stays in `forms/`).
- A heavyweight workspace migration tool for pre-existing specs.
- Changes to the navigation E2E tests in `cypress/e2e/` (already outside the
  solution).
- Rich test result reporting UI (SVY-21174).

## 8. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Confirm the exact target subfolder name `e2e-form` (vs. `e2e-forms`). | Product | resolved — `e2e-form` (user choice; keeps form-testing specs in a dedicated sibling of the existing `e2e/` nav-test dir) |
| Should existing `medias/tests/*.spec.cy.js` be auto-relocated, or require regeneration? | Product | resolved — regenerate; no auto-migration (see 3.8) |
| Remove the `\|**/*.spec.cy.js` fragment from `ServoyDevServer` `.buildpath` scaffolding? | Dev/Reviewer | resolved — remove; exclusion only mattered while specs lived inside the solution (see 3.6) |
| Form-spec `baseUrl` / run mechanics. | Dev | resolved — no runner change; `FormSpecRunner` already uses an absolute `--spec` from the generator and its own config with the correct solution `baseUrl` (see 3.3) |
