# Triage Report — SVY-21660

**Verdict:** PROCEED

## Reported problem
The skill-test harness (SVY-21366) judges a run only on correctness — PASS/FAIL
against expected outcomes, acceptance criteria, or transcript comparison. There is no
cost/efficiency dimension. Two runs that both PASS can differ several-fold in token
usage or number of tool calls, and that kind of regression is currently invisible: a
change that makes the agent correct-but-wasteful looks identical to one that is
correct-and-efficient.

The ticket asks to add a cost/token/efficiency dimension:
- Capture per-run token counts and tool-call count from the session transcript.
- Store them in `SkillTestResult` / `last-results.json` and surface them in the Skill
  Test view and the JUnit `system-out`.
- Optionally add a per-baseline **soft budget** that WARNS (not fails) when tokens or
  tool-calls regress beyond a configurable threshold.

"Done when": every run records token usage and tool-call count; a regression beyond a
configurable threshold is flagged (informational); metrics appear in the view and the
CI report.

This is a `[skilltest]` New Feature, a sanctioned follow-up of SVY-21366 and a sibling
of the trend/history ticket (shared storage). It proposes no contentious solution — it
describes the desired capability, not a specific implementation that could be wrong.

## Root-cause assessment
This is a feature request, not a bug, so there is no faulty code path. The relevant
question is whether the data is actually available and whether the proposed storage/
surfacing points are the right ones. Both check out against the current code.

**Tool-call count is already captured.** `SkillTestRunner.runBaseline(...)` exports the
replayed session and holds `lastActualCalls = actual.getToolCalls()`
(`SkillTestRunner.java` ~line 266). `SessionTranscript.fromExport(...)` already parses
every tool part, including nested Code-Mode (`execute`) calls via
`collectNestedCodeModeCalls` (`SessionTranscript.java` ~lines 288–343, SVY-21711). So the
tool-call count is a `size()` away and needs no new transcript parsing.

**Token usage is present in the export and not yet parsed.** opencode's export carries
token/cost metadata on the session/message `info` object. The webui already relies on
this: `session-export.ts` documents "token/cost metadata, etc." and
`session-export.spec.ts` asserts `info.tokens = { input, output }` is preserved verbatim.
`SessionTranscript.fromExport(...)` currently reads `info.agent`, `info.model`,
`info.directory` and per-message `time.created/completed`, but **not** `info.tokens`.
Adding a token read there is a localized, additive change — the defensive walk that is
already in place (null-tolerant `textValue`/`longValue` helpers) is exactly the right
shape for it. opencode's JSON varies by version, so the read must be tolerant (session-
level `info.tokens` and/or summed per-message `info.tokens`, with `{input, output,
reasoning, cache:{read,write}}` sub-fields handled best-effort).

**The storage and surfacing points named in the ticket are the correct ones:**
- `SkillTestResult` already carries attached run data via `withTranscript`,
  `withOutcomeNodes`, `withJsUnit`, `withTrials`, `withAgent` — a parallel
  `withMetrics(tokens, toolCallCount)` fits the existing builder pattern exactly.
- `SkillTestReporter.writeLastResults` / `loadLastResults` already serialize/restore the
  full result additively to `last-results.json`; new numeric fields are backward-
  compatible (older files simply lack them, and the readers already tolerate absent
  fields via `node.has(...)`).
- `SkillTestView` builds columns with `createColumn(...)`; a "Tokens" / "Tool calls"
  column is a one-line addition each, and `SkillTestHtml.render` can show the detail.
- `JUnitXmlReporter` already emits `<system-out>` on PASS (currently only for a JSUnit
  report) — metrics belong there for CI visibility.

So the ticket's premise is sound and the data is genuinely cheap to capture, exactly as
the description claims.

## Ticket premise check
The premise holds. The description is a capability request with a sensible scope, not a
prescriptive (and possibly wrong) fix. Three points worth pinning down during the spec/
implementation, none of which invalidate the ticket:

1. **Soft budget must WARN, never FAIL** — the ticket is explicit ("WARNS (not fails)",
   "informational"). This must not leak into the PASS/FAIL verdict or the JUnit
   `failures`/`errors` counts, or it would turn an efficiency signal into a hard CI gate
   and cause exactly the kind of flaky-threshold churn the harness avoids elsewhere.
   Surface the warning as `system-out` / a view annotation, keeping `<testcase>` green.

