# Spec: SVY-21472 — MCP applyPatch: fix the 20% patch-failure rate

## 1. Goal

Make the MCP `applyPatch` tool apply the *shape of patch the model actually
produces* instead of only well-formed unified diffs. Measured over 323 real
`servoy-editor_applyPatch` calls from six Windows projects, **20% (65) fail** —
62 with `Could not find matching context for hunk at line N` and 3 with a
missing file. The failures are inherent to the input (13% of even the
*successful* patches carry a placeholder `@@` header), not trainable model
misbehaviour. This spec adds a full-file content-addressed fallback, preserves
the file's original line endings across every editing operation, returns
actionable evidence on failure, rejects malformed headers explicitly, decides
the fate of the parsed-but-unused `originalCount`, and adds minimal fuzz — plus
a dedicated `CodeEditingServiceTest`. All work is confined to
`CodeEditingService.java` and a new test class; nothing else changes.

## 2. Background

### 2.1 Where the patch engine lives

The entire patch engine is in
`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/CodeEditingService.java`,
ported verbatim from AssistAI (commit `8c12337a`, "SVY-20972 Create the
supervisor agent in ServoyAI" — landed as part of a large agent-setup commit,
not as a considered diff-engine design). The relevant methods:

- `parseHunks` (632–664) — splits the patch into `DiffHunk`s, parses the `@@`
  header with `Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*")`.
- `applyUnifiedDiff` (666–674) — reverses the hunks and applies each in turn.
- `applyHunk` (676–700) — builds the expected (context + removed) block, calls
  `findMatchPosition`, splices in the replacement (context + added) block.
- `findMatchPosition` (702–713) — exact hint, then ±1..50 lines around it, else
  `-1`.
- `matchesAt` (715–721) — byte-exact `equals` on every context line.

The `applyPatch` entry point (447–485) reads the file with `readFileLines`
(529–541, `BufferedReader.readLine()`), applies the diff, then re-joins with a
hard-coded `"\n"` (461–467) and writes back.

### 2.2 The five defects (all confirmed line-by-line in triage)

1. **No full-file fallback (biggest cause).** `applyHunk` (683) computes a hint
   from `hunk.originalStart - 1` and hands it to `findMatchPosition`, which
   searches only the hint ±50 lines. The model routinely emits placeholder
   headers (`@@ -1 +1 @@`, every hunk claiming line 1) while the real target is
   hundreds of lines down, so the ±50 window never reaches it. Measured: for
   placeholder/unparseable headers the failure rate is **40%** (23 of 57);
   for real line numbers it is 15% (39 of 258).

2. **Malformed header silently becomes line 0.** `parseHunks` (647–651) sets
   `originalStart` only inside `if (matcher.matches())` with **no `else`**, so
   an unparseable header (`@@ @@`, seen 6 times = 10% of failures) leaves the
   Java default `0`, the hint becomes `-1`, and the error reports "hunk at line
   0" — a line that does not exist.

3. **`originalCount` parsed but never used.** Declared (628), assigned (650),
   never read in `applyHunk`. In **256 of 308 patches (83%) the header count and
   the actual context+removed line count disagree** — so a truncated hunk can be
   applied as if whole whenever it happens to match.

4. **Zero fuzz.** `matchesAt` (719) does byte-exact `lines.get(...).equals(...)`
   on every context line. One trailing space, tab-vs-spaces, or a re-wrapped
   line rejects the whole hunk.

5. **Error carries no evidence.** `applyHunk` (685–686) throws only
   `Could not find matching context for hunk at line N. The file may have been
   modified…` — no expected block, no actual lines at the hint, no searched
   range. The agent's only recovery is to re-read and retry, which is what the
   62 failures cost.

### 2.3 Separate defect — CRLF silently converted to LF

`readFileLines` (529–541) uses `BufferedReader.readLine()`, which discards the
line terminator; the write side (461–467) re-joins unconditionally with `"\n"`.
Every successful patch on a CRLF file rewrites it as LF, turning a one-line
change into a whole-file git diff. All six measured projects are Windows.

The same LF-only assumption exists in the sibling editing methods:

- `insertIntoFile` (113–122) — joins with `"\n"`.
- `replaceStringInFile` (156–181) — joins with `"\n"`.
- `deleteLinesInFile` (412, 426) — splits on `\r?\n` (discarding the terminator)
  and re-joins with `"\n"`.

