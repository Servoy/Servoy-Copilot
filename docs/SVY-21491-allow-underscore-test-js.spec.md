# Spec: SVY-21491 — MCP createTestFile should also allow `*_test.js`

## 1. Goal
The `createTestFile` MCP tool (exposed by `ServoyTestingServer`) currently accepts only
JSUnit test-file names that **start with** `test_` and end with `.js`. Many existing Servoy
solutions name their JSUnit test scopes with the opposite convention — `<name>_test.js`.
This change relaxes the tool's file-name validation so it accepts a `.js` file whose base
name **either** starts with `test_` **or** ends with `_test`, satisfying the reporter's
request to support both `test_*.js` and `*_test.js`. The tool/param descriptions and the
error message are updated to document both conventions.

## 2. Background
`createTestFile` creates a new JavaScript scope file in the active solution's root and
seeds it with a standard JSUnit header comment. The naming rule exists purely as a
convenience guard-rail in the **tool layer**:

- `ServoyTestingServer.createTestFile(testFileName, solutionName)`
  (`bundles/com.servoy.eclipse.developer.mcp/.../servers/ServoyTestingServer.java`,
  lines 466–507) performs two string checks before delegating to the service:
  ```java
  if (!testFileName.startsWith("test_"))
      return "Error: Test file name must start with 'test_' (e.g., 'test_utils.js')";
  if (!testFileName.endsWith(".js"))
      return "Error: Test file name must end with '.js' (e.g., 'test_utils.js')";
  ```
  The `@Tool` description (lines 466–468) and the `testFileName` `@ToolParam` description
  (line 470) hard-code the `test_*` convention.

- `TestFileService.createTestFile(...)` (the service layer) does **not** re-validate the
  name — it creates `project.getFile(testFileName)` verbatim. The tool layer is the only
  gate.

The triage established that **the JSUnit runner does not care about the file/scope name at
all**: test discovery is driven by *method* names (`TEST_METHOD_PREFIX = "test"` in
`SolutionJSUnitSuiteCodeBuilder`), and `JSUnitRunnerService` resolves a scope by stripping
`.js` from the bare name. A `*_test.js` scope containing `test*` methods is already fully
runnable; only the creation tool blocks the name. So relaxing this validator is safe and
carries no runner-side implications.

A separate, unrelated rule lives on `addTestMethod`: the *method* name must start with
`test_`. That genuinely mirrors the runner's method-name discovery and is **left
untouched** by this change.

## 3. Design

### 3.1 Relaxed file-name validation
Replace the current pair of guard clauses in `ServoyTestingServer.createTestFile` with a
check that:

1. First requires the name to end with `.js` (unchanged requirement, but evaluated first so
   the base name can be derived cleanly).
2. Then derives the base name (the part before `.js`) and accepts it if it **either** starts
   with `test_` **or** ends with `_test`.
3. Otherwise returns a single consolidated error message that documents both conventions.

Pseudocode (final implementation lives in `ServoyTestingServer.createTestFile`):
```java
if (testFileName == null || !testFileName.endsWith(".js"))
{
    return "Error: Test file name must end with '.js' (e.g., 'test_utils.js' or 'utils_test.js')";
}
String baseName = testFileName.substring(0, testFileName.length() - ".js".length());
if (!baseName.startsWith("test_") && !baseName.endsWith("_test"))
{
    return "Error: Test file name must follow the convention 'test_*.js' or '*_test.js' (e.g., 'test_utils.js' or 'utils_test.js')";
}
```

Notes:
- Deriving `baseName` and testing `endsWith("_test")` on it (rather than
  `testFileName.endsWith("_test.js")`) keeps the two conventions symmetric and reads
  clearly. Both forms are equivalent in behaviour; use whichever matches surrounding style.
- Keep the existing behaviour for the `.js` requirement and the downstream "already exists"
  guard in the service — only the *base-name convention* branch is widened.

### 3.2 Updated descriptions and error message
- `@Tool` `description` on `createTestFile`: change
  "File name must follow convention: test_functionName.js or test_fileName.js."
  to mention both patterns, e.g.
  "File name must follow convention: `test_*.js` or `*_test.js` (e.g. 'test_utils.js' or 'utils_test.js')."
