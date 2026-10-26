# Servoy AI Skill Tests (internal)

Internal tooling for recording, replaying and verifying Servoy AI skill-test
baselines (SVY-21366). It is **not** part of the customer Servoy AI Copilot
distribution.

A baseline is a **recorded AI session, replayed and verified**: you run a prompt
against the AI in a known solution, export that session, and that export becomes
the baseline. The runner later replays the baseline's prompt against the real
LLM + skills and checks the result against what was recorded (and against any
extra assertions you add).

---

## What a baseline is made of

Baselines appear in the **Servoy AI Skill Tests** view and live on disk at
`<workspace>/servoy_ai_skilltests/baselines/<id>/`. Each baseline folder holds:

- **`export.json`** — the recorded session. **This is the baseline, and the
  starting point for everything.** It carries the prompt that was sent and the
  golden sequence of MCP/tool calls. Every baseline has one; it is not optional.
- **`baseline.json`** — the sidecar config layered on top of the export: id,
  starting-solution source, optional prompt override, optional outcome checks,
  optional JSUnit verification, and run controls.
- **`verify/`** — JSUnit test scripts owned by the baseline (present only when
  JSUnit verification is configured).

---

## The flow: session → export → import (authored baseline)

1. **Run a session.** In the Servoy AI chat, run the prompt you want to capture
   against a known solution and let the AI complete the task.
2. **Export it.** Export the session from the Servoy AI (opencode) view — it owns
   the session. That produces an `export.json`: the full session JSON (prompt,
   MCP/tool calls with args and output, subagents, timings).
