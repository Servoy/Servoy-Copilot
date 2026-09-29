# Triage Report — SVY-21443

**Verdict:** NEEDS_INPUT

## Reported problem

The ticket asks for a new **`servoy-debug` MCP endpoint** so that an AI agent can, against a
running Servoy client:

- set an execution breakpoint,
- step in / out / over,
- read variable values at the breakpoint.

The description notes this capability was discussed at a hackathon between Ron and Johan.

The underlying *goal* (the symptom this is meant to address) is: **an agent currently has no
way to actually run/observe solution JavaScript at runtime** — it can create, edit, analyse and
JSUnit-test code, but it cannot execute an arbitrary method or script in a live client and
inspect the result.

## Root-cause assessment

This is a **feature request**, not a defect, so there is no "root cause" in the bug sense.
What matters for triage is: *which mechanism should satisfy the goal, and is the ticket's
proposed mechanism (a full step-debugger endpoint) the right one?*

Relevant existing code in `com.servoy.eclipse.developer.mcp`:

- `servers/ServoyDevServer.java` — the `servoy-dev` MCP server (solution/target management,
  documentation, persist ops). This is where a runtime-execution or debug tool would most
  naturally be added, or a new `servoy-debug` server registered alongside it.
- `servers/ServoyTestingServer.java` + `services/JSUnitRunnerService.java` — already drive a
  **live client at runtime** by launching JSUnit through the Servoy Eclipse launch
  infrastructure (`RunJSUnitHandler`, DLTK testing model) and collecting results. This proves
  the bundle *already* has a working path to "start a client, run solution JS, read output" —
  the exact substrate a simple "run a script / execute a method" endpoint would reuse.
- No existing `servoy-debug` server, breakpoint, or step-over/into machinery exists in the
  bundle (grep for `breakpoint|stepInto|stepOver|debugTarget|servoy-debug` finds only an
  unrelated `servoy_debug@default` bundle entry in a `.launch` file).

So the goal is real and unimplemented, but the codebase shows a **much cheaper adjacent path**
(reuse the JSUnit/launch runtime substrate for script execution) than building a full
interactive debugger with breakpoints and stepping.

## Ticket premise check

The ticket's premise **does not hold as written** — and the ticket itself already says so.
The architect (Johan Compagner) added a comment on 2026-09-14 that directly challenges the
debugger approach:

> "We need to really check if we really need that debug stuff, because in the end it could be
> that it really just should use debug output statements and then monitor the console for every
> run to analyse the result."
>
> "Maybe better first for this case would be to just be able to run a script … we can say just
> execute `form.a.b()` method"
>
> "or let the ai be able to generate a script and execute that (like in our command console) …"
>
> "So first investigate/implement that way, and then test a bit how this goes."

In other words the ticket now contains **two divergent solutions**:

1. **A** — full interactive debugger endpoint (breakpoints, step in/out/over, variable
   inspection), per the original hackathon description.
2. **B** — a simpler **script/method execution** endpoint (run `form.a.b()` or an
   ad-hoc generated script like the command console) plus console output analysis, per the
   architect's comment — explicitly to be tried *first*.

These lead to materially different implementations and scope, and the ticket does **not**
settle which to build. The architect's comment leans toward B-first but frames it as
"investigate … and then test a bit how this goes," i.e. an unratified direction. This is a
genuine product/architecture decision a human must make before a spec is written — it is not
something triage can resolve from the code.

## Approaches considered

1. **Full `servoy-debug` endpoint (breakpoints + stepping + variable read)** — as originally
   described.
   - Pros: most powerful; matches the literal request; lets an agent inspect intermediate state.
   - Cons: by far the largest effort; needs to hook the Servoy/DLTK/Rhino debug target,
     suspend/resume threads, marshal stack frames and variables over MCP; the architect
     explicitly questions whether it is needed; risk of building something heavy that is rarely
     used.

2. **Simple "run script / execute method" endpoint (the architect's B-first)** — a
   `servoy-debug` (or `servoy-dev`) tool that executes a named method (`form.a.b()`) or an
   ad-hoc script in a live client and returns the result + console output.
   - Pros: much smaller; can largely **reuse the existing JSUnit/launch runtime substrate**
     already in the bundle (`JSUnitRunnerService`/`ServoyTestingServer`); directly matches the
     architect's stated preferred first step; unlocks "trigger multiple things at once" as he
     notes.
   - Cons: no step-through/variable inspection; agent must fall back to debug-output statements
     + console scraping for intermediate state (which the architect considers acceptable).

3. **No code change needed** — rely purely on the agent adding `application.output(...)` /
   debug statements to solution code and reading the console after a normal run.
   - Pros: zero new endpoint; possible today.
   - Cons: no first-class "execute this method now" affordance; clumsy; does not satisfy the
     goal of letting an agent *trigger* runtime execution on demand. Honest assessment: this
     alone is probably insufficient, but it is a real fallback and the architect mentions
     console monitoring as part of the picture.

## Recommendation

**NEEDS_INPUT.** The ticket carries two divergent, unreconciled solutions (full debugger vs.
simple script-execution), and the architect's own comment defers the decision to an
"investigate first, then see" stage. A spec cannot be written until a human confirms the
intended scope for *this* case.

If forced to lean, the evidence points to **Approach 2 (simple run-script/execute-method
endpoint) first**, because (a) the architect explicitly asked for that first, and (b) the
bundle already has the runtime substrate to build it cheaply. But that is a product call, not
a triage conclusion — hence the questions below.

## Git history findings

None relevant. There is no prior `servoy-debug` implementation, no breakpoint/stepping code,
and no earlier spec in `docs/` for SVY-21443. The only `servoy_debug`-matching hit is an
unrelated `servoy_debug@default:default` bundle entry in
`JSUnitRunnerIntegrationTest_mac.launch`.

## Questions for the reporter

1. The description asks for a full debugger (breakpoints, step in/out/over, read variables),
   but the later comment suggests starting instead with a simpler ability to **run a script or
   execute a method** (e.g. `form.a.b()`) and analyse console output. Which scope do you want
   implemented for **this** case — the simple script/method execution endpoint first, or the
   full interactive debugger?

2. If we start with the simpler script-execution approach, is the intended shape: (a) execute a
   **named existing method** such as `forms.myForm.myMethod(args)`, (b) execute an **arbitrary
   ad-hoc script** the agent generates (like the command console), or (c) **both**?

3. In which runtime should the script/method run — a **JSUnit-style headless client** launched
   on demand (reusing the existing JSUnit launch infrastructure), an already-**running debug
   client**, or the **developer/command-console** context?

4. What should the endpoint return — just the method's **return value / thrown error**, the
   captured **`application.output` / console log** for that run, or both?

5. Is the full step-through debugger (breakpoints + variable inspection) still wanted as a
   **follow-up** once the simple approach is validated, or should it be dropped for now and the
   ticket rescoped to the simpler endpoint?
