# Peer-review summary — SVY-21523

**Risk: LOW.** A well-contained robustness fix that *narrows* a DoS surface (unbounded
search-result accumulation + per-match full-file re-reads); the only real behaviour change is
a new default 500-cap on the two text-search tools plus a truncation header, and every
surviving defect is cosmetic/low.

**Scope reviewed:** Servoy-Copilot commit `c90d658` on `master` (single commit) — caps
`fileSearch`/`fileSearchRegExp` at `SEARCH_MAX_RESULTS_DEFAULT=500` with early-stop, reads each
matched file at most once per search via a per-file line cache, reports truncation via a new
`SearchResults` record, and adds an optional lenient `maxResults` tool param.

## Manual test plan

Fix (needs a running Developer against a large workspace):
1. `fileSearchRegExp(pattern:"function")` and `fileSearch(containingText:"function")` →
   bounded result (≤500) with a "more matches exist, results truncated" header, **not**
   `Error: Java heap space`; Developer JVM memory stays flat.
2. `fileSearchRegExp(pattern:"foundset")` and `fileSearch(containingText:"application.output")`
   → return normally (now capped at 500; pass a larger `maxResults` for the full set).
3. `maxResults:2000` on `foundset` → widens to the full result, no truncation header.
4. `maxResults` blank / `"abc"` / `0` / `-1` / omitted → falls back to 500, no error.
5. A search returning < 500 matches → plain `# Search Results (N match(es))` header, no
   truncation wording.

Automated:
6. `mvn -B clean verify -pl tests/com.servoy.eclipse.developer.mcp.tests -am -Dtycho.localArtifacts=ignore -Dmaven.test.failure.ignore=true`
   (or run `WorkspaceServiceSearchTest` in the IDE). Covers record contracts, the 500
   constant, overload/return-type signatures, lenient parse, `@ToolParam` presence and header
   rendering — but **not** an actual capped search over a real workspace, which only the
   manual repro above exercises.

## Possible improvements / follow-ups (non-blocking)

- **Exactly-`limit` false truncation (cosmetic):** `acceptPatternMatch` sets `truncated=true`
  after adding whenever `results.size() >= limit`, so a search finding *exactly* `limit`
  matches and no more reports "more matches exist" when the result is actually complete.
  Over-reports, never loses data.
- **No upper bound on `maxResults`:** a caller may pass an arbitrarily large value and
  re-open the uncapped path; a hard ceiling (`Math.min(parsed, CEILING)`) would make the DoS
  guarantee hold even then. Low risk given the authenticated local caller and safe default.
- **Public no-arg overloads now cap silently at 500:** only the two `@Tool` methods and tests
  call them today, but the behavioural contract of a `public` method changed — a future
  in-bundle caller assuming completeness would get a silently truncated list.
- **No end-to-end capped-search test:** the real OOM repro needs a large live workspace;
  current automated coverage is signature/formatting-level only.
- Pre-existing / out of scope: agent-supplied regex can still backtrack on a single
  pathological file (the cap bounds files visited, not per-file time); CRLF `+1` line-offset
  behaviour is unchanged from the removed `getLineInfo`.

**Security:** LOW — narrows an existing resource-exhaustion surface; no new auth, network,
deserialization or file-write path; the only new input (`maxResults`) is parsed fail-safe.
