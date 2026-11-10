# Triage Report — SVY-21615

**Verdict:** PROCEED

## Reported problem

When the ServoyAI agent builds a form from an HTML mockup plus design tokens
(`design-tokens.json` / `tokens.css`), it has no documented way to bring the tokens file into the
solution. In every evaluation run (005, 006, 008, status badges, sidenav/SVY-21633, retest 014) it
improvises:

- copies a self-selected subset of tokens into a `:root{}` block in the solution `.less`
  (34 tokens in run 005, about 45 in run 006), in run 005 also repeating each value as a
  `var(--token, <literal>)` fallback;
- copies the whole `:root{}` block into a new stylesheet and **hand-edits `medias.obj`** to register
  it (run 008, where the Reviewer caught a duplicate registration without `mimeType`);
- types token values as hex literals with a comment naming the token (sidenav, status badges).

Effects: other forms can't rely on a token being present, the copy drifts from the source, and
"don't define tokens twice" is only partly met.

The ticket's Expected lists examples, not one solution: "an `@import` of a CSS custom-properties
file from the solution's media folder, **or** a generated `tokens.less`", plus documentation "for
the platform and the skills" and guidance not to duplicate literals as `var()` fallbacks. Comment 2
adds a separate need: projecting tokens into compile-time LESS theme variables.

## Root-cause assessment

**The platform already supports the delivery path. What's missing is guidance, and the guidance
that exists contradicts itself.**

1. **Platform: a media `.css` file plus `@import` already works.**
   - Creating a file under `<solution>/medias/` through the IDE registers it automatically. The
     resource-change handler in `servoy-eclipse/com.servoy.eclipse.core/.../ServoyModel.java`
     (lines ~2804–2827) sees a new file under `medias/`, calls
     `editingSolution.createNewMedia(...)`, sets the mime type and writes `medias.obj`. Nobody
     needs to edit `medias.obj` by hand.
   - The NG client LESS compiler resolves `@import` against solution media through the flattened
     solution, so modules are included
     (`servoy-client/servoy_ngclient/.../less/FlattenedSolutionLessFileManager.java`,
     `readLess` → `fs.getMedia(startFolder + path)`).
   - So `medias/tokens.css` + `@import (inline) 'tokens.css';` in `<solution>.less` puts the
     complete token set on `:root` once, with one source of truth. The skills already show the same
     pattern for icon CSS (`@import "css/materialdesignicons.css";` in `styling.spec.md`). One
     detail: under LESS rules a plain `@import "x.css"` is emitted as a CSS `@import`, not inlined.
     The `(inline)` form makes the result predictable. I did not verify in a live client how the
     plain form resolves at runtime.

2. **Skills: the agent was never told to do this, and was told two opposite things about
   registration.** The skills shipped via `SERVOY_SKILLS_ZIP` (extracted by `SkillsZipExtractor`
   in this repo) come from a **separate repository, `github.com/Servoy/skill4servoy`**, under
   `.opencode-shippable/skills/` (checked out at `C:\Users\Cristian\git\skill4servoy`; also
   referenced in `ExpectationLlmGenerator.java`: "the `skills4servoy` repo and shipped via
   `SERVOY_SKILLS_ZIP`"). In that repo:
   - `servoy-platform/context/specs/styling.spec.md:113` says: "To add a new media file: append an
     entry to the array in `medias.obj` with a … UUID v4 from `servoy-model_generateUUID`" (blame
     `2510a10`, 2026-06-14). This matches exactly what the agent did in run 008.
   - `servoy-developer/context/tasks/media.md:37,42` says the opposite: "Create media →
     `servoy-editor_createFile`. IDE adds the UUID to medias.obj" and "Edit medias.obj directly →
     NEVER" (blame `d5d1c8a`, 2026-10-08). `servoy-developer/SKILL.md:203` and
     `servoy-agent-shared/context/mcp-tooling.md:~460` agree with `media.md`.
   - None of the skills describes a design-token delivery recipe (install the file once, import
     it, reference `var(--token)` with no literal fallback). A search for `tokens.css`,
     `design-tokens` and `var(--x, literal)` patterns found only `theme-variables.md:51-54` (blame
     `7310294`, 2026-10-18, citing SVY-21615). That note says theme variables can't take
     `var()` and must be projected to literals. It covers comment 2, not the delivery question.

3. **This repo: no single-call tool existed.** `ServoyMediaServer` (`servoy-media`) only had
   `media_rename` (`a1aec6e`, SVY-21115). The agent could only use the generic `servoy-editor_*`
   file tools. That works, as shown above, but it isn't purpose-built.

4. **Not a regression and not a platform bug.** No dependency or target bump is involved. The
   LESS-variable projection (comment 2) and class-driven components (SVY-21633) are separate
   problems with their own tickets (SVY-21622, SVY-21629, SVY-21633, SVY-21627).

