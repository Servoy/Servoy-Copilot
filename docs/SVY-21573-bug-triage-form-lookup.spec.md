# Spec: SVY-21573 — Bug-triage lookup: resolve a described UI location to a form + its interactive elements

Jira: SVY-21573 (Task, sub-task of SVY-21169, fix version 2026.12). Triage: `SVY-21573-triage.md` (same folder; it was moved here from servoy-eclipse). Prior spec: `SVY-21169-form-navigation-graph.spec.md`.

## 1. Goal

A bug report names a screen in plain language ("the orders screen", "query performance tab"). Give the AI what it needs to go from those words to:

1. the actual form name,
2. how to reach that form from the main form,
3. the interactive elements on that form (name, type, bound handlers, `data-cy` selector), so it can reproduce the bug via Cypress or tell a developer exactly what to click.

Deliverables, in two repos (plus a "no change" confirmation for a third):

- **Servoy-Copilot (main work).**
  - Increment A (core, stands alone): new MCP tool `servoy-test_getFormInteractiveElements`.
  - Increment B (optional, recommended, separable): new MCP tool `servoy-test_locateForm`, which combines find, path and elements in one call and surfaces ambiguity.
- **skill4servoy:** expose the new tool(s) and the existing but under-exposed navigation tools to the right agents, and teach the agents when to use them. Includes a full exposure audit.
- **servoy-eclipse:** no change (confirmed, section 3.9).

## 2. Background

### 2.1 What exists today (read and confirmed for this spec)

| Piece | Where | Notes |
|---|---|---|
| `findForm(query)` | `ServoyTestingServer.findForm` calls `ServoySolutionService.findForm` (lines ~104-209) | Scores every form of the active solution and its modules. Returns **markdown** (name, title, module, dataSource, layout, comment), not JSON. |
| `getFormNavigationGraph(formName, summaryOnly)` | `ServoyTestingServer` ~684-817 | JSON. With `formName` adds `targetFormExists`, `reachability` (only when the subgraph has no edges), `pathTo`. The reachability code is duplicated for the `summaryOnly` and full branches. |
| `getNavigationPath(targetForm, fromForm)` | `ServoyTestingServer` ~819-870 | JSON `{mainForm, pathTo, steps[]}`. Unreachable target returns a plain `"Error: No navigation path found..."` string. |
| `edgeToJson` | `ServoyTestingServer` ~872 | Shared edge serializer. |
| Graph build | `FormNavigationGraphService.buildFullGraph()` / `getMainFormName()` | Rebuilt on every call (no cache, by SVY-21169 design). |
| Web-component spec access | `ComponentSpecService.getObjectSpec(name, false)` and `WebComponentSpecProvider.getSpecProviderState().getWebObjectSpecification(typeName)` | `spec.getHandlers()` is a `Map<String, WebObjectFunctionDefinition>`. `FormNavigationGraphService.processWebComponentFormProperties` already resolves a `WebComponent` to its spec and reads `wc.getFlattenedJson()`. |
| Element iteration | `Form.getAllObjects()` (used in `ServoyDevServer` ~1428, ~2585) | Own children only (no inheritance). |
| Layout containers | `form.getLayoutContainers()` / `AbstractContainer.getWebComponents()` / `getLayoutContainers()` | Recursion pattern already in `FormNavigationGraphService.processLayoutContainerWebComponents`. |
| Method lookup | `form.getScriptMethods(false)` (used in `ServoyArtifactCreationService.resolveOrCreateMethod`), `solution.getScriptMethod(scopeOrForm, name)` (`CodeAnalysisService` ~500) | Only name to UUID is used today. A UUID to method lookup is not used anywhere in the bundle (see 2.3). |
| `data-cy` convention | `FormSpecGenerator` (servoy-eclipse, `com.servoy.eclipse.cypress`) lines ~319-345: `data-cy="<form>.<element>"` | Same convention as the E2E generator in Copilot. |

### 2.2 Corrections to the ticket's premises (from triage, re-confirmed)

- **Form-name resolution already exists** (`findForm`). Fuzzy matching against `NavigationGraph.getAllFormNames()` would be wrong: that method only returns forms that appear in at least one edge, so isolated and script-only forms are invisible. Resolution stays on the solution model.
- **Path lookup already exists** (`getNavigationPath`, `getFormNavigationGraph`).
- **Only the element lookup is missing.** `FormSpecGenerator.parseFrmFile` is private, regex-based and text-based (`"typeid":7` plus the `onActionMethodID` substring means button; `"typeid":47` means web component). It does not read `onDataChange`/`onRightClick` and does not read web-component handlers. It is not a base to reuse. The new lookup is built from the in-memory model instead.

### 2.3 Facts I could NOT verify (the coder must confirm with `getClassOutline`/`getMethodSource` before coding)

1. How to turn a handler value (a method UUID string in the persisted JSON, or a scope-method reference) into a `ScriptMethod` name and owner (form or scope). Candidate APIs: `FlattenedSolution` script-provider lookups by UUID, `form.getScriptMethods(false)` over `fs.getFormHierarchy(form)`, and scope iteration. If no cheap lookup exists, build a UUID to `ScriptMethod` map once per call from the form hierarchy plus the scopes. Fallback (must be implemented regardless): emit `"method": null, "unresolved": true` plus the raw id.
2. Whether `FlattenedSolution.getFlattenedForm(IPersist)` is the right call to include inherited elements (`extendsID`). If not, walk `fs.getFormHierarchy(form)` and mark inherited elements.
3. The per-persist handler accessors for legacy (non-web-component) elements (`GraphicalComponent`, `Field`, `TabPanel`, `Portal`, `Bean`...). Enumerate them through the persist property descriptors rather than hard-coding `getOnActionMethodID()` calls if a generic API exists (look at `StaticContentSpecLoader` and `AbstractBase.getTypedProperty`, which `ServoyDevServer` ~1438 already uses for `PROPERTY_FORMAT`).
4. Whether NG client emits `data-cy` for elements with no name. Assumption: no. Unnamed elements get `dataCy: null` and a note.
5. How `servoy-debug` gets its MCP entry in the shipped config (see 2.5).

### 2.4 Name and server placement decision

The new tool goes on **`ServoyTestingServer`** (`@McpServer(name = "servoy-test")`), next to `findForm`, `getFormNavigationGraph`, `getNavigationPath` and `generateCypressE2ETest`. Reasons: same family and same consumer agents; the `servoy-test` MCP entry already exists in both skill4servoy JSON configs, so no config change is needed; the E2E-Tester and Researcher already hold `servoy-test_*` grants. Putting it on `ServoyDevServer` (`servoy-model_*`) would hand it to the Developer by wildcard, which is the opposite of the least-privilege decision in 3.6.

Tool names: `getFormInteractiveElements` (Increment A), `locateForm` (Increment B).

### 2.5 MCP prefix mapping (opencode config name to Copilot server)

Agents see `<config name>_<tool>`. The mapping is in `skill4servoy\opencode-public.json` / `opencode_servoy.json` (identical `mcp` blocks); the endpoint is `http://localhost:{env:MCP_PORT}/dev_mcp/<server>`.

