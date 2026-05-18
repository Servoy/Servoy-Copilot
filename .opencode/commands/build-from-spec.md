---
description: Runs the full spec-driven pipeline for a Servoy Developer MCP endpoint addition — researcher → architect → validator → architect correction → developer → endpoint-tester → reviewer → skill-builder → skill-auditor → documenter
---

You are orchestrating the full spec-driven development pipeline for a given project-action.

**Usage:** `/build-from-spec $ARGUMENTS`

Example: `/build-from-spec developer-mcp-add-servoy-context`

Do not attempt to do any of this work yourself. Delegate every step to the appropriate subagent.
Do not skip any step. Do not proceed to the next step until the current one completes successfully.
After each step, verify the expected output file exists and is non-empty before continuing.

The `project-action` argument determines all file paths:
- Spec: `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/specs/$ARGUMENTS/spec.md`
- All artefacts: `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/sessions/$ARGUMENTS/`

---

## Pre-flight check

Before starting, verify:
1. `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/specs/$ARGUMENTS/spec.md` exists and is non-empty
   - If missing: stop and tell the user to create the spec file first
2. Create the artefacts directory if it does not exist: `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/sessions/$ARGUMENTS/`

---

## Step 1 — Research (researcher)

**Skip if:** `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/sessions/$ARGUMENTS/research-findings.md` already exists and is non-empty.

Run the `researcher` agent:
> Read the spec at `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/specs/$ARGUMENTS/spec.md` and produce the complete findings report for project-action `$ARGUMENTS`.

Verify: `research-findings.md` exists and is non-empty. Retry once on failure. Stop if still missing.

---

## Step 2 — Architecture (architect)

**Skip if:** `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/sessions/$ARGUMENTS/migration-plan.md` already exists and is non-empty.

Run the `architect` agent:
> Read the spec and research findings for project-action `$ARGUMENTS` and produce the complete migration plan.

Verify: `migration-plan.md` exists and is non-empty. Retry once on failure. Stop if still missing.

---

## Step 3 — Validation (architect-validator)

**Skip if:** `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/sessions/$ARGUMENTS/architect-observations.md` already exists and is non-empty.

Run the `architect-validator` agent:
> Read the spec, research findings, and migration plan for project-action `$ARGUMENTS`. Validate the plan and produce your observations report.

Verify: `architect-observations.md` exists.

---

## Step 4 — Architect Correction (architect)

Read `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/sessions/$ARGUMENTS/architect-observations.md`.

**Skip if:** `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/sessions/$ARGUMENTS/architect-correction-done.md` exists and is non-empty.

Also skip (without writing the sentinel) if the Validation Summary in `architect-observations.md` says `PASS` with no Critical Issues listed — in that case write a one-line `architect-correction-done.md` saying "No correction needed — validator passed with no critical issues" and continue.

Otherwise run the `architect` agent:
> Read the migration plan and the validator's observations in `architect-observations.md` for project-action `$ARGUMENTS`. Update `migration-plan.md` to address all Critical Issues and Minor Issues. Do not change sections marked as Confirmed Correct.

After the architect completes, write a one-line sentinel file:
```
/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/sessions/$ARGUMENTS/architect-correction-done.md
```
Content: `Correction applied — <date>.`

Verify: `architect-correction-done.md` exists.

---

## Step 5 — Implementation (developer)

**Skip if:** `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/sessions/$ARGUMENTS/dev-progress.md` exists AND contains no remaining work and no blockers.

Check if `dev-progress.md` already exists (partial progress from a previous run).

If `dev-progress.md` exists, run the `developer` agent:
> Resume from where you left off for project-action `$ARGUMENTS`. Read `dev-progress.md` to understand current state. Continue from the first incomplete item until all production files compile clean.

If `dev-progress.md` does not exist, run the `developer` agent:
> Implement the full plan for project-action `$ARGUMENTS`. Follow the migration plan exactly. Write production code only — no test classes. Do not stop until all production files compile clean.

