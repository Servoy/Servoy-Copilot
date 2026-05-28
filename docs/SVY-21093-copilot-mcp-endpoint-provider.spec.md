# Spec: SVY-21093 — Copilot plugin implements `IMcpEndpointProvider`

## 1. Goal

The `com.servoy.eclipse.developer.mcp` plugin (Servoy-Copilot repo) must implement
the `com.servoy.eclipse.opencode.mcpEndpoint` extension point introduced in SVY-21091.
The implementation returns the full list of MCP endpoint URLs it serves on Servoy's
embedded Tomcat and the bearer auth token required to call those endpoints, so that
`OpencodeFolderCreatorJob` can write the correct entries into
`{user.home}/.servoy/opencode/opencode.json` before launching opencode.

The auth token is **ephemeral**: generated fresh at each Eclipse startup and held
in memory only — never persisted to disk. This means every Eclipse session gets a
new token, and there is no stored secret to rotate or leak.

As part of this change the existing preference infrastructure for the token
(`McpPreferencePage`, `McpPreferenceInitializer`, `McpPreferenceConstants`) is
removed entirely: the page is internal, the stored token is gone, and the
initializer has nothing left to do.

---

## 2. Background

### 2.1 The `IMcpEndpointProvider` extension point (SVY-21091)

`com.servoy.eclipse.opencode` defines the extension point
`com.servoy.eclipse.opencode.mcpEndpoint`. Any plugin that registers an
`<endpoint class="…"/>` element (where the class implements `IMcpEndpointProvider`)
will have its URLs merged into `opencode.json` before opencode starts.

The `McpConfigWriter` framework:
1. Instantiates the registered class via `IConfigurationElement.createExecutableExtension("class")`.
   This is **plain Eclipse reflection** — no E4 DI, so the class needs a public no-arg
   constructor.
2. Calls `getUrls()` to get the full endpoint URLs.
3. Replaces the port in each URL with `{env:MCP_PORT}`.
4. Writes the templated entries to `opencode.json`.
5. Sets `MCP_PORT` and optionally `MCP_AUTH_TOKEN` in the opencode child process
   environment via `buildEnvVars()`.

### 2.2 MCP server setup in `com.servoy.eclipse.developer.mcp`

`McpServerBuiltins.BUILT_IN_SERVER_CLASSES` is the canonical list of all built-in
MCP server implementation classes. Each class is annotated with
`@McpServer(name = "…")` where `name` is the last URL path segment
(e.g. `@McpServer(name = "servoy-ide")` → endpoint path `/mcp/servoy-ide`).

`McpServerRegistry.initialize()` currently reads the bearer token from the
preference store (`McpPreferenceConstants.MCP_AUTH_TOKEN`) and passes it to each
`BearerTokenAuthenticationFilter` instance. Under the new design it reads the
token from `Activator.SESSION_AUTH_TOKEN` instead.

The Tomcat web server port is obtained at runtime from
`ApplicationServerRegistry.get().getWebServerPort()`.

### 2.3 Existing token persistence (to be removed)

`McpPreferenceInitializer` generates a UUID on first run and persists it to the
Eclipse preference store under `McpPreferenceConstants.MCP_AUTH_TOKEN`.
`McpPreferencePage` exposes this stored token (and the endpoint list) in the
Eclipse Preferences UI.

All three of these — the preference page, the initializer, and the constants
interface — are removed in this ticket. The persistent token is replaced by a
per-session in-memory value (§3.1).

---

## 3. Design

### 3.1 Session auth token in `Activator`

Add a single static final field to `Activator`, initialized once at class-load time:

```java
/** Bearer token for this Eclipse session. Generated fresh on every startup. */
public static final String SESSION_AUTH_TOKEN = UUID.randomUUID().toString();
```

This field is readable from anywhere in the bundle (`Activator.SESSION_AUTH_TOKEN`)
without requiring the activator to be fully started. Its value is stable for the
entire lifetime of the JVM.

### 3.2 Update `McpServerRegistry`

**Token read:** in `initialize()`, replace the preference-store token lookup:

```java
// before
String token = prefs.getString(McpPreferenceConstants.MCP_AUTH_TOKEN);

// after
String token = Activator.SESSION_AUTH_TOKEN;
```

Remove the `IPreferenceStore prefs` local variable and the import of
`McpPreferenceConstants`. The rest of `initialize()` is unchanged.