| Agent-side prefix | Endpoint / `@McpServer` name | Java class |
|---|---|---|
| `servoy-test_` | `servoy-test` | `ServoyTestingServer` |
| `servoy-form_` | `servoy-form` | `ServoyFormServer` |
| `servoy-model_` | `servoy-dev` | `ServoyDevServer` |
| `servoy-workspace_` | `servoy-ide` | `ServoyIdeServer` |
| `servoy-editor_` | `servoy-coder` | `ServoyCoderServer` |
| `servoy-history_` | `servoy-context` | `ServoyContextServer` |
| `servoy-git_` | `servoy-git` | `ServoyGitServer` |
| `servoy-packages_` | `servoy-wpm` | `ServoyWpmServer` |
| `servoy-media_` | `servoy-media` | `ServoyMediaServer` |
| `servoy-i18n_` | `servoy-i18n` | `ServoyI18nServer` |
| `scratchpad_` | `memory` | `MemoryServer` |
| `time_` | `time` | `TimeServer` |
| `servoy-debug_` | `servoy-debug` | `ServoyDebugServer` — **no `mcp` entry in either skill4servoy JSON** (see below) |

Observations (not part of this change unless the answer to open question Q6 says so):

- Developer.md grants `servoy-debug_*: allow`, but neither `opencode-public.json` nor `opencode_servoy.json` declares a `servoy-debug` MCP server. `McpServerRegistry` registers the servlet (`SERVLET_FACTORIES.put("servoy-debug", ...)`), so the endpoint exists in Servoy-Copilot. Either the config is merged at runtime by `McpConfigWriter` from `IMcpEndpointProvider` contributions (not verified), or the debug tools are unreachable from the shipped config.
- The `servoy-media` and `servoy-i18n` entries hardcode port `8183` while all the others use `{env:MCP_PORT}`.

### 2.6 Servoy-Copilot has no tool allow-list/catalog to update

Tools are discovered from `@Tool` annotations by `McpServerFactory`; servers are listed in `McpServerBuiltins.BUILT_IN_SERVER_CLASSES` (`ServoyTestingServer` is already there) and `McpServerRegistry`. A search of Copilot `*.xml/*.md/*.json/*.MF/*.properties` for `getNavigationPath` found only spec/triage docs (SVY-21169, SVY-21187, SVY-21339). `AGENTS.md` has no per-tool list. So in Copilot, a new tool needs: the annotated method, tests, and `McpToolParamValidationTest`-style required-flag coverage (SVY-21339 spec) — nothing else.

### 2.7 Process conventions to follow

- Servoy-Copilot `AGENTS.md`: edit Java through the Eclipse MCP tools (direct names for single calls, `_codemode` names inside `execute` for scripts; servoy-eclipse `AGENTS.md` says Code Mode `execute` only, so use whichever surface the session exposes, never the built-in `edit`/`write` for files in an Eclipse project). After every change run `getCompilationErrors`, `organizeImports`, `formatFile`; fix top-two-severity Spotbugs findings.
- Solution lookups MUST use `activeProject.getEditingFlattenedSolution()` (AGENTS section 1c), not `getEditingSolution()`.
- Tests: package placement is decided by what the code under test needs. `plainjunit` = no OSGi, no workspace, no `Activator`, no `WebComponentSpecProvider` state, no real `Form`. `integrationpde` = anything that needs a loaded solution or the spec providers. Run plain with `eclipse-ide_runJUnitTests`, PDE with `eclipse-pde_runJUnitPluginTests` and launcher `/com.servoy.eclipse.developer.mcp.tests/PDEIntegrationTestsBaseCopy.launch`.
- Jupiter rules: `@DisplayName` on methods only (never on the class), no `public` needed, **no `Assume.*`**, no accept-either assertions (`contains("a") || contains("b")`). Each assertion must fail if the code is broken. (Existing `FormNavigationGraphServiceTest` has a class-level `@DisplayName`; do not copy that.)
- Commits: Copilot subject `SVY-21573 <text> [ai]`; skill4servoy subject `SVY-21573: <text> [AI]` (matches its recent log, e.g. `SVY-21613: ... [AI]`). **Ask the user before every commit and show the staged files plus message first.** Copilot has unrelated untracked files (`docs/SVY-21231-triage.md`, `tests/application_server/`, `tests/.../servoy_updater.jar`): never `git add .`.
- servoy-eclipse is on `lts_2026` with unrelated local changes; nothing is committed there for this case.

## 3. Design

### 3.1 Increment A — `getFormInteractiveElements`

```java
@Tool(name = "getFormInteractiveElements", type = "object", description = "...")
public String getFormInteractiveElements(
    @ToolParam(name = "formName", description = "Exact internal form name (use findForm to resolve a description or UI title).") String formName,
    @ToolParam(name = "includeStatic", description = "If true, also list named elements with no handler and no dataprovider (labels, images, containers). Default false.", type = "boolean", required = false) boolean includeStatic,
    @ToolParam(name = "maxElements", description = "Maximum number of elements to return. Default 100.", type = "integer", required = false) int maxElements)
```

Description must mention: lists interactive elements of ONE form with bound handlers and the `data-cy` selector, for bug reproduction and Cypress; takes an exact internal name; call `findForm` first for a description; `getNavigationPath` for how to reach the form; reads the design-time model, nothing is run.

**Definition of "interactive".** An element is returned when it has at least one bound handler, or it is data-bound input (has a dataprovider-typed property, so the user can type/select), or it is a container the user navigates in (tab panel, split pane, accordion: `kind: "container"`; tab targets stay in `getFormNavigationGraph`). `includeStatic=true` adds the remaining named elements as `kind: "static"`.

**Lazy:** nothing is cached or precomputed; only the one resolved form is walked.

**Form resolution inside the tool:** exact name via `fs.getForm(formName)` on the editing flattened solution (so modules resolve). No fuzzy matching in this tool; on miss, return the error below. This keeps A independent of the ranking refactor in B.

**Element traversal.** For the resolved form (inherited elements included, see 2.3 item 2):
- direct children, recursing into `LayoutContainer`s (responsive forms), `TabPanel`s (the panel itself), `Portal`s (children, with `container` set to the portal name), in `getAllObjects()` order for stable output;
- for each `WebComponent`: spec by `typeName` from `WebComponentSpecProvider.getSpecProviderState()`; for each handler name in `spec.getHandlers().keySet()` check `wc.getFlattenedJson()` for a non-blank value that is not `"-1"` (`-1` explicitly disables an inherited handler, `spec-to-frm.md` section 8). The handler key in the json is the handler name exactly as in the spec (`onActionMethodID`, `onCellClick`, `onCrumbClicked`, ...), never assume the `onXxxMethodID` pattern;
- for legacy persists: their handler properties (2.3 item 3);
- dataprovider detection: any property whose spec type is `dataprovider` (web components) or `ISupportDataProviderID` with a non-blank `getDataProviderID()` (legacy).

**Handler resolution.** Each bound handler becomes `{handler, event, method, owner}`:
- `handler`: persisted key as is; `event`: `handler` without a trailing `MethodID` (`onActionMethodID` gives `onAction`; `onCellClick` stays);
- `method`/`owner`: resolved name and `forms.<form>` or `scopes.<scope>`; when unresolvable `method: null`, `unresolved: true`, `methodId: "<raw>"`.

**Output (compact JSON via Jackson, same style as the sibling tools; errors are plain `"Error: ..."` strings like the siblings):**

