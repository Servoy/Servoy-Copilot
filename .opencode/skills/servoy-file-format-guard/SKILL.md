---
name: servoy-file-format-guard
description: Hard rule and reusable utility pattern for refusing destructive text edits on Servoy structural file formats (.frm, .obj, .tbl, .val, .rel, .dbi) — these encode UUIDs and cross-file references that break if edited as plain text.
---

## Why this exists

Several Servoy file formats encode structural state that is invisible
when treated as plain text:

- UUIDs that link form components to JavaScript variables and methods
- Cross-file references between solutions, modules, datasources, valuelists
- Persistence metadata Servoy uses to detect orphaned or invalid model elements

A single line edit, a regex replace across the workspace, or a fuzzy patch
can quietly break these references. The user then sees a corrupt form,
missing components, or broken solution startup — with no error pointing
back to the edit.

For Servoy Developer MCP tools that AI agents drive, the policy is hard:
**refuse the edit upfront and return a JSON-RPC error.** Silent skipping
or "best effort" partial edits create worse confusion than an explicit
refusal.

---

## Forbidden file extensions

| Extension | Format | Why protected |
|---|---|---|
| `.frm` | Form definition | Component UUIDs, dataprovider/valuelist refs, handler bindings |
| `.obj` | Settings, root metadata, solution settings | Encodes structural metadata |
| `.tbl` | Datasource pointer | References to db tables and their datasource URIs |
| `.val` | Valuelist | UUID-keyed list of values plus type metadata |
| `.rel` | Relation | Foreign-key style joins between datasources |
| `.dbi` | Database info | Per-table column types, defaults, sequences |

Plain JS, TS, CSS, JSON, MD, JAVA, XML, properties, manifest, and similar
text files are NOT protected by this rule — they are normal source.

---

## The shared utility

A single class enforces the policy. Every destructive tool calls it before
performing any write:

```java
package com.servoy.eclipse.developer.mcp.guard;

import java.util.Set;

/**
 * Refuses destructive text edits on Servoy structural file formats.
 * <p>
 * AI-driven MCP tools call {@link #assertEditable(String)} before any
 * write. On a forbidden extension the call throws
 * {@link ServoyFileFormatProtectedException}, which the tool maps to a
 * JSON-RPC error.
 */
public final class ServoyFileGuard
{
    private static final Set<String> FORBIDDEN_EXTENSIONS = Set.of(
        ".frm", ".obj", ".tbl", ".val", ".rel", ".dbi"
    );

    private ServoyFileGuard() {}

    /**
     * @throws ServoyFileFormatProtectedException if {@code path} ends
     *         (case-insensitive) with a Servoy structural extension.
     */
    public static void assertEditable(String path)
    {
        if (path == null) return;
        String lower = path.toLowerCase();
        for (String ext : FORBIDDEN_EXTENSIONS)
        {
            if (lower.endsWith(ext))
            {
                throw new ServoyFileFormatProtectedException(path, ext);
            }
        }
    }

    /**
     * Read-only check — useful where the caller wants to surface the
     * reason without throwing.
     */
    public static boolean isProtected(String path)
    {
        if (path == null) return false;
        String lower = path.toLowerCase();
        for (String ext : FORBIDDEN_EXTENSIONS)
        {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }
}
```

```java
package com.servoy.eclipse.developer.mcp.guard;

/**
 * Thrown by {@link ServoyFileGuard} when a tool tries to perform a
 * destructive edit on a Servoy structural file. Tool implementations
 * catch this and translate it to a JSON-RPC error.
 */
public class ServoyFileFormatProtectedException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final String path;
    private final String extension;

    public ServoyFileFormatProtectedException(String path, String extension)
    {
        super("Refusing to edit Servoy structural file: " + path
            + " (extension '" + extension + "' is protected — Servoy file"
            + " formats encode UUIDs and cross-file references that break"
            + " when edited as plain text). Use the Servoy editor instead.");
        this.path = path;
        this.extension = extension;
    }

    public String getPath() { return path; }
    public String getExtension() { return extension; }
}
```

---

## Which tools must call the guard

Every tool that performs an arbitrary text mutation on a workspace file:

- `replaceString`
- `applyPatch`
- `deleteLinesInFile`
- `replaceFileContent`
- `insertIntoFile`
- `searchAndReplace` (must respect a per-file refusal — if any matched
  file is protected, abort the whole batch and report which file was the
  cause)

Tools that operate on whole files at the resource level (delete,
rename, move) are NOT covered by this guard — those are the user's
responsibility, and renaming a `.frm` is a legitimate workspace operation.

`undoEdit` is also exempt — restoring a backed-up version is recovery,
not authoring.

---

## Tool-side error mapping

In each tool method, wrap the call:

```java
@Tool(name = "replaceString", description = "...")
public String replaceString(@ToolParam(...) String filePath, ...)
{
    try
    {
        ServoyFileGuard.assertEditable(filePath);
        // ... perform the edit ...
    }
    catch (ServoyFileFormatProtectedException ex)
    {
        // Returning the message text is enough — the MCP framework will
        // surface it as a tool-call error with a clear, actionable
        // reason for the AI agent.
        throw new RuntimeException(ex.getMessage(), ex);
    }
}
```

Do not silently no-op. Do not return a fake "success: true". The AI
agent must see the refusal so it can choose a different approach (e.g.
ask the user to make the edit manually, or use the Servoy editor).

---

## Tests to write

Each destructive tool that adds the guard must have at least:

- One test that the guard fires for one forbidden extension (`.frm`)
- One test that a normal file (`.js` or `.txt`) is NOT refused
- One test that the error message includes the offending extension

Plus one test on `ServoyFileGuard.isProtected()` for each of the six extensions.

---

## Out of scope for this skill

- Servoy app-level JavaScript (`.js`) — not protected by this rule, edits
  are normal authoring
- Read-only access to `.frm` / `.tbl` / etc. — reading is fine; the guard
  only blocks writes
- Renaming or deleting files at the resource level — handled by Eclipse's
  own resource APIs, not by these MCP tools