2. **Multi-trial aggregation.** A run executes `maxAttempts` trials (`Trial` records).
   Metrics should be captured per trial and the result should report a representative
   aggregate (e.g. the kept/representative trial's figures, or min/median across trials),
   consistent with how the harness already keeps a representative trial's detail. Pick one
   policy in the spec rather than silently averaging.

3. **Token availability is version/provider-dependent.** When the export carries no
   token data, the fields must degrade to "unknown"/absent — never 0 presented as a real
   measurement — so a missing figure is not mistaken for a free run.

## Approaches considered
1. **Capture metrics in `SessionTranscript.fromExport`, carry them on
   `SkillTestResult`, persist in `last-results.json`, surface in view + JUnit
   `system-out`; add an optional soft, WARN-only per-baseline budget (recommended).**
   - Pros: follows the ticket's explicit scope and done-criteria; reuses every existing
     extension point (builder methods, reporter serialization, view columns, JUnit
     system-out); tool-call count is already in hand and token parsing is a small, well-
     isolated, version-tolerant addition; additive and backward-compatible with existing
     `last-results.json`; no change to the PASS/FAIL verdict.
   - Cons: token data depends on opencode's export shape, so the parser needs tolerant
     handling and tests across the shapes; the soft-budget threshold config needs a home
     (baseline sidecar field or a global default) to be designed.

2. **Capture and store metrics, but skip the soft budget for now (metrics-only).**
   - Pros: smallest change; delivers "every run records tokens + tool-call count" and
     "metrics appear in view + CI" with the least risk; the budget is marked "Optional"
     in the ticket.
   - Cons: leaves the "regression beyond a configurable threshold is flagged" done-
     criterion unmet; a fast follow-up would be needed. Reasonable as a first PR but not
     a full close of the ticket.

3. **No code change.**
   - Pros: none beyond zero effort.
   - Cons: the stated gap is real and confirmed — correctness-only grading hides
     efficiency regressions, and the data to close the gap is already in the transcript/
     export. Doing nothing leaves a sanctioned, scoped follow-up of SVY-21366 unaddressed.

## Recommendation
**PROCEED with Approach 1.** Capture per-run **token usage** (tolerantly, from the
export's `info.tokens`) and **tool-call count** (already available as
`actual.getToolCalls().size()`), carry them on `SkillTestResult` via a new builder
method, persist them additively in `last-results.json` (and restore them in
`loadLastResults`), show them in the `SkillTestView` columns + `SkillTestHtml` detail,
and emit them in `JUnitXmlReporter`'s `system-out`. Add an **optional, WARN-only**
per-baseline soft budget that flags (never fails) a regression beyond a configurable
threshold.

If the implementer prefers to de-risk, **Approach 2 as a first PR** (metrics capture +
surfacing, budget deferred) is acceptable, provided the budget follow-up is tracked so
the "regression flagged" done-criterion is eventually met.

Key constraints for the spec phase (all derived from the ticket + code, not open
questions): the soft budget must stay out of the PASS/FAIL verdict and the JUnit
failure/error counts; metrics must be captured per trial with a documented aggregation
policy; missing token data must degrade to "unknown", never a misleading 0; and the
`last-results.json` changes must remain backward-compatible with files written by the
current reporter.

## Git history findings
- `SessionTranscript.fromExport` and the Code-Mode nested-call harvesting were added for
  SVY-21711 (`b27e6a6 "capture Code Mode (execute) nested MCP tool calls in the
  transcript"`), confirming tool-call capture is complete and current.
- `SkillTestReporter`/`SkillTestResult`/`SkillTestView`/`JUnitXmlReporter` all originate
  from the SVY-21366 work; their builders and serialization are deliberately additive
  (each run-attribute added via a `withXxx(...)` method and an optional JSON field), so a
  new metrics field follows an established, low-risk pattern.
- No prior spec exists for SVY-21660 (`docs/` has `SVY-21366-*` and `SVY-21711-*` but no
  `SVY-21660-*` beyond this triage). No dependency/version bump is involved — this is a
  feature addition, not a regression, so there is no version window to bisect.
- The webui side (`session-export.ts` / `session-export.spec.ts`) already treats
  `info.tokens` as a first-class part of the export, independent evidence that the token
  data exists in the payload the Java harness reads.
