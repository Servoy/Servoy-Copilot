---
name: pde-launch-setup
description: Step-by-step procedure for creating a working JUnit Plugin Test .launch file for a PDE OSGi plugin project in the Servoy Eclipse workspace — covers bundle list construction, OSGi resolution cascade, cache clearing, and SWT guard patterns.
---

## What this covers

How to build a `<ProjectName>Tests.launch` file that lets a PDE plugin bundle's JUnit tests run via `Run As → JUnit Plugin Test` in Eclipse. This was developed and validated for `com.servoy.eclipse.ai.workflows`.

---

## Reference launch

The authoritative bundle list lives at:

```
/Volumes/ServoyWork/git/master/servoy-eclipse/com.servoy.eclipse.core/launch_files/Servoy Launch OSX.launch
```

This file is the working production launch config for the full Servoy Eclipse IDE. Every bundle in it is known-good. Use it as the source of truth for exact bundle IDs and versions.

---

## Step 1 — Create the .launch file skeleton

Place the file at `bundles/<plugin-id>/<PluginName>Tests.launch`. Minimum required structure:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<launchConfiguration type="org.eclipse.pde.ui.JunitLaunchConfig">
  <booleanAttribute key="automaticAdd" value="false"/>
  <booleanAttribute key="automaticValidate" value="false"/>
  <stringAttribute key="bootstrap" value=""/>
  <stringAttribute key="checked" value="[NONE]"/>
  <booleanAttribute key="clearConfig" value="true"/>
  <booleanAttribute key="clearws" value="true"/>
  <booleanAttribute key="clearwslog" value="false"/>
  <stringAttribute key="configLocation" value="${workspace_loc}/.metadata/.plugins/org.eclipse.pde.core/WorkflowTests"/>
  <booleanAttribute key="default" value="false"/>
  <booleanAttribute key="includeOptional" value="false"/>
  <stringAttribute key="location" value="${workspace_loc}/../junit-workspace"/>
  <listAttribute key="org.eclipse.debug.core.MAPPED_RESOURCE_PATHS">
    <listEntry value="/<plugin-id>"/>
  </listAttribute>
  <listAttribute key="org.eclipse.debug.core.MAPPED_RESOURCE_TYPES">
    <listEntry value="4"/>
  </listAttribute>
  <stringAttribute key="org.eclipse.jdt.junit.CONTAINER" value=""/>
  <booleanAttribute key="org.eclipse.jdt.launching.ATTR_SKIP_HOTSWAP_ATTRS_CHECK" value="true"/>
  <stringAttribute key="org.eclipse.jdt.launching.JRE_CONTAINER" value="org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-21"/>
  <stringAttribute key="org.eclipse.jdt.launching.MAIN_TYPE" value="org.eclipse.jdt.internal.junit.runner.RemoteTestRunner"/>
  <stringAttribute key="org.eclipse.jdt.launching.PROJECT_ATTR" value="<plugin-id>"/>
  <stringAttribute key="org.eclipse.jdt.launching.SOURCE_PATH_PROVIDER" value="org.eclipse.pde.ui.workbenchClasspathProvider"/>
  <stringAttribute key="pde.version" value="3.3"/>
  <stringAttribute key="product" value="org.eclipse.platform.ide"/>
  <booleanAttribute key="run_in_ui_thread" value="false"/>
  <mapAttribute key="selectedBundles">
    <!-- setEntry lines go here -->
  </mapAttribute>
  <stringAttribute key="selected_target_plugins" value=""/>
  <booleanAttribute key="show_selected_only" value="false"/>
  <stringAttribute key="templateConfig" value=""/>
  <booleanAttribute key="tracing" value="false"/>
  <booleanAttribute key="useCustomFeatures" value="false"/>
  <booleanAttribute key="useDefaultConfig" value="true"/>
  <booleanAttribute key="useDefaultConfigArea" value="false"/>
  <booleanAttribute key="useNamedApplication" value="true"/>
  <booleanAttribute key="useProduct" value="false"/>
  <booleanAttribute key="useSyspropInsteadOfCustomConfig" value="false"/>
  <stringAttribute key="application" value="org.eclipse.pde.junit.runtime.coretestapplication"/>
  <stringAttribute key="testpluginname" value="<plugin-id>"/>
</launchConfiguration>
```

Key settings:
- `automaticAdd=false` — only the explicitly listed bundles are included
- `includeOptional=false` — no auto-inclusion of optional deps
- `application=org.eclipse.pde.junit.runtime.coretestapplication` — headless, no UI workbench
- `run_in_ui_thread=false` — required for headless test application

---

## Step 2 — Build the bundle list

Start with the full bundle list from the reference launch. Extract with:

```bash
grep 'setEntry value=' "/path/to/Servoy Launch OSX.launch" \
  | sed 's/.*value="\([^"]*\)".*/\1/' \
  | sort > /tmp/ref_bundles.txt
