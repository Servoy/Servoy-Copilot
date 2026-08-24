# Triage Report — SVY-21195

**Verdict:** PROCEED

## Reported problem

The `screenshotForm` MCP tool returns an empty white screenshot instead of an error message
when a form contains invalid property values (e.g. `%%poison%%` used as a boolean value in
a `.frm` file). The AI agent then has no signal that the form is broken. Reading an image
costs significantly more tokens than receiving a plain text error, so the tool should detect
the invalid state and return an error string instead of taking the screenshot.

## Root-cause assessment

The root cause was correct: invalid JSON property values in a `.frm` file cause the Servoy
runtime to silently swallow the error and render a blank/broken form. No textual error was
returned to the MCP caller.

An initial implementation was shipped (Diana Bunaciu, 2026-07-03 — commit history around
`SVY-21296`). It added two pre-flight gates in `FormPreviewService.java`:

1. **`checkFormMarkers()`** — queries Eclipse `IMarker.PROBLEM` markers on the form's
   associated files and blocks preview if any `SEVERITY_ERROR` marker is found.
   Hard-coded files checked: `forms/<name>.frm`, `forms/<name>/` (folder), `forms/<name>.js`.
2. **`validateFormProperties()`** — iterates `WebComponent`s and `LayoutContainer`s via the
   `FlattenedSolution`, compares persisted JSON values against the Sablo spec, and reports
   type mismatches (e.g. String where Boolean expected).

Both gates return early with an error string if issues are detected, preventing the
screenshot from being taken. A `RuntimeErrorCapture` log4j2 appender was also introduced to
capture server-side ERROR logs during the actual render and append them as warnings.

The implementation landed and the ticket was closed. It was then **reopened on 2026-08-17**
with a code review comment from Andrei Costescu identifying four architectural problems with
the current implementation.

## Ticket premise check

The ticket's original goal (return an error instead of a blank screenshot) is sound and
correct. However the implementation overshot in two directions and contains one correctness
bug:

**Issue 1 — Overly broad marker gate (`checkFormMarkers`)**
Blocking on *any* `IMarker.SEVERITY_ERROR` is too aggressive. Many Eclipse error markers
(e.g. JS type warnings promoted to errors, unresolved dataproviders) do not affect visual
rendering. The gate should only fire for markers that structurally prevent the form from
rendering — not as a blanket "any error → block" rule.

**Issue 2 — `showFormInBrowser` should not be gated the same way as `screenshotForm`**
The original ticket was specifically about `screenshotForm` (MCP, token cost). Applying the
same hard blocking gate to `showFormInBrowser` (which can also be called from the UI) means
the user cannot open a form with any error markers at all. Andrei's preferred model: attempt
to open/render the form; only surface an error *inside* the form editor (inline error div)
if the form genuinely failed to render — not as an external blocking response.

**Issue 3 — Hard-coded form file paths are incorrect (correctness bug)**
`checkFormMarkers()` checks:
- `forms/<name>.frm` ✓
- `forms/<name>/` (folder) — **Andrei says this concept does not exist** in Servoy's project
  layout. This check may match nothing or match unrelated resources.
- `forms/<name>.js` ✓
- `forms/<name>.sec` — **missing**; this file holds form security settings and can carry
  markers.

Servoy's `SolutionDeserializer` and related serializers already know how to enumerate all
resources belonging to a form. Hard-coding file paths here duplicates that knowledge and
gets it wrong. A utility method should derive the file list from the same source the rest
of the IDE uses.

**Issue 4 — Browser console errors not captured (enhancement)**
`RuntimeErrorCapture` captures server-side log4j ERROR logs, but browser-side console
errors (component failures, JS exceptions in the browser) are not captured. Andrei suggests
including those alongside the screenshot. This is an enhancement, not a blocking defect.

## Approaches considered

### 1. Recommended — Differentiate the two tools; fix marker gate scope and file enumeration

- **`screenshotForm`**: Keep the gate concept but narrow it. Only block on markers whose
  message/source indicates a structural problem (parse error, unknown component type,
  missing spec) — not all ERROR markers. `validateFormProperties()` already does this
  precisely for type mismatches and should remain. Broaden `checkFormMarkers()` to use
  a Servoy utility to enumerate form files (removing the wrong folder path, adding `.sec`),
  and filter to only render-breaking marker types.
- **`showFormInBrowser`**: Remove the hard gate. Open the form regardless. Append any
  collected marker errors or type-mismatch warnings to the success message as informational
  text. If the form fails to load at the browser level, `RuntimeErrorCapture` will already
  surface that.
- **Browser console errors**: Add a `cy.on('window:console', ...)` stub in the Cypress spec
  to collect `console.error` calls and append them to the screenshot result.

Pros: Directly addresses all four review points. Preserves the token-saving intent for
`screenshotForm`. Does not regress `showFormInBrowser` usability.
Cons: Requires determining which marker types are render-blocking — needs input from the
Servoy team on that list, or a conservative heuristic.

### 2. Remove `checkFormMarkers()` entirely; rely only on `validateFormProperties()`

Drop the Eclipse marker check entirely. Keep only the `validateFormProperties()` type-mismatch
check as the gate for `screenshotForm`. This is already the most targeted check for the
original reported scenario (`%%poison%%` as a boolean). For `showFormInBrowser`, remove
both gates and rely on `RuntimeErrorCapture` to surface runtime failures.

