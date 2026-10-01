# Spec: SVY-21523 — MCP fileSearch / fileSearchRegExp throw "Java heap space" on a high-frequency term

## 1. Goal

Make the MCP workspace text-search tools (`fileSearch`, `fileSearchRegExp`) return a bounded, useful result for any ordinary keyword instead of exhausting the JVM heap. The shared `search(...)` helper in `WorkspaceService` must cap the number of matches it accumulates (with early stop, mirroring the existing `findFiles` pattern) and stop re-reading each matched file in full on every single match. Truncation must be reported to the caller, and the two `@Tool` methods should optionally expose a `maxResults` parameter so a caller can tune the cap.

## 2. Background

### 2.1 The crash and its cause

Both tools call a shared private helper `search(Pattern, String...)` in
`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/WorkspaceService.java`:

- `fileSearch` → `search(Pattern.quote(text), …)` (`WorkspaceService.java:314-319`)
- `fileSearchRegExp` → `search(Pattern.compile(pattern), …)` (`WorkspaceService.java:321-326`)
- both reach `search(...)` (`WorkspaceService.java:589-628`)

The triage report (`docs/SVY-21523-triage.md`) establishes two compounding defects, confirmed by reading the source:

1. **Unbounded result accumulation.** `search()` collects one `SearchResult` per match into an unbounded `ArrayList`, and its `acceptPatternMatch` always `return true` (`WorkspaceService.java:598`, `608-616`). There is no cap and no early stop. A term like `function`, occurring tens of thousands of times across the svyCloud workspace, produces a correspondingly huge list.

2. **Full file re-read on every single match.** For each match, `acceptPatternMatch` calls `getLineInfo(file, offset)` (`WorkspaceService.java:613`), which calls `readFileLines(file)` (`WorkspaceService.java:632-652`, `687-699`). `readFileLines` reads the **entire file** into a fresh `List<String>` every time, then discards it after locating one line. A file with N occurrences of `function` is fully read into memory N times — O(matches × file size) transient allocation and heavy GC pressure on top of the growing results list.

The control-vs-crash split from the ticket is fully explained by scale: `foundset` (1461 matches) and `application.output` (917) fit; `function` does not. This is a scaling bug, not a matching-correctness bug. It is also not a regression — `search`/`getLineInfo`/`readFileLines` shipped unbounded in commit `8c12337` (SVY-20972).

### 2.2 The proven in-repo pattern to mirror

The sibling method `findFiles(...)` in the same class already does this correctly (`WorkspaceService.java:60-103`):

- `int limit = maxResults <= 0 ? 200 : maxResults;` (`:62`)
- `acceptFile` returns `matches.size() < limit && …` (`:78`)
- `acceptPatternMatch` returns `matches.size() < limit` (`:90`), so the `TextSearchEngine` stops early once the cap is reached.

The fix reuses this established, reviewed shape rather than inventing a new one.

### 2.3 Current tool contract

```java
// ServoyIdeServer.java:255-271
@Tool(name = "fileSearch", description = "Searches for a plain substring …")
public String fileSearch(containingText, fileNamePatterns) {
    List<SearchResult> results = workspaceService.fileSearch(containingText, patterns);
    return formatSearchResults(results);
}

@Tool(name = "fileSearchRegExp", description = "Searches workspace files using a Java regular expression …")
public String fileSearchRegExp(pattern, fileNamePatterns) {
    List<SearchResult> results = workspaceService.fileSearchRegExp(pattern, patterns);
    return formatSearchResults(results);
}
```

Neither `@Tool` description (`ServoyIdeServer.java:255`, `:264`) nor `formatSearchResults` (`ServoyIdeServer.java:363-374`) advertises or applies any limit, so a caller has no way to bound the result. `formatSearchResults` is a `private static` helper that takes `List<SearchResult>` and renders a Markdown list headed `# Search Results (N match(es))`.

`SearchResult` is a record `(String filePath, int lineNumber, String lineContent)` (`WorkspaceService.java:312`). `LineInfo` is a private record `(int lineNumber, String lineContent)` (`WorkspaceService.java:630`).

### 2.4 Related code left as-is

`searchAndReplace` (`WorkspaceService.java:332-391`) uses a similar unbounded requestor (`:350-366`) but only stores **distinct files**, so it is far less exposed and is explicitly out of scope (section 6). `getLineInfo`/`readFileLines` are also used by `getFileOutline`, `readFunction`, `readFileContext` — those read a single file once already, so only the per-match re-read inside `search()` needs to change; the shared helpers' signatures must remain compatible.

## 3. Design

### 3.1 Cap results with early stop in `search(...)`

Give the private `search(...)` an explicit `maxResults` parameter and apply the `findFiles` cap logic:

```java
private SearchResults search(Pattern pattern, int maxResults, String... fileNamePatterns)
```

