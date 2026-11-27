# Spec: SVY-21839 — Reject unknown arguments in MCP tool calls

## 1. Goal

When an agent calls a Servoy MCP tool with an argument the tool does not declare (e.g. `servoy-workspace_findFiles({pattern: "*ticket*"})`), the call must fail with a recoverable tool error. The error names the unknown argument(s) and lists the parameter names the tool accepts. Today the argument is dropped silently and the tool runs unfiltered, which returns large irrelevant output (about 52 KB in the reported case).

## 2. Background

### 2.1 Current behaviour
- `McpServerFactory.createToolSpecifications` (bundle `com.servoy.eclipse.developer.mcp`, `McpServerFactory.java` ~lines 157-189) registers a call handler per tool. The handler only calls `validateRequiredParams(tool, args)` (public static, lines 191-219). That method checks the schema's `required` list and never looks at keys that are not declared.
- `ToolExecutor.mapArguments` iterates the Java method's declared parameters and does `argMap.get(name)`. Keys matching no parameter are never read. A missing optional parameter becomes `null`/`0`/`false`.
- `extractAnnotatedTools` (lines 221-252) builds the schema as `type`, `properties`, `required` only. `properties` is a `LinkedHashMap<String,Object>` keyed by `ToolExecutor.toParamName(param)`.
- Reproduction: `ServoyIdeServer.findFiles` declares only `fileNamePatterns` and `maxResults`. `pattern` is ignored, `fileNamePatterns` is null and the tool defaults to `*`.

### 2.2 Existing tests
- `com.servoy.eclipse.developer.mcp.tests`, `src/test/java/com/servoy/eclipse/developer/mcp/plainjunit/McpServerFactoryTest.java` (package `com.servoy.eclipse.developer.mcp.plainjunit`, JUnit Jupiter). It builds `McpSchema.JsonSchema` and `McpSchema.Tool` by hand with the helper `schemaAsMap(...)`, then calls `McpServerFactory.validateRequiredParams(tool, args)`.
- `testValidateRequiredParams_extraParamsIgnored_returnsNull` (lines ~362-375) asserts that `validateRequiredParams` ignores extra keys. It stays valid, because `validateRequiredParams` is not changed. Unknown-argument rejection lives in a separate method.

### 2.3 Callers that might send undeclared arguments
- `McpServerFactory` is the only entry point for tool calls, via the MCP call handler. The MCP `_meta` field is part of the request, not of `arguments`, so it is not affected.
- Not verified in the triage and not verified in this spec phase: whether the opencode agent, its skills or the Angular chat UI send extra keys (for example a `directory` or similar context key). The implementer must run the existing plainjunit suite and the integration tests that call tools through the factory, and grep `webui/` and the opencode bundle for tool-call argument construction. See open question 1.

## 3. Design

### 3.1 Approach (approved: handler-level check)
Add a new `public static String validateUnknownParams(Tool tool, Map<String, Object> args)` in `McpServerFactory`, next to `validateRequiredParams`, with the same style: it returns `null` when the call is fine and an error message string otherwise (no `"Error: "` prefix; the handler adds it).

Behaviour:
- `schema == null` or `args == null` or `args.isEmpty()` returns `null`.
- Declared names = `schema.get("properties")` cast to `Map<?,?>`, taking `keySet()`. If `properties` is missing or null, treat the declared set as empty, so every supplied key is unknown. A tool with no parameters that receives arguments is therefore rejected too.
- Unknown = every key in `args.keySet()` that is not declared, sorted for a deterministic message. A key with a null value is still unknown (it is judged by name, not by value).
- Message format (one line, same tone as `validateRequiredParams`):
  `Unknown parameter(s) for tool '<name>': [a, b]. Accepted parameters: [x, y, z]. Please re-call the tool using only the accepted parameter names.`
  When the tool declares no parameters, print `Accepted parameters: [] (this tool takes no parameters).`
- Returns `null` when there is nothing unknown.

Handler change in `createToolSpecifications`: compute both `validateRequiredParams(tool, args)` and `validateUnknownParams(tool, args)`. Return the first non-null error. Preferred order: required first, then unknown, because a missing required parameter plus an unknown key usually means a misspelled name. To help self-correction, when both fail join the two messages with a space into one error. The result is built exactly like today: `TextContent("Error: " + message)` with `isError(true)`. No tool is invoked (`executeCallTool` is not reached).

### 3.2 Alternatives rejected
- `additionalProperties: false` in the schema: the SDK would report it as a protocol-level error, with an uncontrolled message. This is the behaviour the handler-level validation deliberately avoids. Do NOT add it.
- Silently ignoring or only warning: this is the defect.

