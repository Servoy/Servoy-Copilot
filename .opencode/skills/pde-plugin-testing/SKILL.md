---
name: pde-plugin-testing
description: How to correctly set up and run JUnit tests inside a PDE OSGi plugin bundle project like com.servoy.eclipse.ai.workflows — covers test source folder setup, required MANIFEST.MF entries, and the hard constraint that tests cannot run via plain JUnit launcher.
---

## What I cover

Hard-won knowledge from migrating the MCP server into `com.servoy.eclipse.ai.workflows`. These patterns apply to any PDE plugin bundle that needs JUnit tests.

---

## Constraint: tests cannot run via plain JUnit launcher

**This is non-negotiable.** `com.servoy.eclipse.ai.workflows` is a PDE Plugin bundle. Every class in it has transitive Eclipse platform dependencies (`ILog`, `ISourceModule`, Eclipse API types, etc.) that are only resolvable via the OSGi classloader.

The `eclipse-ide` test runner tools use a **plain JVM JUnit launcher** — it cannot expand the `org.eclipse.pde.core.requiredPlugins` classpath container. This means:

- `eclipse-ide_runClassTests`, `eclipse-ide_runAllTests`, `eclipse-ide_runPackageTests` will **all fail** with classloading errors
- Reflection-only test rewrites do not help — the test *targets* themselves have OSGi dependencies
- There is no workaround available via the eclipse-ide toolset

**The correct approach is a `JUnit Plugin Test` launch configuration** — a `.launch` XML file committed alongside the plugin, run via `Run As → JUnit Plugin Test` in Eclipse IDE. See the `pde-launch-setup` skill for the full procedure to build and fix one.

**Do not waste fix cycles trying to make plain JUnit work.**

---

## Setting up the test source folder

Add a `test/` source folder to the project and configure `.classpath`:

```xml
<classpathentry kind="src" path="test" output="bin-test"/>
```

The output directory must be separate from the main `bin/` to avoid mixing test and production classes.

Also add `test/` to `build.properties`:

```
source.. = src/,test/
```

---

## Required MANIFEST.MF entries for test classes

Add to `Require-Bundle`:

```
org.junit;bundle-version="4.13.0";resolution:=optional,
```

Mark it `optional` so the bundle still resolves in production deployments where JUnit is not present.

---

## Test class structure that compiles cleanly

Write test classes that import only from `org.junit.*` and the classes under test. Avoid importing Eclipse platform classes directly in test classes — call them through the class under test instead.

```java
package com.servoy.eclipse.ai.workflows.mcp.test;

import static org.junit.Assert.*;
import org.junit.Test;

public class AllToolsForMCPTest {

    @Test
    public void testToolCountIsExactly39() {
        AllToolsForMCP tools = new AllToolsForMCP();
        assertEquals(39, tools.getTools().size());
    }
}
```

---

## SWT crash guard in Activator.stop()

When tests run headless (via `coretestapplication`), there is no UI Display. If `Activator.stop()` calls any SWT code — even indirectly via a singleton like `SelectionTracker.getInstance()` — it will crash with:

```
org.eclipse.swt.SWTException: Invalid thread access
  at org.eclipse.swt.widgets.Display.getDefault(...)
```

**Fix:** add a `disposeIfInitialized()` static guard that only disposes the singleton if it was already created, and use that in `stop()` instead of `getInstance().dispose()`:

```java
// In SelectionTracker:
public static void disposeIfInitialized() {
    if (instance != null) {
        instance.dispose();
    }
}

// In Activator.stop():
SelectionTracker.disposeIfInitialized();  // safe in headless
```

This is production-safe: it only skips disposal when the singleton was never initialized.

---

## Hard rule: coretestapplication vs uitestapplication

- `coretestapplication`: starts OSGi, no UI workbench. Use for pure unit tests and MCP/reflection tests.
- `uitestapplication`: starts a real Eclipse workbench. REQUIRED when any test calls:
    - `PlatformUI.getWorkbench()`
    - `workbenchPage.showView(...)`
    - `IDE.openEditor(...)`
    - `SWTWorkbenchBot` with view navigation

If SWTBot tests open an E4 view part, `uitestapplication` is mandatory. Using `coretestapplication`
with SWTBot will produce silent NullPointerExceptions in `@BeforeClass`, not a clear error.

---

## @Inject ILog no-op field initializer

When a class has `@Inject private ILog logger;`, the field is `null` in headless tests because no DI
container is active. **Do not add per-call null guards** — initialize the field to a no-op sentinel
instead. The `@Inject` annotation still works in production: DI overwrites the field after construction.

