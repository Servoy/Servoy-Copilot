# Spec: SVY-21443 — MCP: execute code in a running Servoy client (servoy-debug)

## 1. Goal

Give an AI agent a first-class way to *run* solution JavaScript in a live, already-running
Servoy debug client and observe what happened. A new MCP tool executes either a **named
existing method** (`forms.myForm.myMethod(args)` / a global scope function) or an **arbitrary
ad-hoc script** the agent generates (exactly like the Servoy Command Console), against the
current debug-ready client, and returns **both** the return value / thrown error **and** the
`application.output` / console text produced during that run. This closes the current gap where
the agent can create, edit, analyse and JSUnit-test code but cannot trigger and inspect real
runtime behaviour. Paired with a Servoy skill / AGENTS update, it teaches the agent to debug the
way a developer does: add `application.output(...)` statements, run the code, and read the
console — plus execute code directly into the running client. The full step-through debugger
(breakpoints, variable inspection, step in/out/over) from the original ticket is explicitly
deferred to a follow-up.

## 2. Background

### 2.1 Why the scope is "run a script", not a full debugger

The ticket originally asked for a full debugger (breakpoints, step in/out/over, read variables,
per the Ron/Johan hackathon). The architect (Johan Compagner) then commented that we should
*first* just be able to **run a script** — either "execute `form.a.b()`" or "let the ai generate
a script and execute that (like in our command console)" — and monitor the console via
`application.output`, "then test a bit how this goes." The human owner confirmed this reduced
scope for this case: build the simple execute-in-running-client endpoint plus skill/AGENTS
updates now; keep the real step-through debugger as a follow-up. This spec is written for that
approved scope only.

### 2.2 The MCP tool-hosting pattern (where a new tool goes)

MCP tools live in the `com.servoy.eclipse.developer.mcp` bundle. Each server class is annotated
`@McpServer(name = "...")` and `@Creatable`, and each tool method is annotated
`@Tool(name, description, type)` with `@ToolParam(...)` on its arguments. Existing servers:

- `ServoyDevServer` (`@McpServer(name = "servoy-dev")`) — solution/target management,
  documentation, persist ops (`servers/ServoyDevServer.java`).
- `ServoyTestingServer` (`@McpServer(name = "servoy-test")`) — JSUnit runs, form preview,
  Cypress (`servers/ServoyTestingServer.java`).

The ticket names the endpoint `servoy-debug`. A new `ServoyDebugServer`
(`@McpServer(name = "servoy-debug")`) is the natural home, delegating to a
`RunningClientExecutionService` in `services/`. This mirrors how `ServoyTestingServer` delegates
to `JSUnitRunnerService`.

### 2.3 The runtime substrate already exists — the Command Console

The Servoy **Command Console** already does exactly the "execute an ad-hoc script in a live
client and get the return value" part. In `com.servoy.eclipse.debug` (servoy/master):

- `scriptingconsole/ScriptConsole.java` — `getGlobalScope()` resolves the active solution's
  global scope; `getScope(IDebugClient, create)` lazily creates a child scriptable
  (`____TEST_SCOPE____`) hanging off the client's global scope. It reads active clients from
  `ApplicationServerRegistry.get().getDebugClientHandler().getActiveDebugClients()`.
- `scriptingconsole/CommandHandler.java` — `handleCommand(String userInput)` runs the input on
  the client's own event thread via `state.invokeAndWait(...)`, then
  `Context.enter()` → `cx.evaluateString(scope, userInput, "internal_anon", 1, null)` →
  unwraps `Wrapper`, normalises `NOT_FOUND`/`Undefined` to `null`, and returns the value (or the
  caught exception) wrapped in a `ScriptResult`.

This is the reference implementation for the ad-hoc-script half. The endpoint reuses the same
mechanism headlessly (no SWT console UI).

### 2.4 Getting the running client

`DebugClientHandler` (servoy/master `servoy_debug`) already exposes the "current runtime client":

- `getDebugReadyClient()` — returns the active debug client (`debugJ2DBClient` /
  `getDebugHeadlessClient()` / `getDebugNGClient()` / custom) whose solution is loaded and whose
  `RemoteDebugScriptEngine.isConnected(0)` is true; `null` if none is running.
