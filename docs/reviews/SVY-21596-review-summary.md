# Review Summary — SVY-21596: MCP createForm events= neither wires the form handler nor creates the method

**Risk: ELEVATED, bug confirmed by execution** — `createForm` wires events (and saves) onto an already-existing form, because the "already exists" result string is never checked before `applyEventsToForm` runs (`ServoyDevServer.java:1670-1683`). Sent back to the author ("Code review problem").

Scope reviewed: Servoy-Copilot `master`, commits `de674ac` and `319690d`.

Fix Version `2026.12` — Release-target check: **INDETERMINATE**, then confirmed by the reviewer. Servoy-Copilot has no `ClientVersion`, and the Jira issue has no reporter comment naming an extension-package version. The reviewer confirmed that mainline `master` is the intended delivery vehicle for 2026.12.

## Confirmed by execution

Run in Servoy Developer (lts_2026 workspace, solution `btn_fixture`) through the MCP tools. The orchestrator relayed the Developer's read-back; the four test forms exist in the solution tree.

| Step | Call | Result |
|---|---|---|
| 1 | `createForm(name=zz_svy21596_a, events=onShow:handlerA)` | `Form 'zz_svy21596_a' created successfully (css, 640x480)` / `Wired events: onShow -> handlerA`. Correct. |
| 2 | same name, `events=onShow:handlerB` | `Form 'zz_svy21596_a' already exists in the active solution.` / `Wired events: onShow -> handlerB`. **Existing form modified:** `onShow` re-pointed to `handlerB`, `handlerB` added, `handlerA` left unwired. |
| 3 | same two calls with `columns="*"`, datasource `db:/example_data/orders`, form `zz_svy21596_b` | Same behaviour: `already exists` followed by `Wired events: onShow -> handlerB`. **Existing form modified.** |
| 4 | `events=onShow:ok,onHide:bad name`, form `zz_svy21596_c` | `Error: there is a space in the name 'bad name' this is not allowed`. The form is left on disk with neither handler wired (`NamevalidationException` at `ScriptNameValidator.checkName:181`, via `resolveOrCreateMethod:556` / `applyEventsToForm:634`). Not atomic; a retry lands in step 2. |
| 5 | `events=onShow:f(){};x`, form `zz_svy21596_d` | `created successfully` / `Wired events: onShow -> f(){};x`. The form script contains `function f(){};x(event) {`, which is invalid JavaScript. |

An Eclipse error-log entry `RepositoryException: dataSource is required` for `zz_svy21596_b` came from a `columns="*"` call without a datasource (`createFormWithFields:316`). It is input validation before anything is created and is not part of the regression; it is only logged as a severity-4 error, which is noisy.

## Manual test plan

Steps 2, 4 and 5 of the table above are the regression repro. Still to run after the author's fix:

**Verifying the fix**
1. `createForm(name="f1", events="onShow:refresh,onHide")` (no `columns`): `onShow` bound to a new `refresh(event)`, `onHide` bound to a new `onHide(event)`; result ends with `Wired events: ...`.
2. Same with `columns="*"` and a `db:/...` datasource, for `style=responsive` and `style=css`.
3. `onRecordEditStop`, `onElementDataChange`, `onSort`, `onBeforeHide`: open the `.js` and compare parameter lists with the Servoy form-event documentation.
4. Wire `onBeforeRecordSelection`, `onElementFocusGained`, `onElementFocusLost`.
5. `events="onShow,bogus,onLoad:a:b"`: valid one wired, `Skipped unrecognised events: bogus, onLoad:a:b`.

**Regression checks**
1. Duplicate name, with and without `columns`: expect the `already exists` error and no change to the existing form.
2. `events="onShow:ok,onHide:bad name"`: expect either no form left behind or a clear per-event report; `onShow:ok` should not be silently lost.
3. `events="onShow:f(){};x"`: expect rejection, not a broken script.
4. `createForm` without `events` and with `events=""` behaves as before.
5. A name that matches a form living in a module: check which path rejects it.

Automated: plain JUnit `com.servoy.eclipse.developer.mcp.plainjunit` (incl. `ServoyDevServerArtifactBranchesTest`); PDE `CreateFormEventsWiringIntegrationTest` with launcher `PDEIntegrationTestsBaseCopy-lts_2026` or `-release`; Spotbugs via the normal Tycho build (not run in this review).

## Possible improvements / follow-ups
- **High, confirmed:** wire events only when the create step actually succeeded, and keep the `already exists` error; otherwise change the tool description and result text.
- **Medium, confirmed:** on partial failure, keep the form and report per event, or roll back. Today the form is saved, the tool returns `Error:`, nothing is wired, and a retry lands in the duplicate case.
- **Medium (raised from Low after the run):** restrict method names from `event:method` to JS identifiers. Core's `ScriptNameValidator` only rejects empty, keyword, OS-reserved, space and duplicate names, so `(`, `)`, `;`, `{` pass, and the tool reports success while writing a broken script. The string concatenation pre-dates this change and the content stays inside the user's own solution.
- Confirm the per-event parameter lists in `skeletonForEvent` against the real Servoy event signatures (`onRecordSelection`, `onBeforeRecordSelection`, `onRecordEditStart`, `onElementFocus*`, `onResize` use `(event)`). The spec marks this open.
- Check whether any skill or prompt pack passes `events=` (bare names used to be no-ops and now create real methods).
- `SUPPORTED_EVENTS` is a hand-kept copy of the `AiBridge` switch (identical 14 events on master, release and lts_2026 today).
- Documentation drift: the service Javadoc says "No AiBridge dependency" but the class now uses it; the tool description still promises an error for an existing form; the `skeletonForEvent` Javadoc/spec differ from the code on unrecognised events.
- No plain-JUnit tests for the parser or skeletons, although AGENTS.md prefers them; none of the integration tests covers duplicate-name plus `events=`.

Verified: `AiBridge.applyEventMethod` and the exported `com.servoy.eclipse.core.ai` package exist on `release` and `lts_2026`.

Test leftovers: forms `zz_svy21596_a` to `_d` remain in `btn_fixture` and need manual deletion.
