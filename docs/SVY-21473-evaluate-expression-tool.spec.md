# Spec: SVY-21473 — MCP: a command console — evaluate an expression against the running solution

## 1. Goal

Give an AI agent working in a Servoy solution the ability to **make the solution do
something and observe the value** — the one thing today's tool set cannot do. An agent
can read every file and compile it, but the loop is write → compile → hand off, and
nothing in it observes a runtime value.

Deliver a single new MCP tool, **`evaluate`**, that runs a JavaScript expression in the
developer's **already-running debug client** for the active solution and returns
`{ value, console, error }`. The expression is addressed with Servoy's normal top-level
scopes (`scopes.<s>.<fn>()`, `forms.<f>.<fn>()`, `forms.<f>.foundset`, `globals.x`,
`databaseManager.*`). Multi-statement input is allowed; the value of the last expression
is returned.

This is a **headless reuse of the proven Command Console evaluation engine** that already
ships in the Servoy platform (`com.servoy.eclipse.debug` — the `scriptingconsole`
package). It is not net-new debug-client plumbing: the same
`getActiveDebugClients()` → `invokeAndWait(...)` → `Context.evaluateString(...)` path the
Command Console uses is reused without any SWT/UI code.

## 2. Background

### 2.1 The capability gap (from the ticket)

Measured over 416 hackathon sessions on six projects: only **3 of 110** Developer
dispatches that wrote code ran any `servoy-test_*` tool at all. A user, after a completed
dispatch, said *"I don't get any values for the order. But if I run the engine (golden
master test) standalone, I get the correct results in memory."* The agent had the code,
the compile markers were clean, and it still could not tell which of the two runs was
wrong — because it could not run either.

Every adjacent tool sits next to the gap without filling it:

| tool (server) | what it gives | why it is not this |
|---|---|---|
| `executeSQL` (`servoy-dev`) | rows from the database | SQL only; no solution code, no scopes, no foundsets |
| `runJsUnitTests` / `runTestMethod` (`servoy-test`) | pass/fail verdicts | needs a committed test file; cold-start launch (minutes per run) |
| `getCompilationErrors` (`servoy-ide`) | static markers | a correct-but-wrong function has clean markers |
| `getConsoleOutput` (`servoy-ide`) | recent console text | passive — you cannot make it print |

### 2.2 Where the tool must run — the developer's own debug client

The tool runs in the **developer's own debug client** — the same target `getTarget`
already reports, the same one the JSUnit runner launches into. **Never** a deployed
server, and **never** a client the tool starts silently. If no client is running for the
active target, the tool returns a **named, actionable message** the way
`checkNGClientStatus` already does (`FormPreviewService.checkNGClientStatus()` returns a
plain "not running / here is what to do" string, never a timeout or a hang).

### 2.3 The proven evaluation engine (Command Console)

The platform's Command Console (`com.servoy.eclipse.debug/.../scriptingconsole/`, in the
target-platform repo, **not** in Servoy-Copilot — which is why triage's grep of this repo
found no debug-client plumbing) already does exactly this evaluation. The reusable,
headless core is `CommandHandler.handleCommand(...)`:

- Active clients:
  `ApplicationServerRegistry.get().getDebugClientHandler().getActiveDebugClients()` returns
  `List<IDebugClient>` (`IDebugClientHandler.getActiveDebugClients()`,
  `servoy_shared`). `getDebugClientHandler()` is on
  `IApplicationServerSingleton`.
- Run on the Servoy event thread via **`IDebugClient.invokeAndWait(Runnable)`** — this is
  how the Command Console avoids threading problems; reuse it, do not roll a thread hop.
- Inside the runnable: `Context cx = Context.enter();`, obtain the scope via the
  `ScriptConsole.getScope(client, true)` pattern — a persistent nested `____TEST_SCOPE____`
  child object created under the active solution's `GlobalScope`, obtained from
  `client.getScriptEngine().getScopesScope().getGlobalScope(scopeName)`, so that top-level
  `scopes` / `forms` / `globals` / `databaseManager` resolve. Then
  `eval = cx.evaluateString(scope, expression, "internal_anon", 1, null);`, unwrap
  `Wrapper`, map `Scriptable.NOT_FOUND` / `Undefined.instance` → `null`, catch `Exception`
  → error; `Context.exit()` in `finally`.
- The console stringifies results with `com.servoy.j2db.util.Utils.getScriptableString(...)`
  (see `ScriptResult.getOutput()` / `IScriptExecResult`).