```json
{
  "form": "orders_list",
  "title": "Orders",
  "module": "orders_module",
  "dataSource": "db:/example_data/orders",
  "layout": "responsive",
  "totalElements": 14,
  "returned": 6,
  "truncated": false,
  "elements": [
    {
      "name": "btn_new",
      "type": "bootstrapcomponents-button",
      "kind": "control",
      "dataCy": "orders_list.btn_new",
      "selector": "[data-cy=\"orders_list.btn_new\"]",
      "container": "panel_toolbar",
      "inherited": false,
      "handlers": [
        {"handler": "onActionMethodID", "event": "onAction", "method": "newOrder", "owner": "forms.orders_list"}
      ]
    },
    {
      "name": "fld_customer",
      "type": "bootstrapcomponents-textbox",
      "kind": "input",
      "dataProvider": "customerid",
      "dataCy": "orders_list.fld_customer",
      "selector": "[data-cy=\"orders_list.fld_customer\"]",
      "inherited": true,
      "handlers": [
        {"handler": "onDataChangeMethodID", "event": "onDataChange", "method": "onCustomerChange", "owner": "scopes.orders"}
      ]
    }
  ],
  "notes": ["1 element has no name and cannot be targeted by data-cy: bootstrapcomponents-button"]
}
```

Field rules:
- `kind` is one of `control` (handlers, no dataprovider), `input` (dataprovider bound, with or without handlers), `container`, `static`.
- `dataCy`/`selector` are `<form>.<element>` with `<form>` the form the user sees (the requested form, also for inherited elements, as NG renders the instance form name). Names containing `"` are escaped in `selector`. Unnamed element: both `null`, counted in `notes`.
- `type`: spec name for web components (`spec.getName()`); the persist type name for legacy persists. If the spec is not installed: `type` from `wc.getTypeName()`, `"specMissing": true`, no handlers, plus a note (never throw).
- `container`: name of the enclosing layout container / portal / tab panel when there is one.
- Order: document order (stable). Truncation: stop after `maxElements` (default 100, hard max 500), set `truncated: true`, `returned < totalElements`.
- `totalElements` counts elements that match the `includeStatic` setting.

**Error cases (plain strings, same wording family as the siblings):**
- `formName` null/blank: `Error: formName parameter is required` (checked first, before touching the model, so it is plain-testable like `getNavigationPath(null, null)`).
- No active solution: `Error: No active Servoy solution project found` (same text as `ServoySolutionService`).
- Unknown form: `Error: Form '<name>' not found in the active solution or its modules. Use findForm to resolve a description or UI title to the internal form name.`
- Form exists but has no interactive elements: valid JSON with `elements: []`, `returned: 0`, and a note.
- Any unexpected exception: `ServoyLog.logError(...)` and `Error: <message>` like the siblings.

### 3.2 Code structure (testability)

New class `com.servoy.eclipse.developer.mcp.services.FormInteractiveElementsService` (`@Creatable`), split in two layers so the logic can be unit-tested without a workspace:

1. **Collector** (needs `Form`, `FlattenedSolution`, spec providers; PDE-tested): `List<InteractiveElement> collect(Form form, FlattenedSolution fs, Function<String, Set<String>> handlerNamesBySpec, boolean includeStatic)`. The spec lookup is injected (default implementation calls the new `ComponentSpecService.getHandlerNames(String specName)`, which returns `spec.getHandlers().keySet()` or an empty set when the spec is unknown) so the collector can be driven with a fake spec map from tests that already have real forms.
2. **Formatter** (pure; plain-tested): `InteractiveElement` (name, type, kind, dataProvider, container, inherited, `List<HandlerBinding>`), `HandlerBinding` (handler, method, owner, unresolved, methodId), static helpers `eventName(String handlerKey)`, `selector(String form, String element)`, and `toJson(...)` implementing the field rules, ordering, truncation and notes. No Eclipse/Servoy types in the formatter's signatures.

`ServoyTestingServer` gets `private final FormInteractiveElementsService elementsService = new FormInteractiveElementsService();` next to the other services and a thin tool method (guard, call, catch, log).

### 3.3 Increment B — `locateForm` (optional, recommended)

**Recommendation: build it, as a separate increment after A, in the same case.** Why: the ticket title and goal are literally "described UI location in, form + path + elements out", and the AI chain `findForm` then `getNavigationPath` then `getFormInteractiveElements` is three round trips whose orchestration would otherwise live only in prompt text; cheap Haiku agents (Researcher, 15 steps) benefit most from one call. Why separate: A is useful and shippable alone; B adds the only genuinely new product decision (when to call a match ambiguous) and a small refactor of `ServoySolutionService`. If the user declines B, the skill4servoy docs still describe the three-call chain (section 4.2, step S5 vs S9).

```java
@Tool(name = "locateForm", type = "object", description = "...")
public String locateForm(
    @ToolParam(name = "query", description = "The user's wording: UI title, description, partial name or datasource, e.g. 'query performance tab'.") String query,
    @ToolParam(name = "includeElements", description = "Include the interactive elements of the resolved form. Default true.", type = "boolean", required = false) Boolean includeElements)
```

**Structured ranking (refactor, behaviour of `findForm` unchanged).** In `ServoySolutionService`:
- extract a pure `static int[] score(String name, String title, String ds, String comment, String queryLower)` returning `{tier, wordMatches}` (tier 3 exact name/title, 2 name/title contains, 1 datasource/comment contains, 0 words only), and `compare` on that pair. **Reason:** today `scoreForm` returns a single int where the per-word match count (1..n) collides with the tier values, so a three-word query that matches three words scores the same as an exact match, and ties would be misreported. `findForm`'s markdown must keep its current ordering and text; `rankForms(String query)` returns `List<FormCandidate>` (`Form`, module, tier, wordMatches) and `findForm` renders from it.
- `findForm`'s observable markdown must stay byte-identical (guarded by the characterization test in 5.2).

**Resolution policy (never guess silently):**

| Situation | `status` | Payload |
|---|---|---|
| No candidate | `notFound` | `candidates: []`, hint to widen the query or use `findForm` with the datasource |
| Exactly one candidate, or the best candidate's `(tier, wordMatches)` is strictly greater than the second best | `resolved` | `form`, `reachability`, `pathTo`, `elements` (if requested), and `candidates` (top 5, so the caller sees what lost) |
| Two or more candidates share the top `(tier, wordMatches)` | `ambiguous` | `candidates` (top 5 with name, title, module, dataSource, layout, tier), **no path, no elements** |

A contains-match such as `customer` will usually be `ambiguous`; that is the desired behaviour. A datasource query ("which screens bind to this table") returns many forms with the same tier and is also `ambiguous`, which is the right answer for that question (callers who want the list keep using `findForm`). The caller disambiguates by calling `getFormInteractiveElements`/`getNavigationPath` with one of the returned names, or by asking the user.

**Reachability helper.** Extract the duplicated block of `getFormNavigationGraph` into one helper (e.g. `FormNavigationGraphService.describeReachability(NavigationGraph graph, String mainForm, String form)` returning `isMainForm`, `reachable`, `unreachable`, `unknownForm`, or `noMainForm` when the solution has no start form) computed from the BFS result (`findPath`), not from "edges is empty". Use it in `locateForm` and in both branches of `getFormNavigationGraph` without changing that tool's existing output (the old field is only emitted when the subgraph has no edges, keep that rule). `getNavigationPath` keeps its plain-string errors (changing a public contract is out of scope). In `locateForm` the form was resolved from the solution model, so `unknownForm` from the graph (which only knows forms that appear in an edge) is reported as `unreachable` (`FormLocatorService.locatedReachability`); `getFormNavigationGraph` keeps its old output.