Verify: `dev-progress.md` exists. Retry up to 2 times if production code is not compiling clean. Stop and report blockers if still not done after 2 retries.

---

## Step 6 — Endpoint Testing (endpoint-tester)

**Skip if:** `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/sessions/$ARGUMENTS/endpoint-test-progress.md` exists AND its Summary line says `Overall: PASS`.

**Before running:** The new MCP server endpoint is only registered after Servoy Developer restarts. Tell the user:
> "Step 5 (implementation) is complete. Please restart Servoy Developer now so the new `/svymcp/<endpoint-name>` endpoint is registered. Reply when Servoy is back up."
Wait for the user to confirm before running the endpoint-tester.

You can verify Servoy is up by running:
```
curl -s --max-time 5 -X POST http://localhost:8183/svymcp/time \
  -H "Authorization: Bearer bd6f7df6-2872-4e5c-9387-ae5fae62ca3c" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```
If this returns a tool list, Servoy Developer is up and the endpoint-tester can proceed.

Run the `endpoint-tester` agent:
> Run end-to-end endpoint tests for the new MCP endpoint introduced by project-action `$ARGUMENTS`. Initialise an MCP session, exercise every tool in the migration plan's Section 5, and run the file-format-guard refusal tests for every destructive tool. Record results in `endpoint-test-progress.md`.

Verify: `endpoint-test-progress.md` exists.

If the Summary says `BLOCKED` and the cause is a production bug, re-run the `developer` agent to fix it then re-run the `endpoint-tester`. Maximum 2 cycles. Stop and report if still BLOCKED.
If the Summary says `FAIL` for any tool, the reviewer will decide whether to block or accept with notes.

---

## Step 7 — Review (reviewer)

**Skip if:** `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/sessions/$ARGUMENTS/review-notes.md` exists and contains "APPROVED".

Run the `reviewer` agent:
> Review the completed implementation for project-action `$ARGUMENTS`. Validate against the spec's Acceptance Criteria and Constraints. Read `dev-progress.md`, `endpoint-test-progress.md`, and the actual implementation files in `com.servoy.eclipse.developer.mcp`. Produce review-notes.md and knowledge harvest notes.

Verify: `review-notes.md` exists.

- If `review-notes.md` contains `BLOCKED`: re-run the `developer` agent (max 2 retries) then re-run the `reviewer`. If still BLOCKED after 2 retries, stop and report the blockers to the user.
- If `APPROVED` or `APPROVED WITH NOTES`: continue.

---

## Step 8 — Skill Building (skill-builder)

**Skip if:** `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/sessions/$ARGUMENTS/skill-proposals.md` exists and is non-empty.

Run the `skill-builder` agent:
> Process all knowledge harvest notes for project-action `$ARGUMENTS`. Update existing skills surgically (project-local skills under Servoy-Copilot/.opencode/skills/) and propose new skill candidates.

Verify: `skill-proposals.md` exists.

---

## Step 9 — Skill Audit (skill-auditor)

**Skip if:** `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/sessions/$ARGUMENTS/skill-audit.md` exists and is non-empty.

Run the `skill-auditor` agent:
> Evaluate all skill proposals for project-action `$ARGUMENTS`. Approve or deny each. Write approved skills under Servoy-Copilot/.opencode/skills/ and update agent assignments in Servoy-Copilot/.opencode/agents/.

Verify: `skill-audit.md` exists.

---

## Step 10 — Documentation (documenter)

Run the `documenter` agent:
> Assemble final documentation for project-action `$ARGUMENTS`. Write the session record summarising what was built, which tools were registered, and any deferred items.

Verify: `session-record.md` exists.

---

## Step 11 — Final summary

Output a brief summary to the user:
- Project-action completed
- Endpoint added (/svymcp/<endpoint-name>)
- Number of tools registered
- Endpoint test results (pass/fail counts)
- Skills created or updated
- Any known limitations or deferred items
- Paths to key artefacts
