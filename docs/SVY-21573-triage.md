# Triage Report — SVY-21573

**Verdict:** PROCEED (scoped down from the ticket's wording; see Recommendation)

_Written in the servoy-eclipse session and moved to the Servoy-Copilot repo. See "Outcome" at the end for what was built._

**Where the change belongs:** the sibling **Servoy-Copilot** repo, not servoy-eclipse.

- Repo: `D:\Eclipse\202603Workspace\Servoy-Copilot` (branch `master`)
- Bundle: `D:\Eclipse\202603Workspace\Servoy-Copilot\bundles\com.servoy.eclipse.developer.mcp`
  - `src\com\servoy\eclipse\developer\mcp\servers\ServoyTestingServer.java` (MCP tools `findForm`, `getFormNavigationGraph`, `getNavigationPath`)
  - `src\com\servoy\eclipse\developer\mcp\services\ServoySolutionService.java` (`findForm` / `scoreForm`)
  - `src\com\servoy\eclipse\developer\mcp\services\FormNavigationGraphService.java` and `NavigationGraph.java`
  - Tests: `D:\Eclipse\202603Workspace\Servoy-Copilot\tests\com.servoy.eclipse.developer.mcp.tests` (`plainjunit` package, e.g. `ServoyTestingServerTest`, `NavigationGraphTest`)
- servoy-eclipse (`D:\Eclipse\202603Workspace\servoy-eclipse`) needs no change. The only related code there is `com.servoy.eclipse.cypress\src\com\servoy\eclipse\cypress\services\FormSpecGenerator.java` (the "#formtest" flow). Its `.frm` parsing is private and regex-based; see the premise check below.

## Reported problem

SVY-21169 added the navigation graph (`FormNavigationGraphService`, `NavigationGraph`, `NavigationEdge`) and the MCP tools `getFormNavigationGraph` and `getNavigationPath`. These answer "how do I reach form X from the main form".

The gap: a bug report names a screen in plain language ("the orders screen", "query performance tab"). The AI has no single step that turns that into:

- the actual form name,
- the path to it, and
- the clickable/interactive elements on it.

Proposed solution (from the ticket):

1. Fuzzy form-name resolution against `getAllFormNames`, surfacing ambiguity.
2. A per-form interactive-element lookup for the one resolved form, reading `.frm` handler bindings (`onAction`, `onDataChange`, `onRightClick`, ...) "the same way the existing `#formtest` flow already does".
3. A combined lookup (new MCP tool or an extension of `getNavigationPath`) returning form, path (`NavigationGraph.findPath`) and elements.

The ticket is a sub-task of SVY-21169 (Closed), fix version 2026.12, no comments, no attachments.

## Root-cause assessment

This is a feature gap, not a defect. Reading the Copilot code shows that part 1 is already mostly built, and the ticket's description of two other pieces is not accurate.

### Form resolution already exists

`ServoyTestingServer.findForm` calls `ServoySolutionService.findForm(query)`. It scores every form in the active solution and its modules on exact name/title, name/title contains, datasource/comment contains, and per-word matches. It returns internal name, title, module, datasource, layout and comment.

- The tool description already uses the ticket's own example ("Query performance popup").
- It was added in commit `7020564` (SVY-21169).
- The ticket does not mention it.

### `getAllFormNames` is the wrong source for resolution

`NavigationGraph.getAllFormNames()` returns only forms that appear as the source or target of at least one edge (`adjacency` and `reverseAdjacency` keys).

- A form with no edges (an isolated or script-only form) is invisible to it.
- `hasForm()` is true only for forms that appear in at least one edge, so it cannot tell an isolated form ("known but unreachable") from a wrong name. (Found while testing: the first `locateForm` reported `unknownForm` for an isolated form for this reason.)
- Resolving against the graph would therefore miss exactly the forms most likely to be hard to reach.
- It also carries no title, datasource or comment to match on.

Resolution has to stay on the solution model, as `findForm` does.

### Path lookup already exists

`getNavigationPath(targetForm, fromForm)` and `getFormNavigationGraph(formName)` return the BFS path from the main form via `graph.findPath`. They also report `reachability` (`isMainForm`, `unreachable`, `unknownForm`); `unknownForm` there means no edge mentions the form, not that the name is wrong.

One gap: `getNavigationPath` returns a plain `Error: No navigation path found...` string for the unreachable case and for the "target is the main form" case. A combined tool must reuse the `reachability` logic from `getFormNavigationGraph`.

### Element listing is the only genuinely missing piece

- Nothing in Servoy-Copilot lists a form's interactive elements. A grep of `bundles\...\src` for `onAction`, `onDataChange`, `onRightClick` and `formtest` found nothing relevant.
- `FormSpecGenerator.parseFrmFile` (servoy-eclipse) does not read `onDataChange` or `onRightClick`. It splits the `.frm` text on `{` and uses regexes.
  - It tags an element as a button if it has `"typeid":7` and the substring `onActionMethodID`.
  - It tags it as a label if it has `"typeid":7` without that substring.
  - It tags it as a web component if it has `"typeid":47`.
  - Handlers on web components (the `handlers` of the `.spec`) are not read at all.
- So the ticket's "same way `#formtest` already does" overstates what exists. Reusing that parser would give buttons and labels only.
- `parseFrmFile` is `private` and works on text. Reusing it would mean exporting a new API from `com.servoy.eclipse.cypress` for a weak result.

A better source is the in-memory model, which Copilot already uses everywhere (`Form`, `IPersist`, `ComponentSpecService` for web-component handlers). That gives typed handler bindings and element names, plus the `data-cy` selector convention `<form>.<element>` used by `FormSpecGenerator` and the E2E generator.

## Ticket premise check

- **Is the problem real?** Yes, as a missing capability. It is not a bug, and not misunderstood behaviour.
- **Is the code in the right repo?** The session started in servoy-eclipse, but all the relevant code (`findForm`, the graph, the MCP tools) lives in Servoy-Copilot. The user's hunch is correct.
- **Is the proposed approach right?** Partly.
  - Fuzzy matching against `getAllFormNames` is wrong, and unnecessary because `findForm` exists.
  - "Computed only for the resolved form, not precomputed" is a good constraint and should be kept.
  - A new tool versus extending `getNavigationPath` is the open design decision.
  - "Reads `.frm` handler bindings like `#formtest`" should not be followed literally (see above).
- **Does the AI really need a combined tool?** The AI can already chain `findForm`, then `getNavigationPath`, then `getSource` on the `.frm`. What it lacks is a structured list of elements and handlers. `getSource` on a `.frm` returns raw JSON, which is noisy and token-heavy. I did not check whether `getFormLayout` (`ServoyFormServer`, rendered DOM and appearance map) already exposes enough element information. It is design-time and not handler-aware, per its own description, but this is unverified.

## Approaches considered

1. **New tool `getFormInteractiveElements(formName)`, composed by the AI with `findForm` and `getNavigationPath`.**
   - Pros: smallest change and the clearest tool contract. Per-form lazy computation is satisfied. It reuses `findForm` as-is and does not touch the graph schema (the ticket marks the graph out of scope). It is easy to unit test.
   - Cons: three calls for one bug report. The orchestration lives in the prompt or skill text, not in code.

2. **Combined tool `locateForm(description)`: resolve with `findForm` scoring, then path via `findPath`, then elements.**
   - Pros: matches the ticket's goal in one call. Ambiguity can be surfaced in one place (for example, return candidates when the top score ties instead of picking one).
   - Cons: more surface area, and it duplicates `findForm` output unless `ServoySolutionService` is refactored to return structured candidates instead of markdown. Ambiguity thresholds are a product decision.

3. **Extend `getNavigationPath` with `includeElements`.**
   - Pros: no new tool name.
   - Cons: `getNavigationPath` requires an exact `targetForm`, so it does not solve natural-language resolution. It also muddies a tool whose description restricts it to E2E planning, and the unreachable case currently returns a plain error string.

4. **No code change.** The AI chains `findForm`, `getNavigationPath` and `getSource`/`getFormLayout`.
   - Pros: zero risk. Resolution and path are already covered.
   - Cons: no structured element or handler list, which is the stated goal. The AI would parse `.frm` JSON from `getSource` by hand. Web-component handlers would be easy to miss. Not recommended, but it is a legitimate fallback if the team prefers prompt guidance.

## Recommendation

PROCEED in **Servoy-Copilot**, with approach 1 as the base:

1. Add a per-form element lookup (new service method plus MCP tool, for example `getFormInteractiveElements`) in `com.servoy.eclipse.developer.mcp`.
   - Build it from the Servoy model: `Form` elements, the handlers set on each (`onAction`, `onDataChange`, `onRightClick`, ...), and web-component handlers via the existing spec lookup.
   - Return element name, type, bound handlers and the `data-cy` selector `<form>.<element>`.
   - Do not port the regex `.frm` parser from `FormSpecGenerator`.
2. Decide with the user whether to also add the combined `locateForm` wrapper (approach 2).
   - If yes, refactor `ServoySolutionService.findForm` to expose structured, scored candidates. Surface ties as ambiguous and never guess silently.
   - Reuse the `reachability` logic from `getFormNavigationGraph` for the path part.
3. Leave `NavigationGraph`/`NavigationEdge` unchanged. Resolve names from the solution model, not `getAllFormNames`.
4. Add `plainjunit` tests in `Servoy-Copilot\tests\com.servoy.eclipse.developer.mcp.tests`, plus tool-registration checks like the ones in `ServoyTestingServerTest`.
5. Update the tool descriptions and the AI instruction/skill text so the model knows when to call the new tool.

## Git history findings

- `findForm` and the `ServoySolutionService.findForm` implementation come from `7020564` "SVY-21169 form navigation graph for AI flow-aware Cypress generation [ai]" (Servoy-Copilot, 2026-06-29). `db140d4` (SVY-21012) is the earlier tool/service refactor in which the file layout was set.
- `FormNavigationGraphService` last changed in `f091516` "SVY-21169 investigate how AI can know where a form is in a full flow of an application..." (2026-07-13).
- The design doc is `D:\Eclipse\202603Workspace\Servoy-Copilot\docs\SVY-21169-form-navigation-graph.spec.md`. Its goals already list "understanding user problem reports... map that to the actual form", so SVY-21573 is the follow-up that spec implied.
- No spec for SVY-21573 exists yet in either repo's `docs/`.
- servoy-eclipse is on `lts_2026`, with unrelated local modifications to `servoy.properties` and a launch file. Servoy-Copilot is on `master` with unrelated untracked files (`docs/SVY-21231-triage.md`, `tests/application_server/`). Keep SVY-21573 changes separate from those.

## Questions for the reporter (NEEDS_INPUT only)

Not applicable. Two product decisions are worth confirming at spec time, but neither blocks the verdict:

- one combined tool versus separate tools,
- how ambiguous matches should be returned.

## Outcome

- **Approach taken:** approach 1 plus approach 2. The user approved both increments: `getFormInteractiveElements` (per-form elements from the in-memory model, not the regex `.frm` parser) and `locateForm` (find, reachability, path and elements in one call; ties return candidates only).
- **Where:** Servoy-Copilot, bundle `com.servoy.eclipse.developer.mcp`. servoy-eclipse needed no change. The user also asked for the new tools, and the older navigation tools, to be exposed to the right agents in skill4servoy, which was done and committed separately.
- **Corrections to this report:** `hasForm()` cannot tell an isolated form from a wrong name (fixed in the text above), and the unreachable/unknownForm distinction therefore does not hold for forms without edges. `locateForm` maps it to `unreachable`.
- **Details, deviations and limitations:** see section 9 of `SVY-21573-bug-triage-form-lookup.spec.md`.