**Output:**

```json
{
  "status": "resolved",
  "query": "query performance tab",
  "form": {"name": "qp_tab", "title": "Query performance", "module": "admin_module", "dataSource": null, "layout": "responsive"},
  "candidates": [{"name": "qp_tab", "tier": 3}, {"name": "qp_popup", "tier": 2}],
  "mainForm": "main_form",
  "reachability": "reachable",
  "pathTo": [ /* same step objects as getNavigationPath.steps (edgeToJson) */ ],
  "elements": { /* exactly the getFormInteractiveElements payload, without the form header duplicated */ }
}
```

Errors: blank `query`: `Error: query parameter is required` (a blank query in `findForm` lists all forms; in `locateForm` that is not a location, so reject). No active solution: same text as above.

### 3.4 Tool-description changes to existing tools (small, in scope)

- `getFormNavigationGraph` and `getNavigationPath` descriptions say "This is for E2E test planning only". That steers agents away from using them for bug triage ("how do I reach screen X"). Reword to: "Use for E2E planning and for bug triage (how to reach a screen from the main form). Do NOT use for single-form tests". Keep the word `Cypress` in both (existing tests `descriptionMentionsCypress` assert it).
- `findForm` description: replace the pointer to the deprecated `showFormInBrowser` with `getFormInteractiveElements` (and `locateForm` once B ships).

### 3.5 Git history relevant to the touched lines

Confirmed lightly, deep dig is in the triage: `findForm` / `ServoySolutionService.findForm` come from `7020564` (SVY-21169, 2026-06-29); `FormNavigationGraphService` last changed in `f091516` (SVY-21169, 2026-07-13). I did not re-run blame. Constraints inherited from the SVY-21169 spec: the graph is rebuilt on every call (no cache), `NavigationGraph`/`NavigationEdge` schema stays unchanged, spec/test script files are excluded from script analysis, tool output must be valid JSON parseable by the model. The newest Servoy-Copilot tool-adding commits (`de153fe` SVY-21515, `948be97` SVY-21524) show the convention: `[ai]` suffix, case number first.

### 3.6 skill4servoy — exposure design

#### 3.6.1 How skill4servoy is organised (what must stay in sync)

- `.opencode-shippable/agents/*.md`: per-agent `permission:` front-matter. `"*": deny` at the top, then explicit `servoy-<config>_<tool>: allow` lines; a later specific line overrides an earlier wildcard (e.g. `servoy-workspace_*: allow` then `servoy-workspace_searchAndReplace: deny`), so insert new lines next to the existing `servoy-test_*` lines, not after a wildcard that could override them.
- Tool docs are **duplicated by design** across layers; every tool change touches several files (4.2 lists them): `skills/servoy-agent-shared/context/mcp-tooling.md` (generic, all agents, section 2 table), `.../mcp-tooling-readonly.md` (read-only agents table, mentions which agent holds which tool), per-role `skills/<role>/context/tooling.md`, route text in `skills/servoy-orchestrator/context/routes.md`, and the agent bodies themselves.
- Pack rules visible in its git log (recent commits `pack: ASCII in code fences...`, `Contents headers for partial loading`, `no 50-word system line`): ASCII only inside code fences and inline code; adding a heading means updating that file's `## Contents` register; keep table rows short; agent front-matter descriptions and system lines are length-constrained. I could not confirm a `validate.py` in the repo (the commit message names it); search for it and run it if it exists. Do not rewrite whole files; use targeted replacements and preserve encoding (UTF-8, no BOM, existing line endings).
- Config JSONs (`opencode-public.json`, `opencode_servoy.json`): per-MCP-server entries only; no per-tool lists. `servoy-test` and `servoy-form` are already declared with `{env:MCP_PORT}`. **No change needed for a new tool on an existing server.**
- `README.md`, `SAMPLE-AGENTS.md`, `codebase-index.json`, `plugin/servoy-guard.js`: searched for `servoy-test`, `findForm`, `getNavigationPath`, `getFormNavigationGraph`: no tool lists, no change (guard.js only mentions `createTestFile`/`addTestMethod`).

#### 3.6.2 Exposure audit — the four navigation tools plus the new ones

`Y` = in the agent's front-matter today; `doc` = documented where that agent reads; target = after this case.

| Agent | role | findForm | getFormNavigationGraph | getNavigationPath | getFormInteractiveElements (new) | locateForm (B) |
|---|---|---|---|---|---|---|
| Orchestrator | director, dispatches, bounded reads | **Y** (keep) | no (keep: dispatch to Researcher) | no (keep) | no (keep: heavy payload, Researcher/E2E-Tester own it) | no |
| Researcher | read-only investigation, 15 steps, Haiku | **Y** | **Y** | **missing, add** (read-only path is exactly bug triage) | **add** | **add** |
| Developer | build/fix, receives facts via dispatch | no | no | no | no (default, Q3) | no |
| Reviewer | diff review, render check | no | no | no | no (default, Q3) | no |
| E2E-Tester | Cypress / `#formtest` / `#e2e` | **Y** | **Y** | **Y** | **add** (replaces ".frm read" in `#formtest`) | **add** |
| JSUnit-Tester | scope logic tests | no | no | no | no (not a form agent) | no |
| Security-Reviewer | security review | no | no | no | no | no |
| Database-Researcher | live SQL | no | no | no | no | no |

Where the existing tools are documented today: `findForm` in mcp-tooling.md section 2 (line ~364, all agents), mcp-tooling-readonly.md (~142), e2etester tooling.md (~52), brownfield-migration.md (~68), Researcher.md (~180), routes.md (~87, ~99). `getFormNavigationGraph` in mcp-tooling-readonly.md (~139, "Researcher"), e2etester tooling.md (~104-105), E2E-Tester.md (~172), `frm.spec.md` (~564). `getNavigationPath` only in E2E-Tester.md (~169) and e2etester tooling.md (~104). **Gap:** `getNavigationPath` and `getFormNavigationGraph` have no row in the generic mcp-tooling.md section 2, and `getNavigationPath` has no row in the Researcher's read-only table.

#### 3.6.3 Exposure audit — every other Servoy-Copilot tool vs the agents

Computed by listing every `@Tool` of every `@McpServer` class in `bundles\com.servoy.eclipse.developer.mcp\src\...\servers` and comparing against all `servoy-*_` entries and wildcards in `agents\*.md`. Result: every registered tool is reachable by at least one agent **except** the ones below.

