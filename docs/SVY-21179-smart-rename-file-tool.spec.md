# Spec: SVY-21179 — Smart `renameFile` MCP tool (replaces `renamePersist` + raw `renameFile`)

## 1. Goal

Create a single smart `renameFile(oldName, newName)` MCP tool on `ServoyDevServer`
that auto-detects the type of the named artifact and performs the correct rename
operation for that type — using the Servoy model API for persists (form, relation,
valuelist, scope, media, menu, menuitem, solution) and raw Eclipse `IFile.move` for
non-model files.

This tool completely replaces two existing tools:
- `ServoyDevServer.renamePersist` — type-explicit Servoy persist rename (removed)
- `ServoyCoderServer.renameFile` — raw `IFile.move` rename (removed)

`newName` is always a bare filename (never a path). The tool renames in-place — it
never moves.

## 2. Background

### 2.1 Existing `renamePersist` tool (`ServoyDevServer`)

`ServoyDevServer.renamePersist` (lines 714–723) delegates to
`PersistRenameService.renamePersist(persistType, oldName, newName, solutionName)`.
It handles all Servoy persist types: form, relation, valuelist, menu, menuitem, media,
scope, solution. For forms it additionally renames `.spec.cy.js` and `.spec.js` files
via `renameFormSpecFiles`. The caller must supply an explicit `persistType` parameter,
which forces the agent to know the artifact type in advance — a significant usability
problem.

### 2.2 Existing `renameFile` tool (`ServoyCoderServer`)

`ServoyCoderServer.renameFile(projectName, filePath, newFileName)` (lines 141–150)
delegates to `CodeEditingService.renameFile`, which performs a raw Eclipse `IFile.move`
within the same directory. It has no Servoy model awareness and operates on arbitrary
workspace files (config files, CSS, templates, etc.) that are not Servoy persists.

### 2.3 Why they must be merged

An agent asked to rename `myForm` should not have to know whether it is a Servoy form
(requiring `renamePersist`) or a raw file (requiring `ServoyCoderServer.renameFile`).
Having two tools with overlapping rename semantics on different MCP servers is confusing
and error-prone. A single tool with smart type detection eliminates the choice entirely.

### 2.4 Servoy project directory structure

The following layout is used to infer artifact type from a resolved file's
project-relative path:

| Project-relative path pattern | Artifact type | Notes |
|---|---|---|
| `forms/<name>.frm` or `forms/<name>.js` | form | Strip `.frm` / `.js` to get name |
| `relations/<name>.rel` | relation | Strip `.rel` |
| `valuelists/<name>.val` | valuelist | Strip `.val` |
| `medias/<filename>.<ext>` | media | Full filename (incl. extension) is the Servoy name |
| `<name>.js` at project root | scope | Strip `.js` |
| project root (no match above) | raw file | — |
| project name known to `model.getServoyProject` | solution | — |

### 2.5 Flattened solution namespace

At Servoy runtime, the active solution and all its modules form one flat namespace.
Names for forms, relations, valuelists, menus, and scopes must be unique across this
combined namespace. `renameByName` therefore searches the full flattened solution
(active solution + all its modules) when resolving a simple name.

## 3. Design

### 3.1 New `renameFile` tool on `ServoyDevServer`

Replace the existing `renamePersist` `@Tool` method with:

```java
@Tool(name = "renameFile",
      description = "Renames a Servoy artifact (form, relation, valuelist, scope, media, " +
          "menu, menuitem, or solution) or any raw workspace file. " +
          "Automatically detects the artifact type — no need to specify it. " +
          "oldName may be: a simple artifact name ('myForm'), " +
          "a workspace-relative path ('mySolution/forms/myForm.frm'), " +
          "a solution-relative path ('forms/myForm'), " +
          "or an absolute filesystem path inside the workspace. " +
          "For forms, also renames associated .spec.cy.js and .spec.js test files if present. " +
          "newName must be a bare name (no path separators). Renames in-place — never moves.",
      type = "object")
public String renameFile(
    @ToolParam(name = "oldName",
               description = "Current name or path of the artifact or file to rename.",
               required = true) String oldName,
    @ToolParam(name = "newName",
               description = "Desired new name (bare name only, no path separators).",
               required = true) String newName)
{
    return persistRenameService.renameByName(oldName, newName);
}
```

