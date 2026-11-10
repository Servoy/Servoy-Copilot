# SVY-21615 — Install a design-tokens CSS custom-properties file into a solution (MCP tool)

- **Jira:** SVY-21615 — *ServoyAI - supported way to deliver design tokens into a solution instead of hand-copying a subset into the solution .less [AI]*
- **Type:** Task (missing capability) · **Priority:** Minor
- **Triage:** `docs/SVY-21615-triage.md` (verdict PROCEED: this tool, plus the skills fix in `skill4servoy`, tracked separately)
- **Bundle:** `com.servoy.eclipse.developer.mcp` · **MCP server:** `servoy-media` (`ServoyMediaServer.java`)

## 1. Goal

Give the ServoyAI agent **one supported MCP tool** that installs a CSS **custom-properties**
design-tokens file into the **active** solution's media folder — properly registered (UUID +
`medias.obj`) exactly like solution-created media — and **idempotently** ensures a single
`@import` of that file in the solution's `<solution>.less`. This replaces the hand-rolled
delivery the agent re-invents on every run (copying a self-selected subset of tokens into a
`:root{}` block, verbatim-copying + hand-editing `medias.obj`, or typing hex literals), which
causes subset drift, duplicate registrations, and triple-duplicated values.

**Scope is CUSTOM PROPERTIES ONLY.** The compile-time LESS-variable projection, the skill
text, and class-driven components are explicitly out of scope (§6).

## 2. Background

### 2.1 Reported problem (from the ticket + comments)

Across many "Servoy Form Vision" evaluation runs (005, 006, 008, status-badges, sidenav,
retest 014) the agent has **no supported path** to bring a `tokens.css` / `design-tokens.json`
file into a solution. It always does it by hand:

- copies a self-selected *subset* of resolved token values into a `:root{}` block in the
  solution `.less` (34 tokens run 005, ~45 run 006), sometimes repeating each value a third
  time as a `var(--token, <literal>)` fallback;
- copies the whole `:root{}` block verbatim into a stylesheet and hand-edits `medias.obj` to
  register it (run 008 — reviewer caught a **duplicate registration**);
- types token values as hex literals with a comment naming the token (sidenav / status badges).

Consequences the reporter calls out: a trimmed copy means other forms can't rely on a token
being present; the copy drifts from the source; the "don't define tokens twice" instruction is
only partly honoured.

The ticket's **Expected** phrases the fix as a *menu* of examples ("for example an `@import` of
a CSS custom-properties file from the solution's media folder, **or** a generated
`tokens.less`"). Comment 2 (Sean Devlin) adds an orthogonal requirement (projection into
compile-time LESS variables); comment 4 notes class-driven components (sidenav) can't be
reached by any variable mechanism. Triage therefore returned NEEDS_INPUT.

### 2.2 Approved approach (resolves the open questions)

Add **ONE** new MCP tool on the `servoy-media` server (beside `media_rename` in
`ServoyMediaServer.java`) that:

1. installs a CSS custom-properties tokens file into the **active** solution's media folder,
   resolving the solution via the **editing flattened solution** (per AGENTS.md);
2. registers it properly (UUID + `medias.obj`) the way
   `ServoyDevServer.createSolutionArtifacts(...)` writes & registers solution media — **not** a
   raw file write;
3. idempotently ensures a **single** `@import` of that media file in `<solution>.less` (no
   duplicate import, no duplicate media registration on re-run).

**Custom properties only.** Out of scope (§6): the LESS-variable projection into
`solution_properties.less` (SVY-21622 / SVY-21629), the skill/documentation text shipped via
`SERVOY_SKILLS_ZIP`, and class-driven components like the sidenav (SVY-21633).

### 2.3 Why custom-properties-only is coherent

`solution_properties.less` holds **compile-time LESS variables** (`@font-family`,
`@main-color`, `@border-radius`, `@field-*`, `@brand-*`) which **cannot** consume the CSS
custom properties (`var(--token)`) in `tokens.css`. Bridging the two value systems is a
separate, mapping-owning deliverable (SVY-21622 / 21629). This ticket delivers the raw
custom-properties file + its `@import` wiring, which is the part the agent re-invents today and
the ticket's first named example.