- `getActiveDebugClients()` — list of active debug clients (what the console lists).
- `executeMethod(ISupportChilds persist, String scopeName, String methodname)` — already runs a
  **named** global function (`getScriptEngine().getScopesScope().executeGlobalFunction(...)`) or
  a **form** method (`leaseFormPanel(...).executeFunction(...)`) on the debug-ready client, on
  the correct thread. This is the reference implementation for the named-method half — but note
  it is *fire-and-forget* (returns `void`, swallows exceptions to `Debug.log`), so the endpoint
  needs a variant that captures the return value and error.

Access from Eclipse code is via
`ApplicationServerRegistry.get().getDebugClientHandler()` (also surfaced through
`com.servoy.eclipse.core.Activator.getDefault().getDebugClientHandler()`).

### 2.5 How `application.output` / console text flows (what "capture output" means)

When solution code calls `application.output(msg)` in a debug client, it reaches the client's
`output(Object msg, int level)`. For `DebugNGClient` (and the J2DB/headless debug clients) that
routes to `DebugUtils.stdoutToDebugger(getScriptEngine(), msg)` (or `errorToDebugger` for
WARNING/ERROR/FATAL), which pushes the text over DBGP to the developer-side debugger — this is
what fills the Console view during a debug run. There is **no** existing per-call return of "the
output produced by this one execution". So the endpoint must capture output for the duration of
one run itself (see Design 3.4), rather than rely on an existing return channel.

### 2.6 Where the Servoy skill / AGENTS docs live

The agent's Servoy know-how ships as a **skills zip** (`SERVOY_SKILLS_ZIP`), extracted by
`com.servoy.eclipse.opencode/.../SkillsZipExtractor` into `~/.servoy/opencode/`; the same class
writes/updates `AGENTS.MD` in the active project root (with runtime Servoy/Postgres versions and
DB names). The skill content itself is **not** in this repo — it is authored in the source that
builds the skills zip. The documentation deliverable in this case is therefore: (a) add a
"debugging at runtime" section to that Servoy skill/AGENTS material describing the new tool and
the `application.output` workflow, and (b) make sure the tool's own `@Tool` description is
explicit enough that the agent discovers it. (See Open questions for the exact skill-source
location to edit.)

## 3. Design

### 3.1 New MCP server + service

Add `servers/ServoyDebugServer.java` (`@McpServer(name = "servoy-debug")`, `@Creatable`)
delegating to `services/RunningClientExecutionService.java` (`@Creatable`). Keep the server thin
(argument marshalling, error-to-string) and put the runtime logic in the service, matching the
`ServoyTestingServer` → `JSUnitRunnerService` split.

Register the new server wherever the existing `@McpServer` classes are discovered/registered
(the same mechanism that already picks up `servoy-dev` and `servoy-test`; confirm in the MCP
server factory / builtins and add `ServoyDebugServer` there if registration is not purely
annotation-driven).

### 3.2 Tool surface

One primary tool with a mode that covers both halves the architect described. Suggested shape
(final names/signatures may be tuned during implementation):

- `executeInRunningClient` — the core tool.
  - `script` (string, optional) — an ad-hoc JavaScript snippet to evaluate in the running
    client's global scope, exactly like the Command Console (e.g.
    `forms.myForm.myMethod(1,2); application.output('done')`). The agent can chain multiple
    statements ("trigger multiple things at the same time", per the architect).
  - `methodName` (string, optional) — a named method to invoke instead of / in addition to a
    raw script, e.g. `forms.customers.recalcTotals` or `scopes.globals.doThing`. Provided as a
    convenience over `script`; internally it may just be turned into a small script call, or
    routed through the `executeMethod`-style path (see 3.3).
  - `args` (string, optional) — JSON array of arguments for `methodName`.
  - `timeoutSeconds` (integer) — max time to wait for the run to finish.
  - At least one of `script` / `methodName` is required; if both are given, define a clear order
    (e.g. run `methodName(args)` after evaluating `script`, or reject the combination — decide in
    implementation and document in the tool description).

The `@Tool` description must state plainly: this runs code **in the currently running debug
client**, returns the result/error **and** the captured `application.output`/console text, and
requires a running client (point the agent at how to start one on failure).

### 3.3 Execution mechanics (reuse the Command Console + executeMethod paths)

- Resolve the target client with `getDebugClientHandler().getDebugReadyClient()`. If `null`,
  return a clear, actionable message ("No running Servoy client. Start a debug client
  (run the solution) and retry.") — do **not** launch one implicitly (launching is out of scope;
  see 3.6).
