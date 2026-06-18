# Spec: SVY-21142 - Remove useless MCP tools for Servoy developers

## 1. Goal

Remove MCP tool endpoints that are Java/JDT-specific and provide no value to Servoy developers. These tools waste prompt tokens, create noise in tool listings, and confuse AI agents by advertising capabilities that either throw `RuntimeException` or are explicitly "NOT IMPLEMENTED". Removing them reduces token overhead and prevents agents from calling dead-end tools.

## 2. Background

The Servoy Developer MCP server (`com.servoy.eclipse.developer.mcp`) exposes tools to AI agents via the MCP protocol. It was originally scaffolded from a full Eclipse JDT-capable MCP server, so many Java-specific tools were included. Servoy developers work in JavaScript/TypeScript - not Java - so JDT tools like `findReferences`, `getMethodCallHierarchy`, and `executeQuickFix` are useless in this context.

A first pass was done by Johan (commit `dfdf96e`, June 1 2026) which removed 14 tools from `ServoyCoderServer` and `ServoyIdeServer`. However, 5 stub tools remain: they are annotated with `@Tool` (so they appear in the tool listing and consume tokens) but their implementation just throws `RuntimeException`.

### Current state of tools from the issue's list

| Tool | Status | Location |
|------|--------|----------|
| organizeImports | Already removed | - |
| organizeImportsInPackage | Already removed | - |
| refactorMoveJavaType | Already removed | - |
| refactorRenameJavaType | Already removed | - |
| refactorRenamePackage | Already removed | - |
| restoreFileVersion | **STILL PRESENT** (dummy, throws) | `ServoyContextServer.java:194` |
| findTestClasses | Already removed | - |
| findReferences | **STILL PRESENT** (throws, JDT-only) | `ServoyIdeServer.java:577` |
| getEffectivePom | Already removed | - |
| getImportSuggestions | Already removed | - |
| getJavaDoc | Already removed | - |
| getMethodCallHierarchy | **STILL PRESENT** (throws, JDT-only) | `ServoyIdeServer.java:560` |
| getProjectDependencies | Already removed | - |
| listMavenProjects | Already removed | - |
| runAllTests | Already removed | - |
| runClassTests | Already removed | - |
| runMavenBuild | Already removed | - |
| runPackageTests | Already removed | - |
| runTestMethod | Already removed | - |

### Additional unimplemented tools (same pattern, not in original list)

| Tool | Status | Location |
|------|--------|----------|
| getTypeHierarchy | **STILL PRESENT** (throws, JDT-only) | `ServoyIdeServer.java:570` |
| executeQuickFix | **STILL PRESENT** (throws, JDT-only) | `ServoyIdeServer.java:585` |

## 3. Design

### 3.1 Remove remaining stub tools

Delete the `@Tool`-annotated methods that throw `RuntimeException` or are explicitly marked as not implemented. These methods serve no purpose other than advertising a tool that always fails.

**Tools to remove:**

1. `restoreFileVersion` in `ServoyContextServer` (line 194) - intentionally disabled, always throws.
2. `findReferences` in `ServoyIdeServer` (line 577) - JDT-only, throws.
3. `getMethodCallHierarchy` in `ServoyIdeServer` (line 560) - JDT-only, throws.
4. `getTypeHierarchy` in `ServoyIdeServer` (line 570) - JDT-only, throws.
5. `executeQuickFix` in `ServoyIdeServer` (line 585) - JDT-only, throws.

### 3.2 Update class Javadoc

The `ServoyIdeServer` class Javadoc (line 54-55) lists excluded tools. After removal, update the Javadoc to reflect the final set of removed tools.

The `ServoyContextServer` class Javadoc (line 48) references `restoreFileVersion` - remove that reference.

### 3.3 Update tests

The test class `ServoyContextServerTest` (in `com.servoy.eclipse.developer.mcp.tests`) contains tests for `restoreFileVersion` (lines 134, 153). These tests must be removed since the method will no longer exist.

No tests exist for the other stub methods (`findReferences`, `getMethodCallHierarchy`, `getTypeHierarchy`, `executeQuickFix`) since they only throw.

### 3.4 Tools to keep

The following tools share names with JDT tools but have been **repurposed for Servoy**:
- `getSource` - resolves Servoy forms/scopes via `ServoyScriptResolver`
- `getClassOutline` - returns JS file structure via `FileStructureService`
- `getMethodSource` - reads specific JS functions
- `getFilteredSource` - filtered JS source view

These must NOT be removed.

## 4. Implementation plan

1. **ServoyIdeServer.java** - Delete the methods `findReferences`, `getMethodCallHierarchy`, `getTypeHierarchy`, and `executeQuickFix` (lines 560-591). Remove any now-unused imports.
2. **ServoyContextServer.java** - Delete the `restoreFileVersion` method (around line 192-207). Remove the Javadoc reference to it.
3. **ServoyIdeServer.java Javadoc** - Update the "Excluded" paragraph (lines 53-55) to remove references to the now-deleted tools.
4. **ServoyContextServer.java Javadoc** - Remove the `restoreFileVersion` bullet from the class doc (line 48).
5. **ServoyContextServerTest.java** - Remove test methods that exercise `restoreFileVersion`.
6. **Compile check** - Run `eclipse-ide_getCompilationErrors` and fix any issues.
7. **Run tests** - Run `ServoyContextServerTest` to verify nothing is broken.

## 5. Acceptance criteria

- [ ] `restoreFileVersion` is no longer exposed as an MCP tool (method deleted from `ServoyContextServer`)
- [ ] `findReferences` is no longer exposed as an MCP tool (method deleted from `ServoyIdeServer`)
- [ ] `getMethodCallHierarchy` is no longer exposed as an MCP tool (method deleted from `ServoyIdeServer`)
- [ ] `getTypeHierarchy` is no longer exposed as an MCP tool (method deleted from `ServoyIdeServer`)
- [ ] `executeQuickFix` is no longer exposed as an MCP tool (method deleted from `ServoyIdeServer`)
- [ ] Servoy-repurposed tools (`getSource`, `getClassOutline`, `getMethodSource`, `getFilteredSource`) remain functional
- [ ] All existing tests pass after removal
- [ ] No compilation errors in `com.servoy.eclipse.developer.mcp` or its test fragment
- [ ] Class Javadocs accurately reflect the current tool set

## 6. Out of scope

- Re-implementing `getMethodCallHierarchy` or `findReferences` for Servoy JS (separate future issue)
- Removing `getSource`/`getClassOutline`/`getMethodSource`/`getFilteredSource` (these are repurposed and functional)
- Token-budget optimization beyond tool removal (e.g., shortening tool descriptions)
- Changes to the `opencode.json` MCP configuration (tools are registered via `@Tool` annotations, not config)

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should `getTypeHierarchy` be kept as a placeholder for future Servoy form/scope hierarchy support? | Johan | open |
| Should `executeQuickFix` be re-implemented for Servoy's own compilation markers? | Johan | open |