### 3.3 Git history
- The handler block (lines 177-186) comes from commit `ca2a69e` (Andrei Costescu, 2026-08-03), which moved required-parameter validation into the handler so that errors are recoverable tool errors. This is the design intent to follow. The surrounding builder comes from `fe33637`.
- Later changes to `McpServerFactory`: `2727d89` (SVY-21303, error redaction), `abb4af0` (SVY-21618, lenient value-type parsing via `acceptString`). SVY-21618 is about value types, not about argument names, so there is no conflict.
- No earlier commit made lenient handling of unknown names a deliberate decision. `git blame -L 175,190` confirms these lines.

## 4. Implementation plan

1. `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/McpServerFactory.java`
   1. Add `public static String validateUnknownParams(Tool tool, Map<String, Object> args)` after `validateRequiredParams`, as described in 3.1. Use `@SuppressWarnings("unchecked")` or a `Map<?,?>` cast. Use `Collectors`/`TreeSet`, which are already imported or trivial to import.
   2. Change the call handler: compute the required error and the unknown error and combine them as in 3.1. The `isError(true)` result shape must stay unchanged.
   3. No change to `extractAnnotatedTools`, `ToolExecutor` or any `@Tool` signature.
2. `tests/com.servoy.eclipse.developer.mcp.tests/src/test/java/com/servoy/eclipse/developer/mcp/plainjunit/McpServerFactoryTest.java`: add tests (see section 5) with `@DisplayName` on the methods only, following the existing hand-built `Tool` style. Add one test through the real call handler if it is cheap (see 4.3).
3. Handler-level coverage: the handler is a lambda in a private method. Prefer testing through `createSyncServer` plus the tool specification if the existing tests expose one. Otherwise extract the lambda body into a package-private static `CallToolResult validateCall(Tool tool, Map<String,Object> args)` that returns the error result or `null`, and unit-test that. This is allowed as a small behaviour-preserving seam. No reflection.
4. Compile loop: `getCompilationErrors` (and quick fixes) for both projects, then run the plainjunit package `com.servoy.eclipse.developer.mcp.plainjunit` (`eclipse-ide_runJUnitTests`). Then run the integration tests that call tools through the factory, to confirm that no internal caller sends undeclared arguments.
5. Check Spotbugs on the new code (top two severities are blocking).
6. Commit message: `SVY-21839 reject unknown arguments in MCP tool calls [ai]`.

## 5. Acceptance criteria

- [ ] A call with an undeclared argument returns `isError == true`, the text starts with `Error: ` and names every unknown argument.
- [ ] The error lists all accepted parameter names of the tool.
- [ ] The tool method is not invoked when an unknown argument is present.
- [ ] A call that uses only declared arguments (required and optional) is unchanged and `validateUnknownParams` returns `null`.
- [ ] A call with no arguments, `null` args or an empty map returns `null` from `validateUnknownParams`.
- [ ] A null-valued undeclared key is still reported as unknown.
- [ ] A tool with a null schema, or with no `properties`, behaves as described in 3.1 (null schema returns `null`; missing `properties` makes every key unknown).
- [ ] When both a required parameter is missing and an unknown key is present, the single error names both problems.
- [ ] `validateRequiredParams` behaviour is unchanged, and `testValidateRequiredParams_extraParamsIgnored_returnsNull` still passes.
- [ ] Unit tests in `McpServerFactoryTest` (plainjunit, Jupiter, `@DisplayName` only on methods) cover the cases above: single unknown, multiple unknown (sorted), all declared, null args, empty args, null-valued unknown key, no-parameter tool, combined required + unknown, null schema.
- [ ] The existing plainjunit suite and the integration tests that call tools through the factory pass. The result of the check for internal/UI callers sending extra keys is noted in the commit or review notes.
- [ ] No `additionalProperties` is added to the schema.
- [ ] No compilation errors; no new blocking Spotbugs findings.

## 6. Out of scope

- Part 2 of the ticket: renaming or aliasing search-tool parameters (`fileNamePatterns` / `containingText` / `pattern`). Raise a separate follow-up case if the ticket owner still wants it. The three names mean different things (glob list, substring, regex), and aliases would require schema duplication or alias handling in `ToolExecutor`.
- Changes to `ToolExecutor.mapArguments` and to value coercion (SVY-21618 leniency).
- The `servoy-guard` workaround in the agent pack (it can stay).
- Schema-level validation (`additionalProperties:false`).

## 7. Open questions

| Question | Owner | Status |
|---|---|---|
| Do the opencode agent, its skills or the Angular chat UI send extra keys inside tool-call `arguments` that would now be rejected? | Implementer (grep `webui/` and opencode bundle; run integration tests) | Open, not verified in triage or spec |
| Should the combined required + unknown error be one message (as specified) or only the first error? | Reviewer | Assumed: one combined message |
| Does the ticket owner want Part 2 (aliases) as a follow-up case? | Ticket owner | Open |
