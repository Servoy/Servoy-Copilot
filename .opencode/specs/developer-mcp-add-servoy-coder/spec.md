---
name: developer-mcp-add-servoy-coder
workflow: full
---

## Goal

Add a new MCP server endpoint `servoy-coder` to `com.servoy.eclipse.developer.mcp` that exposes generic file-editing tools (create, insert, replace, delete, patch, move, rename) ported from the AssistAI plugin's `eclipse-coder` server. All Java/JDT-specific refactoring tools are excluded — this endpoint operates on any text file.

The endpoint must be reachable at `/svymcp/servoy-coder` with Bearer token auth, alongside `memory`, `time`, and the previously-added `servoy-context`.

## Target Project

- **Bundle:** `com.servoy.eclipse.developer.mcp`
- **Path:** `/Volumes/ServoyWork/git/master/Servoy-Copilot/bundles/com.servoy.eclipse.developer.mcp`
- **New server class:** `ServoyCoderServer.java` under `com.servoy.eclipse.developer.mcp.servers`

## Source Material

### Reference (read-only, AssistAI)

- `/Volumes/ServoyWork/git/master/eclipse-chatgpt-plugin/plugins/com.github.gradusnikov.eclipse.plugin.assistai.main/src/com/github/gradusnikov/eclipse/assistai/mcp/servers/EclipseCodeEditingMcpServer.java`
- `CodeEditingService` and any other service classes the server delegates to (see `mcp/services/`)
- `ResourceService` (used by createDirectories, deleteFile, moveResource, renameFile)
- DTOs / exceptions referenced by those services

### Existing Servoy Developer MCP

- The same files listed in the `servoy-context` spec (template servers, annotations, McpServerBuiltins, MANIFEST.MF)
- `ServoyContextServer.java` (if available) — produced by the previous project-action; useful as another structural reference
- `ServoyFileGuard.java` and `ServoyFileFormatProtectedException.java` if they were already added in a previous project-action; otherwise add them here.

## Tools to port

| Tool | Notes |
|---|---|
| `createFile` | New file in a project; if the target path is a Servoy structural extension, refuse via `ServoyFileGuard` |
| `insertIntoFile` | Insert content at a 1-based line; **destructive — guard required** |
| `replaceString` | Find/replace exact match; **destructive — guard required** |
| `undoEdit` | Revert from backup (no guard — recovery is exempt) |
| `createDirectories` | Recursive mkdir; safe |
| `renameFile` | Workspace rename; not guarded — renaming `.frm` is a legitimate workspace op |
| `moveResource` | Workspace move; not guarded |
| `deleteFile` | Workspace delete; not guarded |
| `replaceFileContent` | Replace whole file; **destructive — guard required** |
| `deleteLinesInFile` | Delete a 1-based range; **destructive — guard required** |
| `applyPatch` | Apply unified diff with fuzzy matching; **destructive — guard required** |

## Tools to skip (Java/JDT-only)

- `formatFile` — JDT formatter; Servoy JS uses DLTK
- `refactorRenameJavaType`, `refactorMoveJavaType`, `refactorRenamePackage` — JDT only
- `organizeImports`, `organizeImportsInPackage` — JDT only

State each skip explicitly in the migration plan with the reason.

## Constraints

1. **Endpoint name** — `servoy-coder` (path `/svymcp/servoy-coder`)
2. **Independence** — no `com.github.gradusnikov` imports anywhere
3. **Servoy file-format guard** — every tool listed as "destructive" above MUST call `ServoyFileGuard.assertEditable(filePath)` before performing the edit. The exception is mapped to a JSON-RPC error per the `servoy-file-format-guard` skill.
4. **`searchAndReplace` is NOT in this server** — it is part of `servoy-ide`. Do not add it here.
5. **Add `ServoyFileGuard` if missing** — if `com.servoy.eclipse.developer.mcp.guard.ServoyFileGuard` doesn't yet exist, the migration plan must include creating both `ServoyFileGuard` and `ServoyFileFormatProtectedException` per the skill.
6. **Registration** — register `ServoyCoderServer` in `McpServerBuiltins.createServerInstances()`.
7. **Skills to load** — `mcp-dependency-analysis`, `servoy-file-format-guard`, `eclipse-file-revert-patterns` (for `undoEdit`).
8. **Edit only inside Servoy-Copilot** — never write to other projects.
9. **No git operations**.

## Acceptance Criteria

1. Zero compilation errors in `com.servoy.eclipse.developer.mcp`
2. No `com.github.gradusnikov` imports anywhere
3. `ServoyCoderServer` annotated `@McpServer(name = "servoy-coder")` exposes exactly 11 `@Tool` methods (the list above)
4. `ServoyFileGuard` is present at `com.servoy.eclipse.developer.mcp.guard.ServoyFileGuard` and is invoked from every destructive tool method
5. `ServoyCoderServer` is registered in `McpServerBuiltins.createServerInstances()`
6. After Servoy restart, `/svymcp/servoy-coder` `tools/list` returns 11 tools
7. Endpoint-tester confirms each tool returns a valid `result` for a valid input
8. Endpoint-tester confirms each destructive tool returns a JSON-RPC error containing `Refusing to edit Servoy structural file` when invoked with a `.frm` path
9. `undoEdit` does NOT trigger the file-format guard (recovery is exempt)
10. `ARCHITECTURE.md` updated to describe the new endpoint

## Out of Scope

- `formatFile`, `refactor*`, `organizeImports*` (Java/JDT-only)
- `searchAndReplace` (belongs to `servoy-ide`, not here)

## JUnit plugin tests

Write JUnit plugin tests for the new server class and `ServoyFileGuard` (if newly introduced). Tests must:
- Cover `ServoyFileGuard.assertEditable()` for each forbidden extension and for safe extensions
- Cover `ServoyCoderServer` error paths (null project, null file, forbidden extension)
- Cover `@McpServer` annotation name and exact `@Tool` count
- Be added to `AllDeveloperMcpTests` suite
- Compile clean — runtime execution is manual via Run As → JUnit Plugin Test

## Servoy Developer context

`com.servoy.eclipse.developer.mcp` runs inside **Servoy Developer** (port 8183), NOT the Eclipse IDE (port 8124). When testing:
- Use Servoy solution project names (e.g. `Example_AI_Plugin`), not Eclipse IDE workspace projects
- File paths are relative to the Servoy project root (e.g. `forms/myForm.js`)
- Restart Servoy Developer after implementation to register the new endpoint