| Tool (agent-side name) | Exposed to | Status | Decision |
|---|---|---|---|
| `servoy-test_showFormInBrowser` | nobody | `@Tool` description says DEPRECATED, prefer `servoy-form_getFormLayout` | Leave unexposed. Not a judgment call: deprecated. |
| `servoy-git_gitCreateBranch`, `gitDeleteBranch`, `gitCheckout`, `gitStash`, `gitStashPop` | nobody | write operations; the agents' bash allow-lists deny `git checkout`/`switch`/`stash`/`branch -D` on purpose (Orchestrator.md git rail) | Leave unexposed. Deliberate guardrail; changing it is a separate decision. |
| `servoy-git_gitListRepositories` | nobody | read-only, added in SVY-21524 with the new `repo` parameter on the git tools | Judgment call, Q5. Default: leave (no agent needs to pick a repo today). |
| `servoy-debug_*` (incl. `ping`) | Developer only (wildcard) | deliberate: runtime driving is Developer-owned (Orchestrator routes `run`/`debug`) | Keep. Config gap in 2.5 is Q6. |
| `servoy-model_*` write/security tools (`createSolution`, `createUser`, `setUserPassword`, `createPermission`, `setFormElementAccess`, `setFormSecurityBulk`, `createTable`, `createServer`, `addColumn`, menu mutators ...) | Developer (wildcard) only | deliberate | Keep. Not touched. |
| `servoy-model_getFormSecurity` | Security-Reviewer | fine | none |
| `servoy-model_listMenus`/`getMenuStructure` | Researcher | Reviewer does not hold them | out of scope, not related to this case |
| `servoy-workspace_*`, `servoy-editor_*`, `servoy-history_*`, `servoy-packages_*`, `servoy-i18n_*`, `servoy-media_media_rename`, `scratchpad_*`, `time_*` | one or more agents via wildcard or explicit lines | fine | none |

Everything else in `servoy-test_*` (`runJsUnitTests`, `runTestMethod`, `getJSUnitCoverageReport`, `suggestTestsFromCoverage`, `checkNGClientStatus`, `testForm`, `showAndTest`, `listE2ETests`, `testE2E`, `showAndTestE2E`, `generateFormSpec`, `screenshotForm`, `executeTestSetup`, `executeTestTeardown`, `createTestFile`, `addTestMethod`, `generateTestCases`, `analyzeCodeForTesting`, `generateCypressE2ETest`) and `servoy-form_getFormLayout` is exposed to the owning agent(s). Not re-litigated here.

#### 3.6.4 Guidance the agents need (content, not wording)

1. **Screen described in words, bug triage.** The Orchestrator dispatches a **Researcher** with the user's wording ("locate the screen the user calls '...'"). The Researcher chain: `findForm(query)`, then `getNavigationPath(targetForm)` (or `getFormNavigationGraph(formName)` for context), then `getFormInteractiveElements(form)`; or one `locateForm(query)` once B ships. It returns form, path, the relevant elements and handler names as `## Known facts` lines. Ambiguity rail: if `findForm`/`locateForm` shows several equally good candidates, the Researcher returns the candidates (name, title, module) and `Status: INPUT_REQUIRED`-style question for the Orchestrator to put to the user; it never picks one silently and never dispatches downstream work on a guessed form.
2. **`#formtest`.** The E2E-Tester takes the list of elements to exercise from `getFormInteractiveElements` instead of reading the `.frm`; fallback to the `.frm` read (`spec-to-frm.md` section 8) only when the tool is missing (older build) or the form is not in the active solution. `dataCy` is the selector; "unnamed" notes become Known gaps (cannot be targeted).
3. **Relation to the existing flow:** `getFormNavigationGraph` says how a form is reached, not what is on it (already stated in `frm.spec.md` ~564; add the pointer to the new tool); `getFormLayout` is the design-time render (what it looks like, no handlers); `getFormInteractiveElements` is what can be clicked and what each click calls; `generateFormSpec` scaffolds the test file. These four answer different questions.
4. **Version rail.** The tool ships in 2026.12. On an older Servoy build the call fails as "tool not found": the agent falls back to the `.frm` read and says so. No retry loop.

### 3.7 What the new tools deliberately do not do

No navigation-edge changes, no `.frm` text parsing, no run of any code, no caching, no fuzzy matching in `getFormInteractiveElements`, no form-level events (`onShow`, `onLoad`; Q4), no DOM state (visibility at runtime), no rewrite of `getNavigationPath`'s error contract.

### 3.8 Risks

- Handler resolution and inherited-element APIs are unverified (2.3); the plan has fallbacks (`unresolved`, hierarchy walk) so wrong guesses degrade the output instead of failing the tool.
- Responsive forms with deeply nested layout containers: recursion must be bounded by the persist tree only (no cycles possible), but output size can be large; hence `maxElements`.
- Agents may call `getFormInteractiveElements` with a title instead of an internal name. The error message and the tool description both point to `findForm`.

### 3.9 servoy-eclipse — confirmed no change

Triage and re-check: all relevant code (`findForm`, graph, MCP tools) is in Servoy-Copilot. `FormSpecGenerator` (`com.servoy.eclipse.cypress.services`) is reference only: its `parseFrmFile` is private and text-based, and it defines the `data-cy="<form>.<element>"` convention the new tool mirrors (lines ~319-345). `ServoyTestingServer` already depends on `FormSpecGenerator` via the cypress bundle, so no new bundle dependency is needed. A later follow-up could make `generateFormSpec` consume the new element service as a single source of truth; not in this case (Q7).

## 4. Implementation plan

Work order: Servoy-Copilot A (code + tests), skill4servoy A (docs and permissions), then optionally Servoy-Copilot B and skill4servoy B. skill4servoy edits assume the tool names above are final; do not merge them before the Copilot change is available in a build that has the tool.

### 4.1 Servoy-Copilot — Increment A (`D:\Eclipse\202603Workspace\Servoy-Copilot`, branch `master`)

Bundle `bundles\com.servoy.eclipse.developer.mcp`, package base `src\com\servoy\eclipse\developer\mcp`.

- [ ] **C1. `services\ComponentSpecService.java`:** add `Set<String> getHandlerNames(String specName)` (components only, `WebComponentSpecProvider.getSpecProviderState()`, empty set for unknown/null state). No other change.
- [ ] **C2. New `services\InteractiveElement.java`, `services\HandlerBinding.java`** (simple immutable classes/records; no Servoy/Eclipse types).
- [ ] **C3. New `services\FormInteractiveElementsFormatter.java`** (pure): `eventName`, `selector`, `toJson(formHeader, elements, includeStatic, maxElements)` per 3.1 field rules (kind rules are decided in the collector; formatter just serializes), truncation and `notes` (unnamed, specMissing, unresolved handler count, empty form).
- [ ] **C4. New `services\FormInteractiveElementsService.java`** (`@Creatable`): `String getElements(String formName, boolean includeStatic, int maxElements)`; guards in the order blank name, no active project (`ServoyModelManager...getActiveProject()`, `getEditingFlattenedSolution()` per AGENTS 1c), unknown form; `collect(...)` per 3.1/3.2; resolves handlers via a one-time UUID to method map (2.3 item 1) and reports `unresolved` when absent. Log with `ServoyLog`, no `System.out`.
- [ ] **C5. `servers\ServoyTestingServer.java`:** add field `elementsService`, tool `getFormInteractiveElements` with `@ToolParam`s annotated `required` correctly (`formName` true; the others false), clamp `maxElements` (<=0 means 100, max 500). Update the `findForm`, `getFormNavigationGraph`, `getNavigationPath` descriptions per 3.4 (keep the word `Cypress`).
- [ ] **C6.** `organizeImports`, `formatFile`, `getCompilationErrors` (zero), Spotbugs top-two levels clean on new code.
- [ ] **C7. Tests** (section 5).
- [ ] **C8.** Run plain tests (`eclipse-ide_runJUnitTests`, project `com.servoy.eclipse.developer.mcp.tests`, package `com.servoy.eclipse.developer.mcp.plainjunit`) and the new PDE test class.