3. **Import it** with the **"Import export…"** action in the Servoy AI Skill
   Tests view. This opens the **import wizard**:
   - **Details** — pick the export (+ optional UI mockup), give it a **title**
     (the baseline folder id is generated from the title and made unique — you
     don't enter it), and set how its **solution** is set up: start from an
     **Empty** solution, a **Git** repository (URL + optional ref), or a
     **Folder** on disk, plus the number of **runs** (replay trials).
   - **Analyze** — an **AI baseline-authoring agent** reads the session and the
     artifacts the run built and **proposes the verification checks** that fit
     (expected persists, acceptance criteria (LLM judge), and/or a JSUnit script),
     with a short rationale. The analysis runs on a background job with a progress
     bar; if it is slow you can press **"Run in background…"** to close the wizard
     and keep working — it reopens here, at the review step, when the analysis
     finishes.
   - **Configure** — tick/untick the proposed checks (you can enable one the agent
     didn't propose, or drop one it did); each ticked check gets an editor step.
4. **Review, edit and approve.** You are shown an editor only for the checks you
   ticked; edit each as needed. **Finish** writes the baseline folder
   (`export.json` + `baseline.json`, plus any generated `verify/` script).
5. **Run** from the Servoy AI Skill Tests view and read the result.

> **"Record baseline…" is deprecated.** Recording belongs to the Servoy AI
> (opencode) view, which owns the session; export there and **import** here. The
> Record action may linger for backwards compatibility but is not the path for
> new baselines.

---

## Configuring `baseline.json`

Everything here is layered on top of the recorded `export.json`.

### 1. Starting solution — `precondition.source`

The most consequential choice; it drives setup.

- **`empty`** — the runner creates a fresh solution via the MCP `createSolution`
  tool, auto-provisioning the default web packages (bootstrapcomponents, 12grid,
  fontawesome, servoyextra). For tasks that build from scratch.
- **`git`** — clones a repo (via JGit, using the IDE's stored git credentials),
  imports the named solution, and provisions the default NG packages into it so
  bound components resolve. For tasks needing a pre-existing solution with
  forms/data.
- **`folder`** — imports a solution from a separate pristine folder on disk
  (never the live workspace).

For `git`/`folder` also set `precondition.solution` to the exact project name to
activate (e.g. `testcase_forms`). A wrong name is the classic setup failure.

```json
"precondition": {
  "solution": "testcase_forms",
  "source": { "type": "git", "location": "https://github.com/Servoy/servoy_test", "ref": "master" }
}
```

### 2. Prompt

By default the prompt replayed is the one recorded in `export.json`. Set
`promptOverride` only when you want to replay different text than was captured.

### 3. How success is judged

Four mechanisms. The first two are the primary verdict (mutually exclusive: a
baseline uses one or the other); JSUnit and the acceptance-criteria judge are
additive tiers that can be layered on top and combined.

- **Outcome checks — `expect.persists`.** Assert the model produced the right
  artifacts (a form with a given `dataSource`/`useCssPosition`, components bound
  to specific dataproviders, a scope method, …). When present, these are
  authoritative and the transcript comparison is skipped. **Prefer this** — it is
  robust to the AI accomplishing the task differently each run.
- **Transcript comparison.** When no `expect.persists` is declared, the replay's
  tool calls are diffed against the golden tool calls recorded in `export.json`,
  tuned by the `compare` block (`ordered`, `strict`, `forbidden`, `argOverrides`).
- **Acceptance criteria (LLM judge) — `verify.acceptanceCriteria`.** The most
  robust check for LLM-built UI, where structural persist-tree equality is too
  brittle (valid runs legitimately produce different layouts). A **separate**
  LLM — the "judge", forced to differ from the builder model so a form's author
  is never its own judge — reads the built form's artifacts for itself (the
  `.frm`, the form `.js` incl. `onShow`, the form and solution `.less`/`css`,
  and the UI mockup; it may call `servoy-form_getFormLayout` with
  `screenshot=true` for visual fidelity) and grades each natural-language
  criterion pass/fail, the way the SDD code-review agent signs off. Nothing is
  serialized into the prompt — the judge inspects the real files. The baseline
  passes only when every **required** criterion passes.
  - **Judge-authoritative baselines.** When a baseline declares
    `verify.acceptanceCriteria` but **no** `expect.persists`, the structural /
    transcript gate is skipped entirely and the judge is the **sole** verdict.
    This is how the brittle structural scenarios (e.g. `order_tabs`) are made
    robust: drop the `expect.persists` `form` block and let the judge decide.
    Keep a minimal form-exists assertion only if you still want a structural
    sanity check.
  - Write criteria as outcome statements, not implementation prescriptions —
    "the tab headers render their HTML markup as styled elements (any enabling
    mechanism counts)" rather than "an onShow handler sets property X". A
    criterion that mandates a specific mechanism fails a valid run that reached
    the same result another way.
  ```jsonc
  "verify": {
    "acceptanceCriteria": {
      "criteria": [
        "A form named order_tabs exists and is fully responsive.",
        "There is a tab panel with exactly five tabs in order: Overview, Items, Shipping, Notes, History.",
        "The History tab is disabled; the other four are enabled."
      ],
      "judgeModel": "kiro/claude-sonnet-4-6", // optional; default picks a model != the builder
      "required": true                         // false = report but don't fail the baseline
    }
  }
  ```
- **JSUnit verification — `verify.jsunit`.** Configured through the **"Edit JSUnit
  verification"** dialog in the view. Two options that can coexist:
  - **Inject a baseline-owned script** — author a `test_...` script; it is stored
    under `verify/scopes/<name>.js` and injected into the active solution at run
    time as a global scope. Keep it **name-tolerant**: query `solutionModel`
    rather than assuming the element names the AI chose.
  - **Run existing solution test files** — for a non-empty (git/folder) solution,
    tick "Use test files that already exist in the solution" and check the
    `test_`-bearing `.js` files to run. Stored as solution-relative paths, run
    without injection.

  Both lists coexist and are de-duplicated by scope; the baseline passes only if
  every scope passes.

### 4. Run controls

- `maxAttempts` — the runner replays this many trials and reports the
  distribution (the AI is non-deterministic; look at the spread). Overall PASS
  requires every trial to match.
- `verify.jsunit.timeoutSeconds` — per-test JSUnit timeout.
- `verify.jsunit.warmupTimeoutSeconds` (default 300) — covers the one-time
  Titanium/NG bundle build + SmartClient boot on a fresh solution, so cold-start
  time is not charged against the test timeout; the verifier launches with the
  larger of the two.
- `verify.jsunit.required` — if false, a JSUnit failure is reported but does not
  fail the baseline.
- `verify.acceptanceCriteria.required` — if false, a judge FAIL is reported but
  does not fail the baseline (advisory).
- `verify.acceptanceCriteria.judgeModel` — optional; when blank the runner picks
  a default and always forces it away from the builder model.
- `active` — whether "Run all active" includes this baseline.

---

## Worked example

`create-customerdetail-form-with-data-bindings`:

- Recorded `export.json` capturing the create-form session (the baseline's origin
  and golden).
- `precondition.source`: `git` → `https://github.com/Servoy/servoy_test` @
  `master`, `solution: testcase_forms`.
- `promptOverride`: create a CSS-position form `customerDetail` bound to
  `db:/example_data/customers` with two text fields bound to `companyname` and
  `contactname`.
- `expect.persists`: form `customerDetail` (dataSource + useCssPosition) with two
  `bootstrapcomponents-textbox` children bound to those dataproviders.
- `verify.jsunit`: injects `skilltest_verify.js` (name-tolerant checks via
  `solutionModel`), scope `skilltest_verify`, `required: true`.

---

## How the import agent chooses the checks

When you **Import export…**, you don't write the checks by hand — an AI
baseline-authoring agent does the first draft:

1. It runs as its own embedded-opencode session (model-independent of the
   builder), reads the export + the artifacts the run built + the mockup, and
   follows the `skilltest-authoring` skill, which encodes **when each check
   fits**: a deterministic artifact (valuelist, relation, scope method,
   datasource binding) → `expect.persists`; visual/layout fidelity to a mockup →
   `verify.acceptanceCriteria`; behaviour that must run → `verify.jsunit`.
2. It answers with a plan (any combination of the three) plus a short rationale.
3. On the wizard's **Analyze** step the plan is shown as **checkboxes** (pre-ticked
   as the agent proposed, but yours to change) with the rationale as a readable
   bulleted list. Each ticked check gets an editor step; you edit or exclude it,
   then **Finish** writes `baseline.json` (and any generated JSUnit script to
   `verify/scopes/<name>.js`) with the solution/source/runs you set on the details
   step.

