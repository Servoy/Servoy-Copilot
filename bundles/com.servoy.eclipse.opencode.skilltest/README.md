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

## The flow: session → export → baseline

1. **Record a session.** In the Servoy AI chat, run the prompt you want to
   capture against a known solution and let the AI complete the task.
2. **Export it.** Use the **"Record baseline…"** toolbar action in the Servoy AI
   view (or `SkillTestRunner.recordBaseline(...)`), entering the baseline id and
   prompt. It produces the `export.json` — the full session JSON: prompt,
   MCP/tool calls with args and output, subagents, timings.
3. **A baseline folder is written** with that `export.json` plus a starter
   `baseline.json`.
4. **Configure `baseline.json`** (below) to pin the starting solution and choose
   how success is judged.
5. **Run** from the Servoy AI Skill Tests view and read the result.

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

Three mechanisms; outcome checks and JSUnit are independent and may be combined.

- **Outcome checks — `expect.persists`.** Assert the model produced the right
  artifacts (a form with a given `dataSource`/`useCssPosition`, components bound
  to specific dataproviders, a scope method, …). When present, these are
  authoritative and the transcript comparison is skipped. **Prefer this** — it is
  robust to the AI accomplishing the task differently each run.
- **Transcript comparison.** When no `expect.persists` is declared, the replay's
  tool calls are diffed against the golden tool calls recorded in `export.json`,
  tuned by the `compare` block (`ordered`, `strict`, `forbidden`, `argOverrides`).
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
