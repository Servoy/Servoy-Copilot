# SVY-21614 — Design-time appearance fixes for `getFormLayout`

_Repo: Servoy-Copilot · branch `master` · SDD Phase 2 (spec) · 2026-11-04_
_Supersedes the earlier `SVY-21614-interactive-formlayout-capture.spec.md` (v2, approach B), which is withdrawn: `captureFormState`, synthetic interaction and per-call viewport width are all dropped. See `SVY-21614-triage.md`._

---

## 1. Goal

Make `getFormLayout`'s computed `appearance` reflect the **solution's real styles** at
design time, and make a form's **focus-ring and enabled/editable state** observable in that
map. Two changes, both inside the injected read/readiness JavaScript of
`FormLayoutInspectionService`. No new tool, no new parameter, no synthetic events.

This is the **design-time half** only. Interaction (does a tooltip appear on hover, does a
focus handler fire) needs a running client and is a **separate testing-phase ticket**.

## 2. Problem (verified in `master`)

1. **The stylesheet race (Run 005, the root cause).** `READINESS_JS_TEMPLATE` declares a
   form ready when `window.formtemplateName` matches, the nav clock is fresh, and the root (or
   a descendant) has a non-zero box. The solution stylesheets are injected by
   `FormTemplateHttpEndpoint.buildInjection` as **async** `<link rel="stylesheet"
   svy-stylesheet="…">` tags (one per solution/module sheet). A laid-out box can appear
   **before** those links load, so the subsequent `getComputedStyle` read returns **Bootstrap
   defaults** instead of the solution cascade.
2. **The appearance map omits focus and state.** The `VISUAL` property list in
   `READ_JS_TEMPLATE` stops at `margin`/`padding`: no `outline*`, so a focus ring is invisible
   in the computed styles; and there is no `disabled`/`readonly` signal per element.

## 3. Design

### 3.1 Readiness: wait for the solution stylesheets to apply

Add one clause to `READINESS_JS_TEMPLATE`, after the existing root-box check, before the
final `return false`:

```js
// SVY-21614: the solution stylesheet <link>s load asynchronously; a laid-out box can
// appear before they apply, so getComputedStyle would read Bootstrap defaults. Require
// every svy-stylesheet link to have a parsed .sheet before the form is declared ready.
var sheets = document.querySelectorAll('link[rel="stylesheet"][svy-stylesheet]');
for (var s = 0; s < sheets.length; s++) { if (!sheets[s].sheet) return false; }
```

Rationale:
- A `<link rel="stylesheet">`'s `.sheet` is **`null` until the stylesheet has loaded and
  parsed**, non-null once applied. It is the standard "stylesheet is live in the cascade"
  signal; no `onload` wiring or timer is needed — the existing poll loop simply keeps getting
  `false` until the sheets are in.
- The sheets are **same-origin** (served by the same Servoy web server as the form template,
  `http://127.0.0.1:<port>/…`), so `.sheet` is populated for them (the cross-origin caveat
  where `.sheet` can be null does not apply). The predicate only reads `.sheet` existence, not
  `.cssRules`, so even a hypothetical cross-origin sheet would at worst keep the form "not
  ready" until timeout — a safe failure, never a false "ready".
- **Zero solution sheets** → the `querySelectorAll` set is empty → the loop makes no
  difference and readiness proceeds on the existing box check. Correct: there is nothing to
  wait for.
- The clause is placed so it runs **only after** `formtemplateName`, the fresh-nav check and
  the root-box check have already passed, keeping the existing short-circuits intact.

This clause is inserted into the readiness predicate string, so it is reachable both from the
production poll loop **and** from a test that evaluates the predicate directly (see §5.1).

### 3.2 Appearance: outline\* + disabled/readonly

In `READ_JS_TEMPLATE`:

1. Extend the `VISUAL` property array with the five outline properties:
   `'outline-width','outline-style','outline-color','outline-offset'` (and `'outline'` is
   redundant shorthand — use the four longhands so each value is unambiguous; the longhands
   are what `getComputedStyle` reports deterministically).
2. In the per-element loop (which already computes `cs = getComputedStyle(el)` and `box(el)`
   for every element carrying an `id` or `class`), add two booleans to the element's entry:
   ```js
   disabled: !!(el.disabled || el.getAttribute('disabled') !== null ||
                (el.classList && el.classList.contains('disabled')) ||
                el.getAttribute('aria-disabled') === 'true'),
   readonly: !!(el.readOnly || el.getAttribute('readonly') !== null ||
                el.getAttribute('aria-readonly') === 'true')
   ```

Rationale for the `disabled`/`readonly` source (triage open question 3):
- A **native control** (input/button inside a component) exposes `el.disabled` / `el.readOnly`
  as DOM properties. A **Servoy component rendered as a `<div>`** carries the state as an
  attribute (`disabled`) and/or a class (`.disabled`) and/or ARIA (`aria-disabled`,
  `aria-readonly`). Reading **all** of these with an OR makes the boolean meaningful across
  both shapes rather than always-false on the div case.
- The booleans are added per entry alongside the existing `box` and `styles`, so the envelope
  shape is a pure superset: existing keys unchanged, two keys added.

### 3.3 No other change

`assembleResult`, the result envelope, the spill convention, the viewport handling (SVY-21591)
and the `getFormLayout` signature are **untouched**. A bad/out-of-range viewport still rejects
with SVY-21591's named error. The `@Tool` description already documents `appearance` as "the
browser's COMPUTED values after applying the solution stylesheet + theme" — which, after this
fix, becomes **true**; one sentence may be added noting that outline and disabled/readonly are
included, but no behavioural description changes.

## 4. Acceptance criteria

