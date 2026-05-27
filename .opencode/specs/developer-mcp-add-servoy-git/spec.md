---
name: developer-mcp-add-servoy-git
workflow: full
---

## Goal

Add a new MCP server endpoint `servoy-git` to `com.servoy.eclipse.developer.mcp` that exposes EGit-backed git operations for projects in the Servoy workspace, ported from the AssistAI plugin's `eclipse-git` server.

The endpoint must be reachable at `/svymcp/servoy-git` with Bearer token auth. **This endpoint is for AI agents that explicitly want git tooling exposed by the MCP server**, not the user-facing project rule. AI agents calling this endpoint are operating with the user's explicit intent.

## Target Project

- **Bundle:** `com.servoy.eclipse.developer.mcp`
- **Path:** `/Volumes/ServoyWork/git/master/Servoy-Copilot/bundles/com.servoy.eclipse.developer.mcp`
- **New server class:** `ServoyGitServer.java` under `com.servoy.eclipse.developer.mcp.servers`

## Source Material

### Reference (read-only, AssistAI)

- `/Volumes/ServoyWork/git/master/eclipse-chatgpt-plugin/plugins/com.github.gradusnikov.eclipse.plugin.assistai.main/src/com/github/gradusnikov/eclipse/assistai/mcp/servers/EclipseGitMcpServer.java`
- `GitService` and any helpers it delegates to (under `mcp/services/`)
- DTOs / exceptions referenced by `GitService`

### Existing Servoy Developer MCP

- The same template files as in earlier project-actions

## Tools to port (13 total)

| Tool | Description |
|---|---|
| `gitStatus` | Working tree status of a project's repo |
| `gitLog` | Commit history (max count parameter) |
| `gitAdd` | Stage files (path pattern) |
| `gitCommit` | Commit staged changes with a message |
| `gitDiff` | Unified diff (staged or unstaged) |
| `gitBranch` | List branches |
| `gitCreateBranch` | Create a branch (does not switch) |
| `gitDeleteBranch` | Delete a branch |
| `gitCheckout` | Switch branches |
| `gitReset` | Unstage files |
| `gitStash` | Stash working tree changes |
| `gitStashPop` | Apply and remove most recent stash |
| `gitStashList` | List stashes |

All tools take a `projectName` argument and operate on that project's git repo via EGit.

## Constraints

1. **Endpoint name** — `servoy-git` (path `/svymcp/servoy-git`)
2. **Independence** — no `com.github.gradusnikov` imports anywhere
3. **No file-format guard needed** — git operations work at the repo level, not on individual file content; they don't text-edit `.frm` files.
4. **Use EGit (jgit) APIs already in the Servoy target platform** — verify in research findings that the required `org.eclipse.egit.core` / `org.eclipse.jgit` packages are exported by the platform. If not, add them to `Import-Package` per the research.
5. **Project resolution** — when a tool receives a `projectName`, resolve to the corresponding `IProject` and locate its git repository via the EGit `RepositoryUtil` API.
6. **Error handling** — every tool returns a JSON-RPC error if:
    - The named project does not exist
    - The project is not under git
    - The git operation throws (e.g. merge conflict, dirty tree on stash)
7. **Registration** — register `ServoyGitServer` in `McpServerBuiltins.createServerInstances()`.
8. **Skills to load** — `mcp-dependency-analysis`.
9. **Edit only inside Servoy-Copilot** — never write to other projects.
10. **The project-level "no git operations" rule does NOT apply to this endpoint at runtime.** The `AGENTS.md` rule applies to OpenCode agents managing source-control commits during development. Exposing EGit operations as runtime MCP tools is a deliberate feature; users invoke it via AI agents only when they explicitly want it.

## Acceptance Criteria

1. Zero compilation errors in `com.servoy.eclipse.developer.mcp`
2. No `com.github.gradusnikov` imports anywhere
3. `ServoyGitServer` annotated `@McpServer(name = "servoy-git")` exposes exactly **13** `@Tool` methods
4. Every tool resolves the named project via `ResourcesPlugin.getWorkspace().getRoot().getProject(name)` and returns a clear error if absent
5. `ServoyGitServer` is registered in `McpServerBuiltins.createServerInstances()`
6. After Servoy restart, `/svymcp/servoy-git` `tools/list` returns 13 tools
7. Endpoint-tester confirms `gitStatus` and `gitLog` happy-path calls succeed against a real project
8. Endpoint-tester confirms a "project not found" call returns a JSON-RPC error with a clear message
9. `ARCHITECTURE.md` updated

## Out of Scope

- Cross-repository operations (push, pull, fetch, clone) — not in this batch
- Conflict resolution UI — agents must surface conflicts and let the user resolve
- Sub-module operations

## JUnit plugin tests

Write JUnit plugin tests covering:
- `ServoyGitServer` — project not found returns error, null project throws
- `@McpServer` annotation name and exact `@Tool` count (13)
- Registration in `McpServerBuiltins`
- Add to `AllDeveloperMcpTests` suite; compile clean; runtime execution is manual

## Servoy Developer context

`com.servoy.eclipse.developer.mcp` runs inside **Servoy Developer** (port 8183), NOT the Eclipse IDE (port 8124). When testing:
- Use Servoy solution project names (e.g. `Example_AI_Plugin`), not Eclipse IDE workspace projects
- Restart Servoy Developer after implementation to register the new endpoint