Because the whole service assumes LF, the fix is applied **service-wide**, not
only in `applyPatch`.

### 2.4 Test landscape

There is currently **no** `CodeEditingServiceTest`. The only related test is
`ServoyCoderServerTest`
(`tests/com.servoy.eclipse.developer.mcp.tests/src/test/java/com/servoy/eclipse/developer/mcp/servers/ServoyCoderServerTest.java`),
which exercises the *server* wrapper's null-input and guard paths, not the patch
algorithm. This bundle is **mixed** JUnit 4 and JUnit 5/6 (Jupiter): the MANIFEST
imports both APIs and the newest tests (e.g. `FormatValidatorServiceTest`,
`NavigationGraphTest`, `PersistDuplicateServiceTest`) are Jupiter. **New plain unit
tests use JUnit 5/6 (Jupiter)** — the current standard for this bundle and the SDD
test-gen mandate; JUnit 4 is only for extending existing JUnit 4 classes. Both run
with `eclipse-ide_runJUnitTests` (no OSGi). The patch parsing/matching logic
(`parseHunks`, `applyHunk`, `findMatchPosition`, `matchesAt`, and the fuzz/splice/
line-ending helpers) is pure — no Eclipse workspace, no OSGi runtime — so it can be
tested plainly *provided the methods are reachable from the test package*.

## 3. Design

The design keeps the hand-rolled parser (a real unified-diff library rejects the
malformed pseudo-diffs this tool must accept — see triage Approach 3) and hardens
it along the ticket's priority order.

### 3.1 Line-ending preservation (service-wide)

Introduce a tiny internal helper that carries the file's text *and* its dominant
line ending, and route every write through it.

- Add a method `detectDominantLineEnding(String content)` returning `"\r\n"`,
  `"\r"`, or `"\n"`:
  - Count `\r\n`, lone `\r`, and lone `\n` occurrences.
  - Pick the most frequent terminator. **On a CRLF-vs-LF tie, prefer `"\n"` (LF)**
    (resolved open question 3).
  - Default to the platform-neutral `"\n"` for a file with no terminator (empty
    or single unterminated line).
- Add `boolean endsWithNewline(String content)` so a file that had no trailing
  newline is not given one (avoid a spurious final-line diff).
- Change `readFileLines` to keep returning the split lines (terminators removed),
  but have callers obtain the raw content once (via `readFileContent`) so they
  can detect the ending and the trailing-newline state before writing.
- Replace every hard-coded `append("\n")` join in `applyPatch` (461–467),
  `insertIntoFile` (113–122), `replaceStringInFile` (156–181), and
  `deleteLinesInFile` (419–428) with a shared
  `joinLines(List<String> lines, String eol, boolean trailingNewline)` helper
  that uses the detected `eol` and only appends a trailing terminator when the
  original had one.
- `deleteLinesInFile` currently splits on `\r?\n`; keep that split for line
  identification but preserve the detected `eol` on re-join.

Charset handling is unchanged: continue reading/writing with `file.getCharset()`
(`applyPatch`, `insertIntoFile`, `replaceStringInFile`) — `deleteLinesInFile`
hard-codes UTF-8 today and should be left as-is except for the join fix, to keep
this change focused on line endings.

### 3.2 Full-file fallback search with ambiguity reporting

Rework `findMatchPosition` (or add a `locateHunk` wrapper) so location degrades
gracefully:

1. **Hint window (unchanged fast path).** Try exact hint, then ±1..50, using the
   fuzz-aware matcher (3.4). If a unique match is found in the window, use it.
2. **Full-file fallback.** When the window fails, scan the whole file for the
   expected block:
   - Collect **all** positions where the expected block matches (exact first;
     see 3.4 for the fuzz tiers).
   - **Exactly one match** → use it (this is the dominant real-world case: the
     context block in these hunks is long and unique).
   - **More than one match** → do **not** guess. Throw an ambiguity error naming
     the count and the candidate line numbers, e.g. *"The hunk context matches N
     locations (lines a, b, c); the @@ header did not disambiguate. Re-emit the
     patch with a correct @@ line number or a longer unique context."*
   - **Zero matches** → fall through to the evidence-rich failure (3.3).
- An empty expected block (pure insertion) keeps the current behaviour: clamp the
  hint to `[0, lines.size()]`; if the hint is a placeholder/invalid, this becomes
  a malformed-header rejection (3.5) rather than a silent insert at 0.

