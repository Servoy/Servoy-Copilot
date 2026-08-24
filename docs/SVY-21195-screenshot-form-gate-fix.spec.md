# Spec: SVY-21195 — Fix screenshotForm/showFormInBrowser gate logic

## 1. Goal

Revise the pre-flight validation in `FormPreviewService` so that `screenshotForm`
blocks only on property type mismatches — values the client genuinely cannot
render — while `showFormInBrowser` never blocks and surfaces findings as
warnings. Problem markers stop gating either tool and become informational,
because marker presence does not establish that a form cannot render. Fix the
incorrect hard-coded form-file list, remove the duplicated gates that raised a
dialog from the form editor, and capture browser-side console errors alongside
the screenshot.

## 2. Background

### 2.1 Original issue

`screenshotForm` was returning a blank white PNG instead of an error when a
`.frm` file contained an invalid property value (e.g. `%%poison%%` as a boolean).
The Servoy runtime silently swallowed the parse error and rendered a blank form.
Returning a blank image costs significantly more tokens than a plain error string,
so detecting the broken state before taking the screenshot was the right goal.

### 2.2 First implementation (Diana Bunaciu, 2026-07-03)

Two pre-flight gates were added to `FormPreviewService`:

- `checkFormMarkers()` — queries Eclipse `IMarker.PROBLEM` markers on the form's
  files and returns an error string if any `SEVERITY_ERROR` marker is found.
- `validateFormProperties()` — iterates `WebComponent`s and `LayoutContainer`s
  via `FlattenedSolution`, compares persisted JSON values against the Sablo spec,
  and reports type mismatches.

Both gates were applied identically to **both** `showFormInBrowser` and
`screenshotForm`, blocking either tool if any error was found.

### 2.2a Where a poisoned property has to sit to matter

A `.frm` component item has two levels, and only one of them is read:

```json
{
    "enabled":"wawawiwa",       ← item level: ignored by runtime AND by the check
    "json":{
        "enabled":"%%poison%%", ← inside json: read by runtime, caught by the check
        "text":"Label"
    },
    "name":"label_2",
    "typeName":"bootstrapcomponents-label"
}
```

`WebComponent.getFlattenedJson()` returns the `json` blob, and
`validateFormProperties()` tests `json.has(propName)` against it. A bad value at
item level is invisible to both the client and the check — so it neither breaks
rendering nor gets reported. See §6.

### 2.3 Code review issues (Andrei Costescu, 2026-08-17)

The reviewer identified four problems, which re-opened the ticket:

1. **Too-broad marker gate**: blocking on *any* `SEVERITY_ERROR` is excessive.
   JS type errors, unresolved dataproviders, and similar markers do not affect
   visual rendering.

2. **`showFormInBrowser` should not be blocked**: that tool is invoked from the UI
   as well as from MCP. Hard-blocking it means a developer cannot open any form
   that has error markers at all. The correct model: always open the form; show
   errors only if the form genuinely failed to render (inline in the form editor,
   not as an external blocking response).

3. **Incorrect file list in `checkFormMarkers()`**:
   - `forms/<name>/` (a folder) — this path does not correspond to any real
     Servoy project structure and should be removed.
   - `forms/<name>.sec` — missing; this file holds form security settings and
     can carry markers.
   - The code should derive the file list from `SolutionSerializer` rather than
     duplicating (and getting wrong) the knowledge of what files belong to a form.

4. **Browser console errors not captured** (enhancement): `RuntimeErrorCapture`
   catches server-side log4j ERROR logs but not browser-side `console.error`
   calls from component failures.

### 2.4 Current code locations

