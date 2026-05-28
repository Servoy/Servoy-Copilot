---
name: increase-test-coverage
description: >
  Workflow for adding unit tests to increase line and branch coverage of any
  class or module. Use this skill whenever the user asks to improve test
  coverage, add tests to increase line or branch coverage, find untested code
  paths, or reduce missed lines or branches. Trigger even for partial requests
  like "what code isn't tested" or "write a test for this method". Works with
  any language and test framework (JUnit, pytest, Jest, etc.) and any coverage
  tool.
compatibility:
  tools:
    - eclipse-ide_runClassTests
    - eclipse-ide_readProjectResource
---

# Increasing Unit Test Coverage

## Key Files

Fill these in for your project before starting:

| Role | Path |
|---|---|
| Test class | `` |
| Class under test | `` |
| Constraints doc | `` |

---

## The Loop

Repeat this cycle until coverage plateaus or only blocked paths remain.

```
Run with coverage → Extract gaps for target file → Read uncovered lines
→ Check for duplicates → Write tests → Run again → Confirm gains
```

**Stop condition:** When `lines.nocovered` for the target file stops shrinking
between iterations, or all remaining lines are in blocked paths
(see [`specs/coverage-targets.md`](specs/coverage-targets.md)).

**Session target:** Aim to eliminate a meaningful batch of uncovered lines
before re-running. For large files, 20–30 lines per iteration is a good target;
adjust to the size of the codebase.

---

## Step 1 — Run Tests with Coverage

Run the test suite with coverage enabled, filtered to the file you care about.
Confirm **0 failures and 0 errors** before writing any new tests — do not add
tests on top of a broken baseline.

Example using the Eclipse IDE MCP tool:
```
eclipse-ide_runClassTests(
  projectName  = "<test-project-name>",
  className    = "<fully.qualified.TestClass>",
  withCoverage = true,
  classFilter  = "<fully.qualified.ClassUnderTest>",
  timeout      = 240
)
```

---

## Step 2 — Extract the Coverage Gap

The response may be large and truncated. The full output is saved to a temp
file shown in the response. Extract the entry for your target file:

```powershell
# Find the line number of the target file entry
Select-String -Path "<temp-file>" -Pattern '"TargetFile\.java"'

# Read around that line (adjust offset as needed)
Read <temp-file> offset=<line-number - 5> limit=40
```

If there are multiple hits, match on the package name of the class under test.

The entry looks like:
```json
{
  "sourcefile": "TargetFile.java",
  "package": "com.example.package",
  "lines": {
    "nocovered":        [233, 259, 273, ...],
    "partiallycovered": [232, 258, 264, ...]
  },
  "branch": {
    "nocovered":        [291, 293, ...],
    "partiallycovered": [232, 258, ...]
  }
}
```

Work from `lines.nocovered` first (completely untouched lines), then
`branch.nocovered` (lines reached but with untaken branches).

---

## Step 3 — Identify What to Test

For each cluster of uncovered line numbers:

1. Read those lines in the source file:
   ```
   eclipse-ide_readProjectResource(
     path            = "<path/to/TargetFile.java>",
     showLineNumbers = true,
     startLine       = <first uncovered line - 5>,
     endLine         = <last uncovered line + 5>
   )
   ```

2. Before writing a test, check whether a test already covers this path:
   ```
   eclipse-ide_readProjectResource(
     path = "<path/to/TestClass.java>",
     showLineNumbers = true
   )
   ```
   Search for the input string or method name you'd use.

3. Check whether the path is permanently blocked — see
   [`specs/coverage-targets.md`](specs/coverage-targets.md)
   under "Permanently Blocked Paths". If blocked, skip and move to the next cluster.

4. Check whether the construct is restricted by the project constraints doc —
   see the path noted in Key Files above.

**Prioritization:** When multiple clusters are available, pick the one with the
most uncovered lines first. The high-value targets list in
[`specs/coverage-targets.md`](specs/coverage-targets.md) gives good starting points.

---

## Step 4 — Write the Tests

Three common patterns (adapt syntax to your language and framework):

### Happy path test
```java
@Test
public void testMyFeature() {
    // set up minimum input to reach the target path
    ResultType result = unitUnderTest.method("input");
    assertNotNull(result);
    // further structural or value assertions
}
```

### Restricted / edge case test
For features only available in certain modes or versions, test using only the
appropriate entry point and skip cross-parser or cross-mode comparison.

### Error / recovery test
```java
@Test
public void testBadInput_reportsError() {
    // instantiate the unit directly
    ErrorType[] errors = unit.parse("invalid input", null);
    assertTrue(errors.length > 0);
}
```

**Hard rules for new tests:**

- Do NOT introduce duplicate test method names.
- Do NOT use constructs forbidden by the project constraints doc.
- Do NOT hard-code source positions — use relational assertions
  (`>= 0`, `> startPosition()`).
- Always assert boolean helper return values: `assertTrue(equals(...))`,
  never bare `equals(...)`.

For project-specific cast rules, helper methods, and import patterns, see
[`specs/api-patterns.md`](specs/api-patterns.md).

---

## Step 5 — Verify Gains

Re-run with coverage and re-extract the target file entry.
Compare `lines.nocovered` count before and after. If the count did not shrink:

- Check for silent compile errors — the test may not have been picked up.
- Check that the input you wrote actually reaches the target line — add a
  temporary log statement to confirm if needed.
- Check whether an intermediate condition short-circuits before the target line.
- If the line is truly unreachable, record it as blocked in
  [`specs/coverage-targets.md`](specs/coverage-targets.md) and move on.

---

## Constraints Summary

- **0 failures, 0 errors** must be maintained at all times.
- Do not write tests that duplicate behavior already covered by an existing test.
- Do not use constructs forbidden by the project constraints doc.
- Do not modify existing tests unless they have a compile error from an API
  change — look up the correct API in the source, don't guess.
- Always assert boolean helpers; never call them bare.

---

## Reference Files

| File | When to read |
|---|---|
| [`specs/api-patterns.md`](specs/api-patterns.md) | When writing tests: cast rules, helper methods, import patterns specific to this project |
| [`specs/coverage-targets.md`](specs/coverage-targets.md) | When prioritizing uncovered lines; when deciding whether a path is permanently blocked |
