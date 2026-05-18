---
name: target-platform-directory-safety
description: Safety rules for the shared exported target platform directory at /Volumes/ServoyWork/TargetDefinitions/Master/plugins/ — never delete files programmatically, covers duplicate bundle causes, recovery procedure, and why bulk rm is dangerous.
---

## What this covers

The `/Volumes/ServoyWork/TargetDefinitions/Master/plugins/` directory is a **shared exported target platform directory** used by all Eclipse instances in the workspace (Eclipse IDE, Servoy Developer, etc.). It is populated by exporting two target platform files into the same directory.

---

## Rule 1 — Never delete files from this directory programmatically

This directory contains hundreds of Eclipse platform JARs that are **not explicitly declared** in any `.target` file — they come from the Eclipse release train p2 repository and are resolved transitively. You cannot know which files are safe to delete by inspecting the `.target` files alone.

**Never run `rm`, `find -delete`, or any bulk delete on this directory without:**
1. Explicit user confirmation
2. A verified backup or the ability to re-export both target platforms

---

## Rule 2 — Duplicate bundles are expected and harmless for Eclipse

When two target platforms are exported into the same directory, duplicate bundle versions appear (e.g. `org.eclipse.osgi_3.18.600` and `org.eclipse.osgi_3.24.0`). Eclipse's p2 resolver handles this correctly — it picks the appropriate version per bundle requirement.

**Do not attempt to "clean up" duplicates** unless you have a specific, confirmed problem caused by them.

---

## Rule 3 — The `System Bundle was updated` PDE error is NOT caused by duplicates in the directory

The `IllegalStateException: The System Bundle was updated` error in PDE JUnit tests is caused by the `simpleconfigurator` seeing two versions of `org.eclipse.osgi` in `bundles.info`. This is a PDE test runner issue, not a target platform directory issue. The fix is to run tests manually, not to delete files.

---

## Why duplicates appear

The workflow exports two target platforms into the same directory:
1. `com.servoy.eclipse.target.target` — Eclipse 2025-12 release train (newer Eclipse bundles)
2. `com.servoy.eclipse.servoypilot..target` — Servoy update site (older Eclipse bundles from Servoy LTS)

The second export adds older versions of bundles already present from the first export. This is by design — the constraint is that both must export to the same directory.

---

## Recovery procedure if files are accidentally deleted

1. In Eclipse IDE: open `com.servoy.eclipse.target.target` → click **Export** → destination `/Volumes/ServoyWork/TargetDefinitions/Master/` → **clear destination first**
2. Open `com.servoy.eclipse.servoypilot..target` → click **Export** → same destination → **do NOT clear**
3. In Eclipse IDE: open `eclipse_local.target` → click **Reload Target Platform**
4. Restart Servoy Developer

---

## Verification after export

```bash
ls /Volumes/ServoyWork/TargetDefinitions/Master/plugins/ | wc -l
```

Expected: ~1500+ files. If significantly fewer, the export was incomplete.