| Symbol | File | Lines |
|---|---|---|
| `showFormInBrowser(String, boolean)` | `FormPreviewService.java` | 64–133 |
| `screenshotForm(String, int)` | `FormPreviewService.java` | 135–273 |
| `checkFormMarkers()` | `FormPreviewService.java` | 300–333 |
| `collectErrorMarkers()` | `FormPreviewService.java` | 335–349 |
| `validateFormProperties()` | `FormPreviewService.java` | 351–387 |
| `SolutionSerializer.getFilePath()` | `SolutionSerializer.java` | 1464–1467 |
| `SolutionSerializer.FORM_LESS_FILE_EXTENSION` | `SolutionSerializer.java` | 132 |
| `DataModelManager.SECURITY_FILE_EXTENSION_WITH_DOT` | `DataModelManager.java` | 103 |

### 2.5 Servoy form file structure

From `SolutionSerializer.getRelativePath()` and `PersistDuplicateService.copyFormFiles()`,
the complete set of files that can belong to a form named `<name>` inside a
project is:

| File | Purpose |
|---|---|
| `<project>/forms/<name>.frm` | Form definition JSON — the persisted model |
| `<project>/forms/<name>.js` | Script methods |
| `<project>/forms/<name>.sec` | Security settings (`DataModelManager.SECURITY_FILE_EXTENSION_WITH_DOT`) |
| `<project>/forms/<name>.less` | LESS styling (`SolutionSerializer.FORM_LESS_FILE_EXTENSION`) |

There is no `forms/<name>/` directory. The folder concept in the current
`checkFormMarkers()` implementation is erroneous.

The base path for all these files can be derived authoritatively via:
```java
Pair<String, String> fp = SolutionSerializer.getFilePath(form, false);
// fp.getLeft() = "<solution>/forms/"
```

### 2.6 Git history

The `checkFormMarkers()` and `validateFormProperties()` methods were introduced
in the July 2026 implementation (around `SVY-21296`) and have not been
materially changed since. No prior commit introduced a render-critical marker
classification; the broad `SEVERITY_ERROR` filter was the original design choice.
The `forms/<name>/` folder path has no basis in any Servoy serializer code —
it was invented during the initial implementation.

---

## 3. Design

### 3.1 Differentiating `showFormInBrowser` from `screenshotForm`

The two tools have different calling contexts and different failure modes:

| | `showFormInBrowser` | `screenshotForm` |
|---|---|---|
| Called from | UI context menus + MCP | MCP only |
| Failure cost | Low: browser opens, user sees blank | High: blank PNG costs tokens |
| Desired behaviour on error | Open anyway; append warnings | Block and return error text |

Resulting behaviour:

| Form state | `showFormInBrowser` | `screenshotForm` |
|---|---|---|
| Property type mismatch | opens (blank page) + `Warning:` | `Error:`, no image |
| `SEVERITY_ERROR` markers only | opens + `Warning:` | image + `Warning:` |
| Clean | opens | image |

**`showFormInBrowser`**: Remove both hard gates. Instead, run both
`checkFormMarkers()` and `validateFormProperties()` in *collect* mode and
append any findings as a `Warning:` section at the end of the success message.
The form is always opened regardless.

**`screenshotForm`**: Keep `validateFormProperties()` as the **only** hard gate.
It compares each persisted property value against the component's Sablo spec, so
it identifies values the client genuinely cannot render — which is what the
ticket asked for.

Problem markers no longer gate anything, in either tool. Marker presence is a
poor proxy for "this form cannot render": an unresolved `onActionMethodID`, a
missing dataprovider or a JS syntax error all produce `SEVERITY_ERROR` markers
while the form renders perfectly. The review asked us to react only to problems
that actually prevent rendering, and **file location does not establish that** —
a marker on the `.frm` file is no more render-critical than one on the `.js`
file. `checkFormMarkers()` therefore becomes purely informational: findings are
reported alongside the result, never used to block it.

> **Verified 2026-08-24 against solution `test3`.** An `onActionMethodID` marker
> on a `.frm` file does not block the screenshot. A `"%%poison%%"` string on the
> boolean `enabled` property does block it, naming the component and property.
> See §5.

### 3.2 Fixing form-file enumeration