**Critical:** `ILog` is **not** a `@FunctionalInterface` — a lambda will not compile. Use an anonymous
class. The `getBundle()` override must return a non-null `Bundle` stub whose `getSymbolicName()` returns
a non-null string — the `ILog` default methods (`info`, `warn`, `error`) call
`getBundle().getSymbolicName()` internally to compose the plugin ID. Returning `null` from `getBundle()`
causes an NPE even though `logger` itself is non-null.

Copy-paste ready skeleton (place as field initializer, add a `static final` `NOOP_BUNDLE` field):

```java
@Inject
private ILog logger = new ILog() {
    @Override public void log(org.eclipse.core.runtime.IStatus status) { /* no-op */ }
    @Override public void addLogListener(org.eclipse.core.runtime.ILogListener l) { }
    @Override public void removeLogListener(org.eclipse.core.runtime.ILogListener l) { }
    @Override public org.osgi.framework.Bundle getBundle() { return NOOP_BUNDLE; }
};

private static final org.osgi.framework.Bundle NOOP_BUNDLE = new org.osgi.framework.Bundle() {
    @Override public String getSymbolicName() { return "noop"; }
    @Override public int getState() { return 0; }
    @Override public void start(int options) { }
    @Override public void start() { }
    @Override public void stop(int options) { }
    @Override public void stop() { }
    @Override public void update(java.io.InputStream in) { }
    @Override public void update() { }
    @Override public void uninstall() { }
    @Override public java.util.Dictionary<String, String> getHeaders() { return null; }
    @Override public long getBundleId() { return 0; }
    @Override public String getLocation() { return ""; }
    @Override public org.osgi.framework.ServiceReference<?>[] getRegisteredServices() { return null; }
    @Override public org.osgi.framework.ServiceReference<?>[] getServicesInUse() { return null; }
    @Override public boolean hasPermission(Object permission) { return true; }
    @Override public java.net.URL getResource(String name) { return null; }
    @Override public java.util.Dictionary<String, String> getHeaders(String locale) { return null; }
    @Override public Class<?> loadClass(String name) throws ClassNotFoundException { throw new ClassNotFoundException(name); }
    @Override public java.util.Enumeration<java.net.URL> getResources(String name) { return null; }
    @Override public java.util.Enumeration<String> getEntryPaths(String path) { return null; }
    @Override public java.net.URL getEntry(String path) { return null; }
    @Override public long getLastModified() { return 0; }
    @Override public java.util.Enumeration<java.net.URL> findEntries(String path, String pattern, boolean recurse) { return null; }
    @Override public org.osgi.framework.BundleContext getBundleContext() { return null; }
    @Override public java.util.Map<java.security.cert.X509Certificate, java.util.List<java.security.cert.X509Certificate>> getSignerCertificates(int type) { return null; }
    @Override public org.osgi.framework.Version getVersion() { return org.osgi.framework.Version.emptyVersion; }
    @Override public <A> A adapt(Class<A> type) { return null; }
    @Override public java.io.File getDataFile(String filename) { return null; }
    @Override public int compareTo(org.osgi.framework.Bundle o) { return 0; }
};
```

---

## OSGi singleton dispose() compound-guard hazard

Singleton `dispose()` methods that guard state reset with a compound condition are a test hazard.
When the service field is never injected (tests with no DI), the entire body is skipped silently —
`instance` stays non-null, the singleton is never cleaned up, and tests that reflect on `instance`
will fail.

**Before (broken in tests):**
```java
public void dispose() {
    if (selectionService != null && initialized) {
        selectionService.removeSelectionListener(this);
        instance = null;
        initialized = false;
    }
}
```

**After (correct):**
```java
public void dispose() {
    if (initialized) {
        if (selectionService != null) {
            selectionService.removeSelectionListener(this);
        }
        instance = null;
        initialized = false;
    }
}
```

Rule: **listener removal** is guarded by the service field; **state reset** (`instance`, `initialized`,
and any other fields) is guarded only by whether initialization ever happened. These are two separate
concerns and must not share the same `if` condition.

---

## SWTBot modal dialog boilerplate

When SWTBot tests interact with SWT modal dialogs (e.g. `MessageDialog.openConfirm`, `Dialog.open()`),
the macOS SWT nested event loop pumps `asyncExec` but NOT `syncExec`. Every `bot.*` call uses
`syncExec` internally and will **hang indefinitely** while a modal loop is active.

### Mandatory test class scaffolding

