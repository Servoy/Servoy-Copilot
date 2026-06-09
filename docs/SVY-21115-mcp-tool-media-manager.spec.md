# Spec: SVY-21115 — Media Manager (skill + rename MCP tool)

## 1. Goal

Enable AI coding agents to manage media files in Servoy solutions by leveraging standard file operations (read/write/delete) combined with the existing `ServoyModel` resource change listener that auto-syncs `medias.obj`. A single MCP tool (`media_rename`) is added for the one operation that cannot be done via filesystem alone (renaming preserves UUID; a delete+add would generate a new UUID and break references).

Additionally, an opencode skill is created that instructs agents on the correct workflow for media operations.

## 2. Background

### 2.1 Media in Servoy solutions

A Servoy solution stores media files under `<solution>/medias/`. Media can include images (PNG, SVG), stylesheets (CSS/LESS), JavaScript files, HTML, JSON, and any other binary or text content.

Each solution has a `medias.obj` file at the solution root that tracks metadata:

```json
[
  {
    "uuid": "...",
    "typeid": 9,
    "name": "images/logo.png",
    "mimeType": "image/png",
    "encapsulation": 0,
    "deprecated": ""
  }
]
```

The `name` field uses forward-slash path separators for folder structure (e.g. `css/theme.css`).

### 2.2 Existing auto-sync mechanism

`ServoyModel.resourcesPostChanged()` (in `com.servoy.eclipse.core`) is a workspace `IResourceChangeListener` that already handles media file changes:

- **New files**: Detects files in `medias/` not yet in `medias.obj`, creates a `Media` persist with a new UUID, and rewrites `medias.obj`.
- **Modified files**: Reloads content into the model via `SolutionDeserializer.readMediasFromSolutionDir()`.
- **Deleted files**: Removes orphaned entries from `medias.obj` when the physical file no longer exists.

This means an agent can simply create/edit/delete files in `<solution>/medias/` and the IDE will automatically update `medias.obj`.

### 2.3 The rename gap

A filesystem rename (delete + create) generates a new UUID. If other persists reference the media by UUID (e.g. `imageMediaID` on a form element), those references would break. The rename must go through the model API (`media.setName(newName)`) to preserve the UUID. This is the single operation that requires an MCP tool.

### 2.4 MCP tool infrastructure

MCP tools are registered via `@McpServer` / `@Tool` / `@ToolParam` annotations in the `com.servoy.eclipse.developer.mcp` plugin. The class is added to `McpServerBuiltins.BUILT_IN_SERVER_CLASSES` and the framework handles JSON-RPC dispatch.

## 3. Design

### 3.1 Context file: `servoy-platform/context/media-operations.md`

A new context file under the existing `servoy-platform` skill at `/home/gabi/.servoy/opencode/.opencode/skills/servoy-platform/context/media-operations.md` instructs agents how to:

1. **List media** — read `medias.obj` or list files in `<solution>/medias/`
2. **Read media** — read files directly from `<solution>/medias/<path>`
3. **Create media** — use `servoy-editor_createFile` to write to `<solution>/medias/<path>` (goes through Eclipse API → triggers resource change listener → auto-syncs `medias.obj`)
4. **Update media** — use `servoy-editor_replaceFileContent` to overwrite (Eclipse detects change)
5. **Delete media** — use `servoy-editor_deleteFile` to delete (Eclipse removes from `medias.obj`)
6. **Create folders** — use `servoy-editor_createDirectories` under `<solution>/medias/`
7. **Rename media/folder** — use the `media_rename` MCP tool (preserves UUID)

The context file also documents:
- Valid media name characters: `[_a-zA-Z0-9\-\.]` and `/` for paths
- No spaces (use underscores)
- MIME type is auto-detected by the IDE
- No explicit workspace refresh needed when using `servoy-editor_*` tools (they go through Eclipse APIs which trigger resource change events automatically)

The `servoy-platform/SKILL.md` routing table will be updated to point to this context file for media tasks.

### 3.2 MCP tool: `media_rename`

A single tool in an existing or new `@McpServer` class:

**Tool name:** `media_rename`

Supports both single-file rename and folder rename (all media under a folder prefix).

