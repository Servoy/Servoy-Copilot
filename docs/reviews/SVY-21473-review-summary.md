# Review Summary: SVY-21473 (MCP command console / evaluate against the running solution)

**Risk: MODERATE.** The shipped tool (`servoy-debug/executeInRunningClient`) runs arbitrary JS in the live debug client. The ticket's "mutating, executeSQL-level approval" requirement was not found implemented, and a timed-out script cannot be cancelled.

**Scope reviewed:** Servoy-Copilot, `origin/master`: `3881d60a`, `a912109d`, `6d9836c1`, `d5535284`.
Note: `3881d60a` and `a912109d` (the `servoy-dev/evaluate` tool) are history. Commit `ec64d67a` (SVY-21443, outside the reviewed scope) removed `evaluate` and `ExpressionEvaluationService` and merged their features into `executeInRunningClient`.

**Release target:** Fix Version 2026.12, check MATCH (commits on master, servoy-client master is 2026.12.0 rc). Not verified on other branches.

## Manual test plan

**Verifying the fix**
1. With a solution in the debug client, call `executeInRunningClient` with `1+1`. Expect `2`, not JSON.
2. `application.output('hi'); 42`. Expect value 42 and console `hi`.
3. Return a foundset. Expect a description, not a dump.
4. Return a string over 8000 chars. Expect truncation and a temp-file path.
5. `foundset.getSize()` outside a form. Expect the qualified rewrite hint.
6. `nosuchvar`. Expect a readable error with a solution-relative stack.
7. Stop the client and call again. Expect "No running Servoy client", not a timeout.

**Regression checks**
1. `while(true){}` with `timeoutSeconds: 5`, then `1+1`. Note the behaviour.
2. Print 1,000,000 times with a 10s timeout. Watch heap and response size.
3. Hit a breakpoint via the tool, then use `debugStatus` / `debugResume`.
4. Trigger a UI action while a run is active. Check whether its output leaks into the result.
5. Confirm `servoy-debug` appears in `opencode.json` and other MCP servers still work.
6. Check that the user is asked for approval when the tool is called.

## Possible improvements / follow-ups
- Approval treatment: where is the "mutating, same approval as `executeSQL`" requirement enforced for this tool?
- Internal-user (`@servoy.com`) bearer-token bypass in `BearerTokenAuthenticationFilter` now reaches a code-execution tool. Accept it or require the token for `servoy-debug`.
- `DebugUtils.setOutputSink` is on servoy-client `master` and `release`, but not on `lts_latest`. Confirm which branch Copilot master builds against.
- Timed-out scripts keep the client event thread; `timeoutSeconds` has no upper bound.
- `application.output` capture is uncapped (only the value is capped at 8000 chars).
- The spill temp file is never deleted and has no size cap.
- The `@Tool` description lacks the ticket's "not a substitute for a JSUnit test" sentence.
- `docs/SVY-21473-evaluate-expression-tool.spec.md` and `docs/SVY-21473-triage.md` still describe the removed `servoy-dev/evaluate` tool.
- Run `RunningClientExecutionServiceTest` and `ServoyDebugServerTest` to confirm they run headless (nothing was run in this review).