The client's loaded solution is checked with `IDebugClient.isSolutionLoaded()`
(`ClientState`) and named with `IApplication.getSolutionName()`.

> **Reuse boundary — DECIDED (avoid SWT):** `ScriptConsole.getScope(...)` /
> `ScriptConsole.getGlobalScope()` are `public static` but live on a class that also holds
> the SWT `TextConsole`/`TextConsolePage` UI, so referencing it risks pulling SWT onto the
> headless MCP path. The design therefore does **not** call `ScriptConsole` at all. Instead
> it **lifts the ~15-line scope-acquisition logic into its own headless helper**: read what
> `getScope`/`getGlobalScope` do and reproduce it against the client's script engine
> directly — resolve the active solution's global scope name, get
> `client.getScriptEngine().getScopesScope().getGlobalScope(scopeName)`, and create/reuse a
> persistent nested scope object under it (the same `____TEST_SCOPE____` idea) so
> multi-line evaluations can share state. No SWT, no `com.servoy.eclipse.debug` UI class on
> the classpath path.

### 2.4 MCP server / tool wiring in `com.servoy.eclipse.developer.mcp`

Tools are plain methods annotated `@Tool(name, description, type)` with `@ToolParam` on
each argument, on a class annotated `@McpServer(name)`. `ServoyDevServer` is
`@McpServer("servoy-dev")` and already holds `getTarget` and `executeSQL`. Servers are
registered in `McpServerBuiltins.BUILT_IN_SERVER_CLASSES`; adding a `@Tool` method to an
existing server needs **no** registry change. Every `@Tool` method returns a `String` and
catches its own exceptions (logging via `ServoyLog.logError(...)` and returning
`"Error: " + e.getMessage()`).

The ticket writes `servoy-model_evaluate`, but **there is no `servoy-model` server**. The
tools it references live in `servoy-dev` (`getTarget`, `executeSQL`) and `servoy-test`
(`checkNGClientStatus`). This spec places `evaluate` on **`servoy-dev` / `ServoyDevServer`**,
next to `getTarget` (which it needs for the active target) and `executeSQL` (with which it
shares mutation risk).

### 2.5 Classpath / MANIFEST reality

`ServoyDevServer` already uses platform APIs freely (`ApplicationServerRegistry`,
`ServoyModelManager`, `IServerInternal`, `Utils`), so most of what `evaluate` needs is
already reachable. The bundle **already requires** `com.servoy.eclipse.debug` (so the
`scriptingconsole` statics are visible) and **already imports**
`com.servoy.j2db`, `com.servoy.j2db.scripting`, `com.servoy.j2db.server.shared`,
`com.servoy.j2db.util`. The Rhino package `org.mozilla.javascript` (needed for `Context`, `Scriptable`, `Wrapper`,
`Undefined`, `ScriptStackElement`) is not currently on the MANIFEST. **DECIDED — add it
explicitly:** add `Import-Package: org.mozilla.javascript` (with a version range consistent
with the target platform) to the developer.mcp `META-INF/MANIFEST.MF`. An explicit import is
correct here because the tool now depends on Rhino directly (it enters a `Context` and
evaluates), so the dependency should be declared, not relied on transitively.
`IDebugClient` / `IDebugClientHandler` / `IApplication` are in `com.servoy.j2db` (already
imported); `ServoyContextFactory` is in `com.servoy.j2db.scripting` (already imported).

### 2.6 Git history (from triage)

`ServoyDevServer.java` shows a steady cadence of *added* MCP tools (`renameFile` SVY-21179,
`createTable`/`createForm` column params SVY-21281, `@ToolParam` fixes SVY-21339, menu
manager SVY-21114). Adding `evaluate` fits the established additive direction; `executeSQL`
and `getTarget` are stable, self-contained `@Tool` methods — the structural precedent for
where and how `evaluate` is declared. No prior debug-client evaluation code exists in this
repo to blame or extend.

## 3. Design

### 3.1 Tool signature and shape

```java
@Tool(name = "evaluate", description = <see 3.7>, type = "object")
public String evaluate(
    @ToolParam(name = "expression", description = ..., required = true) String expression,
    @ToolParam(name = "solutionName", description = ..., required = false) String solutionName,
    @ToolParam(name = "timeoutSeconds", description = ..., required = false) String timeoutSeconds)
```