### 3.3 Evidence-rich failure message

Replace the bare message in `applyHunk` (685–686) with a message that lets the
agent recover in one retry. Include:

- The **searched range** (hint line and the ±50 window bounds, and the fact that
  a full-file scan was also performed).
- The **expected block** (the context+removed lines the hunk looked for),
  truncated to a cap of **10 lines** to bound output (resolved open question 1).
- The **actual lines at the hint** (the file's current lines around the hint
  position), capped at **10 lines**.

Keep the message a single thrown `RuntimeException` (the existing contract —
`applyPatch` surfaces it to the MCP layer as the tool error string). Format it as
readable multi-line text, not a stack trace.

**Drift signal on fuzzy success (resolved open question 2).** When a hunk is
applied but matched only via a *fuzzy* tier (trailing-ws, leading+trailing content
match, or drop-outer-context) — i.e. the file has drifted from what the patch
expected — the tool result must tell the caller so it can re-read the changed
region to confirm the outcome. Because `applyPatch` returns a success string to the
MCP layer, append a concise, bounded note to that success message, e.g. *"Applied
with fuzzy matching (tier: leading/trailing whitespace) — the file had drifted from
the patch context; re-read the affected region (around line N) to verify."* Include
the line where the hunk landed and, when the `originalCount` disagreed with the
actual block size, note that too. Keep it a short suffix, not a dump of the diff.

### 3.4 Fuzz — tiered, whitespace-tolerant matching

Extend the matcher used by both the window and full-file passes into an ordered
set of tiers. Each tier is tried, per candidate position, only after all stricter
tiers have failed everywhere — so a match at a stricter tier is always preferred
over a looser one, and a loose match never overrides an available strict match at
another position:

1. **Exact** — current byte-exact `equals` (fast path, tried first everywhere).
2. **Ignore trailing whitespace** — compare `stripTrailing()` of both sides,
   line by line.
3. **Ignore leading *and* trailing whitespace (content match).** Normalize each
   line on both sides with `strip()` (drops leading + trailing spaces and tabs)
   and compare the resulting non-whitespace content, line by line, across the
   whole expected block. This is the tier that catches tab-vs-spaces, re-indented
   blocks, and mixed indentation drift while still requiring the real text of
   every line to line up.
4. **Drop first and last context lines** — retry the match (through tiers 1–3)
   with the outermost context line(s) removed from the expected block (GNU
   `patch`-style fuzz), adjusting the splice offsets so the replacement still
   lands correctly.

**Write-back rule for the content-match tier (critical).** When a hunk is located
via tier 3 (or tier 2), the patch's own whitespace must **not** be pasted over the
file's real indentation. Splice as follows:

- **Context lines** (` `-prefixed) and **removed lines** (`-`-prefixed) are dropped
  and replaced from the file — i.e. for the kept context the code re-uses the
  **file's original lines** verbatim, never the hunk's copy.
- **Added lines** (`+`-prefixed) are the only genuinely new text. To avoid pasting
  the patch's indentation into a file that uses different indentation, **re-indent
  each added line** to match the surrounding file context: compute the leading
  whitespace of the nearest retained context/removed line at the splice point in
  the *file*, and the leading whitespace the hunk *expected* there; apply the delta
  to each added line's own indentation (a simple prefix-swap when the block is
  uniformly re-indented). If the added line's expected indent cannot be related to
  the file's (no adjacent context), fall back to the added line's own indentation
  unchanged.
- This keeps the file's real indentation intact and only rewrites the lines the
  hunk actually changes, so a whitespace-only difference in *context* never leaks
  into the written result.

Content-match and drop-outer-context are strictly **fallback** tiers: the whole
whitespace-insensitive machinery only runs after exact/trailing matching has failed
across the hint window *and* the full-file scan. Record which tier matched so the
success path and diagnostics can note that fuzz (and, for tier 3, re-indentation)
was used — optional in the returned message, not required for the tool result.

> **Deliberately not done now:** a whole-block "collapse all whitespace including
> newlines into a single space and search the file's joined text" match (which would
> also absorb re-wrapped/reflowed lines) is intentionally **out of scope** — it makes
> write-back span-mapping much harder and risks over-matching. Line-level
> strip-leading+trailing (tier 3) is the agreed depth. See Out of scope.

### 3.5 Reject malformed `@@` headers explicitly

