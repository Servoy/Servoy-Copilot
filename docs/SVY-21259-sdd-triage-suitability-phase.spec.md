# Spec: SVY-21259 — SDD tool missing suitability checks (triage phase)

## 1. Goal

Add a dedicated **Triage phase** to the front of the SDD (Spec-Driven Development)
pipeline so the pipeline no longer takes a Jira ticket's proposed solution at face
value. The Triage phase runs as an isolated, unbiased agent whose only mandate is to
find the *actual* root cause of the reported problem and decide whether — and how — it
should be addressed. This prevents the pipeline from confidently producing a spec (and
implementation) for an approach that may be wrong, unnecessary, or aimed at code that
isn't the real source of the problem.

## 2. Background

### 2.1 Current pipeline

The SDD pipeline is orchestrated by `.opencode/skills/sdd/SKILL.md` and runs six phases,
each as an isolated `task` subagent with a fresh context:

1. **PM Agent** (`phases/pm-agent.md`) — Jira → spec
2. **Coding** (`phases/coding.md`) — spec → implementation
3. **Code Review** (`phases/code-review.md`)
4. **Test Generation** (`phases/test-gen.md`)
5. **Test Review** (`phases/test-review.md`)
6. **Commit**

The pipeline deliberately isolates context between phases to avoid bias (e.g. the Coder
only sees the spec, not the PM's reasoning).

### 2.2 The problem

The PM Agent is currently the first phase. Its job description is "turn a Jira issue into
a complete, developer-ready spec". Its "Identify gaps" step (`pm-agent.md` step 3) only
checks whether the ticket contains *enough information to write a spec* — it never
questions whether the ticket's premise is correct or whether a code change is warranted
at all.

Because the agent that decides *whether/how* to act is the same agent that is tasked with
*producing a spec*, it exhibits self-consistency bias: once it has read and internalized
the ticket's framing, it rationalizes that framing rather than challenging it.

Concrete example from the ticket: while working on
[SVY-21218](https://servoy-cloud.atlassian.net/browse/SVY-21218), the pipeline fixated on
implementing the API described in the ticket, even though the correct fix was to adjust an
internal mechanism — no new API was needed at all.

### 2.3 Why a separate phase (not an in-PM step)

The pipeline's core design principle is **context isolation to prevent bias**. Folding a
"should we even do this?" check into the PM Agent leaves the same biased agent making the
call. A separate Triage agent with a fresh context and a truth-finding mandate (rather than
a spec-producing mandate) is consistent with the rest of the pipeline and directly fixes
the root cause described in the ticket.

## 3. Design

### 3.1 New Phase 0 — Triage & Root-Cause Investigation

A new phase runs **before** the PM Agent. It is defined in a new file
`.opencode/skills/sdd/phases/triage.md` and spawned as an isolated `task` subagent.

Its mandate is explicitly *not* to produce a spec. It must:

1. Read the Jira issue (+ `USER_CONTEXT`), including attachments, logs, linked issues and
   comments.
2. **Early sufficiency check (divergence test).** After reading the issue, do a shallow
   code orientation and list candidate reproduction scenarios. If ≥2 lead to materially
   different root causes AND the ticket lacks the detail to disambiguate, emit
   `NEEDS_INPUT` immediately — do not proceed to the deep investigation. This prevents
   the agent from exhaustively investigating every divergent hypothesis when only the
   reporter can settle which scenario applies. Counter-guardrail: if the shallow look
   reveals a single plausible cause, the full investigation continues even if the ticket
   is terse.
3. Investigate the codebase and git history to locate the *actual* root cause.
4. **Challenge the ticket's premise.** Ask: is the approach described in the ticket the
   right one, or is there a better/internal mechanism? Is the problem even in Servoy code,
   or is it user-side (misconfiguration, API misuse), expected behaviour, or in a
   third-party dependency?
5. Enumerate 2–4 candidate approaches — **always including "no code change needed"** as a
   candidate — with pros/cons for each.
6. Produce a **verdict**, one of:
   - `PROCEED` — a fix is warranted; includes the recommended approach and the alternatives
     considered.
   - `NO_ACTION` — not a Servoy bug / expected behaviour / user-side / third-party; includes
     justification.
   - `NEEDS_INPUT` — genuinely ambiguous; a human decision is required before continuing.
     May be reached early (divergence test) or after the full investigation.
7. Write a short **structured triage report** to `docs/<KEY>-triage.md` (via Write), then
   emit its relative path as the final message.

### 3.2 Triage report structure

The triage agent persists a structured report to `docs/<KEY>-triage.md` (same flat `docs/`
folder as the spec) and returns its path as the final message. The orchestrator reads the
file to display and parse it:

```markdown
## Triage Report — <KEY>

**Verdict:** PROCEED | NO_ACTION | NEEDS_INPUT

### Root-cause assessment
<Where the actual problem lies, backed by code/git evidence.>

### Ticket premise check
<Does the ticket's proposed approach hold up? Why / why not.>

### Approaches considered
1. <Approach> — pros / cons
2. No code change — pros / cons
...

### Recommendation
<The recommended approach and justification. For NO_ACTION, the reasoning. For
NEEDS_INPUT, the specific question(s) that need answering.>
```

### 3.3 Human gate after Triage

The orchestrator shows the triage report and uses the `question` tool. The **AI recommends,
the human decides** — the pipeline never auto-stops on `NO_ACTION`.

Options presented:
- **"Proceed to spec"** — run the PM Agent, passing the triage findings + approved approach.
- **"No action — stop pipeline"** — end gracefully; nothing further is generated.
- **"Redirect approach"** — user provides a different direction; the PM Agent is run with
  that direction as authoritative.

The default recommendation shown to the user reflects the triage verdict, but the human is
always free to override it in either direction.

When the verdict is **`NEEDS_INPUT`**, the triage report includes a "Questions for the
reporter" section — a clean, reporter-facing numbered list of the specific information
needed. The orchestrator presents these to the user with three options:

- **"Answer here"** — the user provides the answers directly; they feed forward into the
  PM Agent. The pipeline does **not** loop back into Triage.
- **"Post questions to Jira"** — the orchestrator composes a Jira comment from the
  questions, shows the user the **exact comment text**, and posts it only on explicit
  approval (strict: a vague "go ahead" is not enough). On success the pipeline pauses
  pending a reporter reply. On 401/403 (token lacks write permission), it falls back to
  "Answer here" with a clear message.
- **"Stop pipeline"** — end gracefully.

The comment is posted via `POST /rest/api/3/issue/{KEY}/comment` using the same
`ATLASSIAN_AUTH_BASIC` token used for reads. Only the reporter-facing questions are
posted — never internal triage reasoning, root-cause analysis, or code references. A
trailing attribution line identifies it as AI-generated (`— posted by SDD triage
assistant`).

### 3.4 Feeding Triage findings into the PM Agent

To avoid redundant investigation, when the pipeline proceeds, the orchestrator passes the
triage report **and** the human-approved approach into the PM Agent's prompt as
authoritative input. The PM Agent:
- Treats the approved approach as the bounded scope for the spec (rather than the raw
  ticket text).
- Uses the triage's root-cause findings so it doesn't have to re-run the deep git-blame /
  codebase dig from scratch; its git-history step is trimmed to a lighter confirmation.

### 3.5 Context isolation preserved

Triage runs with a fresh context. Only its structured report (and the approved approach)
flows forward — its internal scratch reasoning does not. This keeps the pipeline's
isolation guarantees intact.

## 4. Implementation plan

1. **Create `.opencode/skills/sdd/phases/triage.md`** — a new phase file defining the
   Triage agent: mandate, Jira API access notes (mirroring `pm-agent.md`), investigation
   steps, premise-challenge guidance, the `PROCEED` / `NO_ACTION` / `NEEDS_INPUT` verdict
   model, and the structured triage-report output format. The report is persisted to
   `docs/<KEY>-triage.md`; final message = the relative path to that report.

2. **Update `.opencode/skills/sdd/SKILL.md`:**
   - Update the pipeline overview line (top of file) to include the Triage phase.
   - Insert a new **Phase 0 — Triage & Root-Cause Investigation** section before the current
     Phase 1, describing how to spawn the triage `task`, how to display the report, the
     human gate (`question` tool) with the three options, and the branch handling
     (proceed / stop / redirect).
   - Update **Phase 1 (PM Agent)** to note that the triage report + approved approach are
     passed into the PM prompt as authoritative input.

3. **Update `.opencode/skills/sdd/phases/pm-agent.md`:**
   - Add an input note that the PM Agent receives an approved approach + triage findings,
     which are authoritative and bound the spec's scope.
   - Trim the git-blame step (step 5) to a lighter confirmation that leverages the triage
     findings rather than repeating the full investigation.

## 5. Acceptance criteria

- [ ] A new `phases/triage.md` file exists and defines an isolated triage agent whose
      mandate is root-cause finding, not spec production.
- [ ] The triage agent produces a verdict of `PROCEED`, `NO_ACTION`, or `NEEDS_INPUT` with a
      structured report including root-cause assessment, ticket-premise check, approaches
      considered (incl. "no code change"), and a recommendation.
- [ ] The triage report is persisted to `docs/<KEY>-triage.md` and the agent returns its path.
- [ ] On `NEEDS_INPUT`, the orchestrator offers three options: answer here, post questions
      to Jira, or stop.
- [ ] When "Post questions to Jira" is chosen, the exact comment text is shown and posted
      only on explicit user approval (strict — each text must be confirmed individually).
- [ ] The posted comment contains only reporter-facing questions (no internal reasoning)
      and includes an AI-attribution line.
- [ ] On 401/403 from the Jira API, the orchestrator falls back gracefully to "Answer here"
      with a clear message about token permissions.
- [ ] On successful post, the pipeline pauses with a message to re-run when the reporter
      replies.
- [ ] The human answer (from "Answer here") feeds forward into the PM Agent; the pipeline
      does not loop back into Triage.
- [ ] Triage emits `NEEDS_INPUT` **before** the deep investigation when the symptom maps
      to ≥2 divergent, unresolvable root causes and the ticket lacks reproduction detail
      to disambiguate (early sufficiency check / divergence test).
- [ ] Conversely, triage still performs the full deep investigation when a single plausible
      root cause exists, even if the ticket is terse (counter-guardrail against laziness).
- [ ] `SKILL.md` runs Triage as Phase 0 before the PM Agent, shows the report, and gates on
      a human decision with options to proceed / stop / redirect.
- [ ] The pipeline never auto-stops on `NO_ACTION`; the human always confirms.
- [ ] When proceeding, the triage report + approved approach are passed into the PM Agent
      prompt, and the PM Agent's spec is bounded by the approved approach.
- [ ] The PM Agent no longer duplicates the full git-blame investigation when triage
      findings are supplied.
- [ ] Context isolation is preserved (only the structured report + approved approach flow
      forward).

## 6. Out of scope

- Changes to the Coding, Code Review, Test Generation, Test Review, or Commit phases.
- Automating the `NO_ACTION` decision without human confirmation.
- Jira writes beyond comment posting (status transitions, field edits, issue creation).
- Building a machine-readable verdict schema beyond the human-readable structured report.

## 7. Resolved decisions

| Question | Decision |
|----------|----------|
| On `NEEDS_INPUT`, loop back into Triage or feed forward? | **Feed forward.** The human answer + triage findings go straight into the PM Agent; no loop back into Triage. |
| Persist the triage report or keep it ephemeral? | **Persist** as `docs/<KEY>-triage.md` — same flat `docs/` location as the spec, no subfolder. Critical for the `NO_ACTION` case, where it is the only artifact recording *why* no change was made. |
