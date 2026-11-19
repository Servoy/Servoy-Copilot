# Triage Report — SVY-21663

**Verdict:** PROCEED

## Reported problem
In a solution that has passing Cypress **form tests** (`<solution>.<form>.spec.cy.js`
files under `jenkins-custom/e2e-test-scripts/cypress/cy-form/`), an agent asked to
"run all form tests" gets "none found". The reporter hit this after importing the
`servoy_test` repo into a workspace: Servoy AI could not find the `cy-form` tests that
lived in the `jenkins-custom` directory in the workspace.

The symptom is **discovery**, not execution: the single-form runners (`testForm`,
`showAndTest`) work when given a form name, but nothing lets the agent find which forms
have a test, and the only listing tool points at the wrong folder.

## Root-cause assessment
There is no MCP tool that lists form tests, and the one listing tool that exists reads a
different directory:

- **`listE2ETests` reads only `cypress/e2e/`.** Confirmed in
  `com.servoy.eclipse.developer.mcp/src/.../servers/ServoyTestingServer.java`
  (`listE2ETests`, lines 326–379): it walks
  `jenkins-custom/e2e-test-scripts/cypress/e2e` and filters `*.cy.js`/`*.cy.ts`. Its own
  `@Tool` description even says form tests live in `cypress/cy-form/` — so the folder the
  reporter's tests are in is never scanned by any tool.
- **Form tests live in `cy-form/`, discovered by code that no tool exposes.**
  `com.servoy.eclipse.cypress/src/.../services/CypressTestDiscoveryService.discoverAllTestForms()`
  (lines 70–81) lists form names from the `cy-form` directory. `FormSpecGenerator.resolveFormSpecDir()`
  (lines 140–146) resolves that directory to
  `jenkins-custom/e2e-test-scripts/cypress/cy-form`. `discoverAllTestForms()` filters with
  `FormSpecGenerator.isFormSpecFileName(...)` and maps each file through
  `stripFormSpecExtension(...)`. This method backs the IDE's "Run all form tests" action
  but is **not** reachable over MCP.
- **The single-form tools cannot stand in.** `testForm` and `showAndTest` (lines 272–324)
  each take one `formName` and call `specRunner.runFormCypressTests(formName, headless)`.
  There is no "list" and no "run all" form-test tool.

So the gap is exactly as the ticket states: the capability to enumerate form tests already
exists in the product (`discoverAllTestForms()`), it is simply not surfaced as an MCP tool,
and the existing `listE2ETests` tool deliberately scans a different folder.

**Reachability is not a blocker.** `ServoyTestingServer` already imports
`com.servoy.eclipse.cypress.services.FormSpecGenerator` and `FormSpecRunner`, so the
`com.servoy.eclipse.cypress` bundle is already on this bundle's MANIFEST classpath.
`CypressTestDiscoveryService` sits in the same package/bundle and needs no new dependency.

## Ticket premise check
The ticket's proposed approach holds up and is well-aligned with the existing code:

1. **`listFormTests` tool** on `ServoyTestingServer`, returning form names from
   `discoverAllTestForms()` plus the spec file name per form, read-only — this reuses the
   exact method that already backs the IDE action. Sound; this is the core fix.
2. **Optional `runAllFormTests`** that loops the discovered forms through
   `FormSpecRunner.runFormCypressTests(...)` and aggregates, warning that handlers run for
   real. `runFormCypressTests(formName, headless)` already exists (lines 341–351), so the
   loop is a thin wrapper. Reasonable; honor the ticket's requirement that it warn about
   real data changes and (like `testForm`) depend on the NG client running.
3. **skill4servoy chain / `#run-test` route** pointed at `listFormTests` for form tests and
   `listE2ETests` for E2E — this lives in the **skill4servoy / servoy-orchestrator repos**,
   not in Servoy-Copilot. It is out of scope for the Java change here and is tracked by the
   linked follow-up **SVY-21665** ("skill4servoy: fix form-test guidance…"). The Copilot
   change should keep the two tool descriptions clear about which folder each reads
   (`cy-form` vs `e2e`) so the skill side can route correctly.

The acceptance criteria map cleanly onto the existing discovery code: "every form that has
one, and nothing else" = `discoverAllTestForms()`; the empty-folder / no-folder case = the
`null`/`!isDirectory` guards in that method and `resolveFormSpecDir`, which the new tool
should translate into a clear "no form tests" message naming the `cy-form` folder;
`listE2ETests` stays scoped to `cypress/e2e`.

No prior spec for this key exists in `docs/`. The relevant code is recent
(SVY-21517 / SVY-21573 / SVY-21619 commits), so a fix here extends that work rather than
reverting an intentional decision.