In `parseHunks`, add the missing `else` branch: when the header line starts with
`@@` but the regex does **not** match, mark the hunk as malformed (e.g. an
`originalStart = -1` sentinel or a `boolean headerParsed` flag on `DiffHunk`).
When applying such a hunk, throw immediately with a message that **names the
offending header line verbatim**, e.g. *"Malformed hunk header: `@@ @@`. Expected
the form `@@ -<start>[,<count>] +<start>[,<count>] @@`."* Do not let it silently
become line 0.

### 3.6 Decide `originalCount`

The count is decorative today and disagrees with reality in 83% of patches, so
**validating it strictly would break more patches than it fixes**. Choose the
low-risk option that matches the ticket's "validate *or* drop" wording:

- **Drop it as a hard gate**, but **use it as a soft hint**: keep parsing it, and
  when the actual context+removed line count differs from `originalCount`, include
  that discrepancy in the *failure* diagnostics (3.3) only — never reject a hunk
  that otherwise matches solely because the count disagrees. This removes the
  "truncated hunk applied as if whole" risk indirectly: a truncated hunk fails to
  match the file content anyway, and when it does fail the count mismatch is now
  reported as a clue. Add a code comment explaining why strict validation was
  rejected (83% false-positive rate) so a future reader does not "fix" it.

### 3.7 Testability refactor

To let a plain JUnit test drive the algorithm without an Eclipse workspace, make
the pure logic reachable from the test package
(`com.servoy.eclipse.developer.mcp.services`):

- Change `parseHunks`, `applyUnifiedDiff`, `applyHunk`, `findMatchPosition`,
  `matchesAt`, `detectDominantLineEnding`, `joinLines`, and `endsWithNewline` from
  `private` to **package-private** (`static` where they already are). The
  `DiffHunk` inner class likewise package-private.
- No behavioural change to the public tool methods; only visibility widens. This
  keeps the OSGi-bound methods (`applyPatch`, `resolveFile`, etc.) private/instance
  as they are.

## 4. Implementation plan

All edits are in
`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/CodeEditingService.java`
unless noted.

1. **Line-ending helpers.** Add `static String detectDominantLineEnding(String content)`,
   `static boolean endsWithNewline(String content)`, and
   `static String joinLines(List<String> lines, String eol, boolean trailingNewline)`.
2. **Route writes through the helpers.** In `applyPatch`, capture the original
   content once, detect `eol` + trailing-newline, and build the output with
   `joinLines` instead of the `"\n"` loop (461–467). Do the same for
   `insertIntoFile` (113–122), `replaceStringInFile` (156–181), and
   `deleteLinesInFile` (419–428, preserving its `\r?\n` split for line
   identification).
3. **Malformed-header handling.** In `parseHunks`, add the `else` branch and a
   `DiffHunk` field (`headerParsed` / sentinel `originalStart = -1`).
4. **Explicit malformed rejection.** In `applyHunk`, throw a named error for a
   malformed header before attempting to locate the hunk.
5. **Full-file fallback + ambiguity.** Rework `findMatchPosition`/add `locateHunk`:
   hint window first, then whole-file scan; unique → apply, multiple → ambiguity
   error, zero → evidence failure. Collect candidate line numbers for the message.
6. **Fuzz.** Extend `matchesAt` into a tiered matcher (exact → ignore-trailing-ws
   → ignore-leading+trailing-ws content match → drop-first/last-context) used by
   both passes; adjust splice offsets when context lines are dropped. For matches
   found via a whitespace-insensitive tier, splice using the **file's** original
   context/removed lines and **re-indent added lines** to the surrounding file
   indentation (never paste the patch's whitespace over the file's — see 3.4
   "write-back rule").
7. **Evidence-rich failure.** Replace the bare message in `applyHunk` with the
   searched range + capped expected block + capped actual lines at the hint;
   include `originalCount` discrepancy note when relevant.
8. **`originalCount` soft-hint.** Stop treating the count as a gate; surface a
   mismatch only in failure diagnostics; add the explanatory comment.
9. **Visibility.** Widen the pure methods and `DiffHunk` to package-private
   (3.7).