The existing `renamePersist` `@Tool` method is **deleted** from `ServoyDevServer`.
The existing `renameFile` `@Tool` method is **deleted** from `ServoyCoderServer`.

### 3.2 Name/path resolution algorithm (`renameByName`)

The algorithm has two distinct tracks:

- **Path track** — `oldName` contains a path separator (`/` or `\`) or is an absolute
  path. Use Eclipse to resolve an `IFile`, then derive the Servoy artifact type from
  the file's project-relative location.
- **Name track** — `oldName` is a bare name. Scan the Servoy model first; fall back to
  raw file search if nothing is found.

#### Step 0 — Input validation

```
if oldName is null or blank  → "Error: oldName is required."
if newName is null or blank  → "Error: newName is required."
if oldName.trim() == newName.trim() → "Error: oldName and newName are the same."
if newName contains '/' or '\' → "Error: newName must be a bare name, not a path."
```

#### Step 1 — Path track (input contains a separator or is absolute)

The boss's guidance: _"just create a URI and make an Eclipse IFile"_. The implementation
resolves whatever the user provides — absolute, workspace-relative, or project-relative
— into an Eclipse `IFile` using the following cascade:

```java
IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

IFile file = null;

// 1. Absolute filesystem path → IFile via location
if (Paths.get(oldName).isAbsolute()) {
    IFile[] files = root.findFilesForLocationURI(Paths.get(oldName).toUri());
    if (files.length > 0) file = files[0];
}

// 2. Workspace-relative path (e.g. "mySolution/forms/myForm.frm")
//    IPath starting with project name → root.getFile(path)
if (file == null) {
    try {
        IFile candidate = root.getFile(IPath.fromPortableString(oldName));
        if (candidate.exists()) file = candidate;
    } catch (Exception ignored) {}
}

// 3. Project-relative path — try against active project
if (file == null) {
    ServoyProject active = model.getActiveProject();
    if (active != null) {
        IFile candidate = active.getProject().getFile(oldName);
        if (candidate.exists()) file = candidate;
    }
}

if (file == null) {
    return "Error: File or artifact '" + oldName + "' not found. " +
           "Provide a workspace-relative path (e.g. 'mySolution/forms/myForm.frm') " +
           "or an absolute path inside the workspace.";
}
```

Once `file` is resolved:

1. **Security check** — verify `file` belongs to the active solution or one of its
   modules. If not:
   `"Error: Path is outside the active solution. Only files within the active solution or its modules may be renamed."`

2. **Derive artifact type** from the file's project-relative path string using the
   mapping in §2.4.

3. **Rename** using the correct type-aware method (see §3.3).

#### Step 2 — Name track (bare name, no separators)

1. **Collect all matches** across the full flattened solution in this order:
   solution → form → relation → valuelist → menu → menuitem → media → scope

   For each project in the flattened set:
   ```
   solution:   model.getServoyProject(oldName) != null
   form:       solution.getForm(oldName) != null
   relation:   solution.getRelation(oldName) != null
   valuelist:  solution.getValueList(oldName) != null
   menu:       iterate solution.getMenus(false), match getName()
   menuitem:   recursive search across all menus in all projects
   media:      iterate solution.getMedias(false), match getName()
   scope:      project.getProject().getFile(oldName + ".js").exists() at root
   ```

2. **Exactly 1 match** → rename using the correct method for that type.

3. **2+ matches** → return disambiguation error, e.g.:
   ```
   Error: Ambiguous name 'save' — found in multiple locations:
     - menuitem 'save' in menu 'mainMenu' (solution 'mySol')
     - menuitem 'save' in menu 'contextMenu' (module 'myMod')
   Provide a path hint (e.g. 'mySol/forms/save', 'myMod/relations/save') to disambiguate.
   ```

4. **0 matches in model** → raw file fallback:
   - Call `active.getProject().findMember(oldName)` and cast to `IFile` if not null
   - If found → `CodeEditingService.renameFile(projectName, oldName, newName)`
   - If still not found:
     `"Error: No artifact or file named '<oldName>' found in the active solution or project."`

### 3.3 `PersistRenameService.renameByName` — new public method

```java
/**
 * Renames a Servoy artifact or raw workspace file identified by {@code oldName}.
 * <p>
 * {@code oldName} may be a simple artifact name, a workspace-relative path
 * (e.g. {@code "mySolution/forms/myForm.frm"}), a solution-relative path
 * (e.g. {@code "forms/myForm"}), or an absolute filesystem path inside the workspace.
 * {@code newName} must be a bare filename with no path separators.
 * </p>
 *
 * @return a success or error message string; never throws
 */
public String renameByName(String oldName, String newName)
```

- Implements the full resolution algorithm from §3.2.
- Reuses all existing `renameForm`, `renameRelation`, `renameValueList`, `renameMenu`,
  `renameMenuItem`, `renameMedia`, `renameScope`, `renameSolution` methods unchanged.
- For raw file fallback calls `new CodeEditingService().renameFile(projectName, filePath, newFileName)`.
- All exceptions from Servoy API (duplicate name, invalid identifier, etc.) are caught
  and returned as `"Error: <exception message>"` — the method never propagates exceptions.

The existing `renamePersist(persistType, oldName, newName, solutionName)` method is
**kept as a package-accessible internal helper** — it is no longer an MCP tool but
is still called by `renameByName` once a type is known.

### 3.4 Raw file fallback

When falling back to raw file rename (name track, 0 model matches), the behaviour
mirrors the deleted `ServoyCoderServer.renameFile`:

- Resolves `IFile` via `IProject.findMember(oldName)` in the active project
- Renames in-place: `IFile.move(parent.getFullPath().append(newName), IResource.FORCE, null)`
- Checks that a file named `newName` does not already exist in the same directory
- Returns `"Success: File '<oldName>' renamed to '<newName>'."` on success

### 3.5 Error handling contract

The tool and service method never throw. All errors are returned as strings:

| Situation | Message |
|---|---|
| `oldName` null/blank | `"Error: oldName is required."` |
| `newName` null/blank | `"Error: newName is required."` |
| Same names | `"Error: oldName and newName are the same."` |
| `newName` contains path separator | `"Error: newName must be a bare name, not a path."` |
| Path not found (path track) | `"Error: File or artifact '<oldName>' not found. ..."` |
| Path outside active solution | `"Error: Path is outside the active solution. ..."` |
| Artifact not found at path location | `"Error: <Type> '<name>' not found in project '<project>'."` |
| Ambiguous simple name | `"Error: Ambiguous name '<name>' — found in multiple locations: ..."` |
| Nothing found (name track) | `"Error: No artifact or file named '<name>' found ..."` |
| Duplicate `newName` (Servoy API) | `"Error: <message from Servoy API or IFile check>"` |
| Invalid identifier (Servoy API) | `"Error: <message from Servoy API>"` |

## 4. Implementation plan

1. **`PersistRenameService.java`** — add `public String renameByName(String oldName, String newName)`
   implementing the full algorithm from §3.2–§3.4. Keep all existing `rename*` methods
   and `renamePersist` unchanged (the latter becomes an internal helper, no longer a tool).

2. **`ServoyDevServer.java`** — delete the `renamePersist` `@Tool` method; add the new
   `renameFile` `@Tool` method (§3.1) delegating to
   `persistRenameService.renameByName(oldName, newName)`.

3. **`ServoyCoderServer.java`** — delete the `renameFile` `@Tool` method.
   `CodeEditingService.renameFile(projectName, filePath, newFileName)` stays as a
   non-tool service method (still used internally by `renameByName`).

4. **`PersistRenameServiceTest.java`** — adapt existing tests and add new ones (§5.1).

5. **`ServoyDevServerTest.java`** — adapt existing tests and add new ones (§5.2).

6. **`ServoyCoderServerTest.java`** — add assertion that `renameFile` is no longer a
   `@Tool` on `ServoyCoderServer` (§5.3).

7. **`RenamePersistIntegrationTest.java`** — adapt all `renamePersist` calls to
   `renameByName`; add duplicate-name and raw-file tests (§5.4).

## 5. Acceptance criteria

- [ ] `renameFile("myForm", "myRenamedForm")` renames the form via Servoy API, updates all references, and renames `.spec.cy.js` / `.spec.js` if they exist.
- [ ] `renameFile("myRelation", "newRelation")` renames the relation.
- [ ] `renameFile("myScope", "myRenamedScope")` renames the scope `.js` file at project root.
- [ ] `renameFile("logo.png", "newLogo.png")` renames the media via Servoy API.
- [ ] `renameFile("mySolution/forms/myForm.frm", "myRenamedForm")` resolves via workspace-relative path and renames the form.
- [ ] `renameFile("forms/myForm", "myRenamedForm")` resolves via solution-relative path hint and renames the form.
- [ ] `renameFile("mySolution/medias/logo.png", "newLogo.png")` resolves type as media via workspace-relative path.
- [ ] `renameFile("/absolute/path/to/workspace/mySolution/forms/myForm.frm", "myRenamedForm")` resolves via absolute path.
- [ ] `renameFile("config.json", "config-new.json")` (not a Servoy artifact) renames the raw file via `IFile.move`.
- [ ] Unknown simple name returns a clear error containing "not found".
- [ ] Simple name matching multiple types/locations returns an "Ambiguous" error listing all matches.
- [ ] Renaming to a name that already exists returns a meaningful error string (no exception propagated).
- [ ] `newName` containing `/` or `\` returns an error.
- [ ] Path outside the active solution returns a security error.
- [ ] `renamePersist` is no longer registered as a `@Tool` on `ServoyDevServer`.
- [ ] `renameFile` is no longer registered as a `@Tool` on `ServoyCoderServer`.
- [ ] All adapted and new unit tests in `PersistRenameServiceTest` pass.
- [ ] All adapted and new unit tests in `ServoyDevServerTest` pass.
- [ ] All adapted integration tests in `RenamePersistIntegrationTest` pass.

## 6. Test changes

### 6.1 `PersistRenameServiceTest` (plain JUnit — adapt + extend)

**Adapt existing tests (do not delete):**

| Existing test | Adaptation |
|---|---|
| `testPersistRenameService_canBeInstantiated` | Keep as-is |
| `testPersistRenameService_hasRenamePersistMethod` | Adapt → verify `renameByName(String, String)` exists with `String` return type |
| `testRenamePersist_rejectsNullPersistType` | Adapt → `testRenameByName_rejectsNullOldName`: call `service.renameByName(null, "new")` |
| `testRenamePersist_rejectsBlankPersistType` | Adapt → `testRenameByName_rejectsBlankOldName` |
| `testRenamePersist_rejectsNullOldName` | Adapt → update call to `renameByName` |
| `testRenamePersist_rejectsBlankOldName` | Adapt → update call to `renameByName` |
| `testRenamePersist_rejectsNullNewName` | Adapt → update call to `renameByName` |
| `testRenamePersist_rejectsBlankNewName` | Adapt → update call to `renameByName` |
| `testRenamePersist_rejectsSameName` | Adapt → update call to `renameByName` |
| `testRenamePersist_rejectsUnsupportedType` | Repurpose → `testRenameByName_rejectsNewNameWithPathSeparator`: call `renameByName("x", "a/b")`, expect error containing "bare name" or "path" |
| `testRenamePersist_formType_noWorkspace` | Adapt → `renameByName("oldForm", "newForm")` |
| `testRenamePersist_*TypeAccepted` (relation, valuelist, menu, media, scope, solution) | Adapt → call `renameByName(name, newName)` with same no-workspace tolerance |
| Reflection tests for `rename*` methods | Keep as-is |

**New tests to add:**

- `testRenameByName_notFound_returnsErrorWithMessage` — non-existent bare name returns string containing "not found"
- `testRenameByName_ambiguous_sourceContainsAmbiguousKeyword` — structural source check: verify literal `"Ambiguous"` appears in `PersistRenameService` source
- `testRenameByName_pathHint_formsPrefix_identifiedAsForm` — `renameByName("forms/testForm", "newName")` returns error containing "form" or "not found" (not "unsupported"), confirming the type was correctly parsed
- `testRenameByName_pathHint_relationsPrefix` — same pattern for `"relations/testRel"`
- `testRenameByName_pathHint_valuelistsPrefix` — same pattern for `"valuelists/testVl"`
- `testRenameByName_pathHint_mediasPrefix` — same pattern for `"medias/test.png"`
- `testRenameByName_newNameWithSlash_returnsError` — `renameByName("x", "a/b")` returns error

### 6.2 `ServoyDevServerTest` (adapt + extend)

**Adapt existing tests:**
- Any test asserting a `renamePersist` `@Tool` → adapt to assert `renameFile` `@Tool`

**New tests to add:**

- `testRenameFile_toolAnnotation_hasCorrectName` — reflection: `@Tool(name="renameFile")` exists on `ServoyDevServer`
- `testRenameFile_toolAnnotation_hasTwoParams_oldNameAndNewName` — reflection: method has exactly 2 `@ToolParam` parameters named `oldName` and `newName`, both `required = true`
- `testRenamePersist_toolNotRegistered` — assert no method annotated `@Tool(name="renamePersist")` exists on `ServoyDevServer`

### 6.3 `ServoyCoderServerTest` (extend)

**New test to add:**

- `testRenameFile_toolNotRegisteredOnCoderServer` — assert no method annotated `@Tool(name="renameFile")` exists on `ServoyCoderServer`

### 6.4 `ServoyCoderServerIntegrationTest` (adapt)

Two existing integration tests call `coderServer.renameFile(projectName, filePath, newFileName)`.
That 3-parameter tool method is deleted. Adapt both tests to call
`devServer.renameFile(oldName, newName)` instead, using a workspace-relative `oldName`
(e.g. `PROJECT_NAME + "/" + filePath`) or simple filename when unambiguous.

| Existing test | Adaptation |
|---|---|
| `testRenameFile_success` | Change `coderServer.renameFile(PROJECT_NAME, "rename_me.txt", "renamed.txt")` → `devServer.renameFile("rename_me.txt", "renamed.txt")`. Keep all assertions unchanged. |
| `testRenameFile_targetExists` | Change call to `devServer.renameFile`. **Important:** the method now returns an error string instead of throwing — replace the `try/catch(RuntimeException)` with an assertion that the returned string contains `"Error"` and `"already exists"`. |

### 6.5 `RenamePersistIntegrationTest` (adapt + extend)

**Adapt existing tests:**
- All calls to `renameService.renamePersist(type, oldName, newName, null)` → change to `renameService.renameByName(oldName, newName)` where the name is unambiguous
- For workspace-relative path variants use e.g. `TEST_SOLUTION + "/forms/" + formName + ".frm"`

**New integration tests to add:**

- `testRenameForm_toExistingFormName_returnsError` — create two forms, rename first to second's name → expect result starting with `"Error:"`, no exception thrown
- `testRenameRelation_toExistingRelationName_returnsError` — same pattern for relations
- `testRenameFile_rawFile_success` — create a raw `config.json` in the test project, call `renameByName("config.json", "config-renamed.json")`, assert old gone and new exists
- `testRenameFile_workspaceRelativePath_success` — call `renameByName(TEST_SOLUTION + "/forms/" + formName + ".frm", newName)`, assert rename succeeds
- `testRenameFile_ambiguousName_returnsAmbiguousError` — create artifacts of two different types with the same name, call `renameByName(sharedName, "newName")`, assert result contains `"Ambiguous"`

## 7. Out of scope

- Moving files to a different directory (only same-location rename)
- Cross-solution rename (active solution boundary enforced)
- Renaming Servoy component, service, or layout spec files (handled by UI actions)
- `solutionName` optional parameter — always uses active solution; solution-type rename identifies the solution by name via `model.getServoyProject`
- Batch rename (multiple artifacts in one call)

## 8. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should `renameByName` also search module scopes (`.js` at module root) when a simple name is given, or only the active solution root? | Product | open |
| For media, should `newName` be allowed to change the file extension (e.g. `logo.png` → `logo.svg`)? The Servoy model stores the full filename including extension. | Product | open |
