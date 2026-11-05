# SVY-21614 — Triage (design-time half, rescoped onto `getFormLayout`)

_Repo: Servoy-Copilot · branch `master` · SDD Phase 1 (triage) · 2026-11-04_

## Verdict: **PROCEED**, rescoped to the design-time half only.

The ticket's original headline bug is still unfixed in `master`. The viewport/width
part of the original plan already shipped under SVY-21591, and the interactive
(hover/focus/click) part is deliberately dropped — it belongs to the testing phase,
not a design-time render. What remains is small, honest, and clearly valuable.

---

## 1. What the ticket wanted (original)

ServoyAI's design-time form capture (`getFormLayout`) read **Bootstrap default styles**
instead of the solution's real styles (Run 005). The ticket asked for: correct computed
styles, observable focus/hover/active states, a narrow-viewport layout, and selector-scoped
styles — so an agent can **verify a visual state itself** instead of marking it
"NOT VERIFIABLE / HUMAN MUST LOOK".

## 2. What changed since: SVY-21591 landed, and a replay reset the premise

- **SVY-21591** (merged, in `master`) added `viewportWidth`/`viewportHeight` (200–4000 px)
  directly to `getFormLayout`, with applied-width verification. **The responsive/width goal
  is already delivered there.** A bad/out-of-range width **rejects with a named error** (not
  a silent fallback) — this is the behaviour SVY-21614 inherits and keeps.
- **A real agent replay** of the originally-built `captureFormState` tool showed the
  E2E-Tester **never called it** (0 calls, 2 independent runs): it already has real Cypress,
  which gives a true responsive viewport and real `:focus`/pointer events, and it must
  deliver a `.cy` spec anyway. A synthetic design-time interaction is a lower-fidelity detour.
- **User design decision:** hover and focus are a **testing-phase** concern — e.g. "on hover,
  does the tooltip appear" needs the real event and the real running code. A headless pin of
  `:hover` would show only static CSS, never the JS-built tooltip — a misleading half-truth.
  So pseudo-state pinning is **out**.

## 3. The bug that is STILL open in `master` (verified)

`READINESS_JS_TEMPLATE` declares a form ready when: `window.formtemplateName` matches, the
navigation clock is fresh, and the root (or a child) has a non-zero bounding box. It
**does not wait for the solution stylesheet `<link>`s to apply.**

- `FormTemplateHttpEndpoint.buildInjection` (servoy-eclipse) emits one
  `<link rel="stylesheet" svy-stylesheet="…" href="…">` per solution/module sheet, loaded
  **async** by the browser (verified at `FormTemplateHttpEndpoint.java:148-152`).
- A laid-out box can appear **before** those sheets finish loading, so `getComputedStyle`
  reads **Bootstrap defaults** — exactly Run 005.

**This is the root cause, and it is design-time through and through.**

## 4. Rescoped plan (design-time only, all on `getFormLayout`)

| # | Change | Where |
|---|---|---|
| 1 | **Stylesheet-readiness fix (core).** Require every `link[rel="stylesheet"][svy-stylesheet]` to have a parsed `.sheet` before readiness returns true. Empty set (no solution sheets) passes immediately. | `READINESS_JS_TEMPLATE` (one clause) |
| 2 | **Richer appearance.** Add `outline`, `outline-width`, `outline-style`, `outline-color`, `outline-offset` to the `VISUAL` list; add `disabled` / `readonly` booleans per element. | the read template's `VISUAL` + per-element loop |

Both additions live inside the injected read JS; `assembleResult` and the result envelope
are unchanged. No new `@Tool`, no new parameters, no synthetic events.

## 5. Explicitly OUT of scope

- **No `captureFormState` tool.** The stashed implementation (`stash@{0}`) stays as an archive, not merged.
- **No synthetic hover/focus/click, no pseudo-state class-pinning.** Interaction = testing phase.
- **Viewport width** — SVY-21591 owns it.
- **The entire runtime/E2E-regression feature** (auto-generate responsive + hover/focus/active
  Cypress state assertions so a future change that breaks the look fails CI) → **separate
  follow-up ticket**. That is the real regression-prevention piece and deserves its own spec,
  its own consumer (E2E-Tester + the Cypress generator), and its own replay.

## 6. Consumer & agent-pack note

The design-time enrichment is for the **Developer + Reviewer** during a build (no client
needed). The `skill4servoy` grant work from the earlier round — including the
`captureFormState` grant the replay left on the E2E-Tester — is a **separate cleanup** and is
NOT part of this ticket. It should be revisited once this design-time change ships (the grant
should point at `getFormLayout`, which already exists, not at a dropped tool).

## 7. Behaviour decisions carried from HEAD

- Out-of-range / non-numeric viewport **rejects with a named error** (SVY-21591's rule). Kept.
- `getFormLayout` keeps its 7-parameter signature unchanged. No parameter is added or removed.

## 8. Risks / open questions for the spec

1. **How to prove the readiness fix.** The race is timing-dependent, so a test that only
   renders a styled form and checks the colour catches a regression **probabilistically**. The
   spec must state the test strategy honestly (and whether a deterministic check is feasible —
   e.g. asserting on the readiness predicate directly vs. the rendered colour).
2. **`.sheet` cross-origin caveat.** `.sheet` is non-null once a same-origin sheet is parsed;
   the sheets are same-origin (served from the same web server), so this holds — to be
   confirmed in the spec against the actual served URLs.
3. **`disabled`/`readonly` source.** For a native control these are DOM properties; for a
   Servoy component rendered as a `<div>`, they may be attributes/classes. The spec defines
   exactly where the booleans are read from so the value is meaningful, not always-false.