## Approaches considered
1. **Add `listFormTests` (+ optional `runAllFormTests`) to `ServoyTestingServer`,
   delegating to `CypressTestDiscoveryService.discoverAllTestForms()` and
   `FormSpecRunner.runFormCypressTests(...)`** — the ticket's approach.
   - Pros: reuses the exact, already-tested discovery method that backs the IDE action;
     no new bundle dependency (cypress already imported); small, additive, read-only for
     the listing tool; directly satisfies every acceptance criterion; keeps form vs E2E
     separate as required.
   - Cons: adds one (or two) more MCP tools to an already large server; `runAllFormTests`
     must be honest about real handler execution and NG-client dependency.
2. **Broaden `listE2ETests` to also list `cy-form/`.**
   - Pros: no new tool.
   - Cons: explicitly **out of scope** per the ticket ("the two kinds stay separate"); would
     blur E2E vs form tests and muddy the skill-side routing the follow-up (SVY-21665)
     depends on. Rejected.
3. **Fix only on the skill4servoy side** (teach the agent to read `cy-form` via a generic
   file tool instead of adding an MCP tool).
   - Pros: no Copilot change.
   - Cons: duplicates product logic (spec-name parsing, folder resolution) in a skill;
     fragile to the naming/extension rules already centralized in `FormSpecGenerator`;
     doesn't give a run-all capability. The product already owns `discoverAllTestForms()` —
     the right place to expose it is an MCP tool. Rejected as the primary fix (the skill
     change is still needed, but as the separate SVY-21665).
4. **No code change** — tell users to list form names manually and call `testForm` per form.
   - Pros: zero work.
   - Cons: fails the reported scenario and all acceptance criteria; the whole point is that
     the agent cannot discover the forms. Rejected.

## Recommendation
**PROCEED with approach 1.** Add a read-only `listFormTests` tool to `ServoyTestingServer`
that delegates to `CypressTestDiscoveryService.discoverAllTestForms()`, returning each
form name and its spec file name, with a clear "no form tests" message (naming the
`cy-form` folder) when the folder is absent or empty. Add the optional `runAllFormTests`
that iterates the discovered forms through `FormSpecRunner.runFormCypressTests(...)` and
returns an aggregate, carrying the required warning that handlers execute for real and can
change data (and, like `testForm`, noting the NG client must be running). Make both tool
descriptions state which folder they read, and add a cross-pointer between `listE2ETests`
and `listFormTests`. No new MANIFEST dependency is required.

The skill4servoy / orchestrator routing change is **not** part of this fix — it is the
linked follow-up **SVY-21665** and lives in a different repo.

_Update after implementation: manual testing showed the tools are not used without a routing
change, so a first version of it was committed in skill4servoy under this case key. See
"Outcome" below._

Alternatives 2–4 are rejected for the reasons above (2 and 3-as-primary are out of scope;
4 does not solve the problem).

## Git history findings
- `CypressTestDiscoveryService` and `FormSpecGenerator`'s form-spec helpers are recent,
  introduced/extended by the SVY-21517 line of work (`5075092 SVY-21517 MCP: generateFormSpec
  should generate real behaviour tests…`) and refined by `378b46b SVY-21573` (keep `.ts`
  spec extensions on rename) and `2a7da35 SVY-21619` (viewport widths). This ticket is an
  explicitly-linked follow-up (relates to SVY-21517) that surfaces the already-written
  discovery over MCP — not a revert of any decision.
- No dependency/target-platform version change is involved; this is not a regression, it is
  a missing-tool gap. `git blame`/`git log` on the two source files show no commit that ever
  wired `discoverAllTestForms()` to an MCP tool, confirming it was never exposed.

## Questions for the reporter (NEEDS_INPUT only)
_N/A — verdict is PROCEED._

## Outcome (added after implementation)
Approach 1 was implemented as recommended. Differences and findings:

- **NG-client guard added.** `runAllFormTests` checks the runtime once before looping and
  returns a single "NG client not running" message, instead of one slow failure per form.
- **Routing was needed.** Agents did not call the new tools until the Orchestrator got a
  `run-test` route and the E2E-Tester notes said "run all form tests" is one `runAllFormTests`
  call whose result, including an empty one, is the answer. Committed in skill4servoy
  (`880fc99`, `7c205f2`).
- **Verified by 25 unit tests and the manual scenarios** in the spec (section 8). All pass except
  A4, which is partial, and B1, which was only unit-tested.
- **Still open:** a brief that names tests can make the tester ignore an empty result; the 5
  minute MCP timeout is shorter than a 13-form run; both tools cover every solution in
  `cy-form/`, not only the active one. Generated form tests are thin scaffolds, which is a
  separate case.
