# Spec: SVY-21355 — MCP tool calls for submodules fail often

## 1. Goal

Make `getFilteredSource`, `getClassOutline`, and `getFileOutline` MCP tools work transparently when the target script lives in any module of the active Servoy solution, not only in the active solution project itself. Today, these tools fail whenever the AI agent doesn't provide an explicit `moduleName` and the script belongs to a submodule.

## 2. Background

### 2.1 Current resolution logic

`ServoyScriptResolver.resolveScript(name, moduleName)` resolves a script name to an `IFile`. When `moduleName` is null it searches only the active solution project's `forms/` and `scopes/` folders (direct and recursive). If the form or scope exists in a different module (e.g. `cloudSync`), resolution fails and the caller gets a "Script not found" message that mentions `scopes/` — confusing the agent into making subsequent bad calls.

### 2.2 Established codebase pattern

At least 15+ call sites within the same MCP bundle (`CodeAnalysisService`, `ServoySolutionService`, `MenuService`, `FormNavigationGraphService`, `ServoyDevServer`) iterate through all modules of the active solution using:
```java
for (ServoyProject mod : servoyModel.getModulesOfActiveProject()) { ... }
```
`ServoyScriptResolver` was introduced in SVY-21012 (2026-05-21) as a simple utility and never adopted this pattern — an oversight during extraction.

### 2.3 Secondary failure: `getFileOutline`

`WorkspaceService.getFileOutline(projectName, resourcePath)` performs a literal project-relative path lookup. When the AI agent passes `resourcePath='scmmanager.js'` instead of `forms/scmmanager.js`, the file is not found and a hard `RuntimeException` is thrown. A Servoy-aware fallback that tries `forms/<name>` and `scopes/<name>` for `.js` files would make the tool more forgiving of agent mistakes.

### 2.4 Git history

- `ServoyScriptResolver.java` was introduced in commit `5ba8b6b2` (SVY-21012, 2026-05-21). The single-project limitation was an oversight, not a deliberate design decision.
- Modified once in `aa548ff9` (SVY-21142) to remove stub tools; resolver logic was unchanged.

## 3. Design

### 3.1 Extend `ServoyScriptResolver.resolveScript()` to search all modules

When `moduleName` is null and the script is not found in the active project, iterate through `servoyModel.getModulesOfActiveProject()` and apply the same search strategy (direct `forms/`, direct `scopes/`, recursive `forms/`, recursive `scopes/`) to each module's project. Return on first match.

The active project itself is already searched first (preserving current behaviour for scripts that exist in it). Module iteration is the fallback — only reached when the active project doesn't contain the script.

### 3.2 Improve `buildNotFoundMessage()` to list available modules

When the script is not found even after searching all modules, the error message should list the module names searched. This helps the agent self-correct by showing it which modules exist in the active solution.

### 3.3 Add Servoy-aware fallback in `WorkspaceService.getFileOutline()`

After the direct path lookup fails and before throwing, if the `resourcePath` ends with `.js` and the project has a Servoy nature, try:
1. `forms/<resourcePath>` — direct file lookup
2. `scopes/<resourcePath>` — direct file lookup

If either path exists, use that file for the outline extraction. This handles the common case where the agent omits the `forms/` or `scopes/` prefix.

## 4. Implementation plan

1. **`ServoyScriptResolver.resolveScript()`** (`ServoyScriptResolver.java:51-92`):
   - After the existing search in the active project returns null (line 91), add a fallback block that:
     - Gets the `IServoyModel` via `ServoyModelManager.getServoyModelManager().getServoyModel()`
     - Iterates `servoyModel.getModulesOfActiveProject()`
     - For each module, skips if it equals the already-searched active project
     - Runs the same 4-step resolution (direct forms, direct scopes, recursive forms, recursive scopes) on the module's `IProject`
     - Returns on first match
   - Extract the per-project search logic into a private helper method `searchInProject(IProject, String)` to avoid duplication.

2. **`ServoyScriptResolver.buildNotFoundMessage()`** (`ServoyScriptResolver.java:94-115`):
   - When `moduleName` is null, append the list of module names that were searched (from `getModulesOfActiveProject()`) to the error message.
   - Change the `Expected locations` hint to explicitly state that both the active solution and its modules were searched.

3. **`WorkspaceService.getFileOutline()`** (`WorkspaceService.java:449-488`):
   - After line 463 (`if (!file.exists())`), before throwing, add a Servoy-aware fallback block:
     - Check if the project has the Servoy nature (`project.hasNature("com.servoy.eclipse.core.ServoyProject")`)
     - If yes and `resourcePath` ends with `.js`:
       - Try `project.getFile("forms/" + resourcePath)`
       - Try `project.getFile("scopes/" + resourcePath)`
     - If a match is found, use it instead of throwing
   - If no fallback matches, throw the existing error (unchanged).

4. **Tests** (in `com.servoy.eclipse.developer.mcp.tests`):
   - Add unit tests for the new module-iteration logic in `ServoyScriptResolver`.
   - Add unit tests for the `.js` fallback paths in `WorkspaceService.getFileOutline()`.

## 5. Acceptance criteria

- [ ] `getFilteredSource(name="scmmanager/scmmanager")` resolves correctly when `scmmanager` form exists in a submodule of the active solution, without requiring explicit `moduleName`.
- [ ] `getClassOutline(name="scmmanager/scmmanager")` resolves correctly for submodule forms.
- [ ] `getFileOutline(projectName="cloudSync", resourcePath="scmmanager.js")` succeeds by finding `forms/scmmanager.js` via fallback.
- [ ] When a script truly doesn't exist, the error message lists all modules that were searched.
- [ ] The error message no longer mentions just `scopes/<name>.js` as the only expected location — it includes `forms/<name>.js` and notes that all modules were searched.
- [ ] Existing behaviour is preserved: if `moduleName` is explicitly provided, only that module is searched (no iteration).
- [ ] Existing behaviour is preserved: if the script exists in the active project itself, it is found without module iteration.
- [ ] Unit tests pass for both the resolver module iteration and the `getFileOutline` fallback.

## 6. Out of scope

- Adding `moduleName` auto-detection to other MCP tools beyond the script resolver.
- Changing the `readProjectResource` tool to have a similar Servoy-aware fallback (could be a follow-up).
- Performance optimization of module iteration (the number of modules is typically small, <20).

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should the fallback in `getFileOutline` also handle nested form folders (e.g. `subfolder/form.js` → `forms/subfolder/form.js`)? | Dev | open |
| Should `buildNotFoundMessage()` suggest a specific `moduleName` value if it finds the script during iteration (i.e. "did you mean module X?")? | Dev | open |