No change to: `McpServerBuiltins` (already registers the server), `plugin.xml`, `MANIFEST.MF` (all imports used already exist: `org.sablo.specification`, `com.servoy.j2db.persistence`), `NavigationGraph`, `NavigationEdge`, `FormNavigationGraphService`.

### 4.2 skill4servoy — Increment A (`D:\Eclipse\202603Workspace\skill4servoy`, branch `master`, content under `.opencode-shippable\`)

Check first whether the repo is an imported Eclipse project (`.project` exists at the root): if yes use the `eclipse-coder` tools, otherwise the built-in edit tools are acceptable for these Markdown files. Keep each edit targeted.

Permissions (front-matter only):

- [ ] **S1. `agents\Researcher.md`:** after `servoy-test_getFormNavigationGraph: allow` add `servoy-test_getNavigationPath: allow` and `servoy-test_getFormInteractiveElements: allow`.
- [ ] **S2. `agents\E2E-Tester.md`:** after `servoy-test_getNavigationPath: allow` add `servoy-test_getFormInteractiveElements: allow`.
- [ ] **S3.** No change to Orchestrator, Developer, Reviewer, JSUnit-Tester, Security-Reviewer, Database-Researcher, `opencode-public.json`, `opencode_servoy.json` (per 3.6.2 / 3.6.1).

Documentation and guidance (keep the sync set consistent):

- [ ] **S4. `skills\servoy-agent-shared\context\mcp-tooling.md` section 2** (after the `findForm` row, ~line 364): three rows: how a screen is reached (`getFormNavigationGraph` / `getNavigationPath`, with `reachability`), what is clickable on a form (`getFormInteractiveElements`: element, type, handlers, `data-cy`; Researcher and E2E-Tester hold it), and the screen-in-words chain (`findForm`, then path, then elements; ties are returned to the user, never picked). Note which agent holds which tool (the Orchestrator does not hold the path/element tools).
- [ ] **S5. `skills\servoy-agent-shared\context\mcp-tooling-readonly.md`** (~139-142): add `getNavigationPath` next to the graph row (Researcher) and a row for `getFormInteractiveElements` (Researcher). Keep the "No match is an empty list, widen the query" sentence about `findForm`; add the ambiguity rule.
- [ ] **S6. `agents\Researcher.md` body:** a short "Locate a screen" recipe in the tool/approach section (max three calls, matches the 15-step budget): `findForm`, then `getNavigationPath`, then `getFormInteractiveElements`; return form, path, elements and handlers under `## Known facts`; ambiguity rail (3.6.4 item 1). Read the whole file first and place it near the existing `findForm` reference (step 8 "Visual precedent", ~line 180) and the rails; do not add a new top-level heading without updating its Contents register.
- [ ] **S7. `skills\servoy-e2etester\context\tooling.md`:** Discovery table (~52): add a row for `getFormInteractiveElements`. Single-form section (~60-63, "Enumerate what to click from the form ... Read the `.frm`"): change to "call `servoy-test_getFormInteractiveElements` (each returned handler is one case; `dataCy` is the selector); read the `.frm` (`spec-to-frm.md` section 8) only when the tool is unavailable or the form is not in the active solution". "Reaching a form" note (~104-107): unchanged except a pointer sentence to the new tool.
- [ ] **S8. `agents\E2E-Tester.md`:** in the Tier 1 table or the chain section (~148-187) add one line for `#formtest`: elements come from `getFormInteractiveElements`, not a `.frm` read. Do not change the existing `#e2e` chain.
- [ ] **S9. `skills\servoy-orchestrator\context\routes.md`:** `#formtest` paragraph (~150-156): "What to exercise is read FROM the form" now says the E2E-Tester gets it from `servoy-test_getFormInteractiveElements` (still derived, not guessed). Bug-report path (also `agents\Orchestrator.md` ~278, "bug reports: orient then dispatch"): add that a bug described by screen wording is dispatched to **Researcher** to locate the screen (form, path, elements) before any Developer dispatch, and that candidates returned as ambiguous are put to the user via the `question` tool. In `Orchestrator.md` rail 10 (~195), list the new tool among what Researcher/E2E-Tester hold so the Orchestrator never claims it cannot locate a screen.
- [ ] **S10. `skills\servoy-platform\context\specs\frm.spec.md`** (~564, "`getFormNavigationGraph` shows how a form is REACHED, not what a page contains"): add the pointer to `getFormInteractiveElements` for what is on the form. `skills\servoy-platform\context\components\spec-to-frm.md` section 8 (handler bindings): one sentence that the live tool returns the same bindings resolved to method names.
- [ ] **S11.** Version note: `skills\servoy-platform\context\versions\` has files up to `2026.09.md`, `CHANGELOG.md`, and no `2026.12.md`. Decide with the owner (Q9) whether a version entry is wanted; the fallback rail in 3.6.4 item 4 covers older builds regardless.
- [ ] **S12.** Search the pack for stale statements: `grep` for `findForm`, `getFormNavigationGraph`, `getNavigationPath` and "read the .frm" after the edits; also read the `## Fast Path` and `## Decision tree` sections at the top of `mcp-tooling.md` (not read for this spec) and add the screen row there if they list `findForm`-class questions. Update every touched file's `## Contents` register if a heading was added. Run the pack validator if one exists.

### 4.3 Increment B (only if Q1 is yes)

Servoy-Copilot:
- [ ] **B1. `services\ServoySolutionService.java`:** extract the pure `score(...)`/compare logic and `rankForms(String)` (3.3); `findForm` renders from the ranking; markdown output byte-identical.
- [ ] **B2. `services\FormNavigationGraphService.java`:** add `describeReachability(...)` (3.3) and make both branches of `getFormNavigationGraph` use it without changing that tool's output.
- [ ] **B3. New `services\FormLocatorService.java`:** resolution policy of 3.3, composes `ServoySolutionService.rankForms`, `FormNavigationGraphService`, `FormInteractiveElementsService`. Pure decision method `resolve(List<ScoredName> ranked)` returning status plus candidates so it is plain-testable.
- [ ] **B4. `ServoyTestingServer`:** tool `locateForm` (3.3), description mentions `findForm`'s ambiguity behaviour and Cypress/bug triage; update the `findForm` description to point to it.
- [ ] **B5.** Tests (5.1, 5.2 B rows).

skill4servoy:
- [ ] **B6.** Front-matter: add `servoy-test_locateForm: allow` to Researcher.md and E2E-Tester.md only.
- [ ] **B7.** Docs: in the S4/S5 rows and the S6 recipe, replace the three-call chain by "`locateForm(query)` in one call; if `status` is `ambiguous`, return the candidates"; keep the three-call chain as the fallback description for builds without the tool.

### 4.4 Out-of-repo steps for the person finishing the case

- Jira: the case is in progress; fix version 2026.12 (per the ticket). Do not transition or comment from this spec.
- Commits only after the user approves each one (Copilot and skill4servoy are separate commits in separate repos). Suggested subjects:
  - Copilot: `SVY-21573 add getFormInteractiveElements MCP tool for bug-triage form lookup [ai]`
  - skill4servoy: `SVY-21573: expose getFormInteractiveElements and the navigation tools to Researcher and E2E-Tester [AI]`

## 5. Test plan

### 5.1 `plainjunit` (project `com.servoy.eclipse.developer.mcp.tests`, package `...plainjunit`)

