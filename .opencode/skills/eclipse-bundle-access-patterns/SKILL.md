---
name: eclipse-bundle-access-patterns
description: Which Eclipse bundles require Require-Bundle instead of Import-Package, and how to add .classpath access rules to suppress access restriction errors for JGit, EGit, and jface.text — patterns discovered during com.servoy.eclipse.developer.mcp Sessions 3 and 4.
---

## What I cover

Some Eclipse bundles cannot be consumed via `Import-Package` in MANIFEST.MF because their packages
carry mandatory attributes or are not exported at all. Using `Import-Package` for these bundles
causes unresolved imports at compile time. The fix is `Require-Bundle` instead.

Additionally, some bundles (notably JGit) are on the classpath via the target platform but are
treated as non-API by Eclipse's access restriction checker. This produces 100+ "Access restriction"
errors even though the bundle is present. The fix is a `.classpath` access rule.

---

## Rule: Require-Bundle vs Import-Package

Use `Require-Bundle` (not `Import-Package`) for these bundles:

| Bundle | Packages | Why |
|---|---|---|
| `org.eclipse.jface.text` | `org.eclipse.jface.text`, `org.eclipse.jface.text.source` | Mandatory attributes on exported packages |
| `org.eclipse.jface` (viewers) | `org.eclipse.jface.viewers` | Mandatory attributes |
| `org.eclipse.ui.workbench.texteditor` | `org.eclipse.ui.texteditor` | Mandatory attributes |
| `org.eclipse.jgit` | `org.eclipse.jgit.*` | Non-API bundle; all packages restricted |
| `org.eclipse.egit.core` | `org.eclipse.egit.core.project` | Non-API bundle |

**Symptom when using Import-Package incorrectly:**
```
The import org.eclipse.jface.text cannot be resolved
The import org.eclipse.egit cannot be resolved
```

**Correct MANIFEST.MF pattern:**
```
Require-Bundle: ...,
 org.eclipse.jface.text,
 org.eclipse.ui.workbench.texteditor,
 org.eclipse.jgit,
 org.eclipse.egit.core
```

Do NOT add these to `Import-Package` — remove them from there if present.

---

## Access restriction errors for JGit

Even after adding `org.eclipse.jgit` to `Require-Bundle`, Eclipse's compiler produces access
restriction errors for every JGit type and method:

```
Access restriction: The type 'Repository' is not API (restriction on required library
'.../org.eclipse.jgit_7.5.0.202512021534-r.jar')
Access restriction: The constructor 'Git(Repository)' is not API ...
```

This is because `org.eclipse.jgit` is bundled in the target platform without marking its packages
as public API. The `@SuppressWarnings("restriction")` annotation on the class suppresses the
warnings at the Java level, but Eclipse still reports them as errors by default.

**Fix: add access rules to `.classpath`**

Replace the plain `requiredPlugins` classpath entry with one that grants access:

```xml
<!-- Before -->
<classpathentry kind="con" path="org.eclipse.pde.core.requiredPlugins"/>

<!-- After -->
<classpathentry kind="con" path="org.eclipse.pde.core.requiredPlugins">
    <accessrules>
        <accessrule kind="accessible" pattern="org/eclipse/jgit/**"/>
        <accessrule kind="accessible" pattern="org/eclipse/egit/**"/>
    </accessrules>
</classpathentry>
```

**Note:** After editing `.classpath`, Eclipse may need a project Close + Open (or Clean & Build)
to pick up the new access rules. The errors may persist in the Problems view until the project
is refreshed.

**`@SuppressWarnings("restriction")` on the class** is still needed alongside the access rules —
the two work together.

---

## Checklist when adding JGit/EGit to a plugin

- [ ] `Require-Bundle: org.eclipse.jgit` in MANIFEST.MF (not Import-Package)
- [ ] `Require-Bundle: org.eclipse.egit.core` in MANIFEST.MF (not Import-Package)
- [ ] Remove `org.eclipse.jgit.*` entries from `Import-Package` if present
- [ ] Add `<accessrule kind="accessible" pattern="org/eclipse/jgit/**"/>` to `.classpath`
- [ ] Add `<accessrule kind="accessible" pattern="org/eclipse/egit/**"/>` to `.classpath`
- [ ] Add `@SuppressWarnings("restriction")` to the service class
- [ ] Close + Open project in Eclipse to force classpath re-read

---

## Checklist when adding jface.text / texteditor to a plugin

- [ ] `Require-Bundle: org.eclipse.jface.text` in MANIFEST.MF
- [ ] `Require-Bundle: org.eclipse.ui.workbench.texteditor` in MANIFEST.MF
- [ ] Remove `org.eclipse.jface.text`, `org.eclipse.jface.viewers`, `org.eclipse.ui.texteditor`
      from `Import-Package` if present
- [ ] No access rules needed — these bundles export their packages normally once required via bundle
