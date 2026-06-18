# Spec: SVY-21138 â [mcp tool] duplicate persist

## 1. Goal

Expose a `duplicatePersist` MCP tool in the `servoy-dev` server that duplicates a Servoy persist (form, relation, valuelist, or media) into the same or a different solution. This enables AI agents to clone existing artifacts programmatically, mirroring the "Duplicate" action available in the Solution Explorer (Solex) UI.

## 2. Background

### 2.1 Existing Solex implementation

The Solution Explorer already provides a `DuplicatePersistAction` (in `com.servoy.eclipse.ui`) that:
1. Asks the user for a new name and a target solution via a dialog.
2. Delegates to `PersistCloner.intelligentClonePersist()` which:
   - Calls `duplicatePersist()` to deep-clone the persist into the target solution's editing solution.
   - For forms: relinks event handlers from the original form's methods to the cloned form's methods.
   - Saves the duplicated persist to disk.
3. For forms specifically, also copies associated `.less` and `.sec` (security) files.

### 2.2 PersistCloner capabilities

`PersistCloner` (in `com.servoy.eclipse.ui.views.solutionexplorer.actions`) handles:
- **Forms** â full deep clone via `cloneObj()`, name update via `ISupportUpdateableName`, event method relinking, flattened solution cache flush.
- **Relations** â same clone + name update path.
- **ValueLists** â same clone + name update path.
- **Media** â clone + explicit `setName()` + `setPermMediaData()`.
- **ScriptCalculations** â creates a new calc on the same table, copies properties.
- **AggregateVariables** â creates a new aggregate on the same table.

### 2.3 Existing MCP tool patterns

The `ServoyDevServer` already has tools that follow a consistent pattern:
- `renamePersist` delegates to `PersistRenameService` â resolves a project, switches on persist type, performs the operation.
- `openForm` / `openRelation` / `openValueList` create artifacts via `ServoyArtifactCreationService`.
- All tools return a JSON-formatted string on success or an `"Error: ..."` string on failure.

## 3. Design

### 3.1 New service: `PersistDuplicateService`

Create a new service class following the pattern of `PersistRenameService`:

- **Location:** `com.servoy.eclipse.developer.mcp.services.PersistDuplicateService`
- **Responsibility:** Resolve source persist, validate new name, invoke `PersistCloner.intelligentClonePersist()`, handle form-specific file copying (.less, .sec).
- **Persist types supported:** `form`, `relation`, `valuelist`, `media`.

The service will:
1. Resolve the source `ServoyProject` (from `solutionName` or active project).
2. Resolve an optional destination `ServoyProject` (from `destinationSolution` or same as source).
3. Look up the persist by name and type in the source solution's editing flattened solution.
4. Validate the new name using `ScriptNameValidator`.
5. Call `PersistCloner.intelligentClonePersist(persist, newName, destProject, validator, true)`.
6. For forms: copy `.less` and `.sec` files from the source form directory to the duplicate's directory (mirroring `DuplicatePersistAction.doWork()`).
7. Return a JSON result with the duplicated persist name and destination solution.

### 3.2 MCP tool: `duplicatePersist`

Add a new `@Tool` method in `ServoyDevServer`:

```java
@Tool(name = "duplicatePersist",
    description = "Duplicates a Servoy persist (form, relation, valuelist, or media) "
        + "creating a copy with a new name. Optionally places the copy in a different solution/module. "
        + "For forms, also copies associated .less and .sec files and relinks event handlers.",
    type = "object")
public String duplicatePersist(
    @ToolParam(name = "persistType", description = "Type of persist to duplicate: 'form', 'relation', 'valuelist', 'media'.", required = true) String persistType,
    @ToolParam(name = "name", description = "Name of the existing persist to duplicate.", required = true) String name,
    @ToolParam(name = "newName", description = "Name for the duplicated persist. If omitted, defaults to '<name>_copy' (or '<name>_copy2', etc. if that exists).", required = false) String newName,
    @ToolParam(name = "solutionName", description = "Solution containing the source persist. If omitted, uses the active solution.", required = false) String solutionName,
    @ToolParam(name = "destinationSolution", description = "Target solution for the duplicate. If omitted, uses the same solution as the source.", required = false) String destinationSolution)
```

### 3.3 Name validation

Use `ScriptNameValidator` (same as `PersistCloner` uses internally through `nameValidator.checkName()`). If validation fails, return a clear error message without creating the persist.

### 3.4 Error handling

- Source persist not found â `"Error: <type> '<name>' not found in solution '<solution>'."`
- Invalid new name â `"Error: Invalid name '<newName>': <validation message>."`
- Name already exists â `"Error: A <type> named '<newName>' already exists."`
- Solution not found â `"Error: Solution '<name>' not found."`
- RepositoryException â `"Error: <exception message>"`

### 3.5 Return format

On success, return a JSON string:
```json
{
  "status": "ok",
  "duplicated": "<newName>",
  "persistType": "<type>",
  "solution": "<destination solution name>"
}
```

## 4. Implementation plan

1. Create `com.servoy.eclipse.developer.mcp.services.PersistDuplicateService` with:
   - `public String duplicatePersist(String persistType, String name, String newName, String solutionName, String destinationSolution)`
   - Private methods: `duplicateForm()`, `duplicateRelation()`, `duplicateValueList()`, `duplicateMedia()`
   - Private helper: `resolveProject(String solutionName)` (same pattern as `PersistRenameService`)
   - Private helper: `findPersist(String name, String type, ServoyProject project)` to look up the source persist

2. Add the `duplicatePersist` tool method to `ServoyDevServer`:
   - Instantiate `PersistDuplicateService` as a field (matching existing pattern).
   - Delegate to the service.

3. Handle form-specific .less and .sec file copying in `PersistDuplicateService.duplicateForm()`:
   - Use `SolutionSerializer.getFilePath()` to resolve source and destination paths.
   - Copy files via `IFile.create(sourceFile.getContents(), ...)`.

4. Write unit test `PersistDuplicateServiceTest` in `com.servoy.eclipse.developer.mcp.tests`:
   - Test parameter validation (null/blank checks).
   - Test unsupported persist type error.
   - Test success path with mocked `ServoyModelManager` (following existing test patterns in the test project).

## 5. Acceptance criteria

- [ ] A `duplicatePersist` tool is available in the `servoy-dev` MCP server.
- [ ] Duplicating a form creates a full copy including all child elements, relinked event handlers, and copied .less/.sec files.
- [ ] Duplicating a relation creates an independent copy with all relation items.
- [ ] Duplicating a valuelist creates an independent copy with all properties.
- [ ] Duplicating a media creates a copy with the binary data preserved.
- [ ] The duplicate can be placed in a different solution/module via `destinationSolution`.
- [ ] Invalid names are rejected with a clear error message before any persist is created.
- [ ] Duplicate name collisions are detected and reported.
- [ ] The tool returns a structured JSON response on success.
- [ ] Unit tests pass for the new service.
- [ ] No compilation errors are introduced.

## 6. Out of scope

- Duplicating ScriptCalculations or AggregateVariables (table-node children) â these are rarely duplicated standalone and would need additional parameters (table/datasource). Can be added later.
- Working set assignment â the UI dialog allows picking a working set, but MCP tools don't need this.
- Batch duplication of multiple persists in one call.
- Duplicating solutions themselves (use `createSolution` + manual copy instead).

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should the tool support duplicating scope files? | Dev team | closed â not needed, no existing action for it |
| Should we expose an "auto-name" mode (e.g. `<name>_copy`) when `newName` is omitted? | Dev team | closed â YES, implemented as default when `newName` is null/blank |
