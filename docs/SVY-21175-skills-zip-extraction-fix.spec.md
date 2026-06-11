# Spec: SVY-21175 — Unzip of skills not working correctly with the requirements of opencode

## 1. Goal

Fix the skills zip extraction in `SkillsZipExtractor.extractToConfigDir()` so
that it correctly handles the new zip structure where `agents/`, `skills/`,
`commands/`, `plugins/` folders sit at the zip root (next to `opencode.json`)
rather than inside a `.opencode/` wrapper directory. The zip is authoritative:
any folder or file present in the zip overwrites the local copy. But the method
must NOT delete the entire `~/.servoy/opencode/` directory — only the specific
folders/files that appear in the zip — to preserve opencode session data and
other user state.

## 2. Background

### 2.1 Previous zip structure (no longer used)

```
skills.zip
├── AGENTS.MD
├── opencode.json
└── .opencode/
    ├── agents/
    ├── skills/
    ├── commands/
    └── plugins/
```

### 2.2 Current zip structure (after the zip file was already fixed)

```
skills.zip
├── AGENTS.MD
├── opencode.json
├── agents/
│   └── ...
├── skills/
│   └── ...
├── commands/
│   └── ...
└── plugins/
    └── ...
```

Opencode expects `agents/`, `skills/`, `commands/`, `plugins/` to be siblings
of `opencode.json` in the root config directory (`~/.servoy/opencode/`), NOT
nested inside a `.opencode/` subdirectory.

### 2.3 Current bug

The existing `extractToConfigDir()` code (lines 137–180 of
`SkillsZipExtractor.java`) only extracts entries that:
1. Match `opencode.json` (case-insensitive), or
2. Start with the `.opencode/` prefix.

Since the zip no longer contains the `.opencode/` prefix, the `agents/`,
`skills/`, `commands/`, and `plugins/` folders are silently skipped.

### 2.4 Git history

