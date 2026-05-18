---
name: eclipse-file-revert-patterns
description: Patterns for reverting workspace files to original content via WorkspaceJob and IFile.setContents — covers scheduling rules, flag combinations, UI-thread dispatch after revert, and the semantics of keepFile() for tracker cleanup.
---

## What this covers

Non-obvious rules for reverting an Eclipse workspace file to its pre-edit content
programmatically — e.g. in an "undo agent edit" action. Applies to any feature that
writes files via the Eclipse workspace API and needs to support undo/revert.

---

## Rule 1 — Always use WorkspaceJob for IFile.setContents()

Calling `IFile.setContents()` directly on the UI thread throws:

```
ResourceException: The file is out of sync with the file system
```

under certain conditions (especially when the file was just written by a background
operation). Wrap it in a `WorkspaceJob`:

```java
WorkspaceJob job = new WorkspaceJob("Revert file") {
    @Override
    public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
        IFile file = ResourcesPlugin.getWorkspace().getRoot()
                         .getFile(new Path(projectRelativePath));
        file.setContents(originalContentStream, IFile.FORCE | IFile.KEEP_HISTORY, monitor);
        return Status.OK_STATUS;
    }
};
job.setRule(ResourcesPlugin.getWorkspace().getRoot());
job.schedule();
```

---

## Rule 2 — Use IWorkspaceRoot as the scheduling rule

The scheduling rule must be `ResourcesPlugin.getWorkspace().getRoot()` (the whole
workspace root), not a finer-grained rule like the file or project. This serialises
the revert against all other workspace operations and avoids lock conflicts.

---

## Rule 3 — Capture original content before the agent writes

Store the original file content (as a `byte[]` or `String`) immediately when the
file is first registered as modified — NOT after the agent has written its changes.
At undo time the agent's version is already on disk; the pre-capture is the only
source of truth.

```java
// In FileModificationTracker.notifyFileModified():
if (!originalContents.containsKey(filePath)) {
    originalContents.put(filePath, readCurrentContent(filePath)); // capture once
}
```

---

## Rule 4 — Post-revert UI operations must use asyncExec

After the `WorkspaceJob` completes (still on a background thread), any UI operation
(e.g. closing a Compare Editor) must be dispatched with `Display.getDefault().asyncExec()`:

```java
// Inside WorkspaceJob.runInWorkspace(), in the finally block:
Display.getDefault().asyncExec(() -> compareEditorService.closeCompareEditorForFile(filePath));
```

Do NOT call SWT widget methods directly from the workspace job thread.

---

## Rule 5 — IFile.FORCE | IFile.KEEP_HISTORY is the correct flag combination

- `IFile.FORCE` — overwrite even if Eclipse thinks the file is out of sync.
- `IFile.KEEP_HISTORY` — preserve the overwritten version in Eclipse Local History,
  so the developer can recover it via Team → Show Local History if needed.

```java
file.setContents(stream, IFile.FORCE | IFile.KEEP_HISTORY, monitor);
```

---

## Rule 6 — Use keepFile() to deregister a file from FileModificationTracker after undo

After a successful revert, call `FileModificationTracker.keepFile(filePath)` (not a
bespoke "removeAfterUndo" method) to remove the entry from the tracker. The name is
counterintuitive (it implies accepting the change) but it achieves the correct
outcome: the file is removed from tracking. This is the established convention in
the workflows codebase.