Pros: Simpler. Avoids the "which markers are render-blocking?" question. Directly addresses
the original ticket's scenario.
Cons: Misses structural errors that Eclipse markers would catch but `validateFormProperties()`
wouldn't (e.g. a completely malformed `.frm` JSON that fails to parse). The reviewer's point
about the folder path and missing `.sec` becomes moot, but his concern about broad marker
blocking is resolved.

### 3. No code change

Leave the current implementation in place.

Cons: Violates the reviewer's explicit direction. `checkFormMarkers()` still uses the
incorrect `forms/<name>/` folder path, may check a path that doesn't exist, and misses
`.sec`. `showFormInBrowser` is still hard-blocked by any error marker. This is not
acceptable given the open review.

### 4. Full rewrite of gate logic with SolutionDeserializer integration

Use `SolutionDeserializer` to enumerate all IResources for a form, then filter markers to
only render-critical ones. Add browser console capture. Full alignment with Andrei's review.

Pros: Architecturally cleanest.
Cons: Higher scope than needed; the `validateFormProperties()` approach (Approach 2) already
handles the core use case with less risk. `SolutionDeserializer` integration adds complexity
and a new coupling point.

## Recommendation

**PROCEED with Approach 1 (differentiate the two tools)**, or Approach 2 if the
"render-blocking marker" classification is too difficult to define cleanly.

The minimum required changes are:

1. **`showFormInBrowser`**: Remove both pre-flight gates. Proceed to open the form. Collect
   any `checkFormMarkers()` / `validateFormProperties()` findings and append them as a
   warning/info note to the success message — do not block.

2. **`screenshotForm`**: Keep `validateFormProperties()` as a hard gate (it is targeted and
   directly addresses the original bug). For `checkFormMarkers()`, either:
   - Narrow to render-critical marker types only (e.g., filter by marker source or message
     pattern), **or**
   - Remove `checkFormMarkers()` from `screenshotForm` entirely and rely on
     `validateFormProperties()` alone (Approach 2).

3. **Fix `checkFormMarkers()` file enumeration** regardless of whether it stays as a gate:
   - Remove `forms/<name>/` folder — this path does not correspond to a real Servoy
     project structure.
   - Add `forms/<name>.sec` to the checked resources.
   - Investigate whether `SolutionDeserializer` exposes a utility to enumerate form files;
     if so, use it.

4. **Browser console errors** (optional, lower priority): Add `cy.on('window:console', ...)`
   in the Cypress screenshot spec to collect and report `console.error` calls.

---

## Outcome (added 2026-08-24)

> This section records where the work actually landed. Everything above is the
> triage as written before implementation and is left unedited.

**Approach 1 was approved, then abandoned mid-implementation in favour of
Approach 2.** The `.frm`-only marker gate that Approach 1 proposed was built
first, and manual testing showed the premise was wrong: an `onActionMethodID`
marker sits on the `.frm` file, is `SEVERITY_ERROR`, and the form renders
perfectly. File location is not a proxy for render-impact, so the narrowed gate
was no more defensible than the broad one — it just failed differently.

Final state:

- **`validateFormProperties()` is the only hard gate**, on `screenshotForm` only.
  It compares persisted values against the Sablo spec, so it catches values the
  client genuinely cannot render. Verified: a `"%%poison%%"` string on the boolean
  `enabled` property blocks the screenshot and names the component.
- **`checkFormMarkers()` gates nothing.** It became an informational collector
  for both tools — findings are appended as a `Warning:`, never used to block.
  This closes review issue 1 by removing the question rather than answering it.
- **File enumeration fixed** via a shared `getFormFiles()` helper using
  `SolutionSerializer.getFilePath()`. The `forms/<name>/` folder check is gone,
  `.sec` is included, `.less` excluded (no builder produces markers on it).
- **`EditorServiceHandler`** (in `servoy-eclipse`) turned out to be the source of
  the dialog the reporter saw — it held its own duplicate copy of both checks and
  called `MessageDialog.openError` directly. Its gates and five private helpers
  were deleted. Not mentioned anywhere in the triage above; found only when the
  reporter re-tested and the dialog persisted despite the `FormPreviewService`
  change.
- **Browser console capture** was implemented rather than deferred.

**What the triage missed.** Issue 3 asked whether `SolutionDeserializer` exposes
a form-file utility; no such utility was found, so `getFormFiles()` was written
using `SolutionSerializer.getFilePath()` for the base path. And the triage
searched only `FormPreviewService` — the duplicated gate logic in
`com.servoy.eclipse.designer` was never looked for, which is why the reporter's
original symptom (the dialog) was not traced to its actual source until testing.

Spec: `docs/SVY-21195-screenshot-form-gate-fix.spec.md`

## Git history findings

The SVY-21195 implementation was introduced across multiple commits under `SVY-21296` (the
related showFormInBrowser task) and the original SVY-21195 work:

- `88e7373` — null guard in `FormPreviewService.showFormInBrowser`
- `290373c` — restored Log4j2 `RuntimeErrorCapture` (reverted a JUL attempt)
- `5bbf806` — rewrote `RuntimeErrorCapture` to use JUL (later reverted)
- `37a932a` — extracted Cypress testing into standalone plugin

The `checkFormMarkers()` and `validateFormProperties()` methods were present in the first
implementation (Diana's July comment) and have not been materially changed since. No prior
commit introduced an explicit "render-blocking marker" classification — the broad
`SEVERITY_ERROR` filter was the original design choice.

The `forms/<name>/` folder path in `checkFormMarkers()` has no corresponding entry in any
commit touching `SolutionDeserializer` or form file layout — it appears to have been
invented during implementation rather than derived from Servoy's actual file structure.