```java
@BeforeClass
public static void openChatView() throws Exception
{
    // Open the view under test — must be uitestapplication
    IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    chatView = (ChatView) page.showView(ChatView.ID);
    assertNotNull("ChatView must open", chatView);
}

@Rule
public TestName testName = new TestName();

@Before
public void announceTest()
{
    String name = testName.getMethodName();
    System.out.println("========================================");
    System.out.println("[TEST STARTING] " + name);
    System.out.println("========================================");
    // Set window title for visual identification during interactive runs
    Display.getDefault().asyncExec(() ->
        Display.getDefault().getActiveShell().setText("[TEST] " + name + " - Servoy Developer"));
    try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
}

@Before
public void closeStrayShells()
{
    Display.getDefault().asyncExec(() ->
    {
        for (Shell s : Display.getDefault().getShells())
        {
            String title = s.getText();
            if (s.isVisible() && !title.contains("Servoy Developer") && !title.isEmpty())
            {
                System.out.println("[before-UI] force-closing stray shell: " + title);
                s.close();
            }
        }
    });
    try { Thread.sleep(300); } catch (InterruptedException ignored) { }
}

@After
public void tearDown()
{
    Display.getDefault().asyncExec(() ->
    {
        for (Shell s : Display.getDefault().getShells())
        {
            String title = s.getText();
            if (s.isVisible() && !title.contains("Servoy Developer") && !title.isEmpty())
            {
                System.out.println("[tearDown] force-closing stray shell: " + title);
                s.close();
            }
        }
    });
    try { Thread.sleep(300); } catch (InterruptedException ignored) { }
}
```

### Dismissing a modal dialog (OK/Cancel/Yes/No)

Arm the dismisser **before** the action that opens the dialog:

```java
clickDialogButton("Confirm Close", "OK");          // arm — returns immediately
bot.buttonWithId("org.eclipse.swtbot.widget.key", "closeJobButton").click();  // opens dialog
try { Thread.sleep(500); } catch (InterruptedException ignored) { }  // let dismisser run
```

Helper (add as private static to the test class):

```java
private static void clickDialogButton(String shellTitle, String buttonText)
{
    clickDialogButton(shellTitle, buttonText, 10000);
}

private static void clickDialogButton(String shellTitle, String buttonText, int timeoutMs)
{
    AtomicBoolean dismissed = new AtomicBoolean(false);
    Thread t = new Thread(() ->
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !dismissed.get())
        {
            try { Thread.sleep(100); } catch (InterruptedException ex) { break; }
            Display.getDefault().asyncExec(() ->
            {
                if (dismissed.get()) return;
                for (Shell s : Display.getDefault().getShells())
                {
                    if (shellTitle.equals(s.getText()) && s.isVisible())
                    {
                        dismissed.set(true);
                        clickButtonInComposite(s, buttonText);
                        return;
                    }
                }
            });
        }
    });
    t.setDaemon(true);
    t.start();
}

private static void clickButtonInComposite(Control control, String text)
{
    if (control instanceof Button)
    {
        Button btn = (Button) control;
        if (text.equals(btn.getText()))
        {
            Event e = new Event(); e.widget = btn;
            btn.notifyListeners(SWT.Selection, e);
            return;
        }
    }
    if (control instanceof Composite)
        for (Control child : ((Composite) control).getChildren())
            clickButtonInComposite(child, text);
}
```

### Interacting with dialog contents (nested asyncExec chain)

When you must SELECT a table row or click a widget INSIDE a dialog (not just dismiss it),
all interaction must happen in `asyncExec` calls pumped by the dialog's modal loop:

```java
AtomicBoolean done = new AtomicBoolean(false);

Display.getDefault().asyncExec(() ->
{
    // Fire the button that opens the dialog
    Widget openBtn = findWidgetByKey(chatView, "historyButton");
    Event e = new Event(); e.widget = openBtn;
    openBtn.notifyListeners(SWT.Selection, e);
    // Modal loop starts here; the next asyncExec is pumped by it:
    Display.getDefault().asyncExec(() ->
    {
        Shell active = Display.getDefault().getActiveShell();
        Table table = (Table) findWidgetByKey(active, "historyTable");
        if (table == null || table.getItemCount() == 0) { active.close(); done.set(true); return; }
        table.select(0);
        table.notifyListeners(SWT.Selection, new Event());
        Widget btn = findWidgetByKey(active, "viewReadOnlyBtn");
        if (btn != null) { Event ev = new Event(); ev.widget = btn; btn.notifyListeners(SWT.Selection, ev); }
        else active.close();
        done.set(true);
    });
});

long deadline = System.currentTimeMillis() + 10000;
while (!done.get() && System.currentTimeMillis() < deadline)
    try { Thread.sleep(100); } catch (InterruptedException ignored) { break; }

try { Thread.sleep(300); } catch (InterruptedException ignored) { }
// UI thread is free again — syncExec / bot.* calls are safe here
```

