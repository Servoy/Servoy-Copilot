# Triage Report — SVY-21491

**Verdict:** PROCEED

## Reported problem
The `createTestFile` MCP endpoint (tool `createTestFile` in `ServoyTestingServer`)
currently only accepts test-file names that **start with** `test_` (and end with `.js`).
Many existing Servoy solutions name their JSUnit test scopes with the opposite
convention — `<name>_test.js` — and the tool rejects those names outright. The reporter
(Rene van Veen) asks that **both** `test_*.js` and `*_test.js` be accepted.

This is a feature/enhancement request, not a defect report. The ticket already proposes
the solution: support both naming patterns.

## Root-cause assessment
The restriction is a pair of plain string checks, not a functional requirement of the
JSUnit runner.

- **Tool layer** — `ServoyTestingServer.createTestFile(...)`
  (`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/servers/ServoyTestingServer.java`,
  lines 466–507):
  ```java
  if (!testFileName.startsWith("test_"))
      return "Error: Test file name must start with 'test_' (e.g., 'test_utils.js')";
  if (!testFileName.endsWith(".js"))
      return "Error: Test file name must end with '.js' (e.g., 'test_utils.js')";
  ```
  The `@Tool`/`@ToolParam` description text (lines 466–470) also hard-codes the
  `test_functionName.js` convention.

- **Service layer** — `TestFileService.createTestFile(...)`
  (`.../services/TestFileService.java`, lines 76–108) does **not** re-validate the name;
  it just creates `project.getFile(testFileName)`. So the only gate is the tool-layer
  check above. (`addTestMethod` validates the *method* name starts with `test_`, which is
  a separate, unrelated concern — see below.)

Crucially, **the JSUnit test runner does not care about the file/scope name at all.** Test
discovery is driven by *method* names, not file names:
`com.servoy.eclipse.model.test.SolutionJSUnitSuiteCodeBuilder`
(`servoy/master/servoy-eclipse/com.servoy.eclipse.model/.../SolutionJSUnitSuiteCodeBuilder.java`)
defines `TEST_METHOD_PREFIX = "test"` (line 56) and iterates every scope's script methods
(line 216 `solution.getScopeNames()`, line 313 `method.getName().startsWith(TEST_METHOD_PREFIX)`),
adding any method whose name starts with `test` to the suite. Likewise
`JSUnitRunnerService.buildTestTarget(...)` resolves `scopeOrAll` by stripping the `.js`
extension and matching the bare scope name — it never requires a `test_` prefix on the
file. Therefore a `*_test.js` scope containing `test*` methods is already fully runnable;
only the *creation* tool blocks the name.

So the "root cause" is simply that the convenience validator in the MCP tool encodes one
of the two conventions and rejects the other. There is no deeper design constraint behind
it.

## Ticket premise check
The ticket's premise holds up. It correctly identifies the single gate (`createTestFile`
rejecting non-`test_`-prefixed names) and proposes exactly the right relaxation: accept
both `test_*.js` and `*_test.js`. Investigation confirms the runner imposes no file-name
convention, so broadening the accepted file names is safe and does not conflict with any
other mechanism.

One refinement worth noting for implementation (not a contradiction of the premise): the
check should accept a name that **either** starts with `test_` **or** ends with `_test`
(before the `.js`), while still requiring the `.js` extension. The `addTestMethod` tool's
separate rule — the *method* name must start with `test_` — should be left unchanged, as
that genuinely reflects what the runner discovers (`TEST_METHOD_PREFIX`).

## Approaches considered
1. **Relax the file-name validation in `ServoyTestingServer.createTestFile` to accept both
   `test_*.js` and `*_test.js`** (recommended). Change the two guard clauses to allow a
   name that ends with `.js` and whose base either starts with `test_` or ends with
   `_test`; update the `@Tool`/`@ToolParam` description strings to document both
   conventions; update the error message accordingly.
   - *Pros:* Minimal, localized change in the active bundle; matches the runner's actual
     (file-name-agnostic) behaviour; directly satisfies the request; easy to unit/integration
     test (`CreateTestFileIntegrationTest` already covers this tool).
   - *Cons:* None of note. Slightly looser input validation, but still guarded by the `.js`
     requirement and the "already exists" check.

2. **Move/centralize the naming rule into `TestFileService` (service layer).**
   - *Pros:* Would make the rule reusable if another caller ever creates test files.
   - *Cons:* Currently there is only one caller; the service deliberately does no name
     validation today. Adds scope without present benefit. Not recommended for this ticket.

3. **Drop file-name validation entirely (accept any `.js`).**
   - *Pros:* Simplest possible code.
   - *Cons:* Loses the guard-rail that nudges the AI/user toward a discoverable convention;
     the tool description promises a convention. Over-broad relative to the request, which
     asks for *two* conventions, not *none*. Not recommended.

4. **No code change.**
   - *Pros:* Zero risk; users can already *run* `*_test.js` scopes once created by other
     means.
   - *Cons:* Does not address the request — the `createTestFile` tool still refuses the
     common `*_test.js` name, forcing a rename or manual file creation. The reporter
     explicitly wants the tool to accept both. Honest evaluation: rejecting this would
     leave a real, intended gap.

## Recommendation
**PROCEED with Approach 1.** Relax the two validation checks in
`ServoyTestingServer.createTestFile` so a `.js` file whose base name either starts with
`test_` or ends with `_test` is accepted, and update the tool/param descriptions and error
message to mention both `test_*.js` and `*_test.js`. Leave `addTestMethod`'s method-name
rule (`test_` prefix) untouched, since that mirrors the runner's `TEST_METHOD_PREFIX`
discovery. Extend `CreateTestFileIntegrationTest` with cases for the `*_test.js` pattern and
for a name matching neither convention (should still error).

Alternatives 2 and 3 are viable but either premature (2) or over-broad (3); 4 does not meet
the request.

## Git history findings
- `createTestFile`'s validation was last touched by `b3aa83d` (Johan Compagner,
  2026-08-12, *"SVY-21339 fix @ToolParam annotations across all MCP servers [ai]"*) — a
  cosmetic annotation fix, not a deliberate decision to restrict file naming. The
  `test_`/`.js` checks predate it and carry no design rationale tied to the runner.
- `TestFileService.java` history (`aa548ff`, `cc3914d`) shows no naming-convention
  constraint introduced at the service layer; the service has always created the file
  verbatim.
- `SolutionJSUnitSuiteCodeBuilder` (servoy/master) has long used method-name discovery
  (`TEST_METHOD_PREFIX = "test"`), confirming the file name is irrelevant to running tests.
  No regression or version bump is involved — this is a never-supported case, not a broken
  one.