## 3. Design

### 3.1 Where it lives

A new `@Tool` method on `ServoyMediaServer` (`@McpServer(name = "servoy-media")`). The class is
already registered in `McpServerBuiltins.BUILT_IN_SERVER_CLASSES`, so no registration change is
needed. Mirror the existing `mediaRename` conventions: `@Tool` + `@ToolParam`, `String` return,
try/catch returning `"Error: ..."`, private helpers, JSON-ish success string via `escapeJson`.

### 3.2 Resolving the active solution (AGENTS.md rule)

Resolve the **active project** and its **editing flattened solution** — the tool targets the
*active* solution, not a named one (the agent's current working solution), and must see media
across modules:

```java
IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
ServoyProject activeProject = model.getActiveProject();
if (activeProject == null) return "Error: No active Servoy solution. Activate a solution first.";
FlattenedSolution fs = activeProject.getEditingFlattenedSolution(); // includes modules
Solution editingSolution = activeProject.getEditingSolution();      // write target for new media
```

- Lookups/existence checks (does the tokens media already exist? which media is the
  stylesheet?) go through `fs` so module media are visible.
- The **new media is created on the active solution's own editing solution**
  (`activeProject.getEditingSolution().createNewMedia(...)`), matching
  `createSolutionArtifacts` — new media belongs to the active solution, not a module.

### 3.3 Creating & registering the media (mirror `createSolutionArtifacts`)

Exactly the registration path `ServoyDevServer.createSolutionArtifacts(...)` uses (lines
~892–916), **not** a raw file/`medias.obj` write:

```java
ScriptNameValidator scriptValidator = new ScriptNameValidator();
Media tokens = editingSolution.createNewMedia(scriptValidator, mediaFileName); // allocates UUID + registers in medias.obj
tokens.setMimeType("text/css");
tokens.setPermMediaData(contentBytes);                                         // UTF-8 bytes of the tokens CSS
activeProject.saveEditingSolutionNodes(new IPersist[] { tokens }, true);       // persist media + medias.obj
```

`createNewMedia` is the same call `createSolutionArtifacts` relies on to get a proper UUID and
`medias.obj` entry, so the duplicate-registration / hand-edit problem disappears.

### 3.4 Idempotency — the key behavioural requirement

Re-running the tool must **not** create a second media nor a second `@import`.

- **Media:** before creating, check `fs.getMedia(mediaFileName)` (flattened so a module copy is
  seen). If it exists, do **not** create a new one; **update** its bytes in place
  (`existing.setPermMediaData(contentBytes)`, mime set to `text/css`) so the authored source stays
  the single source of truth, and report `updated` rather than `created`.
  (Rationale: the agent regenerates tokens as the design evolves; refusing would push it back to
  hand-copying. Overwriting the dedicated tokens media is safe and matches "install idempotently
  on re-run" from the approved approach.)
- **Writes go through the owning project.** Lookups use the flattened solution, but a media found
  there may belong to a module. Each changed media (tokens file and stylesheet) is saved through
  the `ServoyProject` of its own root object (`model.getServoyProject(media.getRootObject().getName())`),
  never through the active project. The result names the owning solution
  (`mediaSolution`, `stylesheetSolution`) so a module write is visible.
- **`@import`:** locate the solution stylesheet media via `getStyleSheetID()` (the solution's
  `<solution>.less`), read its text as UTF-8, and ensure **exactly one** compiled-in import of the
  tokens media:
  - The line written is `@import (inline) "<ref>";`. A plain `@import "x.css"` is **not** compiled
    in by Servoy's LESS compiler (jlessc): it is left in the output as a CSS `@import` and the
    browser would fetch it relative to the compiled CSS URL. `(inline)` makes the compiler read the
    media (resolved against the stylesheet's folder by `FlattenedSolutionLessFileManager`) and paste
    it verbatim into the solution CSS — verified against jlessc in the plain-JUnit test.
  - `<ref>` is relative to the stylesheet's folder (e.g. `../tokens.css` for a stylesheet in
    `styles/`), matching how the compiler resolves imports.
  - Existing imports match by the media path they resolve to (not by file name only). Lines
    commented out with `//` or inside `/* ... */` are ignored.
  - An existing `(inline)` or `(less)` import of the media → `alreadyPresent`, stylesheet unchanged.
  - An existing plain or `(css)` import of the media → replaced in place by the inline form,
    reported as `upgraded`.
  - Otherwise insert the line after the leading `@charset` / `@import` header (comments and blank
    lines may sit between them) and before the first rule, preserving the rest of the file
    verbatim and its line delimiter → `added`.
- Running twice in a row with the same inputs yields identical solution state (one tokens media,
  one `@import`).
- **Save failures are not hidden.** If saving the stylesheet fails, the in-memory bytes are
  restored and the tool returns an `"Error: ..."` string that says the tokens media was already
  created/updated but the import could not be added.

### 3.5 Resolving the solution stylesheet

The solution's main stylesheet is the media whose UUID equals
`editingSolution.getStyleSheetID()` (set by `createSolutionArtifacts` to `<solution>.less`).
Resolve it from `fs`/`editingSolution` by UUID. If `getStyleSheetID()` is null/empty or the
media can't be found, the tool still installs the tokens media but reports that the `@import`
could not be wired (so the agent knows to add it), rather than failing the whole operation —
i.e. media install and import wiring are reported independently in the result.

### 3.6 Tool parameters & result

| Param | Required | Default | Meaning |
|---|---|---|---|
| `sourcePath` | one of | — | Path of an existing `.css` tokens file (absolute, or relative to the Eclipse workspace root, the same base the agent pack's workspace-boundary guard uses). Copied **byte for byte**, so the agent cannot install a trimmed subset. Preferred. Must exist, end in `.css` (checked on the real path), be non-blank and at most 1 MB, and its real path (`..` and links resolved) must lie inside the workspace, an open project's location or `~/.servoy/opencode`. |
| `tokensContent` | one of | — | The complete CSS custom-properties file content (e.g. a `:root{ --token: value; … }` block), written verbatim as UTF-8. Fallback when there is no file to point at. |
| `mediaFileName` | no | `tokens.css` | Media path/name for the tokens file (same `VALID_MEDIA_NAME` rules as `media_rename`, validated via the existing `validateMediaName`). |

- No `solutionName` param — the tool operates on the **active** solution by design (that is the
  solution the agent is building). This is the one behavioural difference from `media_rename`.
- **Return:** a JSON-ish string (built with `escapeJson`) reporting: active solution name,
  `mediaFile`, the solution that owns the tokens media (`mediaSolution`), whether the media was
  `created` vs `updated`, the stylesheet name and owning solution when one was found, and whether
  the `@import` was `added`, `upgraded`, `alreadyPresent`, or `skipped` (no stylesheet). Example:
  `{ "solution": "mysolution", "mediaFile": "tokens.css", "mediaSolution": "mysolution", "media": "created", "stylesheet": "mysolution.less", "stylesheetSolution": "mysolution", "import": "added" }`.
- **Error cases** returned as `"Error: ..."` strings (consistent with `mediaRename`): no active
  solution; neither or both of `sourcePath`/`tokensContent`; bad `sourcePath` (not found, not `.css`,
  empty, > 1 MB); invalid `mediaFileName`; persistence failure (including a failed
  stylesheet save, which names what was already written).

### 3.7 Git history note (confirmed)

- `ServoyMediaServer.java` exists only for `media_rename` (`a1aec6e`, SVY-21115) — no
  media-create/install tool has ever existed; adding one is net-new, no regression risk.
- `ServoyDevServer.createSolutionArtifacts` (`a78915a` 2026-05-28, `4a5ee00` 2026-06-10, Diana
  Bunaciu) is the intentional, authoritative pattern for creating & registering solution media
  (`createNewMedia` + `setMimeType` + `setPermMediaData` + `saveEditingSolutionNodes`, and
  `setStyleSheetID`). The new tool reuses this exact pattern. Lines confirmed current at spec
  time (ServoyDevServer ~873–925).

## 4. Implementation plan

All Java edits use the Eclipse MCP tools; after each, run the compile/quick-fix loop
(`eclipse-ide_getCompilationErrors`).

1. **`ServoyMediaServer.java`** (`com.servoy.eclipse.developer.mcp`,
   `src/com/servoy/eclipse/developer/mcp/servers/`): add a new `@Tool` method, e.g.
   `media_install_tokens` (`installDesignTokens`), with the two `@ToolParam`s from §3.6.
   - Add imports needed beyond the current set: `com.servoy.j2db.persistence.FlattenedSolution`,
     `com.servoy.j2db.util.ScriptNameValidator` (the class used by `createSolutionArtifacts` —
     confirm its exact package during coding), `com.servoy.j2db.util.UUID`, and
     `java.nio.charset.StandardCharsets`.
2. **Resolve active solution** (§3.2): active project → `getEditingFlattenedSolution()` for
   lookups, `getEditingSolution()` as the create target. Return the no-active-solution error
   early.
3. **Validate inputs:** blank `tokensContent` → error; default `mediaFileName` to `tokens.css`;
   reuse the existing `validateMediaName(mediaFileName)` helper.
4. **Install/update the tokens media** (§3.3–3.4): if `fs.getMedia(mediaFileName)` exists →
   `setPermMediaData(bytes)` + `setMimeType("text/css")` + save through the media's **owning**
   project (`media = "updated"`); else `createNewMedia` on the active editing solution +
   `setMimeType` + `setPermMediaData` + save (`media = "created"`). Bytes =
   `tokensContent.getBytes(StandardCharsets.UTF_8)`.
5. **Ensure the `@import`** (§3.4–3.5): resolve stylesheet media by `getStyleSheetID()`; compute the
   new text with the pure helper `applyImport(text, stylesheetName, mediaFileName)` (returns the
   text plus `added` / `upgraded` / `alreadyPresent`); if changed, save through the stylesheet's
   owning project, restoring the old bytes and returning `"Error: ..."` if the save fails; if no
   stylesheet, `import = "skipped"`.
6. **Return** the JSON-ish summary via `escapeJson` (§3.6). Keep helpers `private` for
   unit-testability, mirroring the existing `renameSingleMedia` / `validateMediaName` style.
7. **No** `McpServerBuiltins` change (class already registered). **No** production MANIFEST /
   pom / extension-point changes. Keep the file's existing brace style so the diff is the new
   code only. The test bundle imports `com.inet.lib.less` so the plain-JUnit test can check the
   generated import against the real LESS compiler.

### 4.1 Tests (`com.servoy.eclipse.developer.mcp.tests`)

Follow the repo conventions: JUnit 5/6, `@DisplayName` only on methods, no `public`, no silent
skips, no `@Suite`.

- **`...plainjunit/servers/` (headless):** the tool/param contract by reflection, and the pure
  `applyImport` / `relativeImportRef` helpers: insertion point, CRLF, `alreadyPresent` for inline/
  less imports, `upgraded` for plain/css imports, commented-out imports ignored, path-exact
  matching, refs relative to a stylesheet in a sub-folder. Compile the generated stylesheet with
  jlessc the way `JLessCompiler` does and assert the tokens end up in the CSS (and that a plain
  `.css` import does not).
- **`...integrationpde/` (needs workbench, extends `TestUtilitiesClassCopy`):** activate a test
  solution that has a module, call the tool, and assert: the tokens media exists with a UUID,
  `text/css` and the given bytes; the `<solution>.less` gets exactly the expected inline import; a
  plain import is upgraded; a **second** call with the same inputs leaves one media and an
  unchanged stylesheet; changed content updates the same media (same UUID); a tokens media owned
  by the module is updated in the module (no copy in the active solution, `mediaSolution` names the
  module); the invalid-input paths return the expected `"Error: ..."` strings.
- Run plain tests with `eclipse-ide_runJUnitTests`; integration with
  `eclipse-pde_runJUnitPluginTests` using the branch-matched
  `PDEIntegrationTestsBaseCopy-<servoy-branch>.launch` launcher.

## 5. Acceptance criteria

- [ ] A new MCP tool exists on the `servoy-media` server (same class as `media_rename`) that
      installs a CSS custom-properties tokens file into the **active** solution's media folder.
- [ ] The active solution is resolved via the **editing flattened solution** (AGENTS.md);
      no-active-solution returns a clear `"Error: ..."` string, not an exception.
- [ ] The tokens media is created via `createNewMedia` + `setMimeType("text/css")` +
      `setPermMediaData` + `saveEditingSolutionNodes` — i.e. a proper UUID and `medias.obj`
      registration, **not** a raw file write.
- [ ] The tool ensures **exactly one** `@import (inline)` of the tokens media in `<solution>.less`
      (resolved via `getStyleSheetID()`), inserted with the other imports, so the tokens are
      compiled into the solution CSS; commented-out imports don't count and a plain import of the
      same media is upgraded.
- [ ] Media owned by a module (tokens file or stylesheet) is saved through that module's
      project, and the result names it.
- [ ] Every changed media is also written to its file under the owning project's `medias/`
      folder: `saveEditingSolutionNodes` registers a media but does not rewrite the bytes of an
      existing media file, so an update or an added `@import` would otherwise stay in memory only.
- [ ] A failed stylesheet save returns an `"Error: ..."` string, not `skipped`.
- [ ] **Idempotent:** running twice with the same inputs leaves exactly one tokens media and one
      `@import`; running again with changed content updates the existing media's bytes in place
      (no second media, no second import).
- [ ] Exactly one of `sourcePath` (preferred, copied byte for byte) or `tokensContent` must be given;
      both or neither, or a missing/non-`.css`/empty/oversized source file, returns an `"Error: ..."`
      string and writes nothing.
- [ ] `mediaFileName` must end in `.css` and must not name the solution stylesheet itself (both
      return an `"Error: ..."` and write nothing); a failed save of an updated tokens media restores
      its previous bytes and mime type.
- [ ] `mediaFileName` is optional (default
      `tokens.css`, validated with the existing `validateMediaName`); the tool returns a clear
      text result reporting solution, media (created/updated) and import (added/upgraded/
      alreadyPresent/skipped) outcomes.
- [ ] If the stylesheet can't be resolved, the media still installs and the result reports the
      import as skipped (operation doesn't fail wholesale).
- [ ] Plain + integration tests pass; workspace compiles with zero errors; no top-two-severity
      SpotBugs issues in the new code.

## 6. Out of scope (explicit)

- **LESS-variable projection into `solution_properties.less`** (mapping design tokens →
  `@font-family`, `@main-color`, `@border-radius`, `@field-*`, `@brand-*`, …) — tracked by
  **SVY-21622 / SVY-21629**. This tool delivers **custom properties only**.
- **Skill / documentation text** ("import this tokens file", "don't duplicate literals as
  `var()` fallbacks") shipped at runtime via `SERVOY_SKILLS_ZIP` — lives outside this Git repo;
  tracked separately.
- **Class-driven components** (e.g. the sidenav) that read theme values / internal classes
  rather than `var()` — **SVY-21633**; no variable-based mechanism, including this one, reaches
  them.
- Choosing/validating a canonical token *schema* or source-of-truth file
  (`design-tokens.json`); the tool takes the CSS content as a parameter and does not transform
  or validate token semantics.
- Generating a `tokens.less` variant (the ticket's alternative example) — this spec implements
  the CSS custom-properties `@import` route only.

## 7. Open questions

| # | Question | Assumption taken |
|---|---|---|
| 1 | Tool name | `media_install_tokens` (snake_case, consistent with `media_rename`). Coder may align to a house convention if one is clearer. |
| 2 | Default media file name | `tokens.css`. |
| 3 | Re-run with changed content | Update the existing media's bytes in place (overwrite), report `updated`; do not refuse. Matches "install idempotently on re-run". |
| 4 | `@import` placement | After the leading `@charset` / `@import` header, before the first rule; written as `@import (inline) "<ref>";` with `<ref>` relative to the stylesheet's folder. |
| 5 | Stylesheet not found | Install media anyway, report `import: skipped` (don't fail the whole call). |
