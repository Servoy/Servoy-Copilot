---
name: developer-mcp-add-servoy-context
workflow: full
---

## Goal

Add a new MCP server endpoint `servoy-context` to `com.servoy.eclipse.developer.mcp` that exposes generic Eclipse workspace context tools (Local History, resource cache) ported from the AssistAI plugin's `eclipse-context` server, adapted for the Servoy-Copilot product.

The endpoint must be reachable at `http://localhost:<port>/svymcp/servoy-context` with Bearer token auth, registered alongside the existing `memory` and `time` endpoints.

## Target Project

- **Bundle:** `com.servoy.eclipse.developer.mcp`
- **Path:** `/Volumes/ServoyWork/git/master/Servoy-Copilot/bundles/com.servoy.eclipse.developer.mcp`
- **New package:** `com.servoy.eclipse.developer.mcp.servers` (extend the existing one)
- **New server class:** `ServoyContextServer.java`

## Source Material

### Reference (read-only, AssistAI)

- `/Volumes/ServoyWork/git/master/eclipse-chatgpt-plugin/plugins/com.github.gradusnikov.eclipse.plugin.assistai.main/src/com/github/gradusnikov/eclipse/assistai/mcp/servers/EclipseContextMcpServer.java`
- All service classes that `EclipseContextMcpServer` delegates to — typically `LocalHistoryService` plus any cache helpers under `mcp/services/`
- Any DTO or exception class referenced by those services

### Existing Servoy Developer MCP

- `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/servers/MemoryServer.java`
- `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/servers/TimeServer.java`
- `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/McpServerBuiltins.java`
- `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/annotations/McpServer.java`
- `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/annotations/Tool.java`
- `bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/annotations/ToolParam.java`
- `bundles/com.servoy.eclipse.developer.mcp/META-INF/MANIFEST.MF`

### OSGi configuration

- `META-INF/MANIFEST.MF` of the AssistAI bundle (for reference Import-Package list)
- `META-INF/MANIFEST.MF` of `com.servoy.eclipse.developer.mcp`

## Tools to port

Port the following six tools (drop `restoreFileVersion` from this set — it is in "Tools to dummy" below):

| Tool | Description |
|---|---|
| `listCachedResources` | Lists cached workspace resources (URIs, types, version, timestamps, token counts) |
| `getCachedResource` | Returns content of a specific cached resource by URI |
| `getCacheStats` | Resource cache statistics |
| `getFileHistory` | Lists Local History versions of a file (timestamps, sizes) |
| `getFileHistoryContent` | Returns content of a specific Local History version |
| `compareWithHistory` | Unified diff between current file and a Local History version |

## Tools to dummy (return JSON-RPC error, no state change)

| Tool | Reason |
|---|---|
| `restoreFileVersion` | Restoring a Servoy structural file (`.frm`/`.obj`/etc.) from history can break UUID chains; the dummy returns an error message: `"restoreFileVersion is intentionally not implemented in Servoy Developer MCP — restoring history for Servoy structural files can break UUID cross-references. Use the Servoy editor or the file-system manually."` |

The dummy must still appear in the tool list (so AI agents see it and read the error) but must not modify any state.

## Constraints

1. **Endpoint name** — `servoy-context` (path becomes `/svymcp/servoy-context`)
2. **Independence** — zero `import com.github.gradusnikov...` lines in any new file. Every required service/helper/DTO must be re-implemented inside `com.servoy.eclipse.developer.mcp`.
3. **Servoy file-format guard** — none of these tools perform destructive edits, so the `ServoyFileGuard` is not directly invoked here. However, `restoreFileVersion` (the dummy) must explicitly state that it would have been unsafe for `.frm`/`.obj`/`.tbl`/`.val`/`.rel`/`.dbi` files.
4. **Structural template** — follow the same package layout, annotations, and registration style used by `MemoryServer` and `TimeServer`.
5. **Registration** — register the new class in `McpServerBuiltins.createServerInstances()` so the orchestrator picks it up at startup.
6. **OSGi wiring** — add only the `Import-Package` lines actually needed by the ported code; do not add unused ones.
7. **Skills to load** — researcher and architect must load `mcp-dependency-analysis` and `servoy-file-format-guard`. Developer must load both plus `eclipse-file-revert-patterns`.
8. **No git operations** — never commit, stage, or push. All git is manual.
9. **Edit only inside Servoy-Copilot** — never write to other projects.

## Acceptance Criteria

1. Zero compilation errors in `com.servoy.eclipse.developer.mcp` after the changes
2. No `com.github.gradusnikov` imports anywhere in `com.servoy.eclipse.developer.mcp`
3. `ServoyContextServer` is annotated `@McpServer(name = "servoy-context")` and exposes exactly 7 `@Tool` methods (6 functional + 1 dummy)
4. `ServoyContextServer` is registered in `McpServerBuiltins.createServerInstances()`
5. After Servoy restart, the endpoint is reachable at `/svymcp/servoy-context`; `tools/list` returns 7 tools with the names listed above
6. Each functional tool returns a valid JSON-RPC `result` for at least one valid input
7. `restoreFileVersion` returns a JSON-RPC error containing the documented message and changes no state
8. `ARCHITECTURE.md` is updated (or created if absent) inside `com.servoy.eclipse.developer.mcp` to describe the new endpoint

## Out of Scope

- `restoreFileVersion` implementation — only the dummy is required
- Any AssistAI tools outside the six listed
- Changes to existing `memory` / `time` endpoints
- Maven, JDT, or DLTK integrations

## JUnit plugin tests

Write JUnit plugin tests covering:
- `ServoyResourceCache` — put/get/eviction/stats (pure Java, no OSGi)
- `ServoyContextServer` — empty cache responses, `restoreFileVersion` always throws, null-input errors
- `@McpServer` annotation name and exact `@Tool` count (7)
- Registration in `McpServerBuiltins`
- Add to `AllDeveloperMcpTests` suite; compile clean; runtime execution is manual

## Servoy Developer context

`com.servoy.eclipse.developer.mcp` runs inside **Servoy Developer** (port 8183), NOT the Eclipse IDE (port 8124). When testing:
- Use Servoy solution project names (e.g. `Example_AI_Plugin`), not Eclipse IDE workspace projects
- File paths are relative to the Servoy project root (e.g. `forms/myForm.js`)
- Restart Servoy Developer after implementation to register the new endpoint
