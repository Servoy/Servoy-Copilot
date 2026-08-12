# Triage Report — SVY-21339

**Verdict:** PROCEED

## Reported problem

MCP tool arguments in the `servoy-test` server are exposed with meaningless names (`arg0`, `arg1`, `arg2`...) instead of descriptive names. Additionally, parameters described as "Optional" in their description text are marked as `required` in the JSON schema, confusing AI agents into thinking they must always provide a value.

## Root-cause assessment

The root cause is in `ServoyTestingServer.java`. Every `@ToolParam` annotation in this file specifies only `description` (and sometimes `type`), but **never** specifies `name` or `required = false`:

```java
// What ServoyTestingServer does (WRONG):
@ToolParam(description = "Optional: form to start navigation from...") String fromForm,

// What all other servers do (CORRECT):
@ToolParam(name = "fromForm", description = "Optional: form to start navigation from...", required = false) String fromForm,
```

The `@ToolParam` annotation (in `com.servoy.eclipse.developer.mcp.annotations.ToolParam`) defines:
- `name()` default `""` — which at runtime falls back to the compiled parameter name (`arg0`, `arg1`...) since `-parameters` javac flag is apparently not active
- `required()` default `true` — so all omitted `required` attributes default to required

**All other server classes** (`ServoyCoderServer`, `ServoyContextServer`, `ServoyDevServer`, `ServoyGitServer`, `ServoyIdeServer`, `ServoyWpmServer`, `MemoryServer`) correctly specify both `name` and `required` in their `@ToolParam` annotations. The problem is **isolated to ServoyTestingServer.java**.

**Affected tools (23 total):** `runJSUnitTests`, `runSingleTestMethod`, `getCoverageSummary`, `getUncoveredFunctions`, `showFormInBrowser`, `screenshotForm`, `generateFormSpec`, `runFormSpec`, `runCypressFormTest`, `getCypressFormTestResults`, `generateFormSpecOnly`, `executeTestSetup`, `executeTestTeardown`, `createTestFile`, `addTestMethod`, `generateTestCases`, `analyzeCodeForTesting`, `findForm`, `getFormNavigationGraph`, `getNavigationPath`, `generateCypressE2ETest`, `checkNGClientStatus`, `runE2ESpec`.

## Ticket premise check

The ticket correctly identifies the problem: bad argument names (`arg0`, `arg1`...) and misleading required/optional semantics. The proposed solution — "rename all those arguments to appropriate names" — is the correct approach. This is a straightforward annotation fix.

## Approaches considered

1. **Add `name` and `required` attributes to all `@ToolParam` annotations in ServoyTestingServer.java** — Fix each annotation to include an explicit `name` derived from the Java parameter name, and set `required = false` for parameters whose description says "Optional" or "If omitted". Pros: simple, consistent with all other servers, fixes the root cause. Cons: none meaningful — it's ~45 annotations to update.

2. **Enable `-parameters` javac flag project-wide** — This would make Java preserve parameter names in bytecode, so the `name()` default of `""` would resolve to the actual parameter name. Pros: fixes the naming issue without touching each annotation. Cons: does NOT fix the `required` issue (still defaults to `true`); changes compiler behavior globally which may have unintended effects; fragile (depends on build config never changing).

3. **No code change** — Pros: none. Cons: AI agents continue to make wrong calls due to `arg0` names and incorrect required flags. The skills must compensate with extra instructions, wasting tokens and causing errors.

## Recommendation

**Approach 1**: Add explicit `name` and correct `required` attributes to every `@ToolParam` in `ServoyTestingServer.java`. This is the same pattern all other servers already follow. The mapping from existing parameters to proper names is straightforward since the Java parameter names are already descriptive (e.g. `String formName`, `String targetForm`, `String scenario`).

Key parameters that must be marked `required = false`:
- `generateCypressE2ETest`: `fromForm`, `outputFileName`, `baseUrl`, `loginUrl`, `testUsername`, `testPassword`, `loginSuccessSelector`
- `getFormNavigationGraph`: `formName`
- `getNavigationPath`: `fromForm`
- `getCoverageSummary`: `coveragePath`
- `getUncoveredFunctions`: `coveragePath`, `maxFunctions`
- `runSingleTestMethod`: `scopeOrAll`

## Git history findings

Last 5 commits on `ServoyTestingServer.java` are all AI-generated (`[ai]` suffix), part of the SVY-21102/SVY-21131/SVY-21169 feature work. The `@ToolParam` annotations were written without `name`/`required` from the start — this was never correct, just never caught until the MCP tools report made it visible.