10. **New test class.** Create
    `tests/com.servoy.eclipse.developer.mcp.tests/src/test/java/com/servoy/eclipse/developer/mcp/services/CodeEditingServiceTest.java`
    using **JUnit 5/6 (Jupiter)** — `org.junit.jupiter.api.*`, `@Test`, `@Nested`,
    `@DisplayName` on test methods only, `@ParameterizedTest` + `@MethodSource` for
    the fuzz matrix, `org.junit.jupiter.api.Assertions.*` (`assertEquals`,
    `assertThrows`, `assertAll`) — package `com.servoy.eclipse.developer.mcp.services`.
    Register it in the **`AllDeveloperMcpJupiterUnitTests`** platform suite (NOT the
    JUnit 4 `AllDeveloperMcpTests`). Drive the **pure algorithm** (`parseHunks` →
    `applyUnifiedDiff`/`applyHunk`, plus the line-ending helpers and `joinLines`)
    directly on in-memory `List<String>` / `String` inputs — no Eclipse workspace, no
    `IFile`. This is what makes a large, cheap, data-driven fuzz matrix possible: use
    `@ParameterizedTest` with a `@MethodSource` supplying
    `{name, fileText, patchText, expectedResult|expectShouldFail}` cases so scenarios
    are easy to add. Cover:

    **Location & headers**
    - **Placeholder headers:** `@@ -1 +1 @@` hunks whose real target is far past
      the ±50 window are located by the full-file fallback and applied.
    - **`@@ @@` malformed header:** rejected with a message naming the header.
    - **Multi-occurrence ambiguity:** a context block that appears more than once
      with a placeholder header throws the ambiguity error listing candidate lines
      and does not modify content.
    - **Unique long context far from hint:** applies via full-file fallback.

    **Line-ending matrix (each round-tripped, asserting the *output* terminator)**
    - CRLF file + LF-only patch → applies, output stays **CRLF**.
    - LF file + CRLF patch → applies, output stays **LF**.
    - CR-only (old-Mac) file → output stays **CR**.
    - Mixed-ending file (majority CRLF) → output normalized to the **dominant**
      ending; a CRLF/LF **tie** → **LF** (per 3.1).
    - File with **no trailing newline** → output keeps no trailing newline;
      file *with* trailing newline → keeps it.
    - `detectDominantLineEnding` unit cases: pure LF, pure CRLF, pure CR, tie→LF,
      empty string→LF, single unterminated line→LF.
    - Repeat one representative round-trip for each sibling method
      (`insertIntoFile`, `replaceStringInFile`, `deleteLinesInFile`) if reachable as
      pure logic; otherwise assert the shared `joinLines(lines, eol, trailing)`
      helper directly for each `eol` and both trailing states.

    **Whitespace fuzz matrix — SHOULD match**
    - Context identical except **trailing** spaces/tabs (tier 2).
    - Context identical text but **leading** indent differs: file uses **tabs**,
      patch uses **spaces** (tier 3).
    - Reverse: file uses **spaces**, patch uses **tabs**.
    - File **re-indented** (extra indentation level) vs the patch's block (tier 3).
    - **Mixed** tabs+spaces indentation differing between file and patch, same text.
    - Multi-line context where **several** lines differ in leading/trailing ws but
      the real text lines up — whole block matches via tier 3.
    - Drop-first/last-context (tier 4): outer context line absent/changed but inner
      block matches.

    **Whitespace fuzz matrix — SHOULD NOT match (guard against over-matching)**
    - Context whose **non-whitespace text** differs (a real code change) — must NOT
      match even with all ws stripped; produces the evidence-rich failure.
    - Context matching only if **interior** (mid-line) whitespace were collapsed —
      must NOT match, because tier 3 is line-level strip-leading+trailing, not a
      collapse-all-interior match (locks in the agreed depth).
    - A block that would match only if lines were **reflowed/merged** across newlines
      — must NOT match (reflow tolerance is out of scope).

    **Write-back correctness (the critical invariant)**
    - **Content-match write-back:** when a hunk matches only via tier 2/3, the
      written result keeps the **file's** original indentation on retained context
      lines and **re-indents added lines** to the surrounding file indentation — the
      patch's whitespace is never pasted over the file's. Assert the exact resulting
      lines, including their leading whitespace.
    - Added-line re-indent with **no adjacent context** falls back to the added
      line's own indentation unchanged (documented edge).

    **Drift signalling & counts**
    - A fuzzy-tier success returns a result whose message notes fuzzy matching and
      the affected line (open question 2).
    - **`originalCount` mismatch:** a header count that disagrees with the actual
      block still applies when content matches (no strict-count rejection); the
      mismatch appears only in *failure* diagnostics, never as a rejection.
