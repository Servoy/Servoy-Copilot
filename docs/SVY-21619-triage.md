# Triage Report - SVY-21619

**Verdict:** PROCEED

Minor / type unset in Jira; status **In Progress**; fixVersion 2026.12. The design-time `getFormLayout` tool already
covers the measurement asks (SVY-21591, SVY-21614), but the Cypress form/E2E tooling that produced the run-007 false
negative **is in scope**: it lives in `com.servoy.eclipse.cypress` (servoy-eclipse) and in this repo's
`ServoyTestingServer` / `FormNavigationGraphService`. The first version of this triage looked only at `getFormLayout`
and wrongly concluded NO_ACTION. See `SVY-21619-e2e-viewport-measurement.spec.md` for the design.

## Reported problem

Run 007 (a two-card grid that must collapse to one column at <= 640 px) ended badly for a process reason, not a
product bug. The Cypress E2E-Tester measured the running NG client at 480 px and reported the cards still side by
side. That was a false negative. A normal browser at 480 px shows one column correctly. The agent then escalated a
non-existent platform limitation.

The ticket asks for:
1. The E2E tool must **apply and verify** the viewport and report `window.innerWidth` **and** the measured container
   width.
2. **Measure after layout settles.**
3. Agent guidance (measure narrow and wide, cross-check before blaming the platform).

## Where each ask lands

| Ask | Where it lives | Action |
|---|---|---|
| Apply + verify viewport, report innerWidth + container width | Cypress form/E2E tooling (`com.servoy.eclipse.cypress`, `ServoyTestingServer`, `FormNavigationGraphService`) | Implemented by SVY-21619: always-on reporting in both runners, opt-in `viewportWidths` on both generators |
| Measure after layout settles | Same | Implemented: box-stability polling (3 samples x 100 ms), no fixed sleep |
| Design-time `getFormLayout` | `ServoyFormServer` / `FormLayoutInspectionService` | Already covered by SVY-21591 and SVY-21614. No change |
| Agent/skill guidance | `servoy-e2etester` skill (skills zip) | Out of scope for these Java bundles |

## Decisions

- Always-on reporting in `testForm` and `testE2E`, plus an opt-in `viewportWidths` parameter on `generateFormSpec` and
  `generateCypressE2ETest`. The run-time tool signatures are unchanged.
- Measurement lines reach the runner through `cy.task('svyMeasure', ...)` (Node `console.log` into `cypress run`
  stdout), because `cy.log` is not printed by `cypress run`.
- The shared commands `cy.measureAtViewport` and `cy.measureContainer` are scaffolded into `support/commands.js`,
  merged idempotently so an existing `cy.login()` is preserved.

## Correction note

The earlier NO_ACTION verdict assumed the Cypress harness was outside this repository. It is not: the form-spec
generator, the runner and the E2E template are all here (servoy-eclipse `com.servoy.eclipse.cypress` plus this
repo's MCP server layer).