Replace the hard-coded path construction with `SolutionSerializer`-based
derivation:

```java
// Derive base path from the authoritative serializer
Form form = activeProject.getEditingSolution().getForm(formName);
Pair<String, String> fp = SolutionSerializer.getFilePath(form, false);
String basePath = fp.getLeft(); // e.g. "<solution>/forms/"
```

A shared `getFormFiles(IProject, Form, String)` helper returns the files that can
belong to a form, so both callers stop duplicating that knowledge:

```java
String[] extensions = {
    SolutionSerializer.FORM_FILE_EXTENSION,            // ".frm" — form editor JSON
    SolutionSerializer.JS_FILE_EXTENSION,              // ".js"  — form script file
    DataModelManager.SECURITY_FILE_EXTENSION_WITH_DOT  // ".sec" — security settings
};
```

`.less` files do not carry `IMarker.PROBLEM` markers and are excluded. Confirmed
in code: `ServoyBuilder.checkResource()` has no `.less` branch, and no build
participant validates them.

Remove the `IFolder` check (`forms/<name>/`) entirely — it does not correspond
to a real resource.

### 3.3 `checkFormMarkers()` as an informational collector

`checkFormMarkers(ServoyProject, String)` takes no gating flag. It scans every
file from `getFormFiles()` for `SEVERITY_ERROR` markers and returns a summary, or
`null` when there are none. Callers decide what the summary means:

- `screenshotForm` appends it to the saved-screenshot result as a `Warning:`.
- `showFormInBrowser` appends it to the opened-form result as a `Warning:`.

Neither blocks on it.

**Severity prefixes belong to the caller.** `validateFormProperties()` and
`checkFormMarkers()` return unprefixed text; the calling method adds `"Error: "`
or `"Warning: "`. Without this, the same string produced `Warning: Error: Form
'x' has...` on the browser path.

### 3.4 Where the editor dialog goes

`EditorServiceHandler` in the **`servoy-eclipse`** repo held its own copy of both
checks and raised `MessageDialog.openError` directly — that is the dialog the
reporter saw, and it is independent of `FormPreviewService`. Its `openInBrowser`
handler now opens the form unconditionally, and the five duplicated private
helpers (`checkFormMarkersForPreview`, `collectErrorMarkersForPreview`,
`validateFormPropertiesForPreview`, `validateComponentsForPreview`,
`validateContainersForPreview` — ~155 lines) are deleted.

The gates are **removed rather than delegated** to `FormPreviewService`.
Delegating would require a `Require-Bundle` from `com.servoy.eclipse.designer`
onto `com.servoy.eclipse.developer.mcp`, pointing the core IDE at the MCP plug-in
and inverting the dependency direction. With the gates gone there is nothing left
to call across, and the Problems view already surfaces the markers.

> This is a deliberate deviation from the "delegate, and let the UI own the
> dialog" model discussed during review. The observable outcome is the same — no
> dialog — without the new bundle dependency.

### 3.5 Browser console error capture

`screenshotForm()` builds its Cypress spec via a `buildScreenshotSpec()` helper
that hooks four browser-side failure channels before the page loads:

| Channel | Tag in output |
|---|---|
| `console.error` (stubbed; original still called through) | `[console.error]` |
| `window` `error` event | `[window.error]` |
| `unhandledrejection` | `[unhandledrejection]` |
| Cypress `uncaught:exception` | `[uncaught]` |

`uncaught:exception` returns `false` so a page error is *recorded* rather than
failing the run — the screenshot is still wanted. Collected lines are written via
`cy.writeFile` to a per-form log file, which `readBrowserConsoleErrors()` reads
back and folds into the same `Warning:` block as server errors and marker
findings, then deletes.

---

## 4. Implementation plan

1. **Read `FormPreviewService.java` fully** to confirm there are no other callers
   of `checkFormMarkers()` or `validateFormProperties()` that would be affected.