- **Ad-hoc `script`:** replicate `CommandHandler.handleCommand` headlessly — run on the client's
  thread via `client.invokeAndWait(...)`, `Context.enter()`,
  `cx.evaluateString(scope, script, "servoy-debug", 1, null)` where `scope` comes from
  `ScriptConsole.getScope(client, true)` (or an equivalent global-scope resolution copied into
  the service to avoid a UI dependency), unwrap `Wrapper`, normalise `NOT_FOUND`/`Undefined` to
  `null`, and capture any thrown exception as the error result. **Do not** depend on
  `com.servoy.eclipse.debug` UI classes from the MCP bundle if that would add an unwanted
  dependency — prefer replicating the small `getScope`/evaluate logic in the service (confirm
  the bundle dependency direction during implementation).
- **Named `methodName`:** either (a) synthesize a script string (`methodName(...args)`) and go
  through the same evaluate path (simplest, uniform capture), or (b) add a return-value-capturing
  variant of `DebugClientHandler.executeMethod(...)` in servoy/master. Prefer (a) unless a
  concrete need for (b) appears, to keep all changes inside the Copilot repo.
- Serialize the return value to a readable string (numbers/strings/booleans directly; objects via
  a safe `toString`/JSON best-effort; `null`/undefined as an explicit marker). Never throw out of
  the tool — always return a string result.

### 3.4 Capturing `application.output` / console for the run

The run must return the output produced *during that single execution*. Options, in order of
preference:

1. **Scope the capture around the run.** Before executing, install a temporary output sink on the
   target debug client (e.g. wrap/di­vert the client's `output(Object,int)` path, or attach a
   listener to the DBGP stdout/stderr channel that `DebugUtils.stdoutToDebugger` feeds) into a
   per-run `StringBuilder`; execute; then detach and return the buffered text alongside the
   result. This gives exact, run-scoped output.
2. **If no clean hook exists**, capture at the `DebugUtils.stdoutToDebugger` / debugger-console
   boundary for the window of the run (timestamped begin/end markers) and return the slice.

