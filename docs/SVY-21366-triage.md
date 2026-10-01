# Triage Report — SVY-21366

**Verdict:** NEEDS_INPUT

## Reported problem
Servoy AI skills / Orchestrator behaviour is currently tested only manually, which does
not scale. Prompt and skill changes can silently regress unrelated functionality, so the
team wants an automated way to check that "basic functionality" still works after a change
at the skill/prompt level. This ticket (`SVY-21366`) is the follow-up to the research case
`SVY-21245`.

## Root-cause assessment
This is not a defect — it is a New Feature / tooling request, so there is no faulty code
path to point at. The relevant facts from the codebase and ticket lineage:

- **The research spike (SVY-21245)** produced an uncommitted record/replay mechanism
  (`fixture-mode.ts` in the `opencode-kiro-auth` plugin) that captures the LLM↔opencode
  event stream to JSON and replays it positionally, keyed by `sha256(prompt)`. It never
  hits the network on replay (~1.7s for a 16-call run).
- **The ticket's own proposal** (build an internal plugin that switches
  record/replay/live, persist sequences in the opencode DB, grow a library of
  activate/deactivate/update-able scenarios) is built directly on top of that spike.
- **The team has explicitly rejected that premise.** Vid Marian's comment (2026-08-24):
  *"After discussing with the team we have concluded that record/replay mechanism is not
  quite useful (since it just tests the tools calling). So a real llm is needed which is
  able to interpret prompting and skills. This need to run as an unit test / integration
  test within developer."*

So the direction has shifted from "record/replay fixtures" to "real-LLM integration test
running inside Developer", but **no architectural decisions have been made** for that new
direction. A shallow orientation of the two active bundles confirms there is nothing to
extend yet:
- opencode is launched only as an HTTP server via `npm exec -- opencode serve` on a free
  port (`RunOpencodeCommand.java:65`, `OpencodeServerState.java`); there is no
  programmatic driver for a scripted prompt→assert flow.
- The existing test bundles (`com.servoy.eclipse.opencode.tests`,
  `com.servoy.eclipse.developer.mcp.tests`) contain only plain unit tests and
  workbench-backed integration tests — none invoke a live LLM.

## Ticket premise check
The premise as written **does not hold**: the record/replay approach the description is
built around was abandoned by the team after the SVY-21245 research. The description is
therefore stale. The replacement direction (real-LLM integration test in Developer) is
agreed at a high level but is a single sentence — it does not settle where the tests live,
how the LLM is driven, how non-deterministic output is asserted, what the seed scenarios
are, or how cost/frequency are controlled. These are exactly the decisions a spec would
need, and they diverge into materially different implementations.

## Approaches considered
1. **Real-LLM integration tests driving the embedded opencode HTTP server** — start
   opencode `serve`, POST a scripted prompt, assert on the resulting MCP tool calls /
   workspace artifacts (not on exact text). Pros: exercises the real prompt+skill+model
   path the team asked for; reuses existing server launch. Cons: needs `GENAI_API_KEY` in
   CI, real token cost, non-deterministic — assertions must be tolerant; slow.
2. **Real-LLM tests driving the opencode CLI in run mode + native `opencode export`** —
   run a prompt headlessly, then use opencode's built-in `opencode export [sessionID]`
   (confirmed native as of the SVY-21374 close) to get the transcript and assert on tool
   calls. Pros: leans on upstream-maintained export instead of custom extraction; simplest
   moving parts. Cons: same cost/non-determinism; CLI orchestration harness still to be
   designed.
3. **Resurrect the record/replay fixture approach** (the ticket's original text) — persist
   sequences in the opencode DB, build a scenario library. Pros: fast, free, deterministic
   in CI. Cons: **explicitly rejected by the team** — only tests tool wiring, not whether
   the LLM interprets prompts/skills correctly, which is the actual goal.
4. **No code change** — keep testing manually / rely only on the existing non-LLM unit
   tests. Pros: zero cost, no flaky LLM tests. Cons: does not address the stated problem;
   the manual-testing burden the ticket was opened to remove remains.

## Recommendation
`NEEDS_INPUT`. The investigation converges on *what* is wanted (a real-LLM
integration/unit test that verifies prompts+skills still produce the right behaviour after
a change) but the ticket text describes a now-rejected mechanism, and the agreed
replacement has no design decisions attached. The remaining choices (test host bundle, how
the LLM is driven, how to assert on non-deterministic output, seed scenarios, CI cost and
frequency) lead to materially different implementations and cannot be settled from the
ticket. A human needs to answer the questions below before a spec can be written.

The strongest starting candidate to put in front of the team is **Approach 1 or 2** (real
LLM, assert on tool calls / artifacts, use native `opencode export` for the transcript),
with record/replay dropped per the team's decision — but this should be confirmed, not
assumed.

## Git history findings
None relevant. The SVY-21245 record/replay code (`fixture-mode.ts`) was never committed to
this repository (it lived on top of an `opencode-kiro-auth` commit and was described as
"added, but not committed"). There is no prior spec in `docs/` for this key, and no
committed test harness to blame.

## Questions for the reporter
1. Confirming the direction: should we drop the record/replay approach entirely and build
   a **real-LLM** integration test (per the 24 Aug team decision), rather than the
   record/replay plugin the description asks for?
2. Where should these tests live and run? Options: as an integration-test fragment of
   `com.servoy.eclipse.opencode`, of `com.servoy.eclipse.developer.mcp`, or a new dedicated
   test bundle. Should they run inside a PDE/Developer workbench, or headlessly?
3. How should the LLM be driven during a test — through the embedded opencode HTTP server,
   or by invoking the opencode CLI in run mode headlessly? (opencode's native
   `opencode export` can be used to capture the transcript for assertions, per SVY-21374.)
4. Since the model output is non-deterministic, what should a test assert on — that the
   expected MCP tools were called (and with what arguments), that specific workspace
   artifacts were created (e.g. a value list / button with the right properties), or a
   looser "did it succeed" signal? Is a small number of retries per scenario acceptable?
5. What model and credentials should CI use (e.g. `kiro/auto` with a `GENAI_API_KEY`), and
   who owns the token budget? Is there a target ceiling per run so we stay in control of
   cost?
6. How often should these run — on every build, nightly, or on-demand only? Given the cost
   and flakiness of real-LLM calls, on-demand / nightly seems safer; is that acceptable?
7. What is the initial set of "basic operation" scenarios to seed the suite (e.g. "create a
   value list with values one, two, three", "add a button to a form"), and who curates the
   growing scenario library going forward?
