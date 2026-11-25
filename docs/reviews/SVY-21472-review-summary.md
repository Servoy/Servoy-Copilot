# SVY-21472 review summary — MCP: applyPatch: why 20% of patches fail

**Risk: ELEVATED.** The change introduced a bug in `deleteLinesInFile`: deleting lines from a file that ends with a newline adds a blank line at the end, every time. `applyPatch` can also report a clean `Success` after editing an exact copy of the code far from the intended spot, with no drift note. Both were confirmed by a manual test run (see below); 7 of the 12 steps that were run pass fully.

Scope reviewed:
- Servoy-Copilot `master` at `38c59dc` (SVY-21472 make MCP applyPatch robust to real-world patch input [ai]).
- The upstream AssistAI port in eclipse-chatgpt-plugin, `f1c38ce`, merged upstream as PR #168 (`44b714d`). Upstream does not have the `deleteLinesInFile` bug, but its locator is designed the same way.

## Manual test plan

Setup: in a test solution, create `lf.js` (LF line endings) and `crlf.js` (CRLF), each with 300+ lines. After every step, check the bytes on disk with a hex view or `git diff` showing whitespace.

**Verifying the fix**
1. Placeholder header: in `crlf.js`, run `applyPatch` with `@@ -1 +1 @@` and a unique 3-line context block near line 250.
   - Expect: Success, the change lands around line 250, and the file is still CRLF.
   - Expect: `git diff` shows only the hunk.
2. Malformed header: run `applyPatch` with `@@ @@`. Expect an error that quotes the header and does not mention "line 0".
3. Trailing whitespace: add two trailing spaces to a context line in the file (not in the patch), then apply. Expect Success with an "Applied with fuzzy matching (tier: trailing-whitespace)" note.
4. Ambiguous context: use a context block that appears twice, both copies more than 50 lines from the header. Expect an error that lists both locations, and the file left unchanged.
5. CRLF patch body: apply a CRLF-encoded patch to `crlf.js`. Expect no stray CR characters and no mixed line endings.
6. Missing context: apply a patch whose context does not exist in the file. Expect an error showing the expected block, the actual lines at the hint, and the range that was searched.

**Regression checks**
7. `deleteLinesInFile` on a file containing `a\nb\nc\n`, deleting lines 2–2.
   - Expect exactly `a\nc\n`. Current HEAD produces `a\nc\n\n`.
   - Repeat on a CRLF file.
   - Repeat the delete three times in a row and check that blank lines do not pile up.
8. `deleteLinesInFile` with `endLine` set to the real line count + 1 on a newline-terminated file. Expect it to be rejected.
9. Far exact copy:
   - Setup: line 1 is `x = 1;  ` (trailing spaces), line 2 is `y = 2;`, and lines 123–124 hold an exact copy `x = 1;` / `y = 2;`.
   - Run `applyPatch` with `@@ -1,2 +1,2 @@`, changing `y = 2;` to `y = 3;`.
   - Note which copy changed and whether any note came back.
10. Duplicates inside the window: use the lines `a`, `  k = 0;`, `b`, `  k = 0;`, `c`. Run `applyPatch` with `@@ -3,1 +3,1 @@`, changing `k = 0;` to `k = 9;`, and note which line changed.
11. Indentation-only patch: apply `-  doIt();` / `+    doIt();` to a file that already has `    doIt();`, and check the resulting indentation. Repeat in a tab-indented file.
12. `insertIntoFile` and `replaceString` on `crlf.js` and on a file without a trailing newline:
    - line endings are preserved;
    - no trailing newline is added;
    - inserted multi-line content uses the file's line endings.
13. A patch that matches exactly at its header behaves as before, with no drift note.
14. A 3-hunk patch with real headers on a large `.frm` file: every hunk lands correctly.

## Manual test run (2026-11-25)