- Parameters: `evaluate(expression, solutionName?, timeoutSeconds?=15)`. Per the codebase
  convention all `@ToolParam` values arrive as `String`; `timeoutSeconds` is parsed and
  defaulted to `15` when blank/absent/unparseable.
- Returns a **JSON string** `{ "value": ..., "console": ..., "error": ... }`
  (single-line/compact, built the way the other `servoy-dev` tools build JSON — via the
  bundle's existing Jackson usage or the local `escapeJson`/`toJsonArray` helpers). On
  success `error` is `null`; on a script throw `value` is `null` and `error` carries the
  Servoy stack (3.5).
- **No "current form" parameter** — resolution is via top-level scopes only.

### 3.2 Selecting the client

1. Resolve the active target: default to `ServoyModelManager...getActiveProject()`'s
   solution (as `getTarget` does); when `solutionName` is given, it must match a running
   client's loaded solution.
2. `getActiveDebugClients()` → pick the `IDebugClient` whose `getSolutionName()` equals the
   resolved target **and** `isSolutionLoaded()` is true.
3. **No matching running client** → return the named, actionable message (not a timeout,
   not a silent launch), e.g.:
   `"No running debug client for solution '<name>'. Start it in Servoy Developer (Run/Debug the solution) and try again."`
   List the running clients' solution names if any exist but none match.

### 3.3 Evaluating on the correct thread

Mirror `CommandHandler.handleCommand`:

- `client.invokeAndWait(runnable)` to hop onto the Servoy event thread.
- Inside: `Context.enter()`, `Scriptable scope = <headless getScope>(client, true)` (the
  lifted helper from 2.3, **not** `ScriptConsole`),
  `cx.evaluateString(scope, expression, "internal_anon", 1, null)`, unwrap `Wrapper`, map
  `NOT_FOUND`/`Undefined.instance` → `null`, catch `Exception` into a captured error slot,
  `Context.exit()` in `finally`.
- Capture the return value, any thrown exception, and the console output produced during
  the call (3.4) into a holder object read back on the tool thread after `invokeAndWait`
  returns.

**Timeout — DECIDED:** `invokeAndWait` blocks, so run the hop under a bounded wait (a
`Future`/latch with `timeoutSeconds`); on expiry return a timeout error. We do **not** stop
the expression — it may still be running on the Servoy event thread. The timeout error is
plain for now, e.g.
`"Evaluation timed out after Ns; the expression is still running on the debug client's event thread."`

> **Deferred (version constraint) — `ServoyContextFactory.getScriptStackForThread(...)`:**
> The ideal behaviour is to make the timeout *informative* by attaching the running event
> thread's **script stack** so the caller sees where it is stuck:
> ```java
> // ScriptStackElement[] scriptStack = ServoyContextFactory.getScriptStackForThread(eventThread);
> // render each frame (ScriptStackElement.renderJavaStyle(StringBuilder))
> ```
> This API exists on `servoy_master` but is **NOT** in the 2026.03 LTS target platform this
> repo builds against, so it must **not** be called now (it would not compile). The
> implementation must capture the event-thread reference inside the runnable anyway, and
> leave a clearly-marked `TODO (SVY-21473)` at the timeout branch: *when
> `ServoyContextFactory.getScriptStackForThread` is backported to the 26.03 LTS, enable the
> script-stack capture here and append it to the timeout message.* `ServoyContextFactory`
> is in `com.servoy.j2db.scripting` (`servoy_shared`); `ScriptStackElement` is
> `org.mozilla.javascript.ScriptStackElement`.

### 3.4 Capturing `console` — DECIDED (marker/diff against the existing console)

`application.output(...)` already flows to the same console `getConsoleOutput` reads, so we
do **not** install a new listener or redirect. Instead we **diff the console around the
call**: record the console's current end position/length **before** starting the
evaluation, run it, then read whatever the console gained **after** that marker and return
only that delta as `console`. This is exactly the "run something, then ask the console for
what it printed" loop the AI would otherwise do by hand — the tool just does the
before/after bracketing for it in one call.

- Take the marker (console length / last-line index) immediately before `invokeAndWait`.
- After the call returns (or times out), read the console content produced past the marker.
- Return that delta as `console` (string, possibly multi-line); no new output → `console: ""`.
- Because output is captured by position and not by a per-call listener, output from other
  concurrent activity in the same client during the window may be included; that is
  acceptable and documented (the alternative — a private redirect — is heavier and was not
  chosen). Reuse whatever the bundle's `getConsoleOutput` path already uses to obtain
  console text so the source of truth is identical.

### 3.5 Errors — Servoy stack, not a Java trace

A `throw` (or a runtime error) returns the **Servoy stack with solution-relative frames**
(`<module>/<scope>.js:<line>` or `<module>/forms/<form>.js:<line>`), not the raw
Rhino/Java trace. Rhino carries source-name + line on `RhinoException`
(`getScriptStackTrace()` / `sourceName` / `lineNumber`); `ScriptEngine` already composes
solution-relative source names in exactly the `sol.getName() + "/scopes/<scope>/..."` /
`.../forms/<form>/..."` form (`ScriptEngine.java` ~575–603). The design extracts the
script stack from the caught exception and formats each frame to that solution-relative
shape; a frame with no solution mapping is shown as-is but the internal
`internal_anon:<line>` wrapper frame is elided. `error` is a string (message + formatted
frames).

### 3.6 Value size cap + foundset/record/dataset DESCRIBE

`value` gets a **reasonable hard size cap** so a `loadAllRecords()` on a large table does
not come back as a full dump. **DECIDED — cap inline, spill to a temp file when over-cap:**

- A `JSFoundSet` / `JSRecord` / `JSDataSet` return is **described**, never dumped whole:
  datasource, size (row count), and the first **N** rows (assume **N = 10** unless told
  otherwise), rendered compactly.
- Any other value is stringified (`Utils.getScriptableString(...)`).
- **When the stringified `value` exceeds the cap:** truncate the inline `value` to the cap
  with a `"... [truncated, M chars]"` marker **and** write the *full* value to a temp file,
  returning that file's path in the result (a new `valueFile` field, or folded into the
  message) so the AI can read/search the full output with the file tools. Use the approved
  temp directory (the env's `C:\Users\jcomp\AppData\Local\Temp\opencode` on this machine, or
  the platform temp dir generally) with a unique name (e.g. `svy-evaluate-<ts>.txt`).
