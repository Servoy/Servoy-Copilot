# Triage Report — SVY-21296

**Verdict:** PROCEED

## Reported problem

All Cypress test running infrastructure (UI command handlers, results view, execution engine, test discovery, headless CI runner) is exclusively in `com.servoy.eclipse.developer.mcp` — the AI/MCP plugin. Users cannot run Cypress form tests or E2E tests without installing the AI plugin, even though running pre-existing tests has nothing to do with AI.

## Root-cause assessment

The Cypress code was developed incrementally as part of the AI-assisted testing feature story (SVY-21025 → SVY-21170 → SVY-21171 → SVY-21174 → SVY-21173). It landed in the MCP plugin because the AI agent was the first consumer — not because of any technical dependency on MCP infrastructure.

Code analysis confirms that **none** of the Cypress classes import MCP-specific packages (`io.modelcontextprotocol.*`, `com.servoy.eclipse.opencode`, MCP server classes, or the MCP plugin's own `Activator`). All dependencies are on:
- Eclipse platform APIs (`ResourcesPlugin`, UI, JFace, Console, Equinox Application)
- Servoy model/core/ui (`ServoyModelFinder`, `ServoyProject`, `ServoyLog`)
- Servoy ngclient.ui (`Activator` — for Node.js path resolution)
- Servoy exporter (`AbstractWorkspaceExporter` — for the headless runner)
- Jackson (`ObjectMapper` — available in the target platform)
- Sablo websocket APIs (for headless form preview)

The coupling is purely organizational (same bundle), not technical.

## Ticket premise check

The ticket's proposed approach is correct. Creating a new standalone plugin and adding it to `com.servoy.eclipse.feature/feature.xml` is the right architectural solution. There is no simpler alternative that achieves the goal.

## Approaches considered

1. **Extract to a new plugin `com.servoy.eclipse.cypress` (or similar)** — Move all Cypress test running code to a new OSGi bundle, register all UI contributions (commands, handlers, menus, views, property testers, adapters, headless application) in the new plugin's `plugin.xml`, add the plugin to `com.servoy.eclipse.feature/feature.xml`. The MCP plugin's `ServoyTestingServer` would then Import-Package or Require-Bundle the new plugin to delegate tool calls.
   - Pros: Clean separation of concerns; Cypress tests work without AI; MCP tools still work; no user-visible regression.
   - Cons: Non-trivial refactoring; needs careful package naming; requires updating test fragments.

2. **Move code to an existing Servoy bundle (e.g. `com.servoy.eclipse.jsunit` or `com.servoy.eclipse.debug`)** — Avoid creating a new plugin by placing the Cypress code alongside existing test infrastructure.
   - Pros: No new bundle to maintain.
   - Cons: Violates single-responsibility; `com.servoy.eclipse.jsunit` is for JSUnit, not Cypress; adds heavy dependencies (ngclient.ui, exporter) to an existing focused bundle.

3. **No code change** — Leave everything in the MCP plugin and have users install the AI plugin if they want to run Cypress tests.
   - Pros: Zero effort.
   - Cons: Unacceptable per the ticket requirements; architecturally wrong; blocks SVY-21323.

## Recommendation

**Approach 1: Extract to a new plugin.** Suggested bundle name: `com.servoy.eclipse.cypress` (package: `com.servoy.eclipse.cypress`).

Classes to move:
- **actions:** `RunCypressFormTestHandler`, `RunAllCypressFormTestsHandler`, `RunAllE2ETestsCommandHandler`, `CypressConsoleUtil`, `CypressTestSessionManager`, `CypressTestResult`, `CypressFormTestTarget`, `CypressTestAdapterFactory`, `CypressTestPropertyTester`, `CypressEditorInputPropertyTester`
- **services:** `FormSpecRunner`, `FormSpecGenerator`, `CypressOutputParser`, `CypressTestDiscoveryService`, `CypressLoginSupport`
- **views:** `CypressTestResultsView`
- **headless:** `CypressFormTestRunner`, `CypressFormTestArgumentChest`
- **Enum/model:** `TestStatus` (from `CypressTestResult` or `CypressOutputParser`)

plugin.xml contributions to move: commands, handlers, menus, views, property testers, adapter factories, core-expression definitions, perspectiveExtensions, and the `org.eclipse.core.runtime.applications` extension for the headless runner.

After extraction, the MCP plugin's `ServoyTestingServer` should `Import-Package` or `Require-Bundle` the new plugin and delegate all Cypress calls to it.

The `com.servoy.eclipse.feature/feature.xml` needs a new `<plugin>` entry for the new bundle.

## Git history findings

- `6244a8e` SVY-21170 — introduced `RunCypressFormTestHandler`, `CypressConsoleUtil`, discovery service, property testers (originally placed in MCP because the AI agent was the sole consumer)
- `56c5bed` SVY-21171 — moved form test files out of media folder
- `f0ed01f` SVY-21174 — added `CypressTestResultsView`, session manager, output parser
- `c88782d` SVY-21102 — added E2E test support, `RunAllE2ETestsCommandHandler`
- `b65979d` SVY-21173 — added headless `CypressFormTestRunner` application

All commits confirm the code has no MCP-protocol dependency; it was placed in the MCP plugin for historical/organizational convenience only.