Steps 1–4, 6–13 were run through the Servoy AI chat against Servoy Developer 2026.3 LTS (bundle containing `38c59dc`). Each step used its own fresh fixture file; every tool was called with fixed arguments, no retries; results were checked against the bytes on disk. Steps 5 and 14 were not run (a CRLF patch body and a multi-hunk `.frm` patch can't be driven reliably through the chat; better as unit tests).

| Step | Result | Verdict |
|---|---|---|
| 1 | Applied at line 250 of a 300-line CRLF file; still 300 CRLF / 0 LF | Pass |
| 2 | `Malformed hunk header: @@ @@` error, file unchanged | Pass |
| 3 | Applied with `tier: trailing whitespace` note, trailing spaces kept | Pass |
| 4 | Error listing lines 100 and 160, file unchanged | Pass |
| 6 | Error with expected block, actual lines at hint, searched range | Pass |
| 7 | `a\nb\nc\n` minus line 2 → `a\nc\n\n`; CRLF → `a\r\nc\r\n\r\n`; three deletes of line 1 → `\n\n\n` | **Fail** (confirmed bug) |
| 8 | Deleting line 4 of a 3-line file returns `Success: Deleted 1 line(s)`, file unchanged | **Fail** (false success, same root cause) |
| 9 | Exact copy at line 124 edited instead of line 2, no note | Finding confirmed |
| 10 | Line 4 edited; note does not mention the equally near candidate at line 2 | Finding confirmed |
| 11 | Space file: `doIt();` ends up with 6 spaces (double indent). Tab file: tabs preserved | Space case questionable, tab case pass |
| 12 | `insertIntoFile` CRLF and no-trailing-newline, `replaceString` CRLF all correct | Pass |
| 13 | Exact match at header, no fuzzy note | Pass |

Side observation (not a regression of this commit): every expected `applyPatch` failure (steps 2, 4, 6) is thrown as a `RuntimeException` from `CodeEditingService` and logged by `McpServerFactory.executeCallTool` as a full ERROR entry with stack trace in the Eclipse error log, and the message reaches the agent prefixed `Error: Error: Error:`. With a ~20% patch failure rate this floods the Error Log view.

Jira comments on the issue: test plan (235147), author questions (235148), test-run results (235150).

Automated: `eclipse-ide_runJUnitTests` with project `com.servoy.eclipse.developer.mcp.tests` and package `com.servoy.eclipse.developer.mcp.plainjunit`. Also run `ServoyCoderServerIntegrationTest` through `eclipse-pde_runJUnitPluginTests`.

## Possible improvements / follow-ups
- **`deleteLinesInFile`** (confirmed by test run, steps 7–8): drop the trailing empty token when `trailingNewline` is set, or reuse `readFileLines`. This also fixes the false `Success` for `endLine` = line count + 1. Add plain-JUnit tests with newline-terminated LF and CRLF files, repeated deletes, and the out-of-range line.
- **Expected tool failures as ERROR log entries**: return "patch does not apply" style validation failures as a normal tool error (or log at WARNING/INFO without stack trace) instead of throwing a `RuntimeException` that `McpServerFactory` logs as ERROR; this also removes the triple `Error: Error: Error:` prefix.
- **Locator policy**:
  - Decide whether an exact match outside the ±50-line window should count as drift, or whether looser tiers should be tried near the hint before the strict whole-file scan.
  - Consider a uniqueness check for matches inside the window, at least for the loose tiers.
- **Re-indentation**: re-indenting added lines on a fuzzy match double-indents indentation-only patches (confirmed in step 11: 2→4-space patch on a 4-space line yields 6 spaces). The tab-file case kept tabs correctly in the test run.
- **Error and note text**:
  - Cap the candidate list in the ambiguity error.
  - The tier-4 note says `tier: exact`, which is misleading.
- **Performance**: whole-file scans could be slow on very large files. Consider pre-filtering on the first expected line.
- **`docs/assistai-sync.md`**: record the new Servoy/upstream divergences:
  - Servoy splits patches on `\r?\n`, upstream on `\R`;
  - the end-of-line helpers exist only in Servoy;
  - upstream deletes lines through `replaceLineRange`;
  - upstream `5a8e07b` (replaceString fallback) has not been ported.
- **Upstream drive-by** (`ATTR_BUILD_BEFORE_LAUNCH=false` in `PDEService`): check that the launch configuration actually reads this attribute.

## Release target
Fix Version `2026.12`. **INDETERMINATE**:
- Servoy-Copilot has only `master`, with no tags or release branches, and its bundle version is not tied to Servoy core numbering. Confirm that the 2026.12 Copilot build is cut from a master revision that contains `38c59dc` and, ideally, the `deleteLinesInFile` fix.
- The upstream plugin is not a Servoy release artifact.