- `null`/undefined → JSON `null`.

Detect the Servoy runtime types by class after unwrapping the `Wrapper` — confirm the exact
scripting classes during coding (`JSFoundSet`/`JSRecord`/`JSDataSet` vs
`IFoundSet`/`IRecord`/`IDataSet`).

### 3.7 Bare-identifier rewrite hint

A bare `foundset` / `controller` / `currentcontroller` / `elements` has no meaning without
a form context, so such an expression will naturally fail at evaluation. **DECIDED — do NOT
pre-scan the source:** the `expression` may be an arbitrary multi-line script, so a static
"is this identifier bare?" scan is fragile and would produce false positives/negatives. We
rely on the **natural evaluation failure** instead, and only *enrich the error message*
when the caught error clearly refers to one of these specific names (e.g. a
`ReferenceError` naming `foundset`/`controller`/`currentcontroller`/`elements`), appending a
rewrite hint:

> `"'foundset' is not addressable without a form context — use forms.<formName>.foundset. Likewise controller / currentcontroller / elements require a forms.<formName>.<...> qualifier."`

If the caught error does not clearly match, return the natural (solution-relative) error
from 3.5 unchanged. No `@ToolParam` "current form" is added. This keeps behaviour
deterministic and avoids parsing arbitrary script text.

### 3.8 Mutating classification & consent