**Readiness check:** add a public getter so the provider can detect when Tomcat
has finished registering the MCP servlets:

```java
/** Returns {@code true} once {@link #initialize()} has completed successfully. */
public boolean isInitialized() {
    return initialized;
}
```

`initialized` is already a `volatile boolean`, so the read is safe from any thread.

### 3.3 `McpEndpointProvider` class

New class in `com.servoy.eclipse.developer.mcp`.

#### Timing concern

`OpencodeFolderCreatorJob` calls `collectProviders()` immediately after npm setup,
which on a warm start (already installed) can fire before Tomcat has started and
called `McpServiceProvider.getServletInstances()`. If the servlets aren't
registered yet, returning URLs that aren't listening would produce stale config.

`getUrls()` therefore **blocks until `McpServerRegistry.isInitialized()` returns
`true`**, polling every 200 ms with a 30-second timeout. In practice Tomcat starts
in well under a second, so the wait is negligible. If the timeout expires (e.g.
Tomcat failed to start), an empty list is returned and a warning is logged —
opencode will start without MCP tools configured.

```java
package com.servoy.eclipse.developer.mcp;

import com.servoy.eclipse.opencode.IMcpEndpointProvider;

public class McpEndpointProvider implements IMcpEndpointProvider {

    private static final long INIT_TIMEOUT_MS = 30_000;
    private static final long POLL_INTERVAL_MS = 200;

    @Override
    public List<String> getUrls() {
        if (!waitForRegistry()) {
            ServoyLog.logWarning(
                "McpEndpointProvider: MCP servers not initialized within " +
                INIT_TIMEOUT_MS / 1000 + "s — returning no URLs", null);
            return Collections.emptyList();
        }
        int port = ApplicationServerRegistry.get().getWebServerPort();
        List<String> urls = new ArrayList<>();
        for (Class<?> clazz : McpServerBuiltins.BUILT_IN_SERVER_CLASSES) {
            McpServer ann = clazz.getAnnotation(McpServer.class);
            if (ann != null) {
                urls.add("http://localhost:" + port +
                         McpServerRegistry.MCP_PATH_PREFIX + "/" + ann.name());
            }
        }
        return Collections.unmodifiableList(urls);
    }

    @Override
    public String getAuthToken() {
        return Activator.SESSION_AUTH_TOKEN;
    }

    private boolean waitForRegistry() {
        long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            McpServerRegistry registry = McpServerRegistry.getInstance();
            if (registry != null && registry.isInitialized()) return true;
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
```

Key invariants:
- Public no-arg constructor (default, no explicit code needed).
- `getUrls()` blocks until `McpServerRegistry.isInitialized()` is true (or times out).
- `getUrls()` returns URLs of the form `http://localhost:<port>/mcp/<serverName>`, one per
  entry in `McpServerBuiltins.BUILT_IN_SERVER_CLASSES`, in the same order.
- `getAuthToken()` returns the session token — always non-null.

### 3.4 Extension point registration in `plugin.xml`

Add to `com.servoy.eclipse.developer.mcp/plugin.xml`:

```xml
<extension point="com.servoy.eclipse.opencode.mcpEndpoint">
    <endpoint class="com.servoy.eclipse.developer.mcp.McpEndpointProvider"/>
</extension>
```

### 3.5 Remove token preference infrastructure

From `plugin.xml`, remove:

```xml
<extension point="org.eclipse.ui.preferencePages">
    <page
          category="com.servoy.eclipse.ui.preference"
          class="com.servoy.eclipse.developer.mcp.preferences.McpPreferencePage"
          id="com.servoy.eclipse.developer.mcp.preferences"
          name="Servoy Developer MCP">
    </page>
</extension>

<extension point="org.eclipse.core.runtime.preferences">
    <initializer class="com.servoy.eclipse.developer.mcp.preferences.McpPreferenceInitializer"/>
</extension>
```

Delete the source files:
- `src/com/servoy/eclipse/developer/mcp/preferences/McpPreferencePage.java`
- `src/com/servoy/eclipse/developer/mcp/preferences/McpPreferenceInitializer.java`
- `src/com/servoy/eclipse/developer/mcp/preferences/McpPreferenceConstants.java`

If the `preferences` package becomes empty after deletion, delete the package too.

