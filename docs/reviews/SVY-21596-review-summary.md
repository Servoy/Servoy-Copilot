# Review Summary — SVY-21596: MCP createForm events= neither wires the form handler nor creates the method

**Risk of the original change (`de674ac`): ELEVATED, bug confirmed by execution** — `createForm` wires events (and saves) onto an already-existing form, because the "already exists" result string is never checked before `applyEventsToForm` runs (`ServoyDevServer.java:1670-1683`). Sent back to the author ("Code review problem").

**Status after the fix (`5decd453`, cPecican, 2026-11-24): resolved (Fixed). Duplicate-name fix confirmed by a live run; persistence of events in steps 3, 4 and 6 is still unverified.** See "Fix verification" below.

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

## Fix verification (`5decd453`)

Checked by reading the diff and comparing the skeletons with `@templateparam` / `@templatetype` in core `Form.java` on `lts_2026`, then partly executed in Servoy Developer (forms `zz_fix_a` to `_g`). The issue was resolved before the live run reported back.

**Live re-run result: partial.** The run happened in the solution `closePopup`, not `btn_fixture`, because the intended solution was not active. The duplicate-name fix is confirmed; three steps are inconclusive.

| Step | Result | Evidence |
|---|---|---|
| 1. create with `onShow:handlerA` | Pass | `onShow` bound to `handlerA`, declared `function handlerA(firstShow, event)` |
| 2. duplicate name, no `columns` | **Pass** | "already exists in the active solution. Events not applied: the form already exists and was left unchanged." `onShow` still `handlerA`, no `handlerB` |
| 3. duplicate name with `columns` | Duplicate half passes, first create inconclusive | The duplicate returned the same "Events not applied" text. The first call said "Wired events: onShow -> handlerA", but `zz_fix_b.frm` had no `onShowMethodID` and no `.js` existed |
| 4. `onShow:ok,onHide:bad name` | Report format passes, persistence not confirmed | Result lists `onShow -> ok` as wired and `onHide:bad name` as skipped. On disk: no `onShowMethodID`, no `zz_fix_c.js` |
| 5. `onShow:f(){};x` | Pass | Skipped as not a valid JavaScript identifier, nothing written |
| 6. six skeletons | Not verified | All six reported as wired, but no `.js` existed, so signatures and `@return` tags could not be read |
| 7. no events / `events=""` | Pass | Plain "created successfully" message |

**Open question: persistence.** In steps 3, 4 and 6 the tool reported events as wired that were not on disk at read-back; only step 1, run alone, persisted its event. The tester ran the other six forms in one parallel batch and suspects a race (read-back before the save landed, or concurrent edits of the shared editing solution). That is a guess. Nothing here implicates `5decd453`, but nothing rules it out either. Re-run steps 3, 4 and 6 one call at a time, in `btn_fixture`, and read each form back only after its create returns. If the gap reproduces sequentially, it is a new defect (the result claims "Wired events" before the save is visible) and needs its own case.

| Finding | Result of the code read |
|---|---|
| Duplicate name re-wires the existing form | Fixed. `ServoyDevServer.java:1681` tests `createResult.startsWith("Form '<name>' already exists")`, which matches both "already exists" returns in the service (`:91`, `:321`), so both the `columns` and non-`columns` paths skip wiring and the result says "Events not applied". |
| Partial failure leaves an unwired form and an `Error:` | Fixed. `applyEventsToForm` catches `RepositoryException` per event (`NamevalidationException` extends it), reports "Failed to wire events", and saves the events that did wire. |
| Method name not validated | Fixed. `isValidMethodName` allows only `[A-Za-z_$][A-Za-z0-9_$]*`; other names are reported as skipped. Core's validator still rejects keywords, which now ends as a per-event failure. |
| Skeletons unconfirmed | Match core. `onShow(firstShow, event)`, `onBeforeRecordSelection(oldSelection, newSelection, event)` with Boolean return, `onRecordEditStart` / `onElementFocusGained` / `onElementFocusLost` `(event)` with Boolean return. `(event)` is correct for `onRecordSelection`, `onResize`, `onLoad`, `onUnload`, `onHide`. |

Remaining, none blocking:
- The duplicate guard depends on the exact wording of the "already exists" string; a reworded message would silently bring the bug back. A returned status would be sturdier.
- The existing-form check only looks at the active solution, so a same-named form that lives in a module is still not caught (as before this commit).
- The identifier regex is ASCII-only, which errs on the safe side.
- No test seen for the "Failed to wire events" path, and no plain-JUnit test for the pure `isValidMethodName`.

To close the gap: re-run steps 3, 4 and 6 sequentially in `btn_fixture` (see "Open question: persistence" above) and record the results here.

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

Test leftovers: forms `zz_svy21596_a` to `_d` from the first run (the run reports never named the solution, so they are assumed to be in `btn_fixture`), and `zz_fix_a` to `_g` in `closePopup`, need manual deletion.
