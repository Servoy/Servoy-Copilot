---
name: developer-mcp-add-servoy-ide
workflow: full
---

## Goal

Add a new MCP server endpoint `servoy-ide` to `com.servoy.eclipse.developer.mcp` that exposes generic IDE/workspace tools (project layout, file/text search, console output, currently-opened file, Markdown navigation, stripped compilation errors) ported from the AssistAI plugin's `eclipse-ide` server. All Java/JDT, JUnit, Maven, and PDE-specific tools are excluded.

The endpoint must be reachable at `/svymcp/servoy-ide` with Bearer token auth.

## Target Project

- **Bundle:** `com.servoy.eclipse.developer.mcp`
- **Path:** `/Volumes/ServoyWork/git/master/Servoy-Copilot/bundles/com.servoy.eclipse.developer.mcp`
- **New server class:** `ServoyIdeServer.java` under `com.servoy.eclipse.developer.mcp.servers`

## Source Material

### Reference (read-only, AssistAI)

- `/Volumes/ServoyWork/git/master/eclipse-chatgpt-plugin/plugins/com.github.gradusnikov.eclipse.plugin.assistai.main/src/com/github/gradusnikov/eclipse/assistai/mcp/servers/EclipseIntegrationsMcpServer.java`
- Service classes the server delegates to (under `mcp/services/`):
    - `ProjectService`, `ResourceService`, `EditorService`, `ConsoleService`, `SearchService`, `MarkdownService`
- DTOs / exceptions referenced by those services

### Existing Servoy Developer MCP

- The same template files (template servers, annotations, McpServerBuiltins, MANIFEST.MF)
- `ServoyFileGuard` (added by an earlier project-action — reuse it for `searchAndReplace`)

## Tools to port (13 total)

| Tool | Notes |
|---|---|
| `getProjectLayout` | File/folder tree of a project (scopePath, maxDepth) |
| `readProjectResource` | Read a text resource with line ranges; **strip the JDT "import collapsing" feature** — keep line-numbering and range options only |
| `listProjects` | Workspace projects with their natures (Servoy projects show `com.servoy.eclipse.core.ServoyProject` nature) |
| `getCurrentlyOpenedFile` | Active editor file info |
| `getEditorSelection` | Currently selected text in active editor |
| `getConsoleOutput` | Recent Eclipse console output |
| `getProjectProperties` | Generic project properties |
| `findFiles` | Workspace files matching glob patterns |
| `fileSearch` | Plain substring search across workspace files |
| `fileSearchRegExp` | Java regex search across workspace files |
| `getMarkdownOutline` | TOC of a Markdown file with line numbers |
| `getMarkdownSection` | Read a section from a Markdown file by heading |
| `getCompilationErrors` | **Stripped Java-agnostic version** — return marker text, severity, file path, line number, and source ID for ALL workspace markers (not just JDT). Do NOT return JDT-specific Marker IDs or Quick Fix proposals. |
| `searchAndReplace` | Plain text search & replace across files. **Must apply ServoyFileGuard to every matched file**. If any matched file is protected (`.frm`/`.obj`/etc.), abort the whole batch and return a JSON-RPC error naming the offending file. |

(That's 14 — let me recount: getProjectLayout, readProjectResource, listProjects, getCurrentlyOpenedFile, getEditorSelection, getConsoleOutput, getProjectProperties, findFiles, fileSearch, fileSearchRegExp, getMarkdownOutline, getMarkdownSection, getCompilationErrors, searchAndReplace = 14.)

The exact tool count in the migration plan's Definition of Done must be **14**.

## Tools to skip

| Tool | Reason |
|---|---|
| `formatCode` | JDT formatter |
| `getJavaDoc`, `getSource`, `getClassOutline`, `getMethodSource`, `getFilteredSource`, `getMethodCallHierarchy` | Java source navigation, JDT only |
| `getTypeHierarchy`, `findReferences` | JDT type model |
| `executeQuickFix`, `getImportSuggestions` | JDT quick-fix infrastructure |
| `runAllTests`, `runPackageTests`, `runClassTests`, `runTestMethod`, `findTestClasses` | JUnit launcher; Servoy uses jsunit, separate concern |
| `runMavenBuild`, `getEffectivePom`, `listMavenProjects`, `getProjectDependencies` | Maven; Servoy projects are not Maven projects |

State each skip explicitly in the migration plan with the reason.

## Constraints

1. **Endpoint name** — `servoy-ide` (path `/svymcp/servoy-ide`)
2. **Independence** — no `com.github.gradusnikov` imports anywhere
3. **Servoy file-format guard** — `searchAndReplace` MUST call `ServoyFileGuard.assertEditable()` for every matched file path before performing replacements. If any matched file is protected, abort the whole batch and return a JSON-RPC error naming the offending file. None of the other 13 tools perform destructive edits, so they don't need the guard.
4. **`getCompilationErrors` must be stripped** — drop JDT Marker IDs and Quick Fix proposals from the returned data. Return `{ filePath, severity, message, lineNumber, sourceId }` per marker. Iterate over `IResource.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)`.
5. **Add `searchAndReplace`** — this is the home for it. It is NOT in `servoy-coder`.
6. **Registration** — register `ServoyIdeServer` in `McpServerBuiltins.createServerInstances()`.
7. **Skills to load** — `mcp-dependency-analysis`, `servoy-file-format-guard`.
8. **Edit only inside Servoy-Copilot** — never write to other projects.
9. **No git operations**.

## Acceptance Criteria

1. Zero compilation errors in `com.servoy.eclipse.developer.mcp`
2. No `com.github.gradusnikov` imports anywhere
3. `ServoyIdeServer` annotated `@McpServer(name = "servoy-ide")` exposes exactly **14** `@Tool` methods listed above
4. `getCompilationErrors` does not import any `org.eclipse.jdt.*` types and returns a Java-agnostic marker view
5. `searchAndReplace` invokes `ServoyFileGuard.assertEditable()` for each matched file and aborts the entire batch (returning a JSON-RPC error) if any file is protected
6. `ServoyIdeServer` is registered in `McpServerBuiltins.createServerInstances()`
7. After Servoy restart, `/svymcp/servoy-ide` `tools/list` returns 14 tools
8. Endpoint-tester confirms a happy-path call for each tool
9. Endpoint-tester confirms `searchAndReplace` aborts when a `.frm` file matches the pattern
10. `ARCHITECTURE.md` updated

## Out of Scope

- All JDT-specific Java navigation and refactoring tools
- JUnit and Maven tools
- DLTK JS-specific outline/navigation tools (a future endpoint may add those)

## JUnit plugin tests

Write JUnit plugin tests covering:
- `ServoyIdeServer` — error paths (null project, project not found), `getCompilationErrors` returns non-null, `searchAndReplace` refuses `.frm` files
- `@McpServer` annotation name and exact `@Tool` count (14)
- Registration in `McpServerBuiltins`
- Add to `AllDeveloperMcpTests` suite; compile clean; runtime execution is manual

## Servoy Developer context

`com.servoy.eclipse.developer.mcp` runs inside **Servoy Developer** (port 8183), NOT the Eclipse IDE (port 8124). When testing:
- Use Servoy solution project names (e.g. `Example_AI_Plugin`), not Eclipse IDE workspace projects
- File paths are relative to the Servoy project root (e.g. `forms/myForm.js`)
- Restart Servoy Developer after implementation to register the new endpoint
