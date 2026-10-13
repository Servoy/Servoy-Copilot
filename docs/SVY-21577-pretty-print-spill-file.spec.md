# Spec: SVY-21577 — MCP getFormLayout spill file is single-line JSON, so file readers only ever see its first ~2000 chars

## 1. Goal
Make the full result that `servoy-form_getFormLayout` writes to its spill file actually
readable by an AI agent. Today the spill is compact single-line JSON (~170 KB on one physical
line, of which ~155 KB is the `appearance` map), and every file tool an agent has is
line-oriented — so the agent only ever sees the first ~2000 characters of that one giant line
(the leading fragment of `html`) and can never reach the `appearance` map, which the tool's own
description calls the point of the feature. This change pretty-prints **only the spilled file**
(one JSON node per line) so grep + a ranged read can reach any `appearance` entry, and clarifies
the warning text to say the returned path is absolute and must be passed unchanged to a file
reader (not rebuilt as a project-relative `../../` path, which agents did and got blocked).

## 2. Background

### 2.1 How the result is assembled
`FormLayoutInspectionService.getFormLayout(...)` renders a form in a hidden browser, reads the
rendered DOM back as a JSON envelope string (`form`, `selector`, `viewport`, `html`,
`appearance`), and hands that raw string to `assembleResult(...)`
(`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/FormLayoutInspectionService.java`,
~lines 560–608). `assembleResult` applies the cap-inline / spill-to-temp-file convention:

- `RESULT_CAP = 24_000` chars (field ~lines 91–96). Its own Javadoc states "Rendered HTML for a
  real form usually exceeds this, so a spill is the common case" — so the spill path is the
  *normal* path for any real form, not an edge case.
- When `mapper.writeValueAsString(read).length() > RESULT_CAP`, the whole envelope is written to a
  temp file via `spill(formName, "json", mapper.writeValueAsString(read))` (~line 577) and a small
  inline envelope is returned carrying `resultFile`, `truncated`, a `warning`, and a light summary
  (`form` / `selector` / `viewport`).
- `mapper` is a default `ObjectMapper` (field ~line 199), so `writeValueAsString` emits **compact
  single-line** JSON. `spill(...)` → `spillBytes(...)` (~lines 674–693) writes those bytes
  verbatim, so the entire envelope is one physical line.