`evaluate` executes **arbitrary solution code against the developer's database** — it can
write records. Content-based gating is not feasible (same reason as 3.7: the expression can
be any multi-line script), and this codebase has no declarative `mutating`/`readOnly` flag
on `@Tool` (grep found none; `ServoyIdeServer` documents `searchAndReplace` as "the only
destructive tool" purely in prose). So today `evaluate` is an ordinary write `@Tool` on
`servoy-dev`, and consent is handled by the client-side tool-approval layer (opencode,
outside this bundle) exactly as for the other write tools; the **tool description** (3.9)
states plainly that it executes arbitrary mutating code.

**Remains an open question (OQ-6):** whether `evaluate` should *always* be human-gated —
i.e. surface the exact expression to the user ("this is what we are going to evaluate") for
confirmation before running. That is safe but may be more friction than wanted for a
tight loop, so it is left open rather than hard-required here. If a server-side gate is
later added for `executeSQL`, include `evaluate` in the same list.

### 3.9 Tool description — must say what it is *not*

The description is the only text every caller sees, so it must carry the "not a test"
warning verbatim in spirit:

> Runs a JavaScript expression in the developer's already-running debug client for the
> active solution and returns its value, console output and any error. Address it with
> Servoy top-level scopes: `scopes.<s>.<fn>()`, `forms.<f>.<fn>()`,
> `forms.<f>.foundset`, `globals.x`, `databaseManager.*`. Multiple statements are allowed;
> the value of the last expression is returned. **This executes arbitrary, mutating
> solution code against the developer's database — it can write records.** **It is NOT a
> substitute for a JSUnit test: an evaluation is what you do before you know what the test
> should assert. It leaves no artifact and proves nothing to the next run.** Requires a
> running debug client for the target; if none is running it returns a message naming the
> action to take, never a timeout.

## 4. Implementation plan

Ordered, concrete changes (all in `com.servoy.eclipse.developer.mcp` unless noted):

1. **New service `ExpressionEvaluationService`** —
   `src/com/servoy/eclipse/developer/mcp/services/ExpressionEvaluationService.java`. Holds
   the headless evaluation engine: client selection; the **own lifted scope-acquisition
   helper** (client script engine → global scope → persistent nested `____TEST_SCOPE____`,
   **no** `ScriptConsole`/SWT); `invokeAndWait` hop capturing the event-thread reference;
   `Context.enter/evaluateString/exit`; marker/diff console capture (3.4); value
   description + size cap + temp-file spill (3.6); Servoy-stack error formatting (3.5) with
   the natural-error rewrite-hint enrichment (3.7); and the bounded-wait timeout (3.3) — a
   plain timeout message now, with a `TODO (SVY-21473)` to attach the event thread's script
   stack via `ServoyContextFactory.getScriptStackForThread(...)` once that API is backported
   to the 26.03 LTS target (it is not available today, so it must not be called).
   Returns a small result record `{ String value, String valueFile, String console, String error }`.
   Reuses `Utils.getScriptableString(...)`.
2. **New `@Tool evaluate(...)` on `ServoyDevServer`** — add the method next to
   `executeSQL`/`getTarget`, delegating to `ExpressionEvaluationService`, catching
   exceptions and returning the `{ value, console, error }` JSON string. Parse
   `timeoutSeconds` (default 15). No `McpServerBuiltins` change (existing server).
3. **MANIFEST** — add `Import-Package: org.mozilla.javascript` (version range consistent
   with the target platform) to `META-INF/MANIFEST.MF`. No new `Require-Bundle` needed
   (the `com.servoy.j2db.*` packages, incl. `com.servoy.j2db.scripting` for
   `ServoyContextFactory`, are already declared). Note: the design deliberately does **not**
   depend on `com.servoy.eclipse.debug` (SWT avoidance) — do not add it just for scope
   acquisition.
4. **Value-description helper** — implement foundset/record/dataset DESCRIBE + size cap
   (3.6), either inside `ExpressionEvaluationService` or a small
   `EvaluatedValueFormatter`.
5. **Servoy-stack formatter** — implement solution-relative frame formatting (3.5), reusing
   the `sol.getName() + "/scopes|forms/..."` shape from `ScriptEngine`.
6. **Unit tests (plain JUnit, Jupiter)** —
   `tests/.../src/test/java/.../servers/EvaluateToolTest.java` (or extend
   `ServoyDevServerTest`) for the workbench-free parts: `@Tool`/`@ToolParam` annotation
   presence and names, `timeoutSeconds` parsing/default, bare-identifier detection + hint
   text, value-cap/DESCRIBE formatting, Servoy-stack formatter, "no client running" message
   shape. Register in `pom.xml` headless `<test>` block (or integration block if it must
   touch workbench types) **and** in the matching `@Suite`.
7. **Integration test (PDE plug-in test, Jupiter)** —
   `tests/.../integration/EvaluateToolIntegrationTest.java` extending `TestUtilitiesClass`:
   with a running debug client, assert `scopes.<s>.<fn>()` returns the value, `forms.<f>.fn()`
   / `forms.<f>.foundset` resolve, `application.output(...)` shows up in `console`, a
   `throw` yields a solution-relative frame, a foundset return is described not dumped, and
   the no-client path returns the named message. Register in `pom.xml` **integration**
   `<test>` block **and** in `AllDeveloperMcpIntegrationTests.@SelectClasses`.
8. **Compile/quick-fix loop + Spotbugs** — `getCompilationErrors()` clean; fix top-two
   Spotbugs severities in new code (close Rhino `Context`, close JDBC-like resources if any,
   null-checks).

## 5. Acceptance criteria

- [ ] `scopes.<scope>.<fn>(args)` returns the function's value, JSON-serialized in `value`.
- [ ] `forms.<form>.<fn>()` and `forms.<form>.foundset` resolve without any context parameter.
- [ ] A bare `foundset` / `controller` / `currentcontroller` / `elements` fails naturally,
      and when the caught error clearly names one of them the message is enriched with the
      qualified rewrite hint (e.g. use `forms.<formName>.foundset`). No source pre-scan.
- [ ] A `throw` returns the Servoy stack with solution-relative frames, not a Java trace.
- [ ] `application.output(...)` inside the expression comes back in `console`.
- [ ] A foundset return is described (datasource, size, first N=10 rows), never dumped whole;
      `JSRecord`/`JSDataSet` likewise described.
- [ ] `value` is subject to a hard size cap; an over-cap plain value is truncated inline
      with a marker **and** the full value is written to a temp file whose path is returned.
- [ ] No running client → a named, actionable message; **never** a timeout or a silently
      started client.
- [ ] Runs only in the developer's own debug client for the active target; never a deployed
      server.
- [ ] Multi-statement input returns the value of the last expression.
- [ ] `timeoutSeconds` defaults to 15; a runaway expression returns a (plain) timeout error
      rather than hanging the MCP call. A `TODO (SVY-21473)` marks where the script stack
      will be attached once `ServoyContextFactory.getScriptStackForThread(...)` is backported
      to the 26.03 LTS. That API must NOT be called today (not in the target platform).
- [ ] The tool description states it executes arbitrary mutating code **and** that it is
      **not** a substitute for a JSUnit test.
- [ ] `evaluate` gets the same consent treatment as `executeSQL` (write tool, not read-only).
- [ ] Compilation clean; new tests registered in both the `@Suite` and the `pom.xml`
      `<test>` list; top-two-severity Spotbugs clean in new code.

## 6. Out of scope

- Any UI (no Command Console changes; no SWT). Headless reuse only.
- Launching, starting, or shutting down a debug client. If none is running, the tool
  reports and stops.
- Evaluating in deployed/production servers or in a fresh short-lived client (Approach 2 in
  triage — explicitly rejected).
- A declarative server-side `mutating`/approval framework for `@Tool` methods (does not
  exist today; if added later, include `evaluate` alongside `executeSQL`).
- Persisting or exporting evaluation results as an artifact (the tool intentionally leaves
  none — that is the JSUnit test's job).
- Streaming/incremental output; the tool returns once, after the call completes or times
  out.

## 7. Open questions

| # | Question | Owner | Status |
|---|---|---|---|
| OQ-1 | Scope acquisition without dragging SWT in. | Implementer | **DECIDED** — do not touch `ScriptConsole`; lift the scope logic into a headless helper using the client's script engine directly. |
| OQ-2 | Timeout while the expression may still run on the event thread. | Implementer | **DECIDED** — do not cancel; stop *waiting* and return a plain timeout message. Script-stack detail via `ServoyContextFactory.getScriptStackForThread(...)` is **deferred**: that API is not in the 26.03 LTS target yet (must not be called), so leave a `TODO (SVY-21473)` to enable it after a backport. |
| OQ-3 | How to capture `application.output` for the call. | Implementer | **DECIDED** — marker/diff against the existing console (record end position before, return the delta after), reusing the `getConsoleOutput` source; no private listener. |
| OQ-4 | Value cap + foundset/dataset DESCRIBE N; over-cap handling. | Implementer | **DECIDED** — reasonable cap; N=10; over-cap → truncate inline **and** spill full value to a temp file, return its path. Exact scripting classes confirmed during coding. |
| OQ-5 | Bare-identifier detection mechanism. | Implementer | **DECIDED** — no source pre-scan (the expression can be an arbitrary script). Let it fail naturally; enrich the message only when the caught error clearly names `foundset`/`controller`/`currentcontroller`/`elements`. |
| OQ-6 | Consent for arbitrary mutating evaluation. | Product | **OPEN** — content-based gating not feasible; consent is client-side today. Whether to *always* human-gate (show the exact expression for confirmation) is deliberately left open — safe but possibly too much friction. |
| OQ-7 | Rhino `org.mozilla.javascript` import. | Implementer | **DECIDED** — add an explicit `Import-Package: org.mozilla.javascript` (the tool depends on Rhino directly). |