`servers\ServoyTestingServerTest` (add, existing style):
- `hasGetFormInteractiveElementsTool`: tool registered.
- Parameters: exactly three; `formName` has `@ToolParam` with `required = true`; `includeStatic` and `maxElements` have `required = false`; all `@ToolParam` descriptions non-empty.
- Description contains `data-cy`, `findForm` and `Cypress`.
- Returns `String`.
- `getFormInteractiveElements(null, false, 0)` returns exactly `Error: formName parameter is required`; same for `"  "` (guard runs before any model access, same pattern as `testGetNavigationPath_nullTarget_returnsError`).
- Existing description tests for `getFormNavigationGraph`/`getNavigationPath` still pass (word `Cypress` kept) and a new assertion that both descriptions mention "bug triage".
- B: `hasLocateFormTool`, `locateForm(null, true)` returns exactly `Error: query parameter is required`.

`servers\McpToolParamValidationTest`: add the new tool's rows (`getFormInteractiveElements.formName` required true, `includeStatic` false, `maxElements` false; B: `locateForm.query` true, `includeElements` false), following the SVY-21339 table.

`services\FormInteractiveElementsFormatterTest` (new, pure logic; each assertion on one exact outcome):
- `eventName("onActionMethodID") == "onAction"`, `eventName("onDataChangeMethodID") == "onDataChange"`, `eventName("onCellClick") == "onCellClick"`, `eventName("onRightClickMethodID") == "onRightClick"`.
- `selector("orders_list", "btn_new")` equals `[data-cy="orders_list.btn_new"]`; a name with `"` is escaped; null/blank element name gives `null`.
- JSON shape: parse the output and assert exact values for `form`, `totalElements`, `returned`, `truncated`, one full element object including nested `handlers[0].method/owner/event`.
- Truncation: 5 elements with `maxElements=2` gives `returned == 2`, `totalElements == 5`, `truncated == true`, and the first two in input order.
- `includeStatic=false` drops `kind: static` elements; `true` keeps them and counts them.
- Unnamed element: `dataCy` and `selector` are JSON `null`, `notes` contains the unnamed message with the correct count.
- Unresolved handler: `method` null, `unresolved` true, `methodId` equals the raw id, and the note counts it.
- `specMissing` element: serialized with `specMissing: true`, empty `handlers`.
- Empty element list: valid JSON, `elements` is `[]`, and the "no interactive elements" note is present.
- Output is compact (no newline) and parses with Jackson.

B (plain): `services\FormLocatorServiceTest` for `resolve(...)`: unique best gives `resolved`; two equal top `(tier, wordMatches)` gives `ambiguous` with exactly those candidates and no form payload; no candidates gives `notFound`; a three-word query with three word matches does NOT tie-collide with an exact-name match (regression for the int-collision in `scoreForm`); candidate list capped at 5 in rank order. `services\ServoySolutionServiceScoreTest` for the pure `score(...)`: exact name, exact title, contains, datasource/comment, words-only, empty query.

### 5.2 `integrationpde` (package `...integrationpde`, launcher `PDEIntegrationTestsBaseCopy.launch`)

`FormInteractiveElementsIntegrationTest` (new). Follow `GetNavigationPathIntegrationTest` for solution setup, workspace-job drain and base class (`...integrationpde.utils.*Copy`), no `Assume`. Seed a solution plus a module (so module resolution is covered) with a base form and a child form that `extendsID` the base:
- Named button with `onAction` bound to a form method: element present, `kind=control`, handler `event=onAction`, `method` equals the real method name, `owner=forms.<form>`, `dataCy=<form>.<name>`.
- Named textbox with a dataprovider and `onDataChange` bound to a **scope** method: `kind=input`, `owner=scopes.<scope>`.
- Handler value `"-1"`: not listed as a handler.
- Element with no name: `dataCy` null and counted in `notes`.
- Element inside a layout container: `container` equals the container name.
- Element inherited from the base form: `inherited=true`, `dataCy` uses the child form's name.
- A tab panel: `kind=container`.
- Form living only in the module: resolves (flattened solution); unknown form returns the exact error text; no active solution is covered by the plain guard test where possible, otherwise asserted here.
- A web component of an uninstalled spec: `specMissing=true`, no exception.
- Tool-level: `new ServoyTestingServer().getFormInteractiveElements("<form>", false, 100)` parses and matches the above; `maxElements=1` truncates.
- Characterization (needed before B1): `ServoySolutionService.findForm("<known title>")` markdown for the seeded forms asserted verbatim (heading line, per-form bullet lines, order) so the ranking refactor cannot change it.
- B: `locateForm` end-to-end for resolved (path from a seeded `navigateToForm`/tab edge, elements present), ambiguous (two forms with the same title: no path, two candidates) and notFound.

### 5.3 skill4servoy

No automated tests. Verification: (a) every agent file still parses (front-matter YAML valid, no duplicate key conflicts); (b) a text search shows each new tool name appears in the permission block of exactly the agents in 3.6.2 and nowhere else; (c) a doc search shows the tool in the sync set of 4.2; (d) pack validator if present.

## 6. Acceptance criteria

Servoy-Copilot, Increment A:
- [ ] `servoy-test_getFormInteractiveElements` is registered on `ServoyTestingServer` with `formName` required and the other two parameters optional, and every `@ToolParam` has a description.
- [ ] For a form with a named button bound to `onAction`, the result lists it with `event: "onAction"`, the resolved method name, owner (`forms.X` or `scopes.Y`) and `dataCy`/`selector` of the form `<form>.<element>`.
- [ ] `onDataChange` and `onRightClick` bindings and web-component handlers (names taken from the component spec, not assumed) are listed.
- [ ] Handlers with value `-1` or blank are not listed; unresolvable handler ids appear as `unresolved: true` with the raw id instead of failing.
- [ ] Inherited elements are included with `inherited: true`; elements inside layout containers are included with their `container`.
- [ ] Forms in modules resolve (flattened solution). Unknown form, blank name and no active solution return the exact error strings in 3.1.
- [ ] `maxElements` truncation sets `truncated: true` and `totalElements`; default 100.
- [ ] Unnamed elements are reported in `notes` and do not break the output; an uninstalled component spec gives `specMissing: true`.
- [ ] Work is done lazily for the one requested form; nothing is cached; `NavigationGraph`/`NavigationEdge` are unchanged.
- [ ] `findForm`, `getFormNavigationGraph`, `getNavigationPath` descriptions mention bug triage; existing description tests still pass.
- [ ] Plain tests of 5.1 pass; the PDE test class of 5.2 passes; `getCompilationErrors` is clean; Spotbugs top-two levels clean on new code.

Servoy-Copilot, Increment B (if approved):
- [ ] `locateForm` returns `resolved` only when the best candidate strictly beats the second; ties return `ambiguous` with at most 5 candidates and no path/elements; no match returns `notFound`.
- [ ] `findForm` markdown is unchanged (characterization test); the `scoreForm` tier/word-count collision is gone.
- [ ] `reachability` is `isMainForm | reachable | unreachable | unknownForm | noMainForm` and `getFormNavigationGraph` output is unchanged. `locateForm` never reports `unknownForm` for a form it resolved (it reports `unreachable`).

