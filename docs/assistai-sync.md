# AssistAI upstream sync (SVY-21303)

One-way sync (upstream → Servoy) of the ported MCP service files against the
open-source AssistAI project. Maintained by the `assistai-sync` skill / the
`/assistai-sync` command. See that skill for the full process.

- **Upstream:** https://github.com/gradusnikov/eclipse-chatgpt-plugin (branch `main`, MIT)
- **Upstream service dir:** `plugins/com.github.gradusnikov.eclipse.plugin.assistai.main/src/com/github/gradusnikov/eclipse/assistai/mcp/services/`
- **Servoy service dir:** `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/`

## Last synced upstream commit: 65e4aecbba22d50cc61483ff032ec17a894ceb6e (2026-10-09)

> Baseline established at upstream `main` HEAD on the bootstrap run (no code
> changes). Future `/assistai-sync` runs review commits made after this SHA.

## File mapping

| Servoy file | Upstream AssistAI class |
|---|---|
| CodeEditingService.java | CodeEditingService.java |
| WorkspaceService.java | ResourceService.java |
| ProjectService.java | ProjectService.java |
| GitService.java | GitService.java |
| LocalHistoryService.java | LocalHistoryService.java |
| MarkdownService.java | MarkdownService.java |
| IdeStateService.java | ConsoleService.java + EditorService.java (merged) |

## Deliberate port differences (never reintroduce)

- No JDT dependency (no Java refactoring / formatter / organize-imports / Java-nature detection).
- No AiIgnoreService / .aiignore checks (access control is the MCP Bearer token).
- No UISynchronize / editor refresh (the Servoy MCP server is headless).
- Keep Servoy-only additions (e.g. WorkspaceService.readProjectResource populates ServoyResourceCache).

## Sync log

- 2026-10-09 — baseline established at `65e4aec` (upstream `main` HEAD: "Merge pull request #175 from costescuandrei/main", 2026-10-08); no code changes (bootstrap).
