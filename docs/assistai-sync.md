# AssistAI upstream sync (SVY-21303)

Keeps the ported MCP service files aligned with the open-source AssistAI project,
in **both** directions:

- **upstream → Servoy** (pull in) — `assistai-sync` skill / `/assistai-sync`.
- **Servoy → upstream** (contribute back, draft PRs) — `assistai-contribute`
  skill / `/assistai-contribute`.

See those skills for the full process. This file is the shared source of truth:
the mapping, the deliberate port differences, and the two baselines + logs.

- **Upstream (PR target):** https://github.com/gradusnikov/eclipse-chatgpt-plugin (branch `main`, MIT)
- **Servoy fork (contribute branches):** https://github.com/Servoy/eclipse-chatgpt-plugin
- **Upstream service dir:** `plugins/com.github.gradusnikov.eclipse.plugin.assistai.main/src/com/github/gradusnikov/eclipse/assistai/mcp/services/`
- **Servoy service dir:** `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/`

## Last synced upstream commit: 65e4aecbba22d50cc61483ff032ec17a894ceb6e (2026-10-09)

> Baseline established at upstream `main` HEAD on the bootstrap run (no code
> changes). Future `/assistai-sync` runs review commits made after this SHA.

## Last contribute baseline: 58661342ca75ae2c10b9597f7979e1bac9472005 (2026-10-09)

> The Servoy commit from which `/assistai-contribute` looks for fixes to propose
> upstream. Established at Copilot `master` HEAD on the bootstrap run (no PRs).
> Future runs consider ported-file fixes made after this SHA, excluding anything
> a forward sync pulled in or that is already in the contributed ledger below.

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

## Sync log (upstream → Servoy)

- 2026-10-09 — baseline established at `65e4aec` (upstream `main` HEAD: "Merge pull request #175 from costescuandrei/main", 2026-10-08); no code changes (bootstrap).

## Contributed ledger (Servoy → upstream)

Fixes proposed back to upstream as draft PRs. Columns: Servoy commit → PR URL → status.

| Servoy commit | PR | Status |
|---|---|---|
| _(none yet)_ | | |

## Contribute log (Servoy → upstream)

- 2026-10-09 — contribute baseline established at `5866134` (Copilot `master` HEAD); no PRs (bootstrap).
