# Spec: SVY-21319 — Servoy AI view keeps loading even though opencode is ready

## 1. Goal

Ensure the embedded Servoy AI browser view always navigates to the opencode server URL once the server is ready, regardless of whether the view tab is currently visible or focused. The view must never remain stuck on the loading page when the server is already running.

## 2. Background

### 2.1 Current architecture

`OpenCodeView` is an Eclipse `ViewPart` that hosts an embedded SWT Browser. On creation it enters a state machine (`initUrl()`) that shows a loading page and spawns a background "URL-switcher" thread. This thread waits up to 120 seconds for the opencode server to become ready, then posts an `asyncExec` to navigate the browser to the resolved session URL.

### 2.2 The bug

The `asyncExec` callback in `startUrlSwitcherThread()` wraps the `setUrl()` call in an `isPartVisible()` guard:

```java
if (getSite() != null && getSite().getPage().isPartVisible(OpenCodeView.this)) {
    setUrl(targetUrl);
}
```

If the view is not the frontmost tab when the callback fires, the navigation is silently dropped with no retry. There is also evidence that even when the view IS visible, certain user interactions (clicking elsewhere in the workbench at the exact moment `asyncExec` executes) can cause `isPartVisible()` to return `false`, making the bug broader than a hidden-tab scenario.

Once dropped, there is no recovery path — no listener, no periodic check, no `setFocus()` re-trigger. The browser stays on `opencode-loading.html` indefinitely.

### 2.3 Git history

The `isPartVisible()` check was introduced in commit `d15f9555a` (Johan Compagner, 2026-05-29, "moved opencode to copilot repo") — the initial import of the opencode bundle. No rationale was documented; it appears to have been a defensive guard against calling `setUrl()` on a disposed or invisible widget, but `setUrl()` already has its own `!browser.isDisposed()` check and SWT `Browser.setUrl()` is safe to call on a non-visible widget.

## 3. Design

### 3.1 Keep `isPartVisible()` guard, add `IPartListener2` for deferred navigation

Keep the existing `isPartVisible()` check. When the view is NOT visible at the time the server becomes ready, store the target URL in a `pendingUrl` field and register an `IPartListener2` that fires when the view becomes visible.

**Modified `asyncExec` callback:**
```java
PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
    if (getSite() != null && getSite().getPage().isPartVisible(OpenCodeView.this)) {
        ServoyLog.logInfo("opencode the url is: " + targetUrl);
        setUrl(targetUrl);
    } else if (getSite() != null) {
        pendingUrl = targetUrl;
        registerPartVisibleListener();
    }
});
```

### 3.2 `IPartListener2` implementation

Register a `IPartListener2` on the page that listens for `partVisible(IWorkbenchPartReference)`. When the reference matches this view and `pendingUrl` is set, navigate and clear:

```java
private void registerPartVisibleListener() {
    if (partListener != null) return;
    partListener = new IPartListener2() {
        @Override
        public void partVisible(IWorkbenchPartReference partRef) {
            if (partRef.getPart(false) == OpenCodeView.this && pendingUrl != null) {
                ServoyLog.logInfo("opencode the url is (deferred): " + pendingUrl);
                setUrl(pendingUrl);
                pendingUrl = null;
                getSite().getPage().removePartListener(partListener);
                partListener = null;
            }
        }
    };
    getSite().getPage().addPartListener(partListener);
}
```

The listener is removed after successful navigation to avoid leaking listeners.

### 3.3 Instance fields

- `private volatile String pendingUrl` — stores the URL when the view is not visible at server-ready time.
- `private IPartListener2 partListener` — the registered listener (null when inactive).

Both must be cleaned up in `dispose()`.

### 3.4 Timeout fallback port fix

When `waitForServer` times out, the code currently falls back to `DEFAULT_SERVER_URL` (hardcoded port 4096). If port 4096 was occupied and the server started on a different port, this fallback is wrong. Change the timeout branch to also use `activator.getServerPort()` when available:

```java
} else {
    int port = activator.getServerPort();
    targetUrl = port > 0
        ? "http://127.0.0.1:" + port + "/"
        : DEFAULT_SERVER_URL;
}
```

## 4. Implementation plan

1. **Add instance fields** — Add `private volatile String pendingUrl` and `private IPartListener2 partListener` to `OpenCodeView`.

2. **Add `else` branch to asyncExec callback** — In `startUrlSwitcherThread()`, when `isPartVisible()` returns false but `getSite()` is non-null, store the URL in `pendingUrl` and call `registerPartVisibleListener()`.

3. **Implement `registerPartVisibleListener()`** — Private method that registers an `IPartListener2` on the page. On `partVisible()`, if the part is this view and `pendingUrl` is set, call `setUrl(pendingUrl)`, clear `pendingUrl`, and remove the listener.

4. **Clean up in `dispose()`** — Remove the part listener if still registered, null out `pendingUrl`.

5. **Fix timeout fallback port** — In the `else` branch of `startUrlSwitcherThread()`, use `activator.getServerPort()` instead of `DEFAULT_SERVER_URL` when the port is known.

6. **Verify compilation** — Run `eclipse-ide_getCompilationErrors()` and ensure zero errors.

## 5. Acceptance criteria

- [ ] When the Servoy AI view tab is hidden behind another tab during server startup, the URL is stored and the browser navigates as soon as the view becomes visible via the `IPartListener2`.
- [ ] When the view IS visible, navigation happens immediately as before.
- [ ] The part listener is removed after successful deferred navigation (no listener leak).
- [ ] The part listener is cleaned up in `dispose()`.
- [ ] When the server start times out but a port is known, the fallback URL uses the actual server port rather than hardcoded 4096.
- [ ] No compilation errors or warnings introduced.

## 6. Out of scope

- Removing the `isPartVisible()` guard entirely (user prefers to keep it and defer navigation via listener).
- Redesigning the state machine beyond adding the part listener.
- Addressing the 120-second timeout being too short for slow npm installs (separate concern).
- Adding `setFocus()` re-navigation (can be added later if the listener approach is insufficient).

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should the listener also handle `partActivated()` as a secondary trigger in case `partVisible()` doesn't fire in all tab-switch scenarios? | Dev | open |
