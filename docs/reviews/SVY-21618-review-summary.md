# Review Summary — SVY-21618

MCP `getConsoleOutput` rejected a numeric `maxLines`. Fix 1 (`b4b73c6`) added `type = "integer"/"boolean"` to the schema. After review, fix 2 (`abb4af0`) added `ToolParam.acceptString` so the schema also accepts the quoted values that already-shipped skill text sends, plus lenient `maxLines` parsing.

**Risk: LOW (after follow-up)** — the first round was MODERATE because strict `integer`/`boolean` schemas rejected string values and a shipped skill line sends one. `abb4af0` removes that rejection and the `30.0` parse failure. The full plain-JUnit suite passes (1127 tests).

Reviewed: Servoy-Copilot, commits `b4b73c6` and `abb4af0` on `master`.

## Release target
Confirmed by the reviewer: `master` is the intended line for Fix Version `2026.12`.

## What the review found, and how it ended

| Finding (round 1) | Outcome |
|---|---|
| Strings (`"30"`, `"true"`, `"false"`) rejected by the schema validator | Fixed in `abb4af0`: schema type is `[type, "string"]` for the three params |
| A shipped skill sends a string: `tool-addressing.md:258` in `servoy-agent-shared` says `includeAllConsoles: "true"` | No longer breaks. Changing it to a native boolean in skill4servoy is optional cleanup, not a blocker |
| `maxLines: 30.0` passed the schema, then `NumberFormatException` in `Integer.parseInt` | Fixed: `parseMaxLines` parses via `BigDecimal`; `30`, `"30"` and `30.0` all mean 30 |
| Tests only asserted annotation values | Fixed: `ServoyIdeServerCallPathTest` drives the real schema validator and `ToolExecutor` |
| `getSource` fix is wording only | Unchanged; accepted as is |

`ServoyIdeServerCallPathTest` now asserts: numeric and native boolean values accepted; quoted values accepted; `30.0` reaches the service as 30; fractional, zero, negative, text and blank `maxLines` fall back to 100; an object `maxLines` and a numeric `clear` are still rejected.

## Manual test plan

**Verifying the fix**
1. With the MCP server running, call `servoy-ide` `getConsoleOutput` with `{ "maxLines": 30 }`. Expect console text.
2. Call it with `{ "includeAllConsoles": true, "clear": false }`. Expect success.
3. Call `getSource` with `{ "name": "<an existing form>" }`. Expect the source, and confirm the new `name` description shows.

**Regression checks**
1. Call `getConsoleOutput` with `{ "maxLines": "30" }` and `{ "includeAllConsoles": "true", "clear": "false" }`. Expect success (quoted values accepted).
2. Call it with `{ "maxLines": 30.0 }`. Expect 30 lines, no error.
3. Call it with `{ "maxLines": "abc" }` and `{ "maxLines": 0 }`. Expect 100 lines.
4. Call it with `{}`. Expect the defaults (100 lines, no clear).
5. Call `getSource` with `{ "formName": "x" }`. Record the error an agent would see.
6. Load an agent with the current `servoy-agent-shared` skill and let it read the console; confirm the quoted `includeAllConsoles` no longer fails.

Automated: `ServoyIdeServerCallPathTest`, `ServoyIdeServerSchemaTest`, `ServoyIdeServerTest`, `McpToolParamValidationTest` in `com.servoy.eclipse.developer.mcp.tests`.

## Possible improvements / follow-ups
- **Union type in other clients:** the published `type` is now an array (e.g. `["integer","string"]`). The MCP validator accepts it (covered by the call-path test). Clients that convert tool schemas for other providers, such as Gemini-style function declarations, were not checked and some reject array types.
- **Silent fallback:** a malformed `maxLines` now returns 100 lines instead of an error, so the agent gets no signal that its value was ignored.
- **Skill text:** change `includeAllConsoles: "true"` to a native boolean (and give `maxLines` a native number) in `skill4servoy/.opencode-shippable/skills/servoy-agent-shared/context/tool-addressing.md:258`.
- **Javadoc:** `McpServerFactory.schemaType` is described as package-visible for unit testing, but the test bundle is not a fragment and cannot call it. Coverage comes through the real factory path, so the comment is inaccurate but harmless.
- `getSource`: an agent that sends `formName` still gets "Missing key". Consider an alias or a clearer error.
- Other tools' numeric/boolean `String` params (`ServoyDevServer` and others) still publish `type: string`; audit as a follow-up.
- `maxLines` has no upper bound (pre-existing).