```

Copy all `<setEntry .../>` lines from the reference into the `selectedBundles` map. The reference contains ~540 entries — use all of them as a starting point.

Then add the workspace plugin under test itself:

```xml
<setEntry value="<plugin-id>@default:default"/>
```

---

## Step 3 — Cache clear before each run

The PDE test runner caches bundle state. Always clear before running:

```bash
rm -rf "${workspace}/.metadata/.plugins/org.eclipse.pde.core/WorkflowTests"
rm -rf "${workspace}/../junit-workspace"
```

Where `${workspace}` is typically `/Volumes/ServoyWork/Work/eclipse-master-workspace`.

Skipping this causes stale resolution failures that mislead diagnosis.

---

## Step 4 — Diagnose OSGi resolution failures

After running, open the `.log` file in the junit-workspace (or read the Console view). Look for lines like:

```
BundleException: Could not resolve module: some.bundle [N]
  Unresolved requirement: Require-Bundle: missing.dependency
```

The pattern is always a **cascade**: one missing root bundle blocks N others. Focus on the root (the one whose unresolved requirement points to nothing, not to another failing bundle).

**Efficient workflow:** diff the reference launch against the current launch to find ALL missing bundles at once:

```bash
grep 'setEntry value=' reference.launch | sed 's/.*value="\([^"]*\)".*/\1/' | sed 's/@.*//' | sort > /tmp/ref.txt
grep 'setEntry value=' current.launch   | sed 's/.*value="\([^"]*\)".*/\1/' | sed 's/@.*//' | sort > /tmp/cur.txt
comm -23 /tmp/ref.txt /tmp/cur.txt   # in reference but missing from current
```

Then look up the exact `setEntry` lines for the missing ones:

```bash
grep -E 'missing.bundle1|missing.bundle2' reference.launch | grep setEntry
```

Add them all in one batch, clear cache, re-run.

---

## Step 5 — SWT crash guard

When tests run via `coretestapplication` (headless), there is no UI Display. Any `Activator.stop()` that touches SWT — even indirectly — will crash with:

```
org.eclipse.swt.SWTException: Invalid thread access
  at org.eclipse.swt.widgets.Display.getDefault(...)
```

Fix: add a null-guarded static dispose method to any singleton that touches SWT, and call that from `Activator.stop()` instead of `getInstance().dispose()`. See `pde-plugin-testing` skill for the code pattern.

---

## Known bundles NOT needed (save space / avoid confusion)

These appear in the diff between the reference and a minimal test launch but are safe to omit:
- `*.doc.user`, `*.doc.isv` — documentation plugins
- `com.servoy.eclipse.designer.rib` — RIB designer, not needed for tests
- `com.servoy.eclipse.jre.macosx.x86_64` — wrong arch fragment (use aarch64 on Apple Silicon)
- EMF UI/editor/example bundles (`org.eclipse.emf.codegen.*`, `org.eclipse.emf.editor`, etc.) — only needed for visual EMF tooling
- `org.eclipse.jst.*`, `org.eclipse.wst.wsdl.*`, `org.eclipse.wst.wsi.*` — J2EE/WSDL tools, not needed for core tests

---

## Validated final state (com.servoy.eclipse.ai.workflows)

- 541 `setEntry` lines in `WorkflowTests.launch`
- All bundles resolve cleanly
- Tests run and pass via `Run As → JUnit Plugin Test`
- `Activator.stop()` uses `SelectionTracker.disposeIfInitialized()` — no SWT crash on teardown

---

## Common omissions checklist (verify before handing to reviewer)

- [ ] `application` attribute set to `org.eclipse.pde.junit.runtime.uitestapplication`
      (NOT `coretestapplication`) when tests call `PlatformUI.getWorkbench()`.
      Under `coretestapplication`, `getActiveWorkbenchWindow()` returns null and
      SWTBot `@BeforeClass` will NPE immediately.
- [ ] At least 7 `swtbot` bundle `setEntry` lines present. Verify with:
      grep -c "swtbot" WorkflowTests.launch
      Expected: >= 7. If 0, the full bundle-diff procedure (§2.3 of the migration plan) was skipped.
- [ ] Total `setEntry` count is close to the reference launch count (~540).
      Verify with: grep -c "setEntry" WorkflowTests.launch