**Input:**
- `solutionName` (string, required) — name of the Servoy solution
- `mediaName` (string, required) — current media path name (e.g. `css/old.css`) or folder path ending with `/` (e.g. `images/icons/`)
- `newName` (string, required) — new media path name (e.g. `css/new.css`) or new folder prefix ending with `/` (e.g. `images/new-icons/`)

**Output:**
- Single file: `{ "oldName": "...", "newName": "...", "renamed": true }` on success
- Folder: `{ "oldFolder": "...", "newFolder": "...", "renamedCount": N, "renamed": true }` on success
- Descriptive error message on failure

**Implementation (single file):**
1. Resolve `ServoyProject` from `solutionName` via `ServoyModelManager`
2. Get editing solution: `servoyProject.getEditingSolution()`
3. Find media: `solution.getMedia(mediaName)`
4. Validate new name (same rules as existing UI: `[_a-zA-Z0-9\-\./]`, no spaces, no leading/trailing dots)
5. Check no duplicate exists at `newName`
6. Call `media.updateName(validator, newName)` (preserves UUID)
7. Save via `servoyProject.saveEditingSolutionNodes(...)`
8. Return JSON result

**Implementation (folder rename):**
1. Resolve solution as above
2. Find all media whose `name` starts with the folder prefix
3. For each media: compute new name by replacing old prefix with new prefix
4. Validate each new name
5. Rename each media via `media.updateName(validator, newName)` (preserves UUID)
6. Save all changed nodes in one batch
7. Rename the physical folder on disk
8. Return JSON result with count

### 3.3 Error handling

The MCP tool returns clear, actionable errors:
- `"Media 'foo.png' not found in solution 'MySolution'. Available media: [...]"`
- `"Solution 'NonExistent' not found. Available solutions: [Sol1, Sol2]"`
- `"Target name 'new name.css' is invalid: spaces not allowed. Suggested: 'new_name.css'"`
- `"Media 'bar.css' already exists at the target name."`

## 4. Implementation plan

1. **Create the context file** at `/home/gabi/.servoy/opencode/.opencode/skills/servoy-platform/context/media-operations.md` with media operation instructions.

2. **Update `servoy-platform/SKILL.md`** routing table to include a media task row pointing to `context/media-operations.md`.

3. **Create MCP tool class** `MediaServer.java` in `com.servoy.eclipse.developer.mcp.servers` with `@McpServer(name = "media")` annotation.

4. **Implement `media_rename` tool method** following the pattern from `TimeServer`:
   - Annotate with `@Tool(name = "media_rename", ...)`
   - Parameters annotated with `@ToolParam`
   - Resolve solution, find media, validate name, rename, save, return JSON
   - Support folder rename (detect trailing `/` in `mediaName`)

5. **Register** the server class in `McpServerBuiltins.BUILT_IN_SERVER_CLASSES`.

6. **Test** the rename operation via the MCP endpoint.

## 5. Acceptance criteria

- [ ] Context file exists at `/home/gabi/.servoy/opencode/.opencode/skills/servoy-platform/context/media-operations.md`
- [ ] Context file describes create/read/update/delete/list via `servoy-editor_*` file operations
- [ ] Context file documents the rename workflow via MCP tool
- [ ] Context file documents valid media name rules and folder conventions
- [ ] `servoy-platform/SKILL.md` routing table updated with media task entry
- [ ] `media_rename` MCP tool renames a single media file preserving its UUID
- [ ] `media_rename` MCP tool renames all media under a folder prefix preserving UUIDs
- [ ] `media_rename` updates `medias.obj` correctly
- [ ] `media_rename` validates the new name and returns actionable errors
- [ ] `media_rename` rejects duplicates with a descriptive message
- [ ] After creating a file in `<solution>/medias/` via `servoy-editor_createFile`, the IDE auto-syncs `medias.obj` (existing behavior — verify it works)

## 6. Out of scope

- MCP tools for create/read/update/delete/list (handled by standard file operations + auto-sync)
- Moving media between solutions
- Media property editing (encapsulation, deprecated flags)
- Binary image generation
- Reference search (existing `MediaSearch` handles this)

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| — | — | — |
