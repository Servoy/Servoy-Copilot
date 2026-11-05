# Triage Report — SVY-21619

**Verdict:** NO_ACTION

Minor / type unset in Jira; status **In Progress**; fixVersion 2026.12. This triage recommends closing
without a code change in this repository, because the measurement substance SVY-21619 asks for is already
implemented in the current `getFormLayout` (landed via SVY-21591 and SVY-21614), and the remaining substance
(the Cypress E2E-Tester harness and agent/prompt guidance) does not live in this Java MCP bundle. The human
decides; see the open question at the end.

## Reported problem

Run 007 (build a responsive form; a two-card grid that must collapse to one column at ≤ 640 px) ended badly for
a *process* reason, not a product bug:

- The **E2E-Tester (Cypress)** measured the running NG client at a 480 px viewport and reported the two cards
  still side-by-side (`grid-template-columns: "446px 446px"`, `.sd-page` ~892 px). That was a **false negative**:
  ground-truth measurement afterwards in a normal browser at 480 px shows one column correctly (`.sd-page` 468 px,
  grid `"436px"`, document width 480, no overflow — the container query fires).
- Because the E2E-Tester is the agent's only "live eyes", the false negative burned four iterations, likely
  discarded working CSS in iterations 1–2, and the Orchestrator escalated a **non-existent platform bug**
  ("the Servoy NG `svy-respform` shell does not narrow `.sd-page` to the viewport") and stopped to ask the user,
  violating the single-pass instruction.

The ticket's **Expected / Suggestions** are three things:
1. The E2E tool should **apply and verify** the viewport actually used — report `window.innerWidth` **and** the
   measured container width in its output.
2. **Measure after layout settles.**
3. Agent guidance — before blaming the platform, compare container width vs viewport width in the same
   measurement; the Developer must measure **both** narrow and wide after each CSS change; cross-check a
   surprising "platform limitation" against the fact that the same CSS mechanism worked at 375 px in an
   earlier run.

## Root-cause assessment

The measured false negative came from the **Cypress E2E-Tester path** (a running NG client at
`http://localhost:8183/solution/demo#static_display_specimens`, test
`jenkins-custom/e2e-test-scripts/cypress/e2e/demo/static_display_specimens_ac3.cy.js`), confirmed by the agent
trace `09-e2e-tester-syzaP4.md` and by `analysis-run-007.md` root cause #2 ("Unreliable narrow-width measurement
by E2E-Tester"). That harness and its support helpers are **not in this repository** — they live under
`jenkins-custom/e2e-test-scripts/` and in the user-home skill `~/.servoy/opencode/skills/servoy-e2etester`.

The only form-measuring tool that *does* live in this Java MCP bundle is the **design-time `getFormLayout`**
tool (`com.servoy.eclipse.developer.mcp`), which is a different mechanism (stateless `/formtemplate` render in a
hidden browser, no running client, no Cypress). The user's central question is whether the recent
`getFormLayout` work already covers SVY-21619's measurement asks. It does.

### State of getFormLayout after SVY-21591 & SVY-21614

Files read in full:
- `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/servers/ServoyFormServer.java`
  (the `@Tool getFormLayout`, lines 42–123; `viewportWidth`/`viewportHeight` params lines 87–88).
- `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/FormLayoutInspectionService.java`
  (the whole service, 1–1144).

Commits that shaped it (confirmed via `git log --grep`):
- `c5c031c` **SVY-21591** "getFormLayout: render at a requested viewport width/height" (2026-11-03).
- `b8115f6` **SVY-21614** "getFormLayout: wait for solution stylesheets, add outline/disabled/readonly" (2026-11-05).

Ask-by-ask, against the current code:

**Ask 1 — apply AND verify the viewport, report `window.innerWidth` + container width.** ✅ Implemented
(SVY-21591).
- Applies the requested width: `applyViewport(...)` resizes the offscreen shell + browser
  (`FormLayoutInspectionService.java` 841–849), re-applied every call so a previous narrow render cannot leak
  (467–474). `alignViewportWidth(...)` (597–628) corrects the browser's ~+1 px drift so a request for 768 does
  not render at 769 (above the `md` breakpoint).