2. **Verify `SolutionSerializer` constants** for `.frm` and `.js` extensions.
   Search for `FORM_FILE_EXTENSION` in `SolutionSerializer.java`.

3. **Extract `getFormFiles()`** in `FormPreviewService` — a shared helper
   enumerating `.frm`, `.js` and `.sec`, with the base path from
   `SolutionSerializer.getFilePath(form, false)` instead of a `"forms/"` literal.
   The `IFolder` check for `forms/<name>/` is dropped.

4. **Reduce `checkFormMarkers()` to an informational collector** — signature
   `(ServoyProject, String)`, no gating flag. Scans every file from
   `getFormFiles()` for `SEVERITY_ERROR` markers, returns a summary or `null`.

5. **Update `showFormInBrowser(String, boolean)`**:
   - Remove the two early-return blocks that gate on `checkFormMarkers()` and
     `validateFormProperties()`.
   - Collect both after the URL is built, before opening the browser.
   - Append any non-null findings as `Warning:` sections after the
     "Opened form…" success line.

6. **Update `screenshotForm(String, int)`**:
   - Keep `validateFormProperties()` as the only hard gate, prefixing its result
     with `"Error: "`.
   - Call `checkFormMarkers()` for information only and fold its findings into
     the post-screenshot `Warning:` block.

7. **Strip the severity prefix from message producers** — `validateFormProperties()`
   returns unprefixed text so the caller can label it.

8. **Add browser console capture** — `buildScreenshotSpec()` and
   `readBrowserConsoleErrors()` per §3.5.

9. **Remove the duplicated gates from `EditorServiceHandler`** in the
   `servoy-eclipse` repo per §3.4 — the `openInBrowser` handler opens
   unconditionally and the five private helpers are deleted.

10. **Update the `screenshotForm` `@Tool` description** so it states that property
    type mismatches block and problem markers do not.

11. **Update tests** in `com.servoy.eclipse.developer.mcp.tests`:
    - `FormPreviewServiceTest` — collector signature, no boolean flag,
      `getFormFiles()` exists, `.frm`/`.js`/`.sec` present, `.less` absent,
      no `IFolder`.
    - `CypressFormTestingIntegrationTest` — markers on `.js` and on `.frm` both
      fail to block; `showFormInBrowser` never returns `Error:`.
    - `ServoyTestingServerTest` — description names the mismatch gate and does
      not claim markers block.

12. **Compile and verify** — zero compilation errors in
    `com.servoy.eclipse.developer.mcp`, `…mcp.tests` and
    `com.servoy.eclipse.designer`.

---

## 5. Acceptance criteria

- [x] `showFormInBrowser` opens the form even when markers or property mismatches
      are present; findings are appended as a `Warning:` note, never returned as a
      blocking `Error:`. — **manually verified** on `test2` with a poisoned
      `enabled` property: form opened, warning named `label_2.enabled`.
- [x] `screenshotForm` blocks (returns `Error:`) when `validateFormProperties()`
      detects a type mismatch. — **manually verified**: returned
      `Error: Form 'test2' has property type mismatches…`, no `.png`.
- [x] `screenshotForm` does NOT block on `SEVERITY_ERROR` problem markers,
      wherever they live. — **manually verified** with an `onActionMethodID`
      marker on a `.frm` file: screenshot taken.
- [x] `checkFormMarkers()` no longer checks the `forms/<name>/` directory.
- [x] `checkFormMarkers()` covers `.frm`, `.js` and `.sec`; `.less` is excluded.
- [x] Form-file paths derive from `SolutionSerializer.getFilePath()`.
- [x] `validateFormProperties()` returns unprefixed text; callers add `"Error: "`
      or `"Warning: "`.
- [x] The form editor's open-in-browser action raises no dialog. — **manually
      verified** after a full Developer restart: right-click →
      open-in-browser on `testformsomething` (which carries an
      `onActionMethodID` ERROR marker) opened
      `?formpreview=testformsomething` and rendered the button, with no
      dialog. This is the reporter's original symptom.
