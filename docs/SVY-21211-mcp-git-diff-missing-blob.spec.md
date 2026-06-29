# Spec: SVY-21211 — [MCP] servoy-git_gitDiff is always failing

## 1. Goal

Fix the `servoy-git_gitDiff` MCP tool which always fails with a "Missing blob" error when invoked. The tool should produce valid unified diff output for both unstaged (working tree) and staged changes.

## 2. Background

### 2.1 Symptom

When an AI agent calls the `servoy-git_gitDiff` tool, it consistently fails:

```
⚙servoy-git_gitDiff [projectName=api]
Error: Missing blob 4e9dc9aaee9d95074718b058b269ff8c1b440037
```

### 2.2 Root cause

The `getDiff` method in `GitService` (line 257–283) is implemented in two stages:

1. `diffCommand.call()` — returns a `List<DiffEntry>` without formatting output.
2. `formatDiffEntries(repository, diffs)` — creates a **new** `DiffFormatter` with only the repository set, then calls `formatter.format(diff)` for each entry.

When `DiffCommand.call()` is invoked **without** an `OutputStream`, it internally detects differences between the index and working tree using a `FileTreeIterator`, but the `DiffEntry` objects it returns reference blob IDs that may not exist in the object store. Specifically, for working-tree content that differs from the index, JGit uses synthesized object IDs representing on-disk file content.

The separate `DiffFormatter` created in `formatDiffEntries` does **not** have a `WorkingTreeIterator` configured as a content source. When it tries to resolve these synthesized blob IDs via `repository.open(blobId)`, it fails with "Missing blob" because the content lives on disk, not in the object store.

### 2.3 Correct JGit pattern

JGit's `DiffCommand` supports `setOutputStream(OutputStream)`. When an output stream is provided, `DiffCommand.call()` creates its own `DiffFormatter` that is **properly configured** with the correct tree iterators (including `FileTreeIterator` for working tree comparisons). This formatter can resolve working-tree content from disk and produce valid unified diff output.

## 3. Design

### 3.1 Fix `getDiff` to use `DiffCommand.setOutputStream`

Instead of the two-stage approach (get entries, then reformat), set an `OutputStream` on the `DiffCommand` so that JGit handles formatting internally with properly configured content sources.

```java
public String getDiff(String projectName, boolean staged)
{
    Repository repository = getRepository(projectName);
    try (Git git = new Git(repository);
         ByteArrayOutputStream out = new ByteArrayOutputStream())
    {
        ObjectId head = repository.resolve("HEAD");
        if (head == null)
        {
            return "No commits yet.";
        }

        var diffCommand = git.diff();
        diffCommand.setOutputStream(out);
        if (staged)
        {
            diffCommand.setCached(true);
            var headTree = prepareTreeParser(repository, head);
            diffCommand.setOldTree(headTree);
        }

        diffCommand.call();
        String result = out.toString("UTF-8");
        return result.isEmpty() ? "No changes." : result;
    }
    catch (Exception e)
    {
        throw new RuntimeException("Failed to get diff: " + e.getMessage(), e);
    }
}
```

### 3.2 Clean up `formatDiffEntries`

The `formatDiffEntries` helper method is only called from `getDiff`. After the fix it is no longer used and should be removed to avoid confusion and dead code.

## 4. Implementation plan

1. Modify `GitService.getDiff()` (lines 257–283) to use `diffCommand.setOutputStream(out)` instead of calling `formatDiffEntries`.
2. Return `"No changes."` when the output is empty (better UX than returning an empty string).
3. Remove the `formatDiffEntries` method (lines 543–557) since it is no longer referenced.
4. Organize imports (remove unused imports if no longer needed).
5. Verify no compilation errors.

## 5. Acceptance criteria

- [ ] `servoy-git_gitDiff` with `staged=false` (default) produces unified diff output for unstaged working-tree changes without "Missing blob" errors.
- [ ] `servoy-git_gitDiff` with `staged=true` produces unified diff output for staged (cached) changes.
- [ ] When there are no changes, the tool returns a meaningful message (e.g., "No changes.") instead of an empty string.
- [ ] No compilation errors in the `com.servoy.eclipse.developer.mcp` project.
- [ ] The unused `formatDiffEntries` method is removed.

## 6. Out of scope

- Changes to other git MCP tools (`gitStatus`, `gitLog`, etc.).
- Adding rename detection configuration to the diff output (JGit's internal formatter has reasonable defaults).
- Performance improvements or pagination of large diffs.

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should the diff output be truncated if it exceeds a certain size to prevent overwhelming the LLM context? | Team | open |
