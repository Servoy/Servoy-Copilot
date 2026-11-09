# SVY-21614 (reopened) — Triage: hover/focus pseudo-states in `getFormLayout`

_Repo: Servoy-Copilot · branch `master` · SDD Phase 1 (triage) · 2026-11-06_
_Follows the shipped design-time appearance work (readiness fix + outline/disabled/readonly). This round adds the hover/focus half the ticket title asked for._

## Verdict: PROCEED — design-time, folded into `getFormLayout`, with a honest fidelity split.

The ticket's title asks for hover, focus AND computed-style so agents can verify visual states themselves. Computed-style + the stylesheet-readiness fix shipped. This round adds **hover/focus as a design-time capability of `getFormLayout`** — NOT a separate tool, NOT Cypress. The earlier deferral to E2E (SVY-21730) was wrong: the designer renders in a real Chromium and CSS pseudo-states are a design-time truth. SVY-21730 is superseded.

## Corrected understanding (from maintainer feedback)

- The designer render IS a real browser (Equo Chromium); it shows the full CSS cascade including design-time pseudo-states. No NG-client state, no running code needed for the VISUAL part.
- So `getFormLayout` should gain a **target selector + an action** (focus/hover/active). Before reading, drive the target into that pseudo-state; then the EXISTING computed-style map + screenshot reflect it. The `captureFormState` idea was directionally right — it was just packaged as a separate tool instead of folded into `getFormLayout`.
- This is purely design-time. A JS-built tooltip (an event handler) is NOT in scope — that needs code to run. The CSS pseudo-state styling (focus ring, hover colour/border) IS in scope.

## The hard part — forcing the pseudo-state headlessly (research + spike)

- **`:focus`** — `element.focus()` is a real DOM API. Pure JS via `IBrowser.evaluate()`. Produces a REAL `:focus` / `:focus-visible`. Clean.
- **`:hover` / `:active`** — there is **no DOM API** to set these, and `getComputedStyle(node, ':hover')` does not exist (a rejected/pending web proposal). The only faithful mechanism is **CDP `CSS.forcePseudoState`** (what Chrome DevTools' "Force state" uses).
- **Spike of our embedded browser (`com.servoy.eclipse.ui.browser.ChromiumWrapper` → Equo `ChromiumBrowser`):** Equo exposes **no generic CDP send**. Its 65-method API has `showDevTools`/`closeDevTools`/`getDevtoolsUrl`/`hasDevTools` (open the panel / get a URL) and a dedicated `captureScreenshot()` that wraps the CDP `Page.captureScreenshot` command internally — but **no `forcePseudoState` and no raw command channel**. `IBrowser` exposes only `evaluate`/`execute`/`setFocus`/`captureScreenshot`. So **real CDP `forcePseudoState` is not reachable from our side** without Equo adding it.

## Decision (option 1)

| action | mechanism | fidelity | label in result |
|--------|-----------|----------|-----------------|
| (none) | current behaviour | resting styles | — |
| `focus` | `element.focus()` via `evaluate()` | **REAL** `:focus`/`:focus-visible` | `approximate: false` |
| `hover` / `active` | **CSS-rewrite** in injected page JS: scan `document.styleSheets` for rules whose selector carries `:hover`/`:active` and matches the target, apply their declarations to the target (inline style / injected marker) | **APPROXIMATE** — shows the pseudo-state STYLING; misses specificity/combinator edge cases; not a true match | `approximate: true` + a `note` |

After the state is applied, the **existing** appearance read + screenshot pipeline reflect it (reuse, no new read path). The result always states which action was applied and whether it was approximate, so the AI never mistakes an approximated hover for a true `:hover`.

## Scope

- `getFormLayout` gains two OPTIONAL params: `targetSelector` (the element to put in the state) and `action` (`focus`/`hover`/`active`; absent = today's resting render). All existing calls are unaffected (new params optional/null).
- The computed-style map + screenshot already exist; this only injects the state before the read.
- No new tool. No `captureFormState`.

## Out of scope

- A true CDP `:hover` (blocked on Equo exposing `forcePseudoState`; see upgrade path).
- JS-driven interaction side-effects (a tooltip built by an event handler) — needs a running client, not design-time.
- The E2E/Cypress regression-spec idea (SVY-21730) — superseded by this; close or repurpose it.

## Upgrade path for REAL hover

Ask the Equo/Chromium team to expose `CSS.forcePseudoState` as a first-class method, exactly as they did `captureScreenshot` (which already wraps a CDP command). If/when available, add one `IBrowser` capability and switch `hover`/`active` from the CSS-rewrite to the real force, flipping `approximate` to false. Track as a follow-up.

## Risks / open questions for the spec

1. **CSS-rewrite accuracy.** Copying a `:hover` rule's declarations onto the element approximates the cascade; document exactly what it does and does not capture (specificity, combinators, `!important`, descendant `:hover`). The label must be honest.
2. **Which `action` values.** `focus`, `hover`, `active` — is `focus-visible` needed distinctly, or does `focus` cover it? (focus() typically triggers both in a keyboardless render — settle in spec.)
3. **Reset between calls.** The injected state must not leak into the next render (the render already navigates to a fresh document per call, so a new document starts clean — confirm).
4. **Screenshot value.** With `screenshot=true` the PNG should show the state (e.g. the focus ring) — confirm the capture runs after the state is applied.