skill4servoy:
- [ ] Researcher front-matter has `getNavigationPath` and `getFormInteractiveElements`; E2E-Tester has `getFormInteractiveElements`; no other agent file changed its permissions (B adds `locateForm` to those two only).
- [ ] The generic `mcp-tooling.md` section 2 and the read-only table document `getFormNavigationGraph`, `getNavigationPath` and `getFormInteractiveElements`, including which agent holds them.
- [ ] `#formtest` guidance (E2E-Tester tooling, `routes.md`) names the new tool as the source of elements and keeps the `.frm` read only as a fallback.
- [ ] Orchestrator and Researcher guidance describe the screen-in-words flow and the "ambiguous goes back to the user, never guessed" rule.
- [ ] No content change in `opencode-public.json`, `opencode_servoy.json`, `README.md`, `SAMPLE-AGENTS.md` unless Q6 says so; ASCII-in-code-fence and Contents rules respected.

## 7. Out of scope

- Changing `NavigationGraph`, `NavigationEdge` or the graph build; element-level interactions as graph edges.
- Porting or extending `FormSpecGenerator.parseFrmFile`; changing `generateFormSpec`/`FormSpecGenerator` (servoy-eclipse stays untouched).
- Fuzzy/NLP form resolution beyond the existing `findForm` scoring; resolving against `NavigationGraph.getAllFormNames()`.
- Running anything (no Cypress run, no render); runtime state (visibility, enabled).
- Form-level events and scope-level code analysis beyond mapping a handler id to a method name (Q4).
- Making `getNavigationPath` return structured errors.
- Exposing git write tools, `showFormInBrowser`, or granting `servoy-debug_*` outside the Developer.
- Fixing the `servoy-debug` config gap and the hardcoded `8183` ports in the JSON configs (observations only, Q6).
- Any ticket workflow action in Jira, and any commit.

## 8. Open questions

| # | Question | Owner | Status |
|---|---|---|---|
| Q1 | Build `locateForm` (Increment B) in this case? Recommendation: yes, as a separate increment after A, because the ticket's goal is exactly find + path + elements in one call and ties must be surfaced in one place. A and its docs stand alone if the answer is no. | User | Answered: yes. Increments A and B were both built. |
| Q2 | Is a tie defined as equal `(tier, wordMatches)` the right ambiguity rule, with a candidate cap of 5? | User / Servoy-Copilot maintainer | Accepted as in 3.3 and implemented. |
| Q3 | Should Developer and/or Reviewer also hold `findForm` and `getFormInteractiveElements`? Default is no: the Orchestrator's dispatch carries the facts (form, path, elements as `## Known facts`) and the Developer prompt budget is already large. Revisit if runs show the Developer re-resolving screens itself. | User / skill4servoy owner | Open (default: no) |
| Q4 | Include form-level events (`onShow`, `onLoad`, `onRecordSelection`...) in the output? They matter for reproduction but are not "interactive elements". Default: no, add later as an optional `includeFormEvents`. | User | Open (default: no) |
| Q5 | Expose the read-only `servoy-git_gitListRepositories` to the Orchestrator? Nobody uses the new `repo` parameter yet. | skill4servoy owner | Open (default: no) |
| Q6 | How does `servoy-debug` get its MCP entry in the shipped config (`opencode-public.json`/`opencode_servoy.json` have none while Developer.md grants `servoy-debug_*`)? Also, should the hardcoded `8183` in the media/i18n entries move to `{env:MCP_PORT}`? Separate from this case unless the owner wants it here. | skill4servoy owner / Servoy-Copilot maintainer | Open (default: out of scope) |
| Q7 | After A, should `generateFormSpec` (servoy-eclipse `FormSpecGenerator`) consume the new element service so there is one source of truth instead of the private regex parser? Default: separate follow-up case. | User | Open (default: no) |
| Q8 | Tool name: `getFormInteractiveElements` (as in the triage) vs a shorter `getFormElements`. Default keeps the triage name. | User | Closed: name kept. |
| Q9 | Does skill4servoy want a version-pack entry for 2026.12 (`versions\2026.12.md`/`CHANGELOG.md`)? The fallback rail covers older builds either way. | skill4servoy owner | Open |
| Q10 | The ticket asks to read "`.frm` handler bindings the same way `#formtest` does". This spec deliberately builds from the in-memory model instead (typed handlers, web-component handlers, resolved method names), as decided in triage. Confirm that deviation. | User | Closed: confirmed by the approved approach. |
| Q11 | API details marked unverified in 2.3 (handler id to method resolution, flattened form for inheritance, legacy persist handler enumeration). To be settled by the coder with `getClassOutline`; escalate only if no workable API exists. | Coder | Resolved, see section 9. |

## 9. Implementation status (after coding, code review and test review)

Built: Increment A (`getFormInteractiveElements`) and Increment B (`locateForm`, the structured ranking in `ServoySolutionService`, `describeReachability`) in Servoy-Copilot. The skill4servoy changes were committed separately by the user. servoy-eclipse has no change.

### 9.1 Where the implementation differs from the text above

- **`locateForm.includeElements` is a `Boolean`**, not a `boolean`. `ToolExecutor` turns an omitted primitive into `false`, so a primitive broke the documented default. `null` now means `true`.
- **Reachability in `locateForm`.** `unknownForm` from `describeReachability` is mapped to `unreachable`, because the graph only knows forms that appear in an edge and a form resolved from the solution exists by definition. Found by running the tool against an edge-less form.
- **Form collection.** `ServoySolutionService.collectCandidates` reads `getEditingFlattenedSolution().getForms(false)` (AGENTS section 1c) instead of looping over the active solution and its modules. The order across modules is not pinned by a test; the tie order inside one solution is.
- **Handler keys live inside the web component `json`** (for example `json.onActionMethodID`), not as top-level keys of the item. The tool reads `json`.

### 9.2 The unverified API facts of 2.3, as resolved

1. Handler id to method: a one-time UUID map over `fs.getFormHierarchy(form)`, then `fs.getScriptMethod(String)` (which also finds scope methods). Anything else is reported as `unresolved` with the raw id.
2. Inherited elements: `fs.getFlattenedForm(form)`. An element is inherited when its owning form differs from the requested form.
3. Legacy persists: the properties map plus `BaseComponent.isEventOrCommandProperty`; values `-1`, `0` and blank are skipped.
4. Unnamed elements: `dataCy` and `selector` are `null` and counted in `notes`.

### 9.3 Known limitations

- Children of a `servoycore-portal` **web component** (`json.childElements`) are not listed; only the portal itself is. A legacy `Portal` persist is handled. How Servoy saves a real portal in a `.frm` was not verified.
- A legacy label with a dataprovider and no handler is classed `input`.
- `getFormNavigationGraph` still reports `unknownForm` for a form with no edges (old SVY-21169 behaviour, unchanged). The skill4servoy text that says `unknownForm` means the name is wrong is therefore misleading for that tool.
- Not covered by an automated test: the no-active-solution returns, the `catch` blocks, and the "could not determine the main form" return of `getNavigationPath`.
- One PDE test (`namedTextbox_isInputWithScopeHandler`) failed once in a full-class run and passed on rerun. The seeded scope method may not be in the index yet; not investigated.

### 9.4 Still open

Q3 to Q5 keep their defaults (no). Q6 (`servoy-debug` config entry, hardcoded ports) and Q9 (a 2026.12 version-pack entry) are owner decisions outside Servoy-Copilot. Q7 (let `generateFormSpec` reuse the element service) is a follow-up case.