- `testFileName` `@ToolParam` `description`: change
  "Must start with 'test_' and end with '.js'."
  to
  "Must end with '.js' and either start with 'test_' or end with '_test' (e.g. 'test_utils.js' or 'utils_test.js')."
- Error message: a single message naming both accepted forms (see 3.1).

### 3.3 Explicitly unchanged
- `addTestMethod`'s rule that the *method* name must start with `test_` stays as-is (it
  mirrors `TEST_METHOD_PREFIX` discovery).
- `TestFileService` remains free of name validation; the gate stays in the tool layer.

## 4. Implementation plan
1. In `ServoyTestingServer.createTestFile`
   (`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/servers/ServoyTestingServer.java`),
   replace the two existing guard clauses (lines 475–482) with the relaxed check from §3.1:
   require `.js`, then accept a base name that starts with `test_` **or** ends with `_test`,
   returning the consolidated error otherwise. Leave the rest of the method (TARGET
   resolution, delegation to `TestFileService`, catch block) unchanged.
2. Update the `@Tool` description string and the `testFileName` `@ToolParam` description
   string (lines 466–470) to document both `test_*.js` and `*_test.js` as in §3.2.
3. Extend `CreateTestFileIntegrationTest`
   (`tests/com.servoy.eclipse.developer.mcp.tests/.../integration/CreateTestFileIntegrationTest.java`)
   with cases that exercise the **tool-layer** validation via `new ServoyTestingServer()`:
   - a `*_test.js` name is accepted and the file is created on disk;
   - a `test_*.js` name is still accepted (regression guard — may reuse the existing
     service-level happy path, but add at least one through the tool);
   - a name matching neither convention (e.g. `utils.js`) returns an Error whose message
     names both conventions;
   - a non-`.js` name (e.g. `test_utils.txt`) still returns the `.js` Error.

   Because the convention check lives in the tool (not `TestFileService`), the new
   acceptance/rejection cases must call `server.createTestFile(name, SOLUTION_NAME)` on a
   `ServoyTestingServer` instance. Reuse the existing project-fixture setup and
   `deleteFileIfExists` teardown; add teardown for any new file names used. The
   neither-convention and non-`.js` rejection cases return before any file is created, so
   they do not need a solution to exist (pass the existing `SOLUTION_NAME`).
4. Follow the post-modification compile loop: `eclipse-ide_getCompilationErrors` →
   quick-fix if needed → re-check clean.
5. Verify with `eclipse-pde_runJUnitPluginTests` targeting `CreateTestFileIntegrationTest`
   (integration — needs the Eclipse workbench + Servoy App Server).

## 5. Acceptance criteria
- [ ] `createTestFile` accepts a `.js` name that **starts with** `test_` (unchanged
      behaviour preserved).
- [ ] `createTestFile` accepts a `.js` name whose base **ends with** `_test`
      (e.g. `utils_test.js`) and creates the file.
- [ ] `createTestFile` rejects a `.js` name matching neither convention
      (e.g. `utils.js`) with an error that names both `test_*.js` and `*_test.js`.
- [ ] `createTestFile` still rejects a non-`.js` name with the `.js` requirement error.
- [ ] The `@Tool` and `testFileName` `@ToolParam` description strings document both
      `test_*.js` and `*_test.js`.
- [ ] `addTestMethod`'s method-name rule (`test_` prefix) is unchanged.
- [ ] `CreateTestFileIntegrationTest` is extended with the cases above and passes;
      the workspace compiles with zero errors.

## 6. Out of scope
- Moving or centralizing the naming rule into `TestFileService` (triage approach 2) — no
  present benefit; only one caller.
- Dropping file-name validation entirely / accepting any `.js` (triage approach 3) —
  over-broad relative to the request.
- Any change to `addTestMethod`, the JSUnit runner, or `SolutionJSUnitSuiteCodeBuilder`.
- Registering the test class in a new suite/pom entry — `CreateTestFileIntegrationTest`
  already exists and is already registered; no new class is added.

## 7. Open questions
| Question | Owner | Status |
|----------|-------|--------|
| Should a name using *both* conventions (e.g. `test_utils_test.js`) be accepted? Implicitly yes under the OR rule; no special handling planned. | Rene van Veen | open |
| Is case sensitivity a concern (e.g. `Test_utils.js` / `utils_TEST.js`)? Current/relaxed checks are case-sensitive, matching existing behaviour; no change proposed unless requested. | Rene van Veen | open |