- [x] Browser console errors are captured and folded into the `Warning:` block. —
      **manually verified**: a component pointed at an uninstalled
      `typeName` renders a `servoycoreErrorbean` placeholder and logs
      `ERROR FormComponent - Template for servoycoreErrorbean was not found`
      to the browser console only. `screenshotForm` returned the `.png` path
      **and** reported that error, so the `window:before:load` stub survives
      the Sablo logging wrapper.
- [x] Zero compilation errors in all three affected projects.

All acceptance criteria verified at runtime against solution `test3`.

### How to reproduce a browser-only error

Point a component's `typeName` at an uninstalled spec, e.g.
`"typeName":"bootstrapcomponents-doesnotexist"`. `validateFormProperties()` skips
unknown types (`spec == null` → `continue`), so the screenshot is not gated. The
NG client attempts to substitute `servoycore-errorbean`, that template is not
found either, and the failure surfaces **only** in the browser console:

```
ERROR FormComponent - Template for servoycoreErrorbean was not found,
please check form_component template.          sablo.service.ts:99
```

Nothing else in the test set triggers the console-capture path — a property
mismatch blocks before Cypress launches, and a clean form logs nothing. The
underlying rendering defect is SVY-21380.

---

## 6. Out of scope

- Inline error rendering inside the form editor (an error div or iframe when the
  editor genuinely cannot render) — needs Servoy client integration and is a
  separate task. Without it, a form with a poisoned property shows a **blank
  page** plus a warning in the tool result. That is the accepted cost of not
  blocking.
- A "render-blocking marker type" registry or classification system.
- Item-level property values — a bad value that is a *sibling* of the `json`
  object (e.g. `"enabled":"wawawiwa"` next to `"json":{…}`) is not detected.
  `getFlattenedJson()` returns only the `json` blob, which is also all the
  runtime reads, so such a value affects neither rendering nor the check.
  Deliberately out of scope: since it cannot produce the reported symptom (an
  empty screenshot), the original `%%poison%%` must have been inside `json`,
  which the gate catches.
- **Components whose spec is not installed** — `validateFormProperties()` skips
  unknown types (`spec == null` → `continue`), so such a form is not gated. The
  component renders nothing at all: `FormElement.getWebComponentSpec()` falls
  back to `servoycore-errorbean`, whose template is itself missing. The NG client
  loses just that component; the form editor goes entirely blank. Filed as
  **SVY-21380**. Not gated here on purpose — the screenshot still shows the rest
  of the form, and the browser-console capture reports the failure with it.
- Changes to `RuntimeErrorCapture`.

---

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Exact constant names for `.frm` and `.js` in `SolutionSerializer`? | Implementer | **closed** — `SolutionSerializer.FORM_FILE_EXTENSION` (`".frm"`) and `SolutionSerializer.JS_FILE_EXTENSION` (`".js"`). |
| Does `forms/<name>.less` carry `IMarker.PROBLEM` markers? | Servoy team | **closed** — No. `ServoyBuilder.checkResource()` has no `.less` branch; excluded from enumeration. |
| Should the collector also gather `SEVERITY_WARNING` markers? | Servoy team | **closed** — `SEVERITY_ERROR` only. |
| Which marker types, if any, genuinely prevent rendering? | Servoy team | **partly answered** — `Missing Spec` is one: a component whose spec is not installed renders nothing, and in the form editor the whole canvas goes blank. Filed as **SVY-21380**. It still does not need to gate `screenshotForm` — the NG client renders the rest of the form, and the browser-console capture added here reports the failure alongside the image. No other render-blocking marker type is known. |
| Should item-level property mismatches be validated (see §6)? | Reporter | **closed** — no. The reported symptom was an empty white screenshot, i.e. the form did not render. An item-level value is read by neither the runtime nor this check, so it cannot produce that symptom; the poisoned value must have been inside `json`, which is the placement the gate now catches (verified on `test2`). |