If the opencode server isn't up or the `skilltest-authoring` skill isn't present,
the agent degrades to a persists-only draft inferred offline — still editable,
just literal. The degradation is logged to the Servoy AI Console.

The proposed `expect.persists` is also passed through the loosener
(`skilltest-expectations` skill) so agent-chosen styleClass/text leaves become
anchored SQL-`LIKE` patterns; a component's `typeName` is always kept literal
(a deterministic guard restores it even if the model loosens it).

---

## Running

### In Servoy Developer (interactive)

Open Servoy Developer with a solution active and the Servoy AI view working (so
the embedded opencode server + skills are configured — needs `GENAI_API_KEY` +
`SERVOY_SKILLS_ZIP`, set by the product when Servoy AI is enabled). Then use
**"Run selected"** / **"Run all active"** in the Servoy AI Skill Tests view;
results go to the Servoy AI Console and the view's distribution.

### On CI (headless)

The real-Jenkins pipeline is `Jenkinsfile.skilltest` at the repo root. One job,
driven by two `pom.xml` profiles:

- `-Pbuild_product` — materialize a runnable Servoy install from the LTS tarball,
  overlay the freshly built AI bundles (rewriting `bundles.info` so the pinned
  install loads them), and seed the committed baselines into the run workspace.
- `-Prun_test` — launch that install headless (under Xvfb) to run the baselines
  and publish the JUnit report.

Invoked together: `mvn ... clean install -Pbuild_product,run_test`. The baselines
seeded come from `${skilltest.baselines.dir}`.

---

## Gotchas (learned the hard way)

- **git-source baselines need working git credentials on the runner.** JGit uses
  the IDE's stored credentials; on headless CI the agent must be able to clone the
  repo, or setup fails before any prompt is sent.
- **A non-empty solution needs its web packages**, or components become "Error
  Bean — Specification not found" and outcome checks report them missing. The
  runner auto-provisions the defaults; a component from another package needs that
  package present.
- **Editing the source in the dialog rewrites `baseline.json`**, which carries its
  own `precondition`. Copying a `baseline.json` between workspaces also copies its
  source type — that is how a git baseline can silently become empty-source.
- **A timed-out JSUnit run with zero executed tests is a FAIL**, not a vacuous
  "all 0 passed."
- **Inject and existing-files JSUnit modes are independent** — turning on the
  existing-files picker does not wipe an injected script.
- **The judge must read the session EXPORT, not the message list.** In opencode
  v2 the `GET /session/:id/message` list returns message metadata with empty
  `parts`; the assistant's text lives under `content`, which only the export
  endpoint (`/api/experimental/session/:id/export`) inlines. The judge reads its
  verdict from the export (`OpencodeHttpClient.getExport`) — reading the message
  list instead is what caused an early "judge returned no text" even though the
  judge had answered.
- **The judge model is always forced away from the builder model**, even when a
  `judgeModel` is configured that happens to equal it — a form's author must not
  grade its own output.
