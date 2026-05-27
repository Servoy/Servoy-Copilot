---
name: mcp-dependency-analysis
description: Checklist and ground rules for analysing tool-interface dependencies when porting MCP tools from eclipse-chatgpt-plugin into com.servoy.eclipse.developer.mcp — prevents the architect from producing an incomplete plan that misses compile-blocking dependencies.
---

## What I cover

When porting MCP tools from `eclipse-chatgpt-plugin` into `com.servoy.eclipse.developer.mcp`, every `@Tool` method on an MCP server class typically delegates to a Java service class that does the real work (e.g. `CodeEditingService`, `ResourceService`, `LocalHistoryService`, `GitService`). Those service classes have their own dependency chains. The architect's plan is incomplete if it lists only the MCP server class and forgets the service plus the helpers/DTOs that the service uses.

This skill is the checklist that prevents that omission.

---

## The core rule

For every `@Tool` method in a source MCP server, do not stop at reading the `@Tool` body. Also read:

1. Every helper or service class it delegates to
2. Every class those services import from `com.github.gradusnikov.eclipse.assistai.mcp.services` and `...mcp.servers`
3. Every DTO, exception, or enum referenced anywhere in that chain
4. Any helper utility used inside the service implementations

Only when the full chain is mapped can you write a complete file inventory.

Source root for porting:
```
/Volumes/ServoyWork/git/master/eclipse-chatgpt-plugin/plugins/com.github.gradusnikov.eclipse.plugin.assistai.main/src/com/github/gradusnikov/eclipse/assistai/mcp/
```

Target root for porting:
```
/Volumes/ServoyWork/git/master/Servoy-Copilot/bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/
```

---

## Where service classes live in the source

| Source package | What it contains | Examples |
|---|---|---|
| `mcp.services` | Java services backing tool methods | `CodeEditingService`, `ResourceService`, `LocalHistoryService`, `GitService`, `EditorService` |
| `mcp.servers` | The MCP server classes with `@Tool` methods | `EclipseCodeEditingMcpServer`, `EclipseContextMcpServer`, `EclipseGitMcpServer` |
| `mcp` (root) | Annotations and shared types | `annotations.Tool`, `annotations.McpServer`, `annotations.ToolParam`, `ToolExecutor` |

---

## Independence rule for the port

Servoy Developer MCP must have NO runtime dependency on
`com.github.gradusnikov.eclipse.plugin.assistai.main`. The plugin must
not declare it in `Require-Bundle` or `Import-Package`. Service classes
referenced by ported tools must be re-implemented (copy + relocate) inside
`com.servoy.eclipse.developer.mcp`, not imported from AssistAI.

Reading AssistAI source as a reference is allowed. Importing it at runtime
is not.

---

## Servoy file format protection

When porting tools that perform destructive text edits — replaceString,
applyPatch, deleteLinesInFile, replaceFileContent, searchAndReplace —
the architect MUST add a guard layer that refuses to operate on Servoy
structural files (`.frm`, `.obj`, `.tbl`, `.val`, `.rel`, `.dbi`).

The shared utility class is `ServoyFileGuard`. Every destructive tool
must call `ServoyFileGuard.assertEditable(path)` before performing the
edit. On failure, the tool returns a JSON-RPC error so AI agents get an
explicit signal. See the `servoy-file-format-guard` skill for the exact
extension list and refusal pattern.

---

## Implementation order rules

Always implement in this order for the affected packages:

1. DTOs, exceptions, enums (no dependencies on other new classes)
2. `ServoyFileGuard` and other shared utilities
3. Service classes (depend on DTOs and utilities)
4. The MCP server class (depends on services and `ServoyFileGuard`)
5. Registration in `McpServerBuiltins` (last, after the server class compiles clean)

Never schedule a service or server class before all its dependencies are in the list.

---

## Checklist — for every new MCP server endpoint

Before finalising the migration plan for one endpoint, verify:

- [ ] Every `@Tool` method has been traced to its service class(es)
- [ ] Every service class is present in the file inventory (or explicitly
      marked as "skipped because <reason>")
- [ ] Every DTO/exception/enum reachable from any service is in the inventory
- [ ] Destructive tools call `ServoyFileGuard.assertEditable(path)`
- [ ] No `import com.github.gradusnikov.eclipse...` lines in any new class
- [ ] `@McpServer(name = "<endpoint-name>")` matches the spec's required name
- [ ] The endpoint class is added to `McpServerBuiltins.createServerInstances()`
- [ ] The total file count in the plan's Section 1 matches the actual count of files listed
- [ ] The Definition of Done states an exact tool count, not "at least N"

---

## Servoy Developer MCP JVM separation

`com.servoy.eclipse.developer.mcp` runs inside **Servoy Developer** (an Eclipse RCP application), NOT inside the Eclipse IDE used for development. These are two separate JVMs:

| JVM | MCP port | Workspace projects visible |
|---|---|---|
| Eclipse IDE (development) | 8124 (AssistAI) | Eclipse IDE workspace projects (e.g. `j2db_server`, `Servoy-Copilot`) |
| Servoy Developer | 8183 (developer.mcp) | Servoy solution projects (e.g. `Example_AI_Plugin`) |

**When testing `developer.mcp` endpoints via curl:**
- Use project names from the **Servoy Developer workspace**, not Eclipse IDE workspace projects
- File paths are relative to the Servoy project root (e.g. `forms/myForm.js`)
- Eclipse Local History is only populated for files edited through Servoy Developer IDE

---

## AssistAI-specific utilities to drop when porting

Some AssistAI service classes carry dependencies that are specific to the AssistAI DI container
and UI thread model. These must be **dropped entirely** when porting — do not try to replicate them.

### `UISynchronizeCallable`

`com.github.gradusnikov.eclipse.assistai.tools.UISynchronizeCallable` is an AssistAI utility
that runs a `Callable` on the SWT UI thread synchronously. It is injected via `@Inject`.

**When porting `GitService`:** the only method that uses it is `getCurrentDiff()`, which gets
the diff of the currently active editor file. This method is NOT exposed as a `@Tool` in the
server class. Drop it entirely — along with the `EditorService` field that it also requires.

**Rule:** if a service method uses `UISynchronizeCallable` AND is not exposed as a `@Tool`,
drop the method. The MCP server runs headless; UI thread access is not available.

### `@Inject private ILog logger`

Replace with a lazy `Platform.getLog(MyClass.class)` call at the point of use (inside catch
blocks). Do NOT use it as a `static final` field — see the `pde-plugin-testing` skill for why
that crashes JUnit Plugin Tests.

### `@Inject` / `@Creatable` annotations

Remove all `@Inject` and `@Creatable` annotations from ported service classes. The ported
services are plain POJOs instantiated with `new` — no DI container is involved.