| AC | Criterion |
|----|-----------|
| 1 | **Readiness waits for the sheets.** After the change, the readiness predicate returns `true` only once every `link[rel="stylesheet"][svy-stylesheet]` in the document has a non-null `.sheet` (given the form name, fresh-nav and root-box checks already pass). |
| 2 | **No solution stylesheet → still ready.** A document with no `svy-stylesheet` link is declared ready on the existing checks (the new clause is a no-op). |
| 3 | **Solution-styled colour (the Run 005 regression guard).** For a form whose solution stylesheet sets a distinctive colour with `!important`, `getFormLayout`'s `appearance` reports that exact colour, never the Bootstrap default (`rgb(240, 240, 240)` background / `rgb(33, 37, 41)` text). |
| 4 | **outline\* present.** Every `appearance` entry's `styles` carries `outline-width`, `outline-style`, `outline-color`, `outline-offset`. |
| 5 | **disabled/readonly present and meaningful.** Every `appearance` entry carries boolean `disabled` and `readonly`; a component explicitly disabled in the form reports `disabled: true`, an enabled one reports `false` (so the boolean is not always-false). |
| 6 | **getFormLayout contract unchanged.** Same 7 parameters, same names/types/required flags; `html` + `appearance` + `window` still returned; viewport width still rejects out-of-range with a named error. |
| 7 | **No new tool / no synthetic events.** The server exposes no `captureFormState`; no code pins `:hover`/`:focus` or dispatches events. |

## 5. Tests

### 5.1 Deterministic readiness unit test (resolves triage OQ1)

The readiness predicate is a self-contained JS expression built from
`READINESS_JS_TEMPLATE`. A plain test can assert the **template itself** contains the
stylesheet-wait clause (string contract), guaranteeing the production poll evaluates it. This
is deterministic — it does not depend on the timing race.

- `plainjunit/services/FormLayoutReadinessTemplateTest` (new):
  - the formatted `READINESS_JS_TEMPLATE` contains the `link[rel="stylesheet"][svy-stylesheet]`
    query and a `.sheet` guard that `return false`s (AC 1);
  - the clause appears **after** the `formtemplateName` and root-box checks (ordering: the
    stylesheet index in the string is greater than the box-check index) (AC 1);
  - `READ_JS_TEMPLATE`'s `VISUAL` list contains the four `outline-*` longhands (AC 4) and the
    per-element object literal contains `disabled:` and `readonly:` keys (AC 5).
  - Reached by reflection on the two package-private template constants (same pattern as the
    existing `FormLayout*` plain tests).

> Note: the string-contract test proves the clause is **wired into** the predicate, which is
> what a regression would remove. It does not execute the JS. Execution is covered by AC 3 end
> to end below.

### 5.2 Integration test — solution-styled colour (AC 3, the real guard)

- `integrationpde/FormLayoutStyledRenderIntegrationTest` (carried over from the withdrawn spec,
  trimmed to `getFormLayout` only — no `captureFormState` half):
  - its own solution (`test_formstyle_suite`) with a `.less` media wired as the solution
    stylesheet, setting `rgb(18, 52, 86)` / `rgb(171, 205, 239)` with `!important` on a label
    and its descendants;
  - renders the form via `getFormLayout` **3 times** and asserts every run reports the
    distinctive colours and never `rgb(240, 240, 240)`;
  - reuses the existing render-prerequisite gate (`renderPrerequisiteSkipReason` /
    `Assumptions.abort`); `@BeforeAll` deletes its projects and it uses its own solution so the
    sheet cannot leak into other classes.
  - **Honesty note:** this guards the race **probabilistically** (3 renders raise the odds a
    missing gate is caught, but cannot guarantee it on a fast loopback). The deterministic
    guarantee is §5.1. Both together: §5.1 proves the clause is present; §5.2 proves it
    produces the right cascade end to end.

### 5.3 Integration test — appearance enrichment (AC 4, 5, 6)

- Extend the existing `integrationpde/FormLayoutToolIntegrationTest` (which already renders a
  CSS form with a button + label, and has a disabled component in the styled fixture):
  - an `appearance` entry carries the four `outline-*` keys (AC 4);
  - an explicitly disabled component reports `disabled: true`; an enabled one `false` (AC 5);
  - `getFormLayout` still returns `html`/`appearance`/`window` and still rejects an
    out-of-range `viewportWidth` with the SVY-21591 error (AC 6).

### 5.4 Contract test (AC 6, 7)

- Reuse/trim `plainjunit/servers/ServoyFormServerToolContractTest` to assert `getFormLayout`
  has exactly its 7 `@ToolParam`s (names/types/required) and that **no** `@Tool` named
  `captureFormState` exists (AC 7).

## 6. Out of scope (explicit)

- `captureFormState`, synthetic hover/focus/click, pseudo-state class-pinning.
- Per-call viewport width (SVY-21591).
- Auto-generated responsive/state Cypress regression tests (separate follow-up ticket).
- `skill4servoy` agent-pack changes (separate cleanup; the stray `captureFormState` grant the
  replay left on the E2E-Tester should be removed there, pointing nothing at a dropped tool).

## 7. Open questions

| # | Question | Owner | Status |
|---|---|---|---|
| 1 | Is a string-contract readiness test (§5.1) accepted as the deterministic guard, with §5.2 as the probabilistic end-to-end? | reviewer | proposed — **default yes** |
| 2 | Should `disabled`/`readonly` also be surfaced when neither attribute/class/property is present but the component spec marks it enabled=false design-time only? (The DOM is the source of truth for a rendered form; design-time-only state without a DOM signal is out.) | implementer | proposed — **DOM only** |
| 3 | Keep `FormLayoutStyledRenderIntegrationTest`'s second (`captureFormState`) render path, or delete it entirely? Spec says delete — the tool is gone. | implementer | **delete** |
