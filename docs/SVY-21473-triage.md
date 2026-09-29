# Triage Report — SVY-21473

**Verdict:** PROCEED

Issue: *MCP: a command console — evaluate an expression against the running solution*
(New Feature, Critical, fixVersion 2026.12). No attachments, comments, or linked issues.

## Reported problem

An AI agent working in a Servoy solution can read every file and compile it, but it
cannot make the solution *do* anything and observe the result. The write→compile→hand-off
loop never observes a value. The ticket cites hackathon telemetry (only 3 of 110
code-writing Developer dispatches across 416 sessions ran any `servoy-test_*` tool at all)
and a user quote — "I don't get any values for the order, but if I run the engine
standalone I get the correct results" — where the agent had clean compile markers and still
could not tell which run was wrong, because it could not run either.

The ticket surveys the adjacent tools and shows each sits next to the gap without filling it:
- `executeSQL` (in `servoy-dev`) — database rows only; no solution code, scopes, or foundsets.
- `runJsUnitTests` / `runTestMethod` (in `servoy-test`) — needs a committed test file, takes
  minutes per run, and returns pass/fail verdicts, not values.
- `getCompilationErrors` — static; a correct-but-wrong function has clean markers.
- `getConsoleOutput` — passive; you cannot make it print.

**Proposed solution:** one new MCP tool — `evaluate(expression, solutionName?, timeoutSeconds?=15)` —
that runs a JavaScript expression in the active solution's runtime and returns `{ value, console, error }`.
It is addressed with Servoy's normal top-level scopes (`scopes.<s>.<fn>()`, `forms.<f>.<fn>()`,
`forms.<f>.foundset`, `globals.x`, `databaseManager.*`), allows multi-statement input (value of the
last expression is returned), has **no** "current form" parameter, and must fail with a rewrite hint
when a bare `foundset` / `controller` / `currentcontroller` / `elements` is used.

## Root-cause assessment

This is a **capability gap, not a defect** — there is no bug to locate. The investigation
confirms the gap is real and that no existing code already fills it:

- The MCP server bundle (`com.servoy.eclipse.developer.mcp`) exposes tools grouped into
  `@McpServer`-named servers: `servoy-dev` (`ServoyDevServer` — holds `getTarget` and
  `executeSQL`), `servoy-test` (`ServoyTestingServer` — `checkNGClientStatus`,
  `runJsUnitTests`, `runTestMethod`), `servoy-coder`, `servoy-ide`, `servoy-context`, etc.
  **None** of them evaluates arbitrary solution JavaScript and returns the value.
- The closest existing runtime path is `JSUnitRunnerService.runForTarget(...)`, which does
  `new RunJSUnitHandler().findSmartClientTestLaunchConfiguration(target)` then
  `config.launch(RUN_MODE)` and **terminates the launch afterwards**. That is a fresh
  SmartClient JSUnit launch per call — a cold start, which matches the ticket's "minutes per
  run" — not a persistent, reusable live client an expression could be injected into.
- A grep for debug-client plumbing (`IDebugJ2DBClient`, `IDebugClientHandler`,
  `getScriptEngine`, `executeFunction`, `DebugJ2DBClient`, …) across the whole repo returns
  **no matches**. The bundle has no existing hook into a live debug client's script engine.

