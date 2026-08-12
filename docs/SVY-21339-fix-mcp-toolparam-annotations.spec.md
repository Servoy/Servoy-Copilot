# Spec: SVY-21339 — Fix MCP Tooling to have good argument names

## 1. Goal

Add explicit `name` and correct `required` attributes to every `@ToolParam` annotation in `ServoyTestingServer.java`. Without these, parameters are exposed to MCP clients as `arg0`, `arg1`, etc. (because the project is not compiled with `-parameters`) and all parameters appear required even when they are semantically optional. This forces AI agents to guess parameter names and pass null for optional fields, leading to incorrect tool calls.

## 2. Background

### 2.1 The @ToolParam annotation

The `@ToolParam` annotation (`com.servoy.eclipse.developer.mcp.annotations.ToolParam`) has four attributes:

| Attribute | Default | Purpose |
|-----------|---------|---------|
| `name` | `""` (falls back to Java parameter name) | Parameter name in the MCP JSON schema |
| `description` | _(required)_ | Human-readable description |
| `required` | `true` | Whether the parameter is required |
| `type` | `"string"` | JSON Schema type |

When `name` is blank, the framework tries to use the Java parameter name. However, without the `-parameters` compiler flag the JVM only sees `arg0`, `arg1`, etc. This is why every `ServoyTestingServer` tool currently exposes numbered argument names.

### 2.2 Correct pattern (used by all other servers)

All other MCP servers in this project (e.g. `ServoyDevServer`, `ServoyIdeServer`) use the full pattern:

```java
@ToolParam(name = "solutionName", description = "The name of the solution to activate", required = true) String solutionName
@ToolParam(name = "moduleName", description = "Module/project to search in. If omitted, searches the active solution.", required = false) String moduleName
```

### 2.3 Scope of the problem

A workspace-wide search for `@ToolParam(description =` (i.e. without an explicit `name`) returns **45 occurrences** — all exclusively in `ServoyTestingServer.java`. No other server file has this issue.

### 2.4 Additional issues

Besides the missing `name`, several parameters that are described as optional in their description text (e.g. "Optional:", "If omitted", "Default:") are missing `required = false`. The MCP schema therefore marks them as required, confusing AI clients that try to honour the schema.

## 3. Design

### 3.1 Add explicit `name` to every @ToolParam

Every `@ToolParam` in `ServoyTestingServer.java` must include a `name` attribute whose value matches the Java parameter name exactly.

### 3.2 Set `required = false` on optional parameters

Parameters whose semantics are optional (nullable/defaulted) must have `required = false`. The "Optional:" prefix should be removed from descriptions since the schema attribute communicates optionality.

### 3.3 Parameter classification

| Tool | Parameter | required |
|------|-----------|----------|
| `runJsUnitTests` | `scopeOrAll` | true |
| `runJsUnitTests` | `timeoutSeconds` | true |
| `runTestMethod` | `testMethodName` | true |
| `runTestMethod` | `scopeOrAll` | false |
| `runTestMethod` | `timeoutSeconds` | true |
| `getJSUnitCoverageReport` | `coveragePath` | false |
| `suggestTestsFromCoverage` | `coveragePath` | false |
| `suggestTestsFromCoverage` | `maxFunctions` | false |
| `showFormInBrowser` | `formName` | true |
| `screenshotForm` | `formName` | true |
| `screenshotForm` | `waitSeconds` | true |
| `testForm` | `formName` | true |
| `showAndTest` | `formName` | true |
| `testE2E` | `targetForm` | true |
| `showAndTestE2E` | `targetForm` | true |
| `generateFormSpec` | `formName` | true |
| `executeTestSetup` | `serverName` | true |
| `executeTestSetup` | `tableName` | true |
| `executeTestSetup` | `columnValuesJson` | true |
| `executeTestTeardown` | `serverName` | true |
| `executeTestTeardown` | `tableName` | true |
| `executeTestTeardown` | `whereColumn` | true |
| `executeTestTeardown` | `whereValue` | true |
| `createTestFile` | `testFileName` | true |
| `createTestFile` | `solutionName` | true |
| `addTestMethod` | `testFileName` | true |
| `addTestMethod` | `testMethodName` | true |
| `addTestMethod` | `testCode` | true |
| `generateTestCases` | `sourceCode` | true |
| `generateTestCases` | `functionName` | true |
| `analyzeCodeForTesting` | `selection` | true |
| `findForm` | `query` | false |
| `getFormNavigationGraph` | `formName` | false |
| `getFormNavigationGraph` | `summaryOnly` | false |
| `getNavigationPath` | `targetForm` | true |
| `getNavigationPath` | `fromForm` | false |
| `generateCypressE2ETest` | `targetForm` | true |
| `generateCypressE2ETest` | `scenario` | true |
| `generateCypressE2ETest` | `fromForm` | false |
| `generateCypressE2ETest` | `outputFileName` | false |
| `generateCypressE2ETest` | `baseUrl` | false |
| `generateCypressE2ETest` | `loginUrl` | false |
| `generateCypressE2ETest` | `testUsername` | false |
| `generateCypressE2ETest` | `testPassword` | false |
| `generateCypressE2ETest` | `loginSuccessSelector` | false |

### 3.4 Description cleanup

Remove "Optional:" prefixes from descriptions where `required = false` is being added. The description should explain _what_ the parameter is and how it behaves when omitted (e.g. "defaults to the solution main form") without redundantly saying "Optional".

## 4. Implementation plan

1. Edit `src/com/servoy/eclipse/developer/mcp/servers/ServoyTestingServer.java` in `com.servoy.eclipse.developer.mcp`:
   - Add `name = "<paramName>"` to every `@ToolParam` annotation (45 occurrences).
   - Add `required = false` to every optional parameter (per table in §3.3).
   - Remove "Optional:" prefix from descriptions where `required = false` is added; keep the default/fallback behaviour description.

2. Verify compilation: run `eclipse-ide_getCompilationErrors` to ensure zero errors.

3. Run the existing unit test `ServoyTestingServerTest` to confirm no regressions.

## 5. Acceptance criteria

- [ ] Every `@ToolParam` in `ServoyTestingServer.java` has an explicit `name` attribute matching the Java parameter name.
- [ ] Every semantically optional parameter has `required = false`.
- [ ] No `@ToolParam` description starts with "Optional:" — optionality is expressed via the `required` attribute.
- [ ] The MCP JSON schema for the `servoy-test` server exposes human-readable parameter names (not `arg0`, `arg1`).
- [ ] All existing tests pass (`ServoyTestingServerTest`).
- [ ] Zero compilation errors.

## 6. Out of scope

- Adding the `-parameters` compiler flag to `pom.xml` (would solve the name issue globally but is a broader change with side-effects).
- Changing tool descriptions or tool names themselves.
- Fixing other MCP servers (confirmed: no other server has this issue).
- Changing parameter types or method signatures.

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should `suggestTestsFromCoverage.maxFunctions` use `type = "integer"` with a default value annotation, or remain a primitive `int`? | Developer | open |
| Should `runTestMethod.scopeOrAll` change from `String` to allow null gracefully, or is the current null-handling in `JSUnitRunnerService` sufficient? | Developer | open |
