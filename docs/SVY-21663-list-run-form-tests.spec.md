# Spec: SVY-21663 — MCP: add listFormTests (and runAllFormTests) so agents can find and run form tests

## 1. Goal
Agents cannot currently discover the Cypress **form tests** that live in a solution. The
only listing tool, `listE2ETests`, scans `jenkins-custom/e2e-test-scripts/cypress/e2e/`
(the E2E folder), so when a user asks to "run all form tests" against a solution whose
form specs sit in `cypress/cy-form/`, the agent reports "none found" even though the tests
exist and pass. This spec adds a read-only `listFormTests` MCP tool to `ServoyTestingServer`
that surfaces the already-existing `CypressTestDiscoveryService.discoverAllTestForms()`
(the method that backs the IDE's "Run all form tests" action), plus an optional
`runAllFormTests` tool that runs every discovered form test and returns an aggregate. Both
tool descriptions state which folder they read (`cy-form`), and a cross-pointer between
`listE2ETests` (reads `cypress/e2e`) and `listFormTests` (reads `cypress/cy-form`) lets the
skill side route "list tests" requests to the right tool. The result: an agent given
"run all form tests" can enumerate and run them with no manual list of form names.

## 2. Background

### 2.1 Two kinds of Cypress test, two folders
A Servoy solution can carry two distinct kinds of Cypress test under
`jenkins-custom/e2e-test-scripts/cypress/`:

- **Form tests** — single-form specs named `<solution>.<form>.spec.cy.js` (or `.spec.cy.ts`),
  under `cypress/cy-form/`. Created by `generateFormSpec` / `showFormInBrowser`, run per-form
  by `testForm` and `showAndTest`.
- **E2E tests** — multi-form navigation specs named `<form>.cy.js` / `.cy.ts`, under
  `cypress/e2e/<solutionName>/`. Created by `generateCypressE2ETest`, run by `testE2E` /
  `showAndTestE2E`, and listed by `listE2ETests`.

The ticket requires these two kinds to **stay separate** (see Out of scope): `listE2ETests`
must keep scanning only `cypress/e2e`.

### 2.2 The discovery capability already exists — it is just not exposed over MCP
`com.servoy.eclipse.cypress.services.CypressTestDiscoveryService` (in the `servoy-eclipse`
`com.servoy.eclipse.cypress` bundle, already on this bundle's MANIFEST classpath) already
has the exact method needed:

```java
public List<String> discoverAllTestForms() {
    Path testsDir = specGenerator.getFormSpecDir();          // resolves to cypress/cy-form
    if (testsDir == null || !Files.isDirectory(testsDir)) {
        return Collections.emptyList();                       // no folder -> empty
    }
    try (Stream<Path> files = Files.list(testsDir)) {
        return files.filter(p -> FormSpecGenerator.isFormSpecFileName(p.getFileName().toString()))
                .map(p -> FormSpecGenerator.stripFormSpecExtension(p.getFileName().toString()))
                .distinct().toList();
    } catch (IOException e) {
        return Collections.emptyList();
    }
}
```

Supporting facts from the code:
- `FormSpecGenerator.getFormSpecDir()` resolves to
  `jenkins-custom/e2e-test-scripts/cypress/cy-form` via `resolveFormSpecDir()`, returning
  `null` on error.
- `FormSpecGenerator.isFormSpecFileName(name)` recognises `.spec.cy.js` / `.spec.cy.ts`.
- `FormSpecGenerator.stripFormSpecExtension(name)` removes that suffix, so a file named
  `mysolution.myform.spec.cy.js` becomes `mysolution.myform` — i.e. the returned entries are
  the spec file's **base name** (solution prefix included), not the bare form name. See
  section 3.4 for how the tool presents this.
- `discoverAllTestForms()` already handles the empty-folder and missing-folder cases by
  returning an empty list.

This method is used by the IDE's "Run all form tests" action but is **not** reachable over
MCP. `git log`/`git blame` show no commit ever wired it to an MCP tool — the gap is a
missing tool, not a regression.

### 2.3 The single-form runner already exists
`ServoyTestingServer.testForm(formName)` and `showAndTest(formName)` each resolve the active
solution name and call `specRunner.runFormCypressTests(formName, headless)`:

```java
public String runFormCypressTests(String formName, boolean headless) { ... }   // FormSpecRunner
```

So `runAllFormTests` is a thin loop over the discovered forms calling this existing runner.

### 2.4 Host server and existing conventions
`ServoyTestingServer` (`@McpServer(name = "servoy-test")`) already:
- imports `com.servoy.eclipse.cypress.services.FormSpecGenerator` and `FormSpecRunner`, holds
  `specGenerator` and `specRunner` fields — so adding a `CypressTestDiscoveryService` field
  needs **no new MANIFEST dependency** (same package/bundle, already imported).
- uses the `getServoyModel()` seam to reach the active model (unit-testable).
- declares each tool with `@Tool(name=..., description=..., type="object")` and wraps the body
  in try/catch logging via `ServoyLog.logError(...)` and returning `"Error: " + e.getMessage()`.
- writes runner output to the Cypress console via `writeToConsole(...)` in the run tools.

The unit test for this server is
`tests/com.servoy.eclipse.developer.mcp.tests/src/test/java/.../plainjunit/servers/ServoyTestingServerTest.java`
(plain JUnit 6 + Mockito, asserts `@McpServer` / tool presence, uses `getServoyModel()` seam
subclassing). New tool-presence assertions go there.

## 3. Design

### 3.1 New field on `ServoyTestingServer`
Add a `CypressTestDiscoveryService` collaborator alongside the existing cypress services:

```java
private final CypressTestDiscoveryService testDiscovery = new CypressTestDiscoveryService();
```

Add the import `com.servoy.eclipse.cypress.services.CypressTestDiscoveryService`. No MANIFEST
change — the cypress bundle is already required.

### 3.2 `listFormTests` tool (read-only — the core fix)
A new `@Tool(name = "listFormTests", type = "object")` with no parameters. It:
1. Calls `testDiscovery.discoverAllTestForms()`.
2. If the list is empty, returns a clear "no form tests" message that **names the folder**,
   e.g.:
   `"No form tests found in jenkins-custom/e2e-test-scripts/cypress/cy-form/. Use generateFormSpec to create a form test."`
   (Resolve the folder for the message the same way the generator does, so the path shown is
   the real one; if the folder path cannot be resolved, still name `cypress/cy-form/` in the
   message.)
3. Otherwise returns a markdown listing: a count line, the directory, and one line per entry
   showing the form name and the spec file name per form (see 3.4).

The `@Tool` description must:
- state it lists **form tests** from `jenkins-custom/e2e-test-scripts/cypress/cy-form/`;
- contrast with E2E tests ("these are single-form tests, NOT the multi-form E2E tests listed
  by `listE2ETests` in `cypress/e2e/`");
- note it is read-only and point the caller to `testForm` / `showAndTest` (one form) or
  `runAllFormTests` (all) to run them.

Follows the try/catch + `ServoyLog.logError` + `"Error: " + e.getMessage()` convention.

### 3.3 `runAllFormTests` tool (optional — runs every discovered form test)
A new `@Tool(name = "runAllFormTests", type = "object")` with no parameters (headless, like
`testForm`). It:
1. Calls `testDiscovery.discoverAllTestForms()`.
2. If empty, returns the same "no form tests" message as `listFormTests` (naming the folder)
   and does not run anything.
3. Otherwise checks the runtime up front (added in code review): calls
   `formPreview.checkNGClientStatus()`, the same call the `checkNGClientStatus` tool uses. When
   the status does not start with `Servoy Developer is running` it returns one
   `NG client not running: <status>` message and runs nothing, instead of fanning out into one
   slow failure per form. The empty-discovery message in step 2 still comes first.
4. Iterates the discovered forms and, for each, calls
   `specRunner.runFormCypressTests(formName, true)` (headless), collecting each per-form
   result into an aggregate markdown report (one section per form, plus a summary header with
   the total count). Keep it resilient: a failure running one form is captured in that form's
   section and the loop continues to the next (do not abort the whole run on the first error).
5. Writes the aggregate to the Cypress console via `writeToConsole(...)`, as `testForm` does.

See section 3.5 for exactly which name `discoverAllTestForms()` returns and what to pass to
`runFormCypressTests`.

The `@Tool` description must carry the two required warnings, consistent with the ticket:
- **handlers execute for real and can change data** (form specs trigger real event handlers);
- **the NG client must be running** (like `testForm` — point to `checkNGClientStatus`).
It must also state it runs the form tests found in `cypress/cy-form/`.

### 3.4 Presenting each form + its spec file name
`listFormTests` must show the spec file name for each form (acceptance criterion:
"returns every form that has one … plus the spec file name for each"). For each entry
returned by `discoverAllTestForms()` (a spec base name such as `mysolution.myform`), resolve
the actual spec file so the file name can be shown. Use the existing generator helper rather
than re-deriving the file name:
- `FormSpecGenerator.findExistingSpecFile(formName, solutionName)` finds the on-disk spec file
  (it tries the `<solution>.<form>` prefix first, then the bare name). Derive the file's simple
  name with `path.getFileName().toString()`.
- If a file cannot be resolved for an entry, still list the entry and show the raw base name as
  the spec identifier (do not drop it — the discovery already proved a spec file exists).

Output shape (illustrative):
```
**Form Tests** (2 files)

Directory: <abs path>/jenkins-custom/e2e-test-scripts/cypress/cy-form

- **mysolution.myform** → `mysolution.myform.spec.cy.js`
- **mysolution.other**  → `mysolution.other.spec.cy.ts`

Run one with testForm / showAndTest, or all with runAllFormTests.
```

### 3.5 Form-name vs base-name: what to pass to the runner
`discoverAllTestForms()` returns the spec file base name, which includes the solution prefix
(`<solution>.<form>`), because `getSpecFilePath`/`firstExisting` write specs as
`<solution>.<form>.spec.cy.*`. `testForm`/`runFormCypressTests` take a **form name** and
resolve the spec via `specGenerator.specExists(formName, solutionName)` /
`findExistingSpecFile(formName, solutionName)`, which also try the `<solution>.<form>` prefix.
During implementation, confirm against a real `cy-form` listing (the reporter's
`test_webcomponents2` / `servoy_test` setup, or any solution with generated form specs)
whether the correct argument to `runFormCypressTests` is the raw entry (`<solution>.<form>`)
or the entry with the solution prefix stripped to the bare form name. Pick whichever makes
`findExistingSpecFile(...)` resolve the same file `discoverAllTestForms()` enumerated, so
`runAllFormTests` runs exactly the specs `listFormTests` lists (no "spec not found" and no
accidental regeneration). This resolution choice is the one real correctness subtlety — nail
it down with a live listing before finalising, and factor the base-name→form-name logic into a
small helper so it is unit-testable.

**Resolution (confirmed against a live listing):** the raw entry is passed unchanged.
`findExistingSpecFile("<solution>.<form>", solution)` falls back to the exact base name and
resolves the file `discoverAllTestForms()` enumerated, so `runAllFormTests` runs exactly the
specs `listFormTests` lists. `toFormName(String)` is a package-visible static that returns its
argument, kept as a documented, unit-tested seam for this mapping.

### 3.6 `listE2ETests` cross-pointer (description only)
Leave `listE2ETests` behaviour unchanged (still scans only `cypress/e2e`). Append one sentence
to its `@Tool` description pointing to `listFormTests` for the `cy-form` single-form tests, so
the two tools' descriptions make the folder split explicit and the skill side can route
correctly. No logic change.

## 4. Implementation plan

1. **`bundles/com.servoy.eclipse.developer.mcp/.../servers/ServoyTestingServer.java`**
   - Add import `com.servoy.eclipse.cypress.services.CypressTestDiscoveryService`.
   - Add field `private final CypressTestDiscoveryService testDiscovery = new CypressTestDiscoveryService();`.
   - Add the `listFormTests` `@Tool` (no params): call `discoverAllTestForms()`, build the
     markdown listing with per-form spec file name (3.2 / 3.4), empty → "no form tests"
     message naming `cypress/cy-form/` (3.2). Description per 3.2.
   - Add the `runAllFormTests` `@Tool` (no params, headless): loop discovered forms through
     `specRunner.runFormCypressTests(form, true)`, aggregate, `writeToConsole(...)`, empty →
     same "no form tests" message (3.3). Description carries the real-data-change and
     NG-client warnings (3.3).
   - Add a small package-visible/`static` helper that maps a discovered entry
     (`<solution>.<form>` base name) to (a) the argument for `runFormCypressTests` and (b) the
     display spec file name, resolving the correct behaviour per 3.5 (so it is unit-testable).
   - Append the `listFormTests` cross-pointer sentence to the `listE2ETests` `@Tool`
     description (3.6).
   - Follow the existing try/catch + `ServoyLog.logError` + `"Error: " + e.getMessage()`
     convention in every new tool.

2. **`tests/com.servoy.eclipse.developer.mcp.tests/src/test/java/.../plainjunit/servers/ServoyTestingServerTest.java`**
   - Add tool-presence assertions: `hasToolNamed("listFormTests")` and
     `hasToolNamed("runAllFormTests")` (mirrors the existing `testForm`/`showAndTest` tests).
   - Add unit tests for the base-name→form-name/spec-file helper (3.5) over sample entries.
   - If feasible with the `getServoyModel()` seam + a `TempDir` `cy-form` folder, add a unit
     test that `listFormTests` returns the empty "no form tests" message naming the folder when
     the folder is absent/empty, and lists entries (with spec file names) when present. The
     folder is resolved from `ResourcesPlugin` inside `CypressTestDiscoveryService`, so if it
     cannot be mocked at the boundary without a workbench, cover the formatting/helper logic
     as pure units and leave the real discovery path to the integration canary (step 3).

3. **Integration canary (only if an existing integration test naturally covers it)** — one
   thin `integrationpde` happy-path (a solution with a generated `cy-form` spec) asserting
   `listFormTests` enumerates it and names the spec file. Do not add an integration test that
   re-asserts Cypress/runner behaviour; `runAllFormTests` actually running specs needs the NG
   client and is a manual/skilled-run concern, not a CI unit. Keep the branch/format/guard
   coverage in the plain-JUnit test (step 2).

4. **No MANIFEST / build.properties / pom change.** The `com.servoy.eclipse.cypress` bundle is
   already a `Require-Bundle`/import of `com.servoy.eclipse.developer.mcp` (its
   `FormSpecGenerator`/`FormSpecRunner` are already imported), and `CypressTestDiscoveryService`
   lives in the same package. Confirm with `eclipse-ide_getCompilationErrors()` after editing.

5. **Post-edit checklist (AGENTS.md):** run `eclipse-ide_getCompilationErrors()` to zero,
   apply safe quick-fixes, run the plain-JUnit `ServoyTestingServerTest` via
   `eclipse-ide_runJUnitTests` (project `com.servoy.eclipse.developer.mcp.tests`, class
   `...plainjunit.servers.ServoyTestingServerTest`).

### Implementation status
Implemented in `ServoyTestingServer.java` (two tools, `noFormTestsMessage`,
`resolveActiveSolutionName`, `toFormName`, `resolveSpecFileName`, `listE2ETests` description
pointer, NG-client guard). 25 plain-JUnit tests were added to `ServoyTestingServerTest` (see
section 8). No `integrationpde` canary was added: the real discovery runs through
`ResourcesPlugin` and is covered by the manual scenarios in section 8.2 instead.

## 5. Acceptance criteria
- [x] With specs present in `cypress/cy-form/`, `listFormTests` returns **every form that has
      one, and nothing else**, each with its spec file name.
- [x] With **no `cy-form` folder or no specs**, `listFormTests` returns a clear "no form tests"
      message that **names the `cy-form` folder**.
- [x] `listE2ETests` still lists **only** `cypress/e2e`; its only description change is a
      pointer to `listFormTests`.
- [x] An agent given "run all form tests" runs all of them via `runAllFormTests` with **no
      manual list of form names** (it enumerates via `discoverAllTestForms()`).
- [x] `runAllFormTests`'s `@Tool` description warns that **handlers execute for real and can
      change data** and that the **NG client must be running**.
- [x] Both new tool descriptions state they read `cypress/cy-form/`; the folder split between
      form tests (`cy-form`) and E2E tests (`e2e`) is explicit in both tools' descriptions.
- [x] No new MANIFEST dependency; the bundle compiles with zero errors
      (`eclipse-ide_getCompilationErrors()`).
- [x] `ServoyTestingServerTest` passes, including new tool-presence and helper assertions.

## 6. Out of scope
- Changing how form tests are generated (SVY-21517).
- Making `listE2ETests` also list form tests — the two kinds stay separate (only a description
  cross-pointer is added).
- The skill4servoy / servoy-orchestrator routing change (E2E-Tester chain, `#run-test` route
  pointing at `listFormTests` for form tests and `listE2ETests` for E2E) lives in a different
  repo. It was planned as the follow-up **SVY-21665**, but manual testing showed the tools are
  not used without it, so a first version was committed in skill4servoy under this case key
  (`880fc99`, `7c205f2`). See section 8.3.
- Making the generated form tests complete (the E2E-Tester only scaffolds them): see 8.3.
- Any new target-platform / dependency change (none is needed).

## 7. Open questions
| Question | Owner | Status |
|----------|-------|--------|
| Does `discoverAllTestForms()` return `<solution>.<form>` base names such that `runFormCypressTests` must receive the raw entry, or the bare form name? | Coding agent | resolved: the raw entry is passed unchanged (3.5) |
| Should `runAllFormTests` surface a short "NG client not running" hint up front before looping? | Code review | resolved: yes, one up-front check (3.3 step 3) |
| Should `listFormTests` / `runAllFormTests` only cover the active solution? Today they cover every spec in `cy-form/`, whatever solution it belongs to (8.3, item 4). | Product owner | open |
| Should the 5 minute MCP timeout be raised, or `runAllFormTests` made asynchronous? (8.3, item 3) | Product owner | open |

## 8. Verification

### 8.1 Automated
- `ServoyTestingServerTest`: 223 tests pass in the class, 25 of them added for this case: tool
  presence, `@Tool` description obligations, `toFormName`, `listFormTests` formatting and
  fallbacks (empty folder, unresolved folder, no active project, no solution, model throws, spec
  lookup throws), `runAllFormTests` guards and aggregation (empty, NG client not ready, all forms
  run headless, a failing form does not stop the rest, single-form label, top-level exception).
- A coverage run reports every line and branch of the new code covered. Not covered by unit
  tests, by design: the real `discoverAllTestForms()` (reads the workspace), the real spec lookup
  and Cypress itself. Those are covered by the manual scenarios below.
- Both bundles compile with zero errors in the Eclipse incremental build. No Tycho build was run
  locally; CI runs it.

### 8.2 Manual scenarios
Run in a Servoy Developer lts_2026 workspace with the tools called directly over the
`servoy-test` MCP endpoint and through the Servoy AI chat (Orchestrator, then E2E-Tester).
Fixtures: 7 specs in `cy-form/` (5 `btn_fixture`, 2 `testscenario1`) and 33 files in `e2e/`.

| # | Scenario | Result |
|---|----------|--------|
| A1 | "List all form tests" (7 specs) | PASS. Tool returns 7 specs with file names. Through the agent it passed 2 of 4 runs before the `run-test` route existed (the Orchestrator listed `e2e/*.cy.ts` itself and called them form tests) and 3 of 3 after. |
| A2 | Exactly one spec | PASS. `**Form Tests** (1 file)`, singular. |
| A3 / B4 | Empty `cy-form/`, "run all form tests" | PASS. Message names the folder, nothing runs, no NG-client check. Agent: failed once when the Orchestrator's brief still named the 7 earlier tests (the tester fell back to `testForm`, `testE2E` and `npx`, and regenerated 5 specs); passed in a fresh session (one `runAllFormTests` call, reports and stops). |
| A4 | "Form tests vs E2E tests, folders and tools" | PARTIAL. Folders and the "check both lists" rule were right; the answer did not always name both listing tools. |
| B1 | NG client not running | Not run live: with only Developer open there is no separate client, and the check only needs an active project and the web server port. Covered by the unit test `ngClientNotRunningShortCircuits`. |
| B2 | Run all, all pass | PASS. 7 forms, 13 tests, one `runAllFormTests` call, spec hashes unchanged. |
| B3 | One failing spec | PASS. A temporary failing spec (removed afterwards) was reported in its own section and the other 7 still ran. |
| B5 | "Could running all form tests change data?" | PASS. The agent relayed the real-handler warning and ran nothing. |
| C1 | "List the E2E tests" | PASS. 33 files from `e2e/` only, no `cy-form` spec mixed in. |
| C2 | "Run all form tests" for the solution | Covered by B2. |
| D1 | "Run the form test for btn_fixture.narrow_form" | PASS. `testForm`, 1 test passing, spec not regenerated. |
| D2 | "Show it in a browser and run its test" | PASS. `showAndTest`; the Cypress runner window opened (confirmed by screenshot). |
| E | Active solution `test_webcomponents2` has no form tests: "create form tests" | Routed to the E2E-Tester, which wrote 6 specs and 6 setup stubs with `generateFormSpec`. All run: 16 passing, 29 pending, 0 failing. They are thin scaffolds (see 8.3, item 6). |

### 8.3 Findings
1. **Routing was needed (fixed).** Without a route for "list / run the form tests", the
   Orchestrator answered from its own file search. A `run-test` row in `Orchestrator.md` and
   rules in the E2E-Tester notes fixed it. Committed in skill4servoy as `880fc99` and `7c205f2`.
2. **A brief that names tests overrides an empty result (open).** See A3/B4. Proposed fix: say in
   the E2E-Tester notes that the tool result wins over the brief, and have the Orchestrator send
   the intent only, never a pre-built list of forms.
3. **MCP timeout (open).** The `servoy-test` timeout is 300000 ms. Running 13 forms takes longer.
   The run still finishes inside Developer and writes the full report to the Cypress console, but
   the agent's call times out and it falls back to `npx cypress run`.
4. **Discovery covers every solution (open).** Both tools read the whole `cy-form/` folder, so
   with `test_webcomponents2` active they still list and run the `btn_fixture` and `testscenario1`
   specs. This matches `discoverAllTestForms()` and the IDE action.
5. **What `checkNGClientStatus` checks.** It reports "running" whenever a solution is active and
   the web server has a port; it does not look for a browser client. The "NG client not running"
   message is returned when there is no active project.
6. **Generated form tests are scaffolds (out of scope).** In scenario E, 29 of 45 tests are empty
   `it.skip` placeholders and the E2E-Tester did not complete them. It also did not see the six
   hand-written tests in the `servoy_test` repo, because the tools read the Developer workspace
   folder (`<workspace>/jenkins-custom/...`), not `servoy_test/jenkins-custom/...`.
7. **`screenshotForm` rejects fixture forms.** `btn_fixture.narrow_form` is reported as not
   existing in the solution although its test passes. Unrelated tool, not changed.
8. **Skills reach the IDE only through the skills zip.** `~/.servoy/opencode` is re-extracted on
   restart, so skill4servoy changes persist there only once the zip is rebuilt from skill4servoy.
