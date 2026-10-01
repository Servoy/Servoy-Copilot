# Triage Report — SVY-21523

**Verdict:** PROCEED

## Reported problem

The two MCP workspace text-search tools — `fileSearch` (plain substring) and
`fileSearchRegExp` (regex) — crash with `Error: Java heap space` when the search term is
very common. Reproduced against a running Developer on the svyCloud workspace:

- `fileSearchRegExp(pattern: "function")` → `Error: Java heap space` (reproduced twice)
- `fileSearch(containingText: "function")` → `Error: Java heap space`
- (control) `fileSearchRegExp(pattern: "foundset")` → 1461 matches, fine
- (control) `fileSearchRegExp(pattern: "application.output")` → 917 matches, fine

The reporter explicitly notes this is **not** a match-count cap problem: a frequent term
blows the heap while less-frequent terms (hundreds–thousands of matches) return normally.
The tool promises a search result; instead an agent searching an ordinary keyword gets a
crash and falls back to a raw shell grep.

The ticket proposes **no specific solution** — it only observes that the tool should "cap or
stream results" instead of crashing.

## Root-cause assessment

The problem is entirely in this project's code, in the shared `search(...)` helper that both
tools call:
`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/WorkspaceService.java`.

Call chain:
- `fileSearch` → `search(Pattern.quote(text), …)` (`WorkspaceService.java:314-319`)
- `fileSearchRegExp` → `search(Pattern.compile(pattern), …)` (`WorkspaceService.java:321-326`)
- both call the private `search(...)` (`WorkspaceService.java:589-628`)

Two compounding defects make a high-frequency term exhaust the heap:

1. **Unbounded result accumulation.** `search()` collects **one `SearchResult` per match**
   into an unbounded `ArrayList` and its `TextSearchRequestor.acceptPatternMatch` always
   `return true` (`WorkspaceService.java:598`, `608-616`). There is no cap and no early stop,
   so a term that occurs tens of thousands of times across the svyCloud workspace produces a
   correspondingly huge list of record objects.

2. **Full file re-read on every single match.** For each match, `acceptPatternMatch` calls
   `getLineInfo(file, offset)` (`WorkspaceService.java:613`), which calls
   `readFileLines(file)` (`WorkspaceService.java:632-652`, `687-699`). `readFileLines`
   reads the **entire file** into a fresh `List<String>` every time. So a file containing N
   occurrences of `function` is fully read into memory N times, with the resulting list
   discarded after locating one line. This is O(matches × file size) transient allocation —
   massive GC pressure on top of the growing results list — which is what tips a common term
   over into OOM while a rarer term stays under the limit.

The control-vs-crash split in the ticket is fully explained: `foundset` (1461) and
`application.output` (917) are small enough to fit; `function` is not. It is a scaling bug,
not a correctness bug in the matching.

Note the sibling method `findFiles(...)` (`WorkspaceService.java:60-103`) already does this
correctly: it caps at `maxResults` (default 200) and returns `false` from both
`acceptFile` and `acceptPatternMatch` once the cap is reached (`:62`, `:78`, `:90`), so the
engine stops early. `search()` simply never received the same treatment. The
`searchAndReplace` requestor (`:350-366`) is also unbounded but only stores distinct files,
so it is far less exposed.

Neither tool's `@Tool` description (`ServoyIdeServer.java:255`, `:264`) nor
`formatSearchResults` (`ServoyIdeServer.java:363-374`) advertises or applies any limit, so
callers have no way to bound the result themselves — `fileSearch`/`fileSearchRegExp` take
only `containingText`/`pattern` + `fileNamePatterns` (`ServoyIdeServer.java:256-270`).

## Ticket premise check

The ticket reports the symptom accurately and proposes no concrete implementation, only the
direction "cap or stream results." That direction is correct and matches the proven pattern
already used by `findFiles` in the same class. There is no premise to overturn here — the
bug is real, local, and the suggested shape of the fix is sound.

## Approaches considered

1. **Cap results and stop early (recommended).** Give `search()` a result limit (mirror
   `findFiles`: default ~200, overridable) and return `false` from `acceptPatternMatch` once
   the cap is hit so the `TextSearchEngine` stops. Surface a truncation marker in
   `formatSearchResults` (e.g. "showing first N matches — refine your pattern"), and
   optionally expose an explicit `maxResults` param on the two `@Tool` methods.
   - Pros: smallest change, matches an existing in-class pattern, directly removes the
     unbounded list, keeps results deterministic and useful, trivially unit-testable against
     the plain-JUnit fragment.
   - Cons: a cap alone still allows the per-match full-file re-reads up to the cap; best
     paired with fix (2) to also curb transient allocation.

2. **Eliminate per-match full file re-reads (recommended, alongside 1).** Compute line
   number/content without materializing the whole file on every match — e.g. cache the
   file's line-offset table (or its line list) per `IFile` for the duration of one search,
   so each file is read at most once instead of once per match.
   - Pros: removes the dominant source of transient memory churn; benefits even
     moderate-frequency terms; keeps behaviour identical.
   - Cons: slightly more code than (1); needs care to clear the per-file cache between files
     to avoid holding the whole workspace in memory.

3. **Stream results instead of accumulating.** Write matches out incrementally rather than
   building a full `List<SearchResult>` before formatting.
   - Pros: unbounded-safe in principle.
   - Cons: larger refactor of the service/tool contract (return type, formatting), and an
     agent still cannot usefully consume tens of thousands of matches — a cap is wanted
     regardless. Over-engineered relative to approaches 1+2.

4. **No code change.** Document that common terms must be narrowed with `fileNamePatterns`
   or a more specific pattern.
   - Pros: zero code risk.
   - Cons: unacceptable — the tool crashes the whole MCP server JVM (`Java heap space`) on an
     entirely ordinary keyword, which can poison other in-flight tool calls and forces agents
     to abandon the tool for shell grep. A tool advertised to "search workspace files" must
     not OOM on `function`. Rejected.

## Recommendation

**PROCEED** with approaches **1 + 2 together**: add a result cap with early-stop to
`search()` (mirroring `findFiles`), and remove the per-match full-file re-read by resolving
line info without re-materializing the file on every match. Report truncation to the caller
and consider exposing an optional `maxResults` parameter on `fileSearch` /
`fileSearchRegExp`. Approach 3 (full streaming) is the fallback if a cap is later deemed
insufficient, but is not needed now. Approach 4 is not viable.

The change lives in the shared private `search(...)` and its `getLineInfo`/`readFileLines`
helpers in `WorkspaceService` (the `.developer.mcp` bundle), with unit coverage addable to
the plain-JUnit test fragment.

## Git history findings

- `search(...)` and `getLineInfo`/`readFileLines` were introduced by
  `8c12337` (2026-05-18, "SVY-20972 Create the supervisor agent in ServoyAI") — i.e. they
  shipped unbounded from the start; this is not a regression from a later change. The last
  touch to the file overall was `ffbf7da` (2026-09-10, SVY-21281) for unrelated
  "not found" logging.
- The correctly-capped `findFiles` pattern already exists in the same class
  (`WorkspaceService.java:60-103`), so the fix reuses an established, reviewed in-repo
  approach rather than inventing one.
- No prior spec for SVY-21523 exists under `docs/`.