- Resolve the effective limit the same way `findFiles` does: `int limit = maxResults <= 0 ? SEARCH_MAX_RESULTS_DEFAULT : maxResults;`
- Introduce a constant `public static final int SEARCH_MAX_RESULTS_DEFAULT = 500;` (a text match commonly wants more than a bare file list, so this is set higher than `findFiles`' 200; expose it so tests can reference it).
- In the requestor:
  - `acceptFile` returns `results.size() < limit && file != null && file.isAccessible();`
  - `acceptPatternMatch` adds the `SearchResult` only while `results.size() < limit`, then returns `results.size() < limit` so the engine stops early once the cap is reached.
- Keep the existing public `fileSearch` / `fileSearchRegExp` methods and add overloads (see 3.4) so the limit flows through.

### 3.2 Report truncation to the caller

The caller must be able to tell "exactly N, that's all there is" from "at least N, capped". Introduce a small result wrapper so the boolean survives up to `formatSearchResults`:

```java
public record SearchResults(List<SearchResult> matches, boolean truncated) {}
```

- `search(...)` returns `SearchResults`. `truncated` is `true` when the cap stopped the engine (i.e. `results.size() == limit` and the engine was asked to stop). A simple, correct rule: set `truncated = true` as soon as `acceptPatternMatch` would exceed the limit (the first time it returns `false` because the cap is reached). Capture it in a flag inside the requestor.
- `formatSearchResults(SearchResults)` renders a truncation note when `truncated` is true that makes it explicit **there are more matches than shown**, e.g.:
  `# Search Results (first 500 match(es) shown — more matches exist, results truncated; narrow your pattern or pass a larger maxResults)`
  vs. the existing `# Search Results (N match(es))` when not truncated.

Public `fileSearch`/`fileSearchRegExp` on `WorkspaceService` return `SearchResults` (changing the return type from `List<SearchResult>`). Update `ServoyIdeServer` call sites accordingly.

> Note: changing the public return type is a source-compatible change within this bundle; the only callers are the two `@Tool` methods in `ServoyIdeServer` and the unit tests. If keeping `List<SearchResult>`-returning overloads is cheaper for existing tests, the implementer may keep a thin `List`-returning convenience overload, but the tool path must use the `SearchResults` form so truncation reaches the user.

### 3.3 Remove the per-match full-file re-read

Resolve line info without re-materializing the whole file on every match. Each file must be read **at most once per search**, and the per-file state must be released when the engine moves to the next file so the whole workspace is never held in memory at once.

Preferred approach (bounded, no cross-file retention):

- Use the `TextSearchMatchAccess` the engine already hands to `acceptPatternMatch`. It exposes the file's content/length and the match offset directly (`getFileContentLength()`, `getFileContent(int, int)` / `getFileContentChar(int)`), so the line number and line text can be computed from the already-loaded search buffer **without calling `file.getContents()` again at all**. This is the lightest option: the engine has already read the file to find the match; derive the 1-based line number by counting newlines up to `getMatchOffset()` and slice the surrounding line from the same content accessor.
- Fallback approach if the `TextSearchMatchAccess` content API proves awkward: cache the current file's `List<String>` (or a line-offset table) keyed by `IFile`, computed once per file inside `acceptFile`/first match, and cleared when a new file is seen in `acceptFile`. Only one file's lines are ever held.

Either way:
- `getLineInfo(IFile, int)` is no longer called from `search()` per match. Leave the existing `getLineInfo`/`readFileLines` helpers in place (other methods still use them) but stop the per-match re-read.
- Behaviour (reported line number + trimmed line content) must stay identical to today for the cases that currently succeed.

### 3.4 Expose an optional `maxResults` on the `@Tool` methods

Add an optional `maxResults` parameter to both tools, parsed the way `getCompilationErrors` parses its numeric params (`Optional.ofNullable(maxResults).map(Integer::parseInt).orElse(default)` — `ServoyIdeServer.java:346`):

```java
@ToolParam(name = "maxResults",
  description = "Maximum number of matches to return (default: 500). Results are capped to avoid memory exhaustion on very common terms; when the cap is reached the response says more matches exist and were truncated.",
  required = false) String maxResults
```

- Default when omitted/blank: `WorkspaceService.SEARCH_MAX_RESULTS_DEFAULT` (500).
- Update both `@Tool` descriptions to mention the cap and truncation so agents know results may be limited and how to widen them.

## 4. Implementation plan

1. **`WorkspaceService.java`**
   - Add `public static final int SEARCH_MAX_RESULTS_DEFAULT = 500;`.
   - Add `public record SearchResults(List<SearchResult> matches, boolean truncated) {}`.
   - Change private `search(Pattern pattern, String... fileNamePatterns)` → `search(Pattern pattern, int maxResults, String... fileNamePatterns)` returning `SearchResults`; apply the `findFiles` cap/early-stop in `acceptFile`/`acceptPatternMatch` and set a `truncated` flag when the cap stops the engine.
   - Rewrite the match → line-info resolution to use `TextSearchMatchAccess` content (preferred) or a per-file one-time line cache (fallback), eliminating the per-match `readFileLines` call. Clear per-file state between files.
   - Change public `fileSearch(...)` / `fileSearchRegExp(...)` to accept an `int maxResults` and return `SearchResults`; keep the current no-`maxResults` signatures as overloads that pass `SEARCH_MAX_RESULTS_DEFAULT` (so existing tests/callers that don't care about the limit still compile).
   - Leave `getLineInfo` / `readFileLines` / `searchAndReplace` untouched in signature.

2. **`ServoyIdeServer.java`**
   - Add the optional `maxResults` `@ToolParam` to `fileSearch` and `fileSearchRegExp`; parse with `Optional.ofNullable(...).map(Integer::parseInt).orElse(WorkspaceService.SEARCH_MAX_RESULTS_DEFAULT)`.
   - Pass the parsed limit into the service calls; capture the returned `SearchResults`.
   - Change `formatSearchResults` to accept `SearchResults` and, when `truncated` is true, emit a header note that explicitly states more matches exist than are shown (not merely "N matches").
   - Update both `@Tool` `description` strings to document the cap + truncation behaviour.

3. **Tests — `tests/com.servoy.eclipse.developer.mcp.tests` (pure JUnit 5/6 Jupiter)**
   - Add `WorkspaceServiceSearchTest` (package `com.servoy.eclipse.developer.mcp.services`), mirroring the plain-unit style of `WorkspaceServiceFileOutlineTest` (no workbench). Cover what is testable without a live workspace:
     - `SearchResults` record shape/accessors (`matches()`, `truncated()`).
     - `SEARCH_MAX_RESULTS_DEFAULT == 200`.
     - method/overload existence and signatures via reflection (new `maxResults` param on `fileSearch`/`fileSearchRegExp`; `search` returns `SearchResults`).
     - argument validation unchanged (null/blank `containingText`/`pattern` still throw `IllegalArgumentException`).
   - If `formatSearchResults` truncation rendering is unit-testable (it is `private static`), add a small test in `ServoyIdeServerTest` or make the formatting assertion via a package-visible helper; otherwise assert the header text through a lightweight reflective call. Prefer a focused `formatSearchResults` unit if a seam exists; do not loosen visibility beyond package-private.
   - **Register the new test class in BOTH places** (per AGENTS.md): add `WorkspaceServiceSearchTest.class` to `@SelectClasses` in `AllDeveloperMcpJupiterUnitTests.java`, and add `WorkspaceServiceSearchTest,` to the headless `<test>` list in `tests/com.servoy.eclipse.developer.mcp.tests/pom.xml` (alongside `CodeEditingServiceTest`, `ServoyResourceCacheTest`, etc.).

4. **Post-change verification loop** (per AGENTS.md): run `eclipse-ide_getCompilationErrors` and clear any errors; run the new unit test via `eclipse-ide_runJUnitTests` on the fragment; fix SpotBugs issues of the top two severities in the touched code.

## 5. Acceptance criteria

- [ ] `fileSearchRegExp(pattern: "function")` and `fileSearch(containingText: "function")` on the svyCloud workspace return a bounded result (default ≤ 500 matches) **without** `Error: Java heap space`.
- [ ] The response for a capped search clearly states **more matches exist** than are shown and how to widen it (larger `maxResults` or a narrower pattern); a non-truncated search still reports the exact match count as before.
- [ ] A term that currently works (e.g. `foundset`, `application.output`) still returns the same line numbers and line content for the matches it reports (no behavioural change below the cap).
- [ ] `search(...)` reads each matched file at most once per search (no per-match full-file re-read); verified by the implementation using `TextSearchMatchAccess` content or a per-file one-time cache.
- [ ] Both `@Tool` methods accept an optional `maxResults` (string-parsed, default 500, `<= 0` → default) and its description documents the cap + truncation.
- [ ] `WorkspaceServiceSearchTest` is added, passes in the fragment, and is registered in both `AllDeveloperMcpJupiterUnitTests` `@SelectClasses` and the pom `<test>` headless list.
- [ ] Zero compilation errors; no new top-two-severity SpotBugs issues in the changed code.

## 6. Out of scope

- Changing `searchAndReplace` — it only accumulates distinct files and is not implicated in the OOM.
- Full result streaming (triage approach 3) — a cap is sufficient and an agent cannot usefully consume tens of thousands of matches; streaming is the fallback only if a cap later proves insufficient.
- Changing the JVM heap size or MCP server memory configuration.
- Any change to `getFileOutline`, `readFunction`, `readFileContext`, or the shared `readFileLines`/`getLineInfo` signatures beyond no longer calling them per match inside `search()`.

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Is the default cap of 200 (matching `findFiles`) the right default for text matches, or should it be higher given a text search commonly wants more than a file list? | reviewer | **resolved — 500** |
| Preferred line-info source: compute from `TextSearchMatchAccess` content directly (no second read) vs. a per-file one-time line cache — pick in implementation based on which keeps behaviour identical with least code. | implementer | open |
| Should `truncated` also surface the total-seen count, or only "first N (truncated)"? The engine stops early, so an exact total is not available without scanning everything (which defeats the fix). | reviewer | **resolved — "first N shown, more matches exist" wording; no exact total** |
