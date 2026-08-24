# Triage Report — SVY-21319

**Verdict:** PROCEED

## Reported problem

The Servoy AI view keeps showing the loading page indefinitely even though the opencode server is running and accessible via an external browser. The workspace log confirms the server started successfully, and the user can manually open the server URL in a browser and see the opencode UI.

## Root-cause assessment

The bug is in `OpenCodeView.startUrlSwitcherThread()` at line 271 (`bundles/com.servoy.eclipse.opencode/src/com/servoy/eclipse/opencode/OpenCodeView.java`):

```java
PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
    if (getSite() != null && getSite().getPage().isPartVisible(OpenCodeView.this)) {
        ServoyLog.logInfo("opencode the url is: " + targetUrl);
        setUrl(targetUrl);
    }
});
```

The `isPartVisible()` check silently drops the URL navigation if the view tab is not the active/frontmost tab at the exact moment the `asyncExec` fires. Once dropped, there is **no retry mechanism** — no `IPartListener2`, no `setFocus()` re-check, no periodic poll. The browser stays on the static `opencode-loading.html` page forever.

**Reproduction scenario:**
1. User opens Servoy AI perspective / view is created → `initUrl()` reaches State 5 → shows loading page → spawns URL-switcher thread.
2. User switches to Console/Problems/another tab while waiting for the server (npm install + server startup takes 10–60+ seconds).
3. Server becomes ready → latch fires → switcher thread resolves URL → posts `asyncExec`.
4. `asyncExec` fires → `isPartVisible()` returns `false` (user is looking at another tab) → navigation silently skipped.
5. User switches back to Servoy AI view → still sees loading page → stuck forever.

A secondary contributing factor: the switcher thread's 120 s timeout races against `OpencodeFolderCreatorJob` (npm install + skills extraction) + `RunOpencodeCommand` (server launch + watchdog detection). If the total exceeds 120 s, `waitForServer` returns `false` and falls back to `DEFAULT_SERVER_URL` on hardcoded port 4096 — which may differ from the actual server port if 4096 was occupied.

## Ticket premise check

The ticket reports the symptom accurately and does not propose a solution. The premise holds — this is a real bug in the opencode plugin's view lifecycle.

## Approaches considered

1. **Remove the `isPartVisible()` guard** — Replace with just `getSite() != null && !browser.isDisposed()`. Setting a URL on a non-visible but non-disposed SWT Browser is safe; the content loads in the background and renders immediately when the user switches to the tab.
   - Pros: One-line fix, simple, no new abstractions.
   - Cons: Loads the page even when the view is hidden behind a tab (negligible cost for a localhost page).

2. **Add an `IPartListener2.partVisible()` hook** — Keep the visibility check, but when it fails, store the pending URL and register a part-visibility listener that navigates when the view becomes visible.
   - Pros: Defers network activity until the view is actually shown.
   - Cons: More complex, new listener lifecycle to manage, higher chance of subtle bugs.

3. **Re-check in `setFocus()`** — If the browser is still on the loading page when the user activates the tab, re-trigger `initUrl()`.
   - Pros: Self-healing without removing the visibility guard.
   - Cons: Relies on `setFocus()` being called (not guaranteed in all scenarios), adds coupling between focus and URL state.

4. **No code change** — Rely on the user to restart or re-open the view.
   - Pros: No risk.
   - Cons: Broken UX, user has no way to recover without restarting Eclipse or knowing to close/reopen the view.

## Recommendation

**Approach 1** (remove the `isPartVisible()` guard). It's a one-line fix that directly addresses the root cause. The original guard was likely added to avoid setting a URL on a disposed view, but `setUrl()` already has its own `!browser.isDisposed()` check — the `isPartVisible()` layer is redundant protection that causes this bug. Loading a localhost URL in a background tab has negligible cost.

As a bonus improvement, the `else` branch (timeout fallback) should use `activator.getServerPort()` instead of `DEFAULT_SERVER_URL` to avoid port mismatch if port 4096 was occupied.

## Git history findings

The `isPartVisible()` check was introduced in commit `d15f9555a` (Johan Compagner, 2026-05-29, "moved opencode to copilot repo") — the initial import of the opencode bundle. No subsequent commit modified this logic. The check has been present since the code's inception with no known rationale documented.