- **Verifies** it: after the read, lines **413–420** hard-fail with a named error ("requested viewportWidth X
  but the browser rendered at Y px … The viewport could not be applied") when the actual `window.width` differs
  from the request by more than a 2 px tolerance (`VIEWPORT_WIDTH_TOLERANCE`, 118). So it can never silently
  report the default layout — exactly the failure mode SVY-21619 describes.
- **Reports** both numbers: the injected read returns
  `window: { width: window.innerWidth, height: window.innerHeight }` **and** `viewport: box(root)` — the
  `.svy-form` container's on-screen bounding box (x/y/w/h) — plus a per-element `appearance` map with a `box`
  each (`READ_JS_TEMPLATE` 194–196, 180–193). Both `window` and `viewport` survive a spill to file
  (`assembleResult`, 944–947). So the output already carries `innerWidth` **and** the measured container width
  that SVY-21619 asks for.

**Ask 2 — measure after layout settles.** ✅ Implemented (SVY-21591 + SVY-21614).
- The read only runs once the `READINESS_JS_TEMPLATE` predicate (221–237) holds: the document is the requested
  form (`window.formtemplateName`), it is a *fresh* navigation (`performance.timeOrigin` newer than nav start —
  stale renders rejected), at least one node is laid out (non-zero bounding box), **and** every solution
  stylesheet `<link svy-stylesheet>` has its `.sheet` live in the cascade. That last clause is **SVY-21614**
  (the Run 005 bug: reading `getComputedStyle` before the solution sheets parsed returned Bootstrap defaults).
- After a corrective resize, `alignViewportWidth` re-measures and waits for the page to report the new width
  before proceeding (612–622). So the measurement is taken after layout + styles settle, not too early.

**Ask 3 — cross-check container vs viewport in the same measurement.** ✅ The data is present.
- Because a single `getFormLayout` result returns both `window.width` (viewport) and `viewport`/`appearance[*].box`
  (container + element widths), an agent can compare container-vs-viewport in one call — which is precisely the
  cross-check SVY-21619 wants (and which the Cypress measurement in run 007 did not surface). That is a data
  capability of the tool; whether the *agent* performs the comparison is prompt guidance (see Ask 3-behaviour).

**Ask 3 (behaviour) — "measure both narrow and wide", "cross-check before escalating a platform bug".** ⛔ Not
code in this repo. This is agent/orchestrator/skill prompt guidance. It belongs in the `servoy-e2etester` /
loop-protocol / Orchestrator prompt files (shipped from the user-home `~/.servoy/opencode/skills/...` via the
skills zip), not in the Java MCP bundle. Nothing in this repository implements or constrains that behaviour.

**Conclusion of the cross-check:** All three measurement asks (apply+verify viewport, report innerWidth + container
width, measure after settle, and enough data to cross-check container-vs-viewport) are already satisfied by the
current `getFormLayout` as of SVY-21591 + SVY-21614. The ticket's run-007 narrative *predates* both commits
(run dated 2026-10-14; SVY-21591 landed 2026-11-03, SVY-21614 2026-11-05), so the ticket describes a state that
no longer exists for the design-time tool.

## Ticket premise check

The ticket's premise — "the E2E tool does not apply/verify the viewport or report innerWidth+container width, and
does not measure after settle" — is **true of the Cypress E2E-Tester as it behaved in run 007**, but is **false of
the design-time `getFormLayout` tool today**. Two things make the premise not actionable *in this repo*:

1. **Wrong tool / wrong repo for the code asks.** The false negative was produced by the Cypress harness in a
   running client. That harness is not in this repository. The design-time `getFormLayout` tool that *is* here
   already does everything the ticket lists as "Expected" for a measurement tool.
2. **The behavioural asks are prompt guidance, not Java.** "Measure both narrow and wide", "cross-check before
   escalating", "compare container vs viewport" are agent-discipline rules that live in the skill/orchestrator
   prompts, outside this repo.

So there is no genuine remaining code change for the `com.servoy.eclipse.developer.mcp` bundle. The ticket is
effectively superseded by SVY-21591 and SVY-21614 for the part this repo owns.

## Approaches considered

1. **No code change — already solved for this repo by SVY-21591 + SVY-21614 (recommended).** The design-time
   `getFormLayout` already applies and verifies the viewport, reports `window.innerWidth` and container/element
   widths, and measures only after layout + solution stylesheets settle. The measurement substance SVY-21619 asks
   for exists. Close SVY-21619 against this repo (optionally as a duplicate/related of SVY-21591/21614), and route
   any remaining work to where it actually lives.
2. **Harden the Cypress E2E-Tester harness** (apply + assert `window.innerWidth`, read computed widths, wait for a
   stable layout before asserting). This is the true home of the run-007 false negative — but it is **not in this
   repository** (`jenkins-custom/e2e-test-scripts/`), so it cannot be done here. If the team wants the live-client
   path hardened, it needs its own ticket against that harness.
3. **Prompt/skill guidance change** ("measure narrow and wide after each CSS change"; "cross-check a surprising
   platform-limitation conclusion against the same mechanism working at another width before escalating"). Also
   **not in this repository** — it belongs in the `servoy-e2etester` / Orchestrator / loop-protocol prompts shipped
   via the skills zip. A separate ticket against the skills repo.
4. **Redundant re-implementation in `getFormLayout`.** Add more viewport reporting to `getFormLayout`. Rejected —
   it already reports `window` + `viewport` + per-element boxes and hard-fails on an unapplied width; there is
   nothing left to add for the stated asks.

## Recommendation

**NO_ACTION in this repository.** Close SVY-21619 as resolved-by / related-to **SVY-21591** and **SVY-21614** for
the part this repo owns (the design-time `getFormLayout` tool), with a note that the three measurement asks are
already implemented there. Record that the two remaining threads from the ticket are out of scope for the Java MCP
bundle and need their own tickets if still wanted:
- the **Cypress E2E-Tester** harness that produced the run-007 false negative (`jenkins-custom/e2e-test-scripts/`),
- the **agent/skill prompt guidance** ("measure both widths", "cross-check before escalating a platform bug").

If instead the team's intent is that the responsive-stacking verification should **switch from the Cypress
E2E-Tester to `getFormLayout`** (so the design-time tool becomes the narrow/wide check), that is a *prompt/skill*
change (tell the Developer/Orchestrator to verify breakpoints with `getFormLayout viewportWidth=…`), again outside
this repo — and no `getFormLayout` code change is needed because it already supports it.

## Git history findings

- `git log --all --grep="SVY-21591"` → `c5c031c SVY-21591 getFormLayout: render at a requested viewport
  width/height [ai]` (Gabi Boros, 2026-11-03). `git show --stat`: touches `ServoyFormServer.java` (+43),
  `FormLayoutInspectionService.java` (+414/−106), `FormLayoutToolIntegrationTest.java` (+93). Commit message
  explicitly: "Return window {width,height} in the result, also when it spills to a file … reject … a width that
  could not be applied, with a named error instead of rendering a wrong layout" — i.e. apply + verify + report.
- `git log --all --grep="SVY-21614"` → `b8115f6 SVY-21614 getFormLayout: wait for solution stylesheets, add
  outline/disabled/readonly [ai]` (Diana Bunaciu, 2026-11-05). `git show --stat`: touches `ServoyFormServer.java`,
  `FormLayoutInspectionService.java` (+30), and adds `docs/SVY-21614-design-time-appearance.spec.md` and
  `docs/SVY-21614-triage.md`. Message: "readiness now waits for every svy-stylesheet `<link>` to apply before
  reading … (the Run 005 bug)" — i.e. measure after settle.
- `git log --all --grep="SVY-21619"` → **no commits.** Nothing has been implemented for SVY-21619 in this repo,
  consistent with closing it as superseded rather than implementing it.
- Both SVY-21591 (2026-11-03) and SVY-21614 (2026-11-05) **post-date** run 007 (2026-10-14), so the ticket's
  run-007 evidence predates the fixes.
- `grep` for `e2e|Cypress|cy.viewport|innerWidth` across `*.java,*.md,*.ts` in this repo found **no** E2E/Cypress
  harness source — only `docs/SVY-21xxx-*.spec.md` planning docs reference Cypress tooling that lives elsewhere.
  The full `@Tool` inventory of the MCP bundle contains no E2E capture/measure tool; the only form-measuring tool
  is `getFormLayout`.
