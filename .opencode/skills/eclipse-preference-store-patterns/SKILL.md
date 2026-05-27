---
name: eclipse-preference-store-patterns
description: Patterns for using Eclipse IPreferenceStore (via plugin Activator) to persist simple string/int state across workspace restarts — covers null-guard on activator, storage location, key conventions, and the testing constructor pattern to avoid OSGi dependency in plain JUnit.
---

## What this covers

How to use `IPreferenceStore` (accessed via the plugin `Activator`) to store lightweight
state that must survive workspace restarts — without a database or full EMF model.

---

## Pattern 1 — Persisting a string value across restarts

```java
// Constants — define preference keys as static finals in the class that uses them
private static final String PREF_ACTIVE_JOB_ID = "activeJobId";

// Write
IPreferenceStore store = Activator.getDefault().getPreferenceStore();
store.setValue(PREF_ACTIVE_JOB_ID, jobId);

// Read (returns "" if never set — IPreferenceStore.STRING_DEFAULT_DEFAULT)
String saved = Activator.getDefault().getPreferenceStore()
                        .getString(PREF_ACTIVE_JOB_ID);
```

Storage location: `<workspace>/.metadata/.plugins/<plugin-id>/.prefs`
This file persists across workspace restarts and Eclipse restarts.

---

## Pattern 2 — Null-guard on the activator

`Activator.getDefault()` returns `null` if the bundle has not yet been activated
(e.g., during early startup or in plain JUnit without OSGi). Always guard:

```java
private void persistActiveJobId(String id) {
    if (Activator.getDefault() == null) return;   // not in OSGi — skip silently
    Activator.getDefault().getPreferenceStore().setValue(PREF_ACTIVE_JOB_ID, id);
}
```

---

## Pattern 3 — Testing constructor pattern (avoid OSGi in plain JUnit)

Classes that call `Activator.getDefault()` at construction time cannot be
instantiated in plain JUnit tests (no OSGi runtime). Extract all OSGi-dependent
operations into overridable methods and provide a testing constructor:

```java
public class JobManager {

    private final JobHistoryStore store;

    // Production constructor — calls Activator
    public JobManager() {
        this(new JobHistoryStore(getDefaultHistoryPath()));
    }

    // Testing constructor — no Activator dependency
    JobManager(JobHistoryStore store) {
        this.store = store;
    }

    // Override in tests to avoid preference store
    protected void persistActiveJobId(String id) {
        if (Activator.getDefault() == null) return;
        Activator.getDefault().getPreferenceStore().setValue(PREF_ACTIVE_JOB_ID, id);
    }
}
```

In the test subclass, override `persistActiveJobId()` to be a no-op:

```java
class TestablJobManager extends JobManager {
    TestablJobManager(JobHistoryStore store) { super(store); }
    @Override protected void persistActiveJobId(String id) { /* no-op */ }
}
```

---

## Pattern 4 — Test gap: preference-store restore path is untestable in plain JUnit

The "read preference and restore last active job" path cannot be covered by a plain
JUnit test because `Activator.getDefault()` returns null. Document this gap explicitly
in the test class:

```java
// NOTE: testInit_restoresLastActiveJob is intentionally omitted.
// The preference store is not available without OSGi.
// This path is exercised at runtime only.
```

Do not mark this as a bug — it is an expected consequence of running PDE tests outside
the OSGi container.
