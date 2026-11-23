# Review Summary — SVY-21618

MCP `getConsoleOutput` rejected a numeric `maxLines`; fix adds `type = "integer"/"boolean"` to the schema and rewords the `getSource` `name` description.

**Risk: MODERATE (sent back to the author)** — the published schema flips from string to integer/boolean, so any client that sends `"30"` or `"true"` as a string is now rejected. One real string-sending caller exists in the skills (see findings).

Reviewed: Servoy-Copilot, commit `b4b73c6` on `master`.

## Release target
`INDETERMINATE` — Fix Version `2026.12` is unreleased and no branch in this repo carries a source version that maps to it (bundle manifest reads `2026.3.1.qualifier`). Confirm `master` is the intended line.

## Verified findings (after the briefing)

A plain-JUnit test of the real call path (`ServoyIdeServerCallPathTest`: schema built by `McpServerFactory`, arguments checked by the same Jackson validator, dispatch through `ToolExecutor` into a real `ServoyIdeServer` with `IdeStateService` mocked) passed 6/6 and showed:

| Call | Result |
|---|---|
| `maxLines: 30` | passes the schema, reaches the service as 30 (reported bug fixed) |
| `includeAllConsoles: true`, `clear: false` | pass, reach the service |
| `{}` | defaults kept: 100 lines, no flags |
| `maxLines: "30"` | **rejected by the schema** |
| `clear: "false"`, `includeAllConsoles: "true"` | **rejected by the schema** |
| `maxLines: 30.0` | passes the schema, then `NumberFormatException: For input string: "30.0"` in `Integer.parseInt` (`ServoyIdeServer.java:338`) |

- The Jira claim "both native and string values are accepted end-to-end" does not hold at the schema level. `ToolExecutor` coerces both, but the validator rejects strings before coercion runs.
- **A real string-sending caller exists:** `tool-addressing.md:258` in the `servoy-agent-shared` skill tells agents to read the console with `includeAllConsoles: "true"`. Source copy: `skill4servoy/.opencode-shippable/skills/servoy-agent-shared/context/tool-addressing.md`; the extracted copy under `~/.servoy/opencode/skills/` has the same line. That line must change to a native boolean (and `maxLines` to a native number) in skill4servoy.
- No `getSource` call with a key other than `name` was found in the extracted skills and agents. No skills zip was found, so archives were not searched.
- Not verified: the SDK's own request dispatch (the validator was called directly against the published schema), and agents failing live on the skill line.

## Manual test plan

**Verifying the fix**
1. Start Servoy Developer with the MCP server running. Call `servoy-ide` `getConsoleOutput` with `{ "maxLines": 30 }`. Expect console text, not "Expected string".
2. Call `getConsoleOutput` with `{ "includeAllConsoles": true, "clear": false }`. Expect success.
3. Call `getSource` with `{ "name": "<an existing form>" }`. Expect the source. Confirm the new `name` description shows in the tool listing.

**Regression checks**
1. Call `getConsoleOutput` with `{ "maxLines": "30" }` and `{ "clear": "false" }`. Expected: rejected by the schema validator (confirmed by the unit test; confirm once over real MCP).
2. Call `getConsoleOutput` with `{ "maxLines": 30.0 }`. Expected: `NumberFormatException` from the tool (confirmed by the unit test).
3. Call `getConsoleOutput` with `{}`. Expect the defaults (100 lines, no clear).
4. Call `getSource` with `{ "formName": "x" }`. Record the error an agent would see.
5. Load an agent with the current `servoy-agent-shared` skill and let it read the console; check whether it sends `includeAllConsoles: "true"` as a string and fails.

Automated: `ServoyIdeServerCallPathTest`, `ServoyIdeServerSchemaTest`, `ServoyIdeServerTest`, `McpToolParamValidationTest` in `com.servoy.eclipse.developer.mcp.tests`.

## Possible improvements / follow-ups
- **Skill fix (needed):** change `includeAllConsoles: "true"` to `true` and give `maxLines` a native number in `tool-addressing.md:258` in skill4servoy. Installs that keep the old skill text with the new plugin will hit the schema error until they update.
- **Decide with the author:** keep the strict single-type schema, or accept both string and native values (e.g. `["string","integer"]`). That needs a change in how `@ToolParam` publishes `type`, which takes one string today.
- **Decide with the author:** make `maxLines` parsing lenient so `30.0` works, or leave it as a tool error.
- `getSource` fix is wording only; an agent that sends `formName` still gets "Missing key". Consider an alias or a clearer error.
- Other tools' numeric/boolean `String` params (`ServoyDevServer` and others) still publish `type: string`; audit as a follow-up.
- `maxLines` has no upper bound (pre-existing).
- The reviewer added `ServoyIdeServerCallPathTest.java` (plain JUnit, 6 tests); it is uncommitted and can be kept or dropped by the author.
