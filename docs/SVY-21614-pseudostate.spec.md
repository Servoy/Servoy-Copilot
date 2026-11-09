# SVY-21614 (reopened) — Hover/focus pseudo-states in `getFormLayout`

_Repo: Servoy-Copilot · branch `master` · SDD Phase 2 (spec) · 2026-11-06_
_Builds on the shipped design-time appearance work. Triage: `SVY-21614-pseudostate-triage.md`._

---

## 1. Goal

Let `getFormLayout` put one element into a **CSS pseudo-state** (`focus` / `hover` / `active`)
before it reads, so the existing computed-style map and screenshot show that state. This
answers the hover/focus half of the ticket title, at design time, in the hidden Chromium —
no running client, no Cypress, no new tool.

## 2. Mechanism (decided in triage)

| action | how | fidelity |
|--------|-----|----------|
| none (absent) | current resting render | resting styles |
| `focus` | `element.focus()` in injected page JS | **REAL** `:focus` / `:focus-visible` |
| `hover` / `active` | **CSS-rewrite**: find rules in `document.styleSheets` whose selector carries `:hover` / `:active` and whose non-pseudo part matches the target, and apply those declarations to the target via an injected inline `<style>` targeting a marker attribute | **APPROXIMATE** |

Equo exposes no CDP `forcePseudoState` (triage spike), so `hover`/`active` cannot be a true
match. The result is always labelled so the caller knows which it got.

## 3. API

`getFormLayout` gains **two optional** `@ToolParam`s, appended after the existing seven (all
current calls are unaffected — omitted params keep today's behaviour):

- `targetSelector` (string, optional) — the single element to put into the pseudo-state. A CSS
  selector resolved with `document.querySelector` (first match). Independent of the existing
  `selector` (which scopes the returned subtree): you can scope the read to a container and
  still target one element inside it.
- `action` (string, optional) — one of `focus`, `hover`, `active`. Absent/blank = no state
  applied (today's render). An unknown value is a named error (see §6). Requires
  `targetSelector`; `action` without `targetSelector`, or `targetSelector` without `action`,
  is a named error.

The service method gains the two params after `viewportHeight`; the existing overloads keep
delegating with `null, null` so nothing else changes.

## 4. Behaviour

### 4.1 Where it runs in the render sequence
Current order (verified): navigate → await readiness → `alignViewportWidth` → read (`readJs`)
→ screenshot. The pseudo-state is applied **after readiness, before the read**, so both the
`appearance` map and the screenshot reflect it. A fresh navigation per call starts a clean
document, so no state leaks into the next call (the existing `svyRender=<nav>` guard already
guarantees a new document).

### 4.2 `focus`
Inject JS: resolve `targetSelector`; if no match → `ACTION_NO_MATCH` sentinel. Else call
`el.focus()` (and `el.scrollIntoView` is NOT needed — design-time render has no scroll). Real
`:focus`/`:focus-visible` now apply; the subsequent read sees the focused styles. `approximate`
is false.

### 4.3 `hover` / `active` (CSS-rewrite)
Inject JS that:
1. resolves `targetSelector` (no match → `ACTION_NO_MATCH`);
2. tags the target with a unique marker attribute `data-svy-pseudo` (so the injected rule
   targets exactly it, via `[data-svy-pseudo="<id>"]`);
3. walks `document.styleSheets` → `cssRules`; for each style rule whose `selectorText` contains
   `:hover` (resp. `:active`), strips the pseudo-class from the selector and tests whether the
   target `.matches()` that stripped selector; if so, collects the rule's `style.cssText`;
4. writes one injected `<style>`: `[data-svy-pseudo="<id>"] { <collected declarations, each
   !important-preserved as written> }`, so the hover/active declarations win on the target;
5. returns a small summary: how many rules matched (`matchedRules`), and whether any
   cross-origin sheet was skipped (`cssRules` throws on cross-origin — caught, counted in
   `skippedSheets`; the solution sheets are same-origin so normally zero).

`approximate` is true. A `note` states: "hover/active is approximated by applying the matching
pseudo-rule's declarations to the target; it is not a true :hover match (specificity,
combinators and descendant-:hover are not fully modelled)."

### 4.3.1 What the rewrite captures vs misses (documented honestly)
- Captures: a rule like `.btn-x:hover { background: ... !important }` whose base selector
  `.btn-x` matches the target. The declarations are applied to the target.
- Misses / approximate: descendant or sibling `:hover` (`.card:hover .btn`), `:hover` combined
  with other state (`.btn:hover:focus`), specificity interactions between several matched
  rules (last-wins by collection order, not true cascade), and `@media`-nested hover rules
  that don't apply at the current width (these SHOULD be skipped — only rules in matching media
  are honoured by `.matches()`/live `cssRules`).