The `extractToConfigDir` method was introduced in commit `64b1addb` ("Introduce
end-of-line normalization" by Johan Compagner, 2026-06-06). The logic was
designed around the previous zip layout that used `.opencode/` as a container.
The prior spec is `docs/SVY-21108-skills-zip-extract-agents-md.spec.md`.

### 2.5 Preservation requirement

The config directory `~/.servoy/opencode/` also contains opencode session data
(e.g. `sessions/` or similar state files). These must NOT be deleted during
extraction. The zip is leading only for the entries it contains.

## 3. Design

### 3.1 New extraction strategy: per-entry directory cleanup

Instead of only deleting a hardcoded `.opencode/` subdirectory, the extractor
must:

1. **First pass** — Scan the zip entries to collect the set of top-level
   directories present in the zip (e.g. `agents/`, `skills/`, `commands/`,
   `plugins/`). Ignore `AGENTS.MD` and `opencode.json` (handled separately).

2. **Delete local directories** — For each top-level directory found in the
   zip, if the corresponding local directory exists under `configDir`, delete it
   recursively.

3. **Second pass (or single pass with buffering)** — Extract all zip entries
   (except `AGENTS.MD`) into `configDir`, preserving the relative path
   structure. `opencode.json` is written directly into `configDir`. All other
   file entries are written relative to `configDir`.

Since the zip stream is not re-seekable, the implementation should either:
- Buffer zip bytes (the caller already does `is.readAllBytes()` into a
  `byte[]` in `OpencodeFolderCreatorJob`), or
- Collect top-level directory names in a single pass while extracting, and
  delete+overwrite using a "delete directory before writing its first file"
  approach.

The simplest correct approach: **single pass with a `Set<String>` of already-cleaned directories**. When extracting a file whose top-level directory has
not yet been cleaned, delete that directory first, add it to the set, then
write the file. This avoids needing two passes or buffering.

### 3.2 Handling of individual files at zip root

Files at the zip root (other than `opencode.json` and `AGENTS.MD`) are
overwritten in-place — no directory deletion needed.

### 3.3 Backward compatibility with `.opencode/` prefix

If an older zip still contains `.opencode/`-prefixed entries, they should
continue to be extracted correctly. After the refactor, entries like
`.opencode/skills/foo.md` will naturally extract to
`configDir/.opencode/skills/foo.md`, with the `.opencode/` directory being
cleaned on first encounter — same as any other top-level directory.

### 3.4 AGENTS.MD exclusion

`AGENTS.MD` must NOT be extracted into `configDir`. It is handled separately by
`writeOrUpdateAgentsMd()` which writes it to the project root. The extraction
loop should skip entries matching `AGENTS.MD` (case-insensitive).

## 4. Implementation plan

1. **Modify `SkillsZipExtractor.extractToConfigDir()`** — Replace the current
   `.opencode/`-only extraction logic with a general approach:
   - Remove the hardcoded deletion of `configDir/.opencode/`.
   - Remove the `OPENCODE_DIR_PREFIX` check.
   - Maintain a `Set<String> cleanedDirs` tracking top-level directories that
     have already been recursively deleted this run.
   - For each non-directory zip entry:
     - Skip `AGENTS.MD` (case-insensitive).
     - If it is `opencode.json` (case-insensitive) → write to
       `configDir/opencode.json`, set flag.
     - Otherwise: determine the top-level directory (first path segment). If it
       is a directory (entry has more than one path segment) and not yet in
       `cleanedDirs`, delete `configDir/<topDir>` recursively and add to
       `cleanedDirs`. Then write the file to `configDir/<relative-path>`.
     - If the entry is a root-level file (single path segment, not
       `opencode.json` or `AGENTS.MD`), overwrite it directly.

2. **Remove `OPENCODE_DIR_PREFIX` constant** — No longer needed.

3. **Update Javadoc** — Reflect that extraction now handles any top-level
   directory/file from the zip, not just `.opencode/`.

4. **Update tests in `SkillsZipExtractorTest`** — Add/modify tests:
   - Test: zip with `agents/foo.md` and `skills/bar.md` → both extracted to
     `configDir/agents/foo.md` and `configDir/skills/bar.md`.
   - Test: existing `agents/` dir with old content is cleaned before
     extraction.
   - Test: existing `sessions/` dir (not in zip) is NOT deleted.
   - Test: root-level files (other than `opencode.json` and `AGENTS.MD`) are
     overwritten.
   - Test: backward compat — zip with `.opencode/skills/foo.md` still works.
   - Preserve existing tests for `opencode.json` extraction.

5. **Update `OpencodeFolderCreatorJob`** — No changes expected; it already
   buffers zip bytes and passes a `ByteArrayInputStream` to
   `extractToConfigDir()`.

## 5. Acceptance criteria

- [ ] Zip entries like `agents/foo.md`, `skills/bar.md`, `commands/baz.md`,
      `plugins/qux.md` are extracted to corresponding paths under
      `~/.servoy/opencode/`.
- [ ] For each top-level directory present in the zip, the local directory is
      fully deleted before extraction (zip is leading).
- [ ] Directories NOT present in the zip (e.g. `sessions/`) are left untouched.
- [ ] Root-level files in the zip (other than `AGENTS.MD`) overwrite their
      local counterparts.
- [ ] `opencode.json` is still extracted and the method returns `true`.
- [ ] `AGENTS.MD` is NOT extracted into `configDir` (handled by separate
      method).
- [ ] Backward compatibility: zips with `.opencode/` prefix still extract
      correctly (the `.opencode/` dir is cleaned and re-extracted).
- [ ] All existing unit tests continue to pass.
- [ ] New unit tests cover the updated extraction logic.

## 6. Out of scope

- Changing the zip file itself (already done externally).
- Modifying the `AGENTS.MD` write/update logic.
- Changing the `McpConfigWriter` merge logic.
- Handling partial/corrupt zips (current error handling is sufficient).

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Are there other files/dirs in `~/.servoy/opencode/` beyond `sessions/` that must be preserved? | jcompagner | open |
| Should there be a checksum/hash comparison to skip extraction if zip hasn't changed? | jcompagner | open |