**Work already in progress for this ticket in this repo** (staged, uncommitted):
`docs/SVY-21615-install-design-tokens-media.spec.md` (an approved approach) and a new
`servoy-media` tool `media_install_tokens` in `ServoyMediaServer.java`. The tool installs the
tokens file as registered media on the active flattened solution and idempotently ensures one
`@import (inline)` in `<solution>.less`. It comes with plain-JUnit and PDE integration tests. This
covers the convenience-tool part. It does not change the skills.

## Ticket premise check

- The **problem is real**, but its root cause is mostly in the **skills content** (no recipe, plus
  conflicting `medias.obj` instructions), not missing platform capability. The ticket's first
  example (`@import` of a CSS custom-properties media file) needs no platform change.
- A "generated `tokens.less`" (the second example) isn't needed for custom properties. A `.css`
  file imported inline does the same thing. It would only matter for the LESS-variable projection,
  which is out of scope here (SVY-21622/21629).
- "Documented for the platform": the gitbook styling docs (`css-less.md`,
  `default-theme-properties.md`) describe theme variables, not a tokens recipe. A short gitbook
  note would help human developers, but the agent reads the skills, not gitbook.
- The guidance against duplicating literals as `var()` fallbacks belongs in the skills.

## Approaches considered

1. **Skills fix in `skill4servoy` (required).** Add a design-token delivery recipe: create
   `medias/tokens.css` once with the full token file through `servoy-editor_createFile` (or the new
   tool), add `@import (inline) 'tokens.css';` to `<solution>.less`, reference `var(--token)` with
   no literal fallback, and never copy subsets. Also fix the contradiction in
   `styling.spec.md:113` (and the "Register both files in `medias.obj`" step around line 253) so
   they match `media.md` ("the IDE registers it").
   - Pros: fixes the actual cause of every observed variant, including the run 008 hand-edit;
     cheap; no platform risk.
   - Cons: lives in a different repo, so it needs its own commit/PR there; the agent can still
     ignore the guidance.

2. **The `media_install_tokens` MCP tool in this repo (already implemented and staged).**
   - Pros: one idempotent call, no duplicate registration or import; resolves through the flattened
     solution (modules included); gives the skills a single thing to point at.
   - Cons: adds new tool surface for something `createFile` + an edit can already do; still needs
     approach 1 so the agent knows to use it.

3. **Platform change (a generated tokens.less, or a solution-level "design tokens" property).**
   - Pros: first-class support.
   - Cons: not needed for custom properties; larger scope in servoy-eclipse/servoy-client; the
     only part that truly needs the platform (LESS-variable projection) is tracked elsewhere.

4. **No code change.**
   - Pros: the platform path already works.
   - Cons: the evidence shows the agent never finds it, and one skill page actively steers it
     toward hand-editing `medias.obj`. Doing nothing keeps the recurring failure. Not recommended
     as-is, though for this repo specifically "no further code" would be fine if the staged tool
     were dropped.

## Recommendation

**PROCEED** with **approach 1 (skills fix in `skill4servoy`) as the essential change**, together
with **approach 2** (the already-staged `media_install_tokens` tool in this repo), and have the
skill recipe point at that tool, falling back to `servoy-editor_createFile` + `@import (inline)`.

- This repo's share is the MCP tool only, and it is already implemented per the approved spec.
  What's left here is to verify it (compile, plain-JUnit and PDE tests) and commit.
- The skills edits must happen in `Servoy/skill4servoy` (`.opencode-shippable/skills/...`):
  the token recipe, the "no literal `var()` fallback" rule, and removing the contradictory
  "append an entry to `medias.obj`" instructions in `servoy-platform/context/specs/styling.spec.md`.
  Without these the tool is rarely used and run 008-style hand edits continue.
- Keep the LESS-variable projection (comment 2) and class-driven components out of scope and leave
  them to SVY-21622 / SVY-21629 / SVY-21633.
- Optional: add a short gitbook note on delivering a tokens file.

Alternatives: approach 1 alone (drop the tool) is viable and smaller. Approach 3 isn't justified
for custom properties.

## Git history findings

- `a1aec6e` (SVY-21115): `ServoyMediaServer` was created for `media_rename` only. No
  media-create tool existed before the staged SVY-21615 work.
- `ServoyDevServer.createSolutionArtifacts` (`a78915a`, `4a5ee00`, 2026-05/06) seeds
  `<solution>.less` / `solution_properties.less` at solution creation. This is intentional theme
  seeding, unrelated to tokens.
- skill4servoy `2510a10` (2026-06-14, Marian Vid): added the "append an entry to `medias.obj`"
  instruction in `styling.spec.md`, which the run 008 behaviour matches.
- skill4servoy `d5d1c8a` (2026-10-08, Rene van Veen): `media.md` "IDE adds the UUID … Edit
  medias.obj directly → NEVER". This contradicts the above and was never reconciled.
- skill4servoy `7310294` (2026-10-18): `theme-variables.md` note citing SVY-21615 (var() cannot
  feed LESS variables). It covers comment 2 only.
- skill4servoy `2dda519` (SVY-21609): the most recent styling skill change, for the sibling ticket.
  Fixes for this family of tickets are already landing in that repo.
- No target-platform or dependency change is implicated.