### findWidgetByKey helper

Recursive SWT widget lookup by SWTBot data key — safe to call inside `asyncExec`:

```java
private static Widget findWidgetByKey(Widget root, String key)
{
    if (root == null) return null;
    Object data = root.getData("org.eclipse.swtbot.widget.key");
    if (key.equals(data)) return root;
    if (root instanceof Composite)
        for (Control child : ((Composite) root).getChildren())
        {
            Widget found = findWidgetByKey(child, key);
            if (found != null) return found;
        }
    return null;
}
```

### Key rules

- Use `Thread.sleep()` (not `bot.sleep()`) whenever a modal loop MAY be active — `bot.sleep()` uses `syncExec` internally and hangs.
- ALWAYS arm `clickDialogButton()` BEFORE the action that opens the dialog.
- After the modal interaction completes and `done` is set, wait an additional `Thread.sleep(300)` before using any `bot.*` calls to ensure the UI thread has fully exited the modal loop.
- `@Before closeStrayShells()` rescues state from any test that left a dialog open.

---

## Platform.getLog() as static field crashes JUnit Plugin Tests

`Platform.getLog(SomeClass.class)` called as a `static final` field initializer causes
`ExceptionInInitializerError` in JUnit Plugin Tests because the OSGi bundle registry is not
yet available when the class is loaded by the test runner:

```
Caused by: java.lang.IllegalArgumentException: Logging bundle must not be null.
    at org.eclipse.core.internal.runtime.Log.<init>(Log.java:38)
    at org.eclipse.core.runtime.Platform.getLog(Platform.java:1025)
    at com.servoy.eclipse.developer.mcp.services.GitService.<clinit>(GitService.java:60)
```

**Broken pattern:**
```java
private static final ILog logger = Platform.getLog(GitService.class); // crashes at class init
```

**Fix:** call `Platform.getLog()` lazily inside the method body where it is needed, not as a field:
```java
// No static field. Call inline only where needed:
Platform.getLog(GitService.class).error("Failed to refresh: " + projectName, e);
```

This is safe because `Platform.getLog()` is cheap and the OSGi registry is always available
at the point the method body executes (inside a running plugin).

---

## Null message guard in service exception chains

When a service method wraps a third-party exception (e.g. EGit `RepositoryMapping.getMapping()`
returning null, or JGit throwing without a message), the resulting `RuntimeException` may have
a null message. Tests that assert `e.getMessage().contains("...")` will then fail with a bare
`AssertionError` (no message), which is confusing to diagnose.

**Fix in the service:** always guarantee a non-null message at the boundary:

```java
private Repository getRepository(String projectName)
{
    IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
    if (!project.exists())
        throw new RuntimeException("Project not found: " + projectName);

    try
    {
        RepositoryMapping mapping = RepositoryMapping.getMapping(project);
        if (mapping == null)
            throw new RuntimeException("Project is not mapped to a Git repository: " + projectName);
        Repository repo = mapping.getRepository();
        if (repo == null)
            throw new RuntimeException("Could not obtain Git repository for project: " + projectName);
        return repo;
    }
    catch (RuntimeException e)
    {
        if (e.getMessage() != null) throw e;
        throw new RuntimeException("Failed to get Git repository for project: " + projectName, e);
    }
}
```

**Fix in tests:** use a helper that asserts non-null with a descriptive failure message:

```java
private static void assertProjectError(RuntimeException e)
{
    assertNotNull("Exception must have a message", e.getMessage());
}
```

`assertNotNull(message, object)` produces `AssertionError: Exception must have a message`
instead of a bare `AssertionError`, making the failure immediately diagnosable.

---

## `eclipse-pde_runJUnitPluginTestClass` MCP tool limitation

The `eclipse-pde_runJUnitPluginTestClass` MCP tool **ignores the `application` attribute** in the `.launch` file and always launches with `org.eclipse.pde.junit.runtime.uitestapplication`. This requires a full Eclipse workbench.

If the target platform directory has **duplicate OSGi bundle versions** (e.g. two versions of `org.eclipse.osgi`), the `simpleconfigurator` will fail with:

```
IllegalStateException: The System Bundle was updated. The framework must be restarted
```

and no test results are collected. The tool returns `"No test results collected"` silently.

**Do not waste fix cycles on this.** Run tests manually via **Run As → JUnit Plugin Test** on the `.launch` file in Eclipse. The `.launch` file with `coretestapplication` works correctly when run manually.