### 3.6 Add `com.servoy.eclipse.opencode` to `MANIFEST.MF`

`McpEndpointProvider` imports `IMcpEndpointProvider` from `com.servoy.eclipse.opencode`.
That package is exported by `com.servoy.eclipse.opencode` (added in SVY-21091).

Add to `Require-Bundle` in
`com.servoy.eclipse.developer.mcp/META-INF/MANIFEST.MF`:
```
com.servoy.eclipse.opencode,
```

---

## 4. Implementation plan

1. **`Activator.java`** (`com.servoy.eclipse.developer.mcp`):
   Add `public static final String SESSION_AUTH_TOKEN = UUID.randomUUID().toString();`

2. **`McpServerRegistry.java`** (`com.servoy.eclipse.developer.mcp`):
   - In `initialize()`, replace the preference-store token read with
     `Activator.SESSION_AUTH_TOKEN`. Remove the `IPreferenceStore` local and
     the `McpPreferenceConstants` import.
   - Add `public boolean isInitialized() { return initialized; }`.

3. **`McpEndpointProvider.java`** (`com.servoy.eclipse.developer.mcp`):
   Create `src/com/servoy/eclipse/developer/mcp/McpEndpointProvider.java` as in §3.3.
   Organise imports, format, check compilation errors.

4. **`MANIFEST.MF`** (`com.servoy.eclipse.developer.mcp`):
   Add `com.servoy.eclipse.opencode` to `Require-Bundle`.

5. **`plugin.xml`** (`com.servoy.eclipse.developer.mcp`):
   - Add the `com.servoy.eclipse.opencode.mcpEndpoint` extension (§3.4).
   - Remove the `preferencePages` and `preferences` extensions (§3.5).

6. **Delete preference classes** (§3.5):
   `McpPreferencePage.java`, `McpPreferenceInitializer.java`, `McpPreferenceConstants.java`.

7. **Compilation check**: `eclipse-ide_getCompilationErrors` on
   `com.servoy.eclipse.developer.mcp` — zero errors required.

---

## 5. Acceptance criteria

- [ ] `Activator.SESSION_AUTH_TOKEN` is a public static final `String` initialised
      once at class-load time with a random UUID.
- [ ] `McpServerRegistry.initialize()` reads the bearer token from
      `Activator.SESSION_AUTH_TOKEN` (not from the preference store).
- [ ] `McpServerRegistry.isInitialized()` is public and returns the value of the
      existing `volatile boolean initialized` field.
- [ ] `com.servoy.eclipse.developer.mcp` declares a dependency on
      `com.servoy.eclipse.opencode` in its `MANIFEST.MF`.
- [ ] `McpEndpointProvider` implements `IMcpEndpointProvider` and is registered
      under `com.servoy.eclipse.opencode.mcpEndpoint` in `plugin.xml`.
- [ ] `McpEndpointProvider.getUrls()` blocks until `McpServerRegistry.isInitialized()`
      is true, then returns one URL per entry in
      `McpServerBuiltins.BUILT_IN_SERVER_CLASSES`, using the form
      `http://localhost:<port>/mcp/<serverName>`.
- [ ] If the registry is not initialized within 30 seconds, `getUrls()` returns an
      empty list and logs a warning (opencode starts without MCP tools).
- [ ] `McpEndpointProvider.getAuthToken()` returns `Activator.SESSION_AUTH_TOKEN`.
- [ ] `McpPreferencePage`, `McpPreferenceInitializer`, and `McpPreferenceConstants`
      are deleted and their `plugin.xml` registrations removed.
- [ ] No compilation errors remain in `com.servoy.eclipse.developer.mcp`.
- [ ] When Servoy Developer starts and opencode launches, `~/.servoy/opencode/opencode.json`
      contains entries for all MCP servers with `{env:MCP_PORT}` URLs and
      `Bearer {env:MCP_AUTH_TOKEN}` headers.

---

## 6. Out of scope

- Changes to `McpServerBuiltins.BUILT_IN_SERVER_CLASSES` (adding/removing servers).
- Changes to the `com.servoy.eclipse.opencode` framework (McpConfigWriter, etc.).
- UI replacement for the removed preference page.

---

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| The 30-second timeout in `waitForRegistry()` is conservative. Is a shorter value (e.g. 10s) more appropriate given the expected Tomcat startup time? | Developer | open |
