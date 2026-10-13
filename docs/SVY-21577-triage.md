# Triage Report — SVY-21577

**Verdict:** PROCEED

## Reported problem
`servoy-form_getFormLayout` spills its full result to a temp file (`resultFile`) whenever
the inline JSON exceeds the cap. That spill file is written as **compact, single-line
JSON** (`mapper.writeValueAsString(read)`). A typical page form produces ~170 KB on one
line, of which ~155 KB is the `appearance` map.

Every file tool an agent has is line-oriented:
- the native `read` truncates a line at ~2000 chars,
- `servoy-workspace_readFileRanges` returns whole lines.

So an agent reading the spill file only ever sees the first ~2000 characters of a single
giant line — the leading fragment of `html` — and can never reach the `appearance` map,
which the tool description explicitly calls the point ("use them to know the actual
colour/position of something and to confirm a change landed"). A reviewer observed an agent
try to slice the file with `readFileRanges`, hit a path restriction, and give up on the
appearance data entirely.

The ticket proposes any one of three fixes:
1. write the spill pretty-printed (one appearance entry per line) so grep + ranged read can
   reach an element;
2. spill `html` and `appearance` to two separate files and return both paths;
3. accept a selector/`appearanceFor` filter so the caller gets the needed entries inline.

It also asks that the warning text state the returned path is absolute and must be passed
unchanged to a file reader (agents rebuilt it as a project-relative `../../` path).

## Root-cause assessment
The premise is accurate and the mechanism is exactly as described.

In `FormLayoutInspectionService.assembleResult(...)`
(`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/FormLayoutInspectionService.java`,
~lines 560–608), the spill branch is:

```java
if (mapper.writeValueAsString(read).length() > RESULT_CAP)
{
    String file = spill(formName, "json", mapper.writeValueAsString(read)); // compact, ONE line
    ...
    envelope.put("warning",
        "The rendered form (HTML + appearance) exceeded the inline cap; the full result was written to " +
            file + " - read it with the file tools.");
    ...
}
```

- `mapper` is a default `ObjectMapper` (field at ~line 199), so `writeValueAsString` emits
  compact single-line JSON. `spill(...)` (→ `spillBytes(...)`, ~lines 664–693) just writes
  those bytes verbatim — the entire envelope is one physical line.
- `RESULT_CAP = 24_000` (~lines 91–96), and the field's own Javadoc states "Rendered HTML
  for a real form usually exceeds this, so a spill is the common case." So the broken path
  is the *normal* path for any real form, not an edge case.
- The warning text says "read it with the file tools," which is precisely what cannot work
  on a single 170 KB line given the line-based readers. The claim in the ticket that the
  appearance data is unreachable is correct.

Secondary issue (also real): the warning embeds the absolute path but gives no instruction
to pass it unchanged. The spill lives under
`{workspace}/.metadata/.plugins/com.servoy.eclipse.developer.mcp/temp/` (see `tempDir()`,
~lines 695–717) — deliberately inside the plugin state area so the orchestrator's path
restrictions don't forbid it. An agent that rewrites the absolute path into a
project-relative `../../.metadata/...` form gets blocked, matching the reviewer's
observation.

This is a usability/output-format defect in this project's own code, not user error, not
expected behaviour, and not a third-party problem.

## Ticket premise check
The premise holds. The reported mechanism matches the code exactly, and the proposed
remedies are all technically valid. The three options are not mutually exclusive and differ
mainly in cost/benefit:

- **Option 1 (pretty-print the spill)** is the smallest change: replace the two
  `mapper.writeValueAsString(read)` calls in the spill branch with
  `mapper.writerWithDefaultPrettyPrinter().writeValueAsString(read)`. One line per JSON
  node makes the file navigable by grep + ranged read, directly removing the "first ~2000
  chars only" wall. It keeps a single file and the existing envelope shape, so nothing
  downstream changes. Note the inline (non-spill) branches can stay compact to save tokens;
  only the spilled file needs pretty-printing.
- **Option 2 (two files)** is clean conceptually but adds a second path field and more
  surface; still leaves each file as a single compact line unless also pretty-printed, so on
  its own it does not fully solve the line-length problem for the big `appearance` file.
- **Option 3 (selector / `appearanceFor` filter)** is the most work (new tool parameter,
  filtering logic, description changes) and overlaps with the already-existing `selector`
  parameter, which narrows the subtree. It's a feature, not the minimal fix.

The warning-text clarification is a trivial, independently-worthwhile string change and
should ride along regardless of which option is chosen.

## Approaches considered
1. **Pretty-print the spilled JSON (ticket option 1) + clarify the warning text** —
   pros: smallest, lowest-risk change; makes grep + ranged read work; keeps a single file
   and the existing envelope; the warning fix removes the path-rewriting failure. cons:
   pretty JSON is somewhat larger on disk (irrelevant for a temp file) and still not
   trivially slice-able without grep to find line numbers first (acceptable — that's the
   normal agent workflow).
2. **Spill html and appearance to two files (ticket option 2)** — pros: separates the two
   big payloads; agent can read just the one it needs. cons: more envelope surface; each
   file is still one compact line unless *also* pretty-printed, so it doesn't fix the core
   line-length problem by itself; larger change.
3. **Add a selector/`appearanceFor` inline filter (ticket option 3)** — pros: lets the
   caller get only the entries it needs inline, no file at all. cons: biggest change;
   overlaps the existing `selector` parameter; doesn't help an agent that legitimately needs
   the whole appearance map.
4. **No code change** — pros: none beyond zero effort. cons: leaves a core feature
   (`appearance`, the stated reason the tool exists) unreachable for the common case of any
   real form; the tool's own description promises data the agent cannot read. Not
   defensible.

## Recommendation
**PROCEED** with **Approach 1**: pretty-print the spilled JSON file (one node per line) and
clarify the warning text to state the returned path is absolute and must be passed
unchanged to a file reader (not rebuilt as a project-relative path). This is the minimal,
lowest-risk fix that directly resolves the reported symptom — grep + a ranged read can then
reach any `appearance` entry — and the warning fix removes the secondary path-rewriting
failure the reviewer saw.

Keep the inline (non-spilled) branches compact to avoid spending extra tokens when the
result already fits inline; only the file-spill branch needs pretty-printing.

Alternatives 2 and 3 remain available if a future need arises (e.g. appearance maps so large
that even a pretty-printed single file is unwieldy, or a desire to fetch specific entries
inline), but they are more invasive than the problem currently warrants and option 3
partially duplicates the existing `selector` parameter.

## Git history findings
The entire `getFormLayout` feature was introduced under **SVY-21460** across three commits:
`b3da2d4` (original tool), `253932e` (no-browser message + headless CI), and `f79e74b`
(flattened-solution form resolution). The single-line compact spill has been present since
the original commit `b3da2d4` — it was never an intentional, documented decision to make
the file single-line; it is simply the default `ObjectMapper.writeValueAsString` behaviour.
No prior spec addresses the spill file's on-disk format, so pretty-printing it does not
revert any deliberate choice. This is not a regression between versions — the behaviour has
been the same since the feature shipped.