### 2.2 Why one line is unreadable
Every file tool an agent has is line-based: the native `read` truncates a line at ~2000 chars,
and `servoy-workspace_readFileRanges` returns whole lines. A ~170 KB single line therefore shows
only its leading ~2000 chars — the start of `html` — and the `appearance` map (which the tool
description says to "use … to know the actual colour/position of something and to confirm a change
landed") is unreachable. A reviewer saw an agent try to slice the file with `readFileRanges`, hit a
path restriction, and give up on the appearance data entirely.

### 2.3 Why the path gets rebuilt and blocked
The spill lives under the plugin state area
(`{workspace}/.metadata/.plugins/com.servoy.eclipse.developer.mcp/temp/`, via `tempDir()`,
~lines 695–717) — deliberately inside the plugin state area so the orchestrator's path
restrictions do not forbid it. The current warning text ("… the full result was written to
`<path>` - read it with the file tools.") does not say the path is absolute and must be used
unchanged, so an agent rewrote it into a project-relative `../../.metadata/…` form and got blocked.

### 2.4 Git history
The entire `getFormLayout` feature was introduced under **SVY-21460** across three commits
(`b3da2d4`, `253932e`, `f79e74b`). The single-line compact spill has been present since the
original commit `b3da2d4` — it was never an intentional, documented decision to make the file
single-line; it is simply the default `ObjectMapper.writeValueAsString` behaviour. No prior spec
addresses the spill file's on-disk format, so pretty-printing it does not revert any deliberate
choice, and this is not a regression between versions — the behaviour has been the same since the
feature shipped.

## 3. Design

### 3.1 Pretty-print only the spilled file content
In the spill branch of `assembleResult`, change the content written to the temp file from compact
to pretty-printed JSON:

```java
String file = spill(formName, "json",
    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(read));
```

With default pretty-printing each JSON node is on its own line, so the big `appearance` map
becomes one entry (object) per block of lines. An agent can then `grep` the file for an element
ref or a style property to find the line number, and read a small line range around it — the exact
workflow the line-based readers support. This directly removes the "first ~2000 chars only" wall.
`writerWithDefaultPrettyPrinter()` is already the established idiom for human/line-navigable JSON
in this codebase (e.g. `ResolvedElementsProcessor` ~line 95, `McpConfiguration` ~lines 314/344).

### 3.2 Keep the size check and the inline branches compact
The over-cap decision is about **inline token cost**, so the size check must keep measuring the
*compact* serialized length — it decides whether the result is cheap enough to return inline, and
pretty JSON would inflate that measurement with whitespace that never ships inline. Leave the
guard as-is:

```java
if (mapper.writeValueAsString(read).length() > RESULT_CAP)
```

Likewise leave the two inline branches (the "inline + screenshot path" branch, ~line 598, and the
"small enough to inline verbatim" branch, ~line 604) using compact `mapper.writeValueAsString(...)`
so inline results stay token-cheap. Only the bytes written to the spill file change; the returned
inline envelope (summary + `resultFile` + `warning`) also stays compact.

> Minor duplication note: the spill branch currently calls `mapper.writeValueAsString(read)` twice
> (once for the size guard on ~line 575, once for the spill content on ~line 577). After this
> change the second call becomes the pretty-printed writer. The first (guard) call stays compact.
> No need to cache either — this is the already-rare-enough spill path and the code reads clearer
> left explicit.

### 3.3 Clarify the warning text
Update the `warning` string (~lines 582–584) so it states the path is **absolute** and must be
**passed unchanged** to a file reader (do not rebuild it as a project-relative `../../` path), and
that the file is pretty-printed so grep + a ranged read can reach a specific `appearance` entry.
Target wording (final phrasing is the coding agent's to tune, but it must carry these three
points — absolute path, pass unchanged, pretty-printed/greppable):

```
"The rendered form (HTML + appearance) exceeded the inline cap; the full result was written to
the ABSOLUTE path '" + file + "'. Pass this path UNCHANGED to a file-reading tool - do not make it
project-relative (no ../). The file is pretty-printed (one JSON node per line), so you can grep it
for an element ref or style and read a small line range around the match to reach a specific
'appearance' entry."
```

### 3.4 No envelope-shape or tool-signature change
The spill envelope keeps the same fields (`resultFile`, `truncated`, `warning`, `form`,
`selector`, `viewport`, optional `screenshotFile`). The tool parameters are unchanged. The spilled
file is still a single JSON file at the same location and parses to the same object — only its
on-disk formatting changes. Nothing downstream (including the integration test's `envelopeOf`
helper, which reads `resultFile` back and `mapper.readTree`s it) needs to change: pretty-printed
JSON parses identically.

## 4. Implementation plan

1. **Edit `assembleResult(...)` in
   `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/FormLayoutInspectionService.java`:**
   - In the spill branch, change the spill content to pretty-printed JSON:
     `spill(formName, "json", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(read))`
     (~line 577).
   - Leave the size-check guard `mapper.writeValueAsString(read).length() > RESULT_CAP` (~line 575)
     compact.
   - Leave the two inline branches compact (`mapper.writeValueAsString(...)`, ~lines 598 and 604)
     and the inline-envelope serialization compact (~line 590).
   - Update the `warning` text (~lines 582–584) per §3.3: absolute path, pass unchanged (no `../`),
     pretty-printed/greppable.
2. **(Optional, nice-to-have) Refresh the Javadoc on `assembleResult` and/or on `spill`/`RESULT_CAP`**
   to note that the spilled file is pretty-printed (one node per line) for line-based readers,
   while inline output stays compact — so a future reader does not "optimize" the pretty-printing
   back out.
3. **(Optional) Tighten the tool description** in `ServoyFormServer.getFormLayout`
   (`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/servers/ServoyFormServer.java`,
   ~lines 42–66) where it mentions `resultFile`, to say the returned path is absolute, must be used
   unchanged, and the file is pretty-printed/greppable — mirroring the warning text so the model
   gets the guidance both in the schema and in the result.
4. **Compile-check** the bundle via the Eclipse MCP tools and clear any new markers.
5. **Verify** per §5.

## 5. Acceptance criteria
- [ ] When a `getFormLayout` result exceeds `RESULT_CAP`, the file at `resultFile` is valid JSON
      that is **pretty-printed** (multiple physical lines, one JSON node per line) — not a single
      ~170 KB line.
- [ ] The pretty-printed spill file still parses to the same envelope object (`form`, `selector`,
      `viewport`, `html`, `appearance`) — no field is lost or renamed.
- [ ] A grep for a known element ref or style property in the spill file returns a line number, and
      a ranged read of a few lines around it yields a complete, well-formed `appearance` entry
      (box + styles) — i.e. the appearance data is reachable with line-based readers.
- [ ] The over-cap decision is unchanged (still based on the compact serialized length), and inline
      (non-spilled) results remain compact single-line JSON.
- [ ] The `warning` text states the path is absolute, must be passed unchanged (not rebuilt as
      project-relative with `../`), and that the file is pretty-printed/greppable.
- [ ] The envelope shape and the tool's parameters are unchanged; `FormLayoutToolIntegrationTest`
      (whose `envelopeOf` helper reads `resultFile` back) still passes without modification.
- [ ] The bundle compiles with zero new errors/warnings; no blocking SpotBugs issues introduced.

## 6. Out of scope
- Spilling `html` and `appearance` to two separate files (ticket option 2). Not needed once the
  single file is pretty-printed; remains available if appearance maps ever grow so large that even
  a pretty-printed single file is unwieldy.
- Adding a `selector` list / `appearanceFor` inline filter (ticket option 3). It overlaps the
  existing `selector` parameter and is a feature, not the minimal fix.
- Changing `RESULT_CAP`, the inline-vs-spill threshold logic, or the spill location/`tempDir()`.
- Any change to the rendering, readiness, screenshot, or browser-lifecycle paths.
- Pretty-printing inline output (it must stay compact to save tokens).

## 7. Open questions
| Question | Owner | Status |
|----------|-------|--------|
| Should the clarified guidance also go into the tool's `@Tool` description (step 3), or is updating only the runtime `warning` text enough? Spec recommends doing both for consistency; coding agent may treat step 3 as optional. | reviewer | open |
| Is a dedicated unit test for `assembleResult` wanted? There is currently no unit test for this service (only `FormLayoutToolIntegrationTest`, which needs the full PDE+Servoy harness), and unit-testing `assembleResult` would require refactoring it out of the browser-driven flow. Spec assumes the existing integration test is sufficient coverage for a format-only change. | reviewer | open |