The implementation must pick the least invasive hook that yields run-scoped output and document
it. Capturing output is a hard requirement of this case (the architect's whole point), so if
option 1 requires a small, well-contained addition in servoy/master (`servoy_debug`) to expose an
output listener, that is acceptable — keep it minimal and behind a clear API.

### 3.5 Result format

Return a single markdown/string blob with two clearly labelled sections, e.g.:

```
**servoy-debug: executeInRunningClient**

Client: DebugNGClient (solution: mysolution)

Result:
<return value, or "Error: <message>\n<short stack>">

Console output:
<captured application.output / console text, or "(no output)">
```

Keep it compact and stable so the agent can parse it and so it reads well in the chat transcript.

### 3.6 Threading, safety, and non-goals

- Always execute on the client's own event thread (`invokeAndWait` / `invokeLater` + latch), as
  the Command Console and `executeMethod` do — never on the MCP request thread directly.
- Enforce `timeoutSeconds`; if the run does not complete, return a timeout message plus any
  partial captured output, and ensure the temporary output sink is always detached (finally
  block).
- No implicit client launch, no breakpoints, no stepping, no variable inspection — those are the
  deferred follow-up.

### 3.7 Skill / AGENTS documentation update

Update the Servoy skill / AGENTS material (the source that builds `SERVOY_SKILLS_ZIP`, surfaced
at runtime via `SkillsZipExtractor`) with a short "Debugging Servoy at runtime" section that
teaches the agent to:

- add `application.output(...)` statements to solution code to trace values, then run and read
  the console;
- use the new `servoy-debug` `executeInRunningClient` tool to run a named method
  (`forms.x.y()`) or an ad-hoc script in the **running** client and read back the result +
  console output;
- prefer this loop (output + execute-in-client) as the runtime-debugging technique for now, and
  note that a full step-debugger is a future capability.

### 3.8 Optional (nice-to-have): sample-solution debugging scenario

If time permits, add a tiny sample-solution walkthrough ("can you fix this bug I have") that has
a deliberately buggy method, and document the intended agent flow: reproduce by executing the
method in the running client, add `application.output` traces, re-run, read the console, fix.
Keep this strictly optional — drop it if it grows; it must not block the core endpoint.

## 4. Implementation plan

1. **New MCP server** — create
   `com.servoy.eclipse.developer.mcp/src/.../servers/ServoyDebugServer.java`
   (`@McpServer(name = "servoy-debug")`, `@Creatable`) with a `ping` and the
   `executeInRunningClient` `@Tool`, delegating to the service.
2. **New service** — create
   `com.servoy.eclipse.developer.mcp/src/.../services/RunningClientExecutionService.java`
   (`@Creatable`) implementing: resolve debug-ready client, evaluate ad-hoc script (Command
   Console logic replicated), invoke named method, run-scoped output capture, result formatting,
   timeout handling.
3. **Output capture hook** — implement the run-scoped `application.output`/console capture
   (Design 3.4). If a minimal listener API must be added in servoy/master `servoy_debug`
   (`DebugClientHandler` / debug client), keep it small and well-named; otherwise capture entirely
   inside the service.
4. **Register the server** — ensure `ServoyDebugServer` is picked up by the MCP server
   registration path (same place `servoy-dev`/`servoy-test` are wired; add it if registration is
   not annotation-only). Add the class to the bundle if a manifest/package export needs it.
5. **Skill / AGENTS docs** — add the "Debugging Servoy at runtime" section to the Servoy
   skill/AGENTS source that builds `SERVOY_SKILLS_ZIP` (see Open questions for the exact repo/path),
   covering `application.output` tracing + the new endpoint.
6. **Tests** — add plain JUnit unit tests in `com.servoy.eclipse.developer.mcp.tests`
   (`src/test/java/`) for the pure logic (result formatting, arg/JSON parsing, "no running client"
   message, timeout message, `NOT_FOUND`/`Undefined`→null normalisation). Add a PDE plugin
   integration test (`c.s.e.d.mcp.integration`, run via `eclipse-pde_runJUnitPluginTests`) that
   starts a debug client, runs a script and a named method, and asserts both the return value and
   the captured `application.output` come back. Register unit tests in the correct aggregate suite
   (`AllDeveloperMcpTests` for JUnit 4, `AllDeveloperMcpJupiterUnitTests` for Jupiter — prefer
   Jupiter for new tests) and the integration test in `AllDeveloperMcpIntegrationTests`.
7. **Optional sample-solution scenario** (Design 3.8) — only if it stays small.

## 5. Acceptance criteria

- [ ] A `servoy-debug` MCP server exists with an `executeInRunningClient` tool discoverable by
      the agent.
- [ ] Executing an **ad-hoc script** in the running client returns its evaluated value (Command
      Console parity), with `Wrapper` unwrapped and `NOT_FOUND`/`Undefined` reported as null.
- [ ] Executing a **named method** (`forms.x.y(args)` and a global-scope function) in the running
      client returns its result; `args` accepts a JSON array.
- [ ] The tool returns the `application.output` / console text produced **during that run**,
      clearly separated from the return value.
- [ ] A thrown JS error is returned as a readable error (message + short stack), not as a tool
      crash.
- [ ] With no running client, the tool returns a clear, actionable message and does not launch a
      client implicitly.
- [ ] Runs honour `timeoutSeconds`, returning a timeout message plus any partial output, and the
      output sink is always detached afterwards.
- [ ] All execution happens on the client's own event thread.
- [ ] The Servoy skill / AGENTS material documents the `application.output` debugging loop and
      the new endpoint.
- [ ] Unit tests (pure logic) and one PDE integration test (live client, script + named method,
      asserting result and captured output) pass; new tests are registered in the correct
      aggregate suites.
- [ ] Zero compilation errors (`eclipse-ide_getCompilationErrors`).

## 6. Out of scope

- The full interactive step-through debugger: breakpoints, step in/out/over, variable inspection
  at a suspend point (the original hackathon request) — deferred to a follow-up case.
- Launching or starting a debug client on demand (the agent/user must have one running).
- Any UI in the Servoy AI Angular chat frontend for this tool.
- Changes to the reference-only `servoypilot*` bundles.

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Exact repo/path of the Servoy skill source that builds `SERVOY_SKILLS_ZIP` (where the "runtime debugging" section is authored) — it is not in the Servoy-Copilot repo. | Cristian / Johan | open |
| Is the run-scoped output capture achievable purely inside the MCP bundle, or is a minimal listener API needed in servoy/master `servoy_debug` (`DebugClientHandler`/debug client)? | implementer | open |
| When both `script` and `methodName` are supplied, run order (or reject the combination)? | implementer | open |
| If multiple debug clients are active, target the first debug-ready one, or add an optional client selector param? | Johan | open |
| Should the named-method path reuse a return-capturing variant of `DebugClientHandler.executeMethod`, or always be synthesized into a script string? | implementer | open |
