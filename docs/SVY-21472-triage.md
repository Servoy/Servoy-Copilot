# Triage Report — SVY-21472

**Verdict:** PROCEED

## Reported problem

Over 323 real `servoy-editor_applyPatch` calls harvested from six OpenCode session
databases, **20% (65) failed**. 62 of those are `Could not find matching context for
hunk at line N`; the other 3 are a missing file. All six projects are on Windows.

The failures are not random model misbehaviour — they are the *normal shape* of the
input the tool receives (13% of even the successful patches carry a placeholder `@@`
header). The tool's patch-application algorithm is too brittle to cope with it.

## Root-cause assessment

The whole patch engine lives in `CodeEditingService.java`
(`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/CodeEditingService.java`),
ported from AssistAI. I read the source and confirmed every claim in the ticket:

1. **No full-file fallback (biggest cause).** `applyHunk` (line 683) computes a hint
   from the `@@` header and hands it to `findMatchPosition` (702–713), which only
   searches the hint ±50 lines. The model routinely emits placeholder headers
   (`@@ -1 +1 @@`, every hunk claiming line 1) while the real target is hundreds of
   lines down, so the ±50 window never reaches it. Confirmed: `maxSearch = 50`, exact
   hint then ±offset, else `-1`.

2. **Malformed header silently becomes line 0.** `parseHunks` (646–651) sets
   `originalStart` only inside `if (matcher.matches())` with **no `else`**, so an
   unparseable header (`@@ @@`) leaves the Java default `0`, the hint becomes `-1`, and
   the error reports "hunk at line 0" — a line that does not exist. Confirmed.

3. **`originalCount` parsed but never used.** Declared at line 628, assigned at 650,
   and never read in `applyHunk`. A truncated hunk can be applied as if whole whenever
   it happens to match. Confirmed — no reference to `originalCount` after parsing.

4. **Zero fuzz.** `matchesAt` (715–721) does byte-exact `lines.get(...).equals(...)` on
   every context line. One trailing space, tab-vs-spaces, or re-wrap rejects the whole
   hunk. Confirmed.

5. **Error carries no evidence.** `applyHunk` (684–686) throws only
   `Could not find matching context for hunk at line N. The file may have been
   modified…` — no expected block, no actual lines at the hint, no searched range. The
   agent's only recovery is to re-read and retry, which is what the 62 failures cost.
   Confirmed.

6. **Separate defect — CRLF silently converted to LF.** `readFileLines` (529–541) uses
   `BufferedReader.readLine()`, which discards the terminator; the write side (461–467)
   re-joins unconditionally with `\n`. Every successful patch on a CRLF file rewrites it
   as LF, turning a one-line change into a whole-file git diff. All six measured
   projects are Windows. Confirmed. (Note: `deleteLinesInFile` at 411–426 has the same
   LF-only join, and `insertIntoFile`/`replaceStringInFile` likewise — the whole service
   assumes LF, so a fix should be applied service-wide, not only in `applyPatch`.)

## Ticket premise check

The ticket proposes a solution and it holds up. Unlike the typical triage case, this
ticket was written from measured data against the exact source, and every code citation
matches the current file (verified line-by-line). The proposed changes — full-file
fallback, line-ending preservation, richer error evidence, explicit malformed-header
rejection, and a decision on `originalCount`/fuzz — are all correct and address the
measured failure modes directly. No reframing is needed.

One refinement: the line-ending fix should not be scoped to `applyPatch` alone. The
same LF-only assumption exists in `insertIntoFile`, `replaceStringInFile`, and
`deleteLinesInFile`. Fixing only the patch path would leave the other editing tools
polluting Windows diffs.

## Approaches considered

1. **Implement the ticket's five fixes plus the line-ending defect (recommended).**
   Pros: directly targets the measured 20% failure rate; the full-file fallback alone
   would apply almost all the 62 context-not-found failures; line-ending preservation
   stops polluting every Windows diff; better errors make the residue recoverable in one
   retry. Cons: touches the core patch algorithm — needs unit tests (there is currently
   **no** `CodeEditingServiceTest`; only `ServoyCoderServerTest` exists) covering
   placeholder headers, multi-occurrence ambiguity, CRLF round-trip, and fuzz.

2. **Only the full-file fallback + line endings (the top-two priority).** Pros: smallest
   change for the largest measured win (~Cause 1 covers 62 of 65 failures) and removes
   the CRLF pollution. Cons: leaves malformed-header (Cause 2), decorative `originalCount`
   (Cause 3), and opaque errors (Cause 5) in place; the ticket explicitly wants these too
   and they are cheap.

3. **Replace the hand-rolled parser with a real unified-diff library (e.g. java-diff-utils
   / JGit apply).** Pros: battle-tested fuzz, hunk validation, and offset handling out of
   the box. Cons: larger change and dependency surface in an OSGi bundle; the *input* here
   is malformed pseudo-diffs (placeholder headers, find-and-replace shape) that a strict
   library would reject even harder — the content-addressed fallback the ticket asks for is
   precisely the non-standard behaviour a library won't give. Higher risk, likely more
   failures on this input, not fewer.

4. **No code change.** Pros: none. Cons: the measured failure rate is 20% on a Critical
   ticket; the failures are inherent to the input shape, not trainable away; this is our
   own ported code, not third-party. Rejected — this is a real defect in Servoy code with
   a clear, self-contained fix.

## Recommendation

**PROCEED with Approach 1**, following the ticket's own priority order:

1. Full-file fallback search when the hint fails (Cause 1) — the biggest win. When the
   context block occurs more than once, report the ambiguity instead of guessing.
2. Preserve the file's dominant line ending across **all** editing operations, not just
   `applyPatch` (separate defect + the sibling methods).
3. Return evidence in the failure message: expected block, actual lines at the hint, and
   the searched range (Cause 5).
4. Reject malformed `@@` headers explicitly and name them (Cause 2); then validate or
   drop `originalCount` (Cause 3) and add minimal fuzz — retry ignoring trailing
   whitespace and with first/last context lines dropped (Cause 4).

Add a dedicated `CodeEditingServiceTest` (plain JUnit — the service has no OSGi-runtime
dependency for its parsing logic) covering placeholder headers, `@@ @@`, CRLF round-trip,
multi-occurrence ambiguity, and whitespace fuzz.

Alternatives 2 and 3 are documented above; 2 is a valid reduced scope if effort must be
cut, 3 is not recommended for this input.

## Git history findings

The patch engine (`parseHunks`, `applyHunk`, `findMatchPosition`, `matchesAt`) was
introduced by commit `8c12337a` (marianvid, 2026-05-18, "SVY-20972 Create the supervisor
agent in ServoyAI") as a straight port from AssistAI — it was landed as part of a large
agent-setup commit, not as a considered diff-engine design, which explains the brittle
±50 window and the LF-only join. The most recent touch to the file is `ffbf7da`
(SVY-21281), unrelated to patching. Nothing in the history indicates the ±50 window,
byte-exact matching, or LF join was a deliberate, defended decision, so improving them
does not revert an intentional choice. No prior spec for SVY-21472 exists in `docs/`.