11. **Compile & test loop.** Run `eclipse-ide_getCompilationErrors`, fix any
    markers, then run the new test with `eclipse-ide_runJUnitTests`
    (project `com.servoy.eclipse.developer.mcp.tests`, class
    `com.servoy.eclipse.developer.mcp.services.CodeEditingServiceTest`). Also
    re-run `ServoyCoderServerTest` to confirm no regression.

## 5. Acceptance criteria

- [ ] A patch whose every `@@` header is a placeholder (`@@ -1 +1 @@`) applies
      correctly when its context block occurs exactly once anywhere in the file,
      regardless of distance from the hint.
- [ ] When the hunt context block occurs more than once and the header does not
      disambiguate, `applyPatch` fails with an error that states the match count
      and candidate line numbers, and leaves the file unchanged.
- [ ] A CRLF file patched via `applyPatch` remains CRLF even when the patch is
      LF-only; an LF file patched with a CRLF patch remains LF; a CR-only file
      stays CR; a CRLF/LF tie resolves to LF; files with no trailing newline gain
      none. The same holds for `insertIntoFile`, `replaceStringInFile`, and
      `deleteLinesInFile`.
- [ ] A malformed header (`@@ @@`) is rejected with a message that quotes the
      offending header and describes the expected form — never reported as
      "line 0".
- [ ] A context-not-found failure returns the searched range, the expected block,
      and the actual lines at the hint (all bounded in length).
- [ ] A hunk whose context differs only by trailing whitespace, or by leading
      indentation (tabs-vs-spaces / re-indented block), or by one extra outer
      context line, still applies (tiered fuzz).
- [ ] When a hunk matches only via a whitespace-insensitive tier, the written
      file keeps its own indentation on retained context lines and re-indents
      added lines to the surrounding file context — the patch's whitespace is
      never written over the file's real indentation.
- [ ] `originalCount` no longer gates application; a header/actual count mismatch
      does not reject an otherwise-matching hunk, and is surfaced only in failure
      diagnostics.
- [ ] A fuzzy-tier success (trailing/leading-ws or drop-outer-context) reports in
      its result that fuzzy matching was used and names the affected line, so the
      caller can re-read the drifted region.
- [ ] `CodeEditingServiceTest` (plain JUnit 4) exists and passes with a
      data-driven fuzz matrix covering: placeholder headers, `@@ @@`,
      multi-occurrence ambiguity; the line-ending matrix (LF/CRLF/CR, cross
      LF↔CRLF patch/file combinations, tie→LF, trailing-newline preservation); the
      whitespace SHOULD-match cases (trailing, tabs↔spaces both directions,
      re-indent, mixed indent, multi-line, drop-outer-context); the SHOULD-NOT-match
      guards (real text differs, interior-only ws, reflow); and write-back
      correctness (file indentation preserved, added lines re-indented).
- [ ] Zero compilation errors; `ServoyCoderServerTest` still passes.

## 6. Out of scope

- Replacing the hand-rolled parser with a third-party unified-diff library
  (java-diff-utils / JGit apply) — rejected in triage: a strict library rejects
  the malformed pseudo-diffs this tool must accept.
- Changing the MCP tool signatures, names, or the `ServoyCoderServer` wrapper.
- The 3 "missing file" failures — these are correct `resolveFile` errors for
  files that do not exist, not a patch-engine defect.
- Prompt/model-side changes to make the model emit real line numbers (the ticket
  establishes the placeholder shape is the normal input, so the fix is on the
  tool side).
- Charset detection changes beyond preserving line endings (`deleteLinesInFile`
  keeps its existing UTF-8 handling).
- A whole-block "collapse ALL whitespace (including newlines) into one space and
  search the file's joined text" content match that would also absorb re-wrapped /
  reflowed lines. The whitespace fuzz depth is line-level strip-leading+trailing
  (3.4 tier 3); a cross-line reflow-tolerant match is deliberately excluded because
  its write-back span-mapping is much harder and it risks over-matching.

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Truncation cap for the evidence message (expected block / actual lines). | reviewer | resolved — 10 lines each (§3.3) |
| Should a fuzzy (non-exact) match signal drift to the caller? | reviewer | resolved — yes; append a bounded drift note to the *success* result naming the tier and affected line so the caller can re-read the region (§3.3) |
| CRLF-vs-LF dominance tie behaviour. | reviewer | resolved — prefer LF on a tie (§3.1) |