So the tool genuinely needs new plumbing: reach the developer's *already-running* debug
client (the platform's debug client infrastructure, outside this bundle today), obtain its
Rhino script engine / execution context, evaluate the expression on the correct (Servoy
event) thread, capture `application.output`/`Debug` output, and marshal the result back.
That plumbing does not exist yet — the feature is warranted.

## Ticket premise check

- **Is the problem in this project's code?** Yes — it is a missing tool in the actively
  developed `com.servoy.eclipse.developer.mcp` bundle.
- **Is the proposed approach the right one?** Broadly yes. A single `evaluate` tool that runs
  in the live debug client is the correct shape, and the ticket's design constraints are sound
  and specific (mutating → same approval treatment as `executeSQL`; runs only in the
  developer's own debug client, never a deployed server or a silently-started client; named
  actionable message instead of a timeout when no client is running; hard size cap on `value`;
  foundset described not dumped; Servoy-relative stack frames not a Java/Rhino trace; the tool
  description must state it is **not** a substitute for a JSUnit test). These belong in the spec
  verbatim.
- **Naming caveat (for the spec, not a blocker):** the ticket writes `servoy-model_evaluate`,
  but there is **no `servoy-model` server** in the codebase. The tools it references live in
  `servoy-dev` (`getTarget`, `executeSQL`) and `servoy-test` (`checkNGClientStatus`). The
  implementer must place `evaluate` on a real server — most naturally `servoy-dev`, next to
  `getTarget`/`executeSQL`, which it depends on for the active target and shares mutation risk
  with. The spec should settle the final server + tool name.
- **Simpler alternative considered?** The main open question is *which* runtime to evaluate in
  (see approaches). The single-tool idea itself is already minimal.

## Approaches considered

1. **Evaluate in the live developer debug client (the ticket's approach).**
   Locate the running debug J2DB/NG client for the active solution via the platform's debug
   client infrastructure, run the expression on the Servoy script thread with top-level scopes
   in place, capture console output and errors, and return `{ value, console, error }`.
   - *Pros:* exactly the "make it do something and look at the value" loop the ticket needs;
     reuses real solution state (loaded scopes, globals, open foundsets); matches the user's
     mental model; fast (no cold start) when a client is already running.
   - *Cons:* new dependency on debug-client internals not currently referenced by the bundle;
     threading/serialization and output-capture are non-trivial; must guard the "no client
     running" case cleanly (named action, not a hang) and the security surface (arbitrary
     mutating code — needs `executeSQL`-level approval).

2. **Launch a short-lived evaluation client per call (reuse the JSUnit launch pattern).**
   Wrap the expression in a generated method and run it through a fresh launch the way
   `JSUnitRunnerService` does today.
   - *Pros:* reuses an existing, proven launch path; no live-client dependency.
   - *Cons:* re-introduces the very cold-start cost the ticket calls out ("minutes per run");
     a fresh client has none of the live session state the user wants to inspect; explicitly
     *not* what the ticket asks for. Weak fit.

3. **No code change.**
   - *Pros:* zero risk; the security surface of arbitrary mutating evaluation is avoided.
   - *Cons:* leaves the documented, quantified capability gap unaddressed; the agent still
     cannot observe a single runtime value; contradicts the telemetry and the user evidence in
     the ticket. Not defensible for a Critical feature with a concrete design.

## Recommendation

**PROCEED with Approach 1** — implement a single `evaluate` MCP tool that runs the expression
in the developer's already-running debug client, returning `{ value, console, error }`, and
carry the ticket's constraints into the spec as hard requirements:

- Runs only in the developer's own debug client for the active target; never a deployed server,
  never a silently-started client. When no client is running, return a **named, actionable**
  message (as `checkNGClientStatus` does), never a timeout.
- Treated as **mutating** — same approval/consent treatment as `executeSQL`, not the read-only
  tools.
- `value` gets a hard size cap; a `JSRecord`/`JSFoundSet`/`JSDataSet` is *described*
  (datasource, size, first N rows), never dumped whole.
- Errors return the Servoy stack with solution-relative `<module>/<scope>.js:<line>` frames,
  not the Rhino/Java trace.
- Bare `foundset` / `controller` / `currentcontroller` / `elements` fail with the qualified
  rewrite in the message (e.g. use `forms.<formName>.foundset`). No "current form" parameter.
- The tool's own description states it is **not** a substitute for a JSUnit test — an
  evaluation leaves no artifact and proves nothing to the next run.

Spec-phase decisions to settle: the final server + tool name (recommend `servoy-dev` /
`evaluate`), the exact debug-client API used to obtain the script engine and run on the
correct thread, and the mechanism for capturing `application.output`/`Debug` for that one call.

## Git history findings

- Recent history on `ServoyDevServer.java` (`git log`) shows a steady cadence of *added* MCP
  tools — `renameFile` (SVY-21179), `createTable`/`createForm` column params (SVY-21281),
  `@ToolParam` fixes (SVY-21339), menu manager (SVY-21114). The pattern is additive tool growth
  in this bundle, so adding `evaluate` fits the established direction; a "fix" here would not
  revert any prior intentional decision.
- `executeSQL` and `getTarget` are stable, self-contained `@Tool` methods on `ServoyDevServer`
  — good structural precedents for where and how `evaluate` should be declared.
- No existing debug-client evaluation code was found to blame or extend; this is net-new
  plumbing, so there is no prior spec/decision in history that constrains the approach.