### 4.4 Result shape
The envelope gains, only when an `action` was requested, an `interaction` object:
```json
"interaction": {
  "action": "focus",
  "targetSelector": "[data-cy='f.btn']",
  "matched": true,
  "approximate": false
}
```
For hover/active it also carries `matchedRules`, `skippedSheets` and the `note`. When
`matched` is false the read still returns (resting styles) with `matched: false` and a
`note` naming the unmatched selector — not an error (the caller asked to inspect; a missing
target is informative, consistent with how the existing `selector` NO_MATCH is a named
message). `action` without `targetSelector` (or vice-versa) IS an error (§6), because that
is a malformed request, not an inspection outcome.

The `appearance` map and `html`/`window`/screenshot are exactly as today — the state is baked
into the computed styles they already report.

## 5. Acceptance criteria

| AC | Criterion |
|----|-----------|
| 1 | `action=focus`, `targetSelector` matching a focusable element → the element's `appearance` entry shows the solution's `:focus` styling (e.g. the focus-ring `outline-*`), and `interaction.approximate=false`, `matched=true`. |
| 2 | `action=hover` on a component whose solution stylesheet defines a distinctive `:hover` colour → that element's `appearance` reports the hover colour, with `interaction.approximate=true`, `matched=true`, `matchedRules>=1` and the `note`. |
| 3 | `action=active` behaves like hover (same CSS-rewrite path, `:active` rules). |
| 4 | No `action` (today's call) → no `interaction` key; the render is byte-for-byte the resting behaviour (regression guard for existing callers). |
| 5 | `action` with no `targetSelector`, or `targetSelector` with no `action`, or an unknown `action` → a named error (never a hang), listing the valid actions. |
| 6 | `action=focus`, `targetSelector` matching nothing → `interaction.matched=false` + a note; the call still returns the resting appearance (not an error). |
| 7 | With `screenshot=true` the PNG reflects the applied state (e.g. the focus ring is visible). |
| 8 | The applied state does not leak: a pseudo-state call followed by a plain `getFormLayout` shows the plain one with no forced state. |
| 9 | `getFormLayout`'s existing 7-param contract is unchanged; the two new params are optional and documented. |

## 6. Named errors (never a hang)
- unknown `action` → `Error: action must be one of focus, hover, active (got '<x>').`
- `action` without `targetSelector` → `Error: action '<a>' requires a targetSelector.`
- `targetSelector` without `action` → `Error: targetSelector was given without an action (focus, hover or active).`
These are validated in `ServoyFormServer` before the service call (mirrors `parseViewport`).

## 7. Tests

### 7.1 Plain JUnit (deterministic, no browser)
- `FormLayoutPseudoStateTemplateTest` (new): the injected-JS template(s) contain the
  `focus()` path, the `:hover`/`:active` selector-scan + marker-rule rewrite, the
  `ACTION_NO_MATCH` sentinel, and emit the `interaction`/`approximate`/`note` keys. Same
  string-contract style as `FormLayoutReadinessTemplateTest`.
- `ServoyFormServerToolContractTest` (extend): `getFormLayout` now has 9 `@ToolParam`s
  (`targetSelector`, `action` appended, optional); still no `captureFormState`; the
  validation precedence of §6 (unit-test the pure validation helper).

### 7.2 Integration (PDE, real render)
- `FormLayoutStyledRenderIntegrationTest` (extend its own styled solution — the `.less`
  already defines `:hover`/`:focus` rules on `.btn-important` per the btn_fixture pattern):
  - AC1: `action=focus` on the button → its appearance carries the solution focus `outline`
    (the 3px ring), `approximate=false`.
  - AC2: `action=hover` → the hover `background-color`, `approximate=true`, `matchedRules>=1`.
  - AC7: `screenshot=true` with `action=focus` attaches a PNG (smoke — pixel assertion is not
    attempted).
  - AC8: a hover call then a plain call → the plain call shows no forced hover styling.
- Reuse the existing render-prerequisite skip gate. Own solution, `@BeforeAll` cleanup.

## 8. Out of scope
- True CDP `:hover` (blocked on Equo exposing `forcePseudoState`; upgrade path in triage §).
- JS-driven interaction side-effects (a handler-built tooltip) — needs a running client.
- SVY-21730 (Cypress regression spec) — superseded; being closed.

## 9. Open questions
| # | Question | Default |
|---|----------|---------|
| 1 | Is `focus-visible` needed as a distinct action, or does `focus` (via `element.focus()`) cover the common case? | `focus` only; `element.focus()` triggers `:focus-visible` in this render |
| 2 | Should an unmatched `targetSelector` be a soft `matched:false` (spec) or a hard error? | soft (consistent with inspection intent); malformed param combos stay hard errors |
| 3 | Cap on `matchedRules` collected (avoid a pathological sheet)? | cap at a small constant (e.g. 50) + a `truncated` flag, like the existing caps |
