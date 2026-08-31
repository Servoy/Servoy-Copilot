# Spec: SVY-21374 — export function in servoy voor opencode session(s)

## 1. Goal

Add a discoverable "Export session" toolbar action to the embedded `OpenCodeView` that
produces a full JSON transcript (prompts, responses, tool/MCP calls with args/output and
timing, reasoning, subagent calls) of the current opencode session, so users can attach it
to a Jira case or use it to build test flows — without needing a terminal or hand-rolled
`opencode.db` table joins. The action tracks whichever session is actually displayed in the
embedded browser, and on success lets the user jump straight to the saved file in their OS
file explorer.

## 2. Background

### 2.1 The reported gap

With standalone (non-Kiro) opencode clients, users run `opencode export <sessionID>` from a
terminal (`/export` in the TUI or the top-level `export` CLI command) to get a rich JSON dump
of a session — including MCP/tool calls with args, output, and response time; reasoning
parts; subagent calls; durations. In the Servoy Copilot embedded opencode view
(`com.servoy.eclipse.opencode.OpenCodeView`), there is no terminal and no export button —
the view is an SWT browser hosting `opencode serve`'s web frontend — so that workflow is
unavailable. The reporter's current workaround is a hand-written script that reconstructs
the same transcript by joining tables directly in `opencode.db`.

### 2.2 Why not touch `opencode.db`

A Triage phase (`docs/SVY-21374-triage.md`) established that `opencode` already ships this
exact feature via `opencode export [sessionID] [--sanitize]` (confirmed against the pinned
`opencode-ai: "~1.18.6"` version and the official CLI docs), and that the running server
already exposes equivalent data over HTTP. Building custom `opencode.db` cross-table
extraction would duplicate an upstream-maintained feature and couple Servoy to an internal,
historically-breaking storage schema (v1.2.0 was a breaking migration to a single SQLite DB).
The approved approach instead wraps the existing `opencode export` CLI command using
infrastructure the plugin already has.

### 2.3 Existing infrastructure to reuse

| Piece | Location | Role |
|---|---|---|
| `RunOpencodeCommand.buildServoyXdgEnv()` | `RunOpencodeCommand.java:177-184` | Builds `XDG_CONFIG_HOME`/`XDG_DATA_HOME`/`XDG_STATE_HOME`/`XDG_CACHE_HOME` overrides pointing at `{user.home}/.servoy`, so the CLI reads/writes the same isolated data dir the managed server uses. Package-visible `static`, already unit-tested (`RunOpencodeXdgEnvTest`) without OSGi. |
| `ngActivator.createNPMCommand(File, List<String>)` | `com.servoy.eclipse.ngclient.ui.Activator` | Resolves node/npm and returns an `IRunNPMCommand` (or a `NoOpNPMCommand` if Node isn't installed yet) that runs `npm exec -- <args>` in a given folder. Used identically by `RunOpencodeCommand` (`opencode serve`) and `OpencodeFolderCreatorJob` (`npm install`/`npm update`). |
| `IRunNPMCommand.setOutputStream(StringOutputStream)` | `com.servoy.eclipse.ngclient.ui` | Lets a caller redirect the process's merged stdout/stderr line-by-line to a custom sink instead of the default TiNG console (added for SVY-21384's "Servoy AI Console"). This is the mechanism this feature will reuse to *capture* the export JSON instead of just logging it. |
| `Activator.getConsole()` / `logToConsole(String)` | `com.servoy.eclipse.opencode.Activator` | The "Servoy AI Console" — used for lifecycle/diagnostic logging, reused here for progress/error visibility. |
| `OpenCodeView.findLastSessionId(int port, String projectPath)` | `OpenCodeView.java:354-388` | Queries `GET /session?directory=<dir>&limit=1&roots=true` on the running server and extracts the first session `id` from the JSON response by string-scanning (no JSON library dependency). Currently `private`. |
| `OpenCodeUtil.getActiveProjectPath()` | `OpenCodeUtil.java` | Resolves the active Servoy solution's git-root (or project) path — the same `directory` value used everywhere else (server URL, `findLastSessionId`, `PWD` env override). |
| `OpenCodeBranding.buildInjectScript()` | `OpenCodeBranding.java` | Builds the JS IIFE injected on every `LocationListener.changed` event (branding CSS, watermark removal, scratchpad-tool hiding). This is the natural place to add the session-tracking hook (§3.7), since it already re-runs on every full navigation and is unit-tested independently of SWT. |
| `BrowserFunctionWrapper` | `com.servoy.eclipse.servoypilot.chatview.parts.BrowserFunctionWrapper` | Abstract base that registers a `org.eclipse.swt.browser.BrowserFunction` or `com.equo.chromium.swt.BrowserFunction` depending on `IBrowser.isChromium()`, dispatching to an overridable `function(Object[])`. Existing prior art for calling into Java from injected JS (used today by `ChatView` for a dozen different callbacks); `com.servoy.eclipse.opencode` does not yet depend on `com.servoy.eclipse.servoypilot`, so this spec inlines an equivalent minimal wrapper locally rather than adding a new inter-bundle dependency (see §3.7). |
| Managed opencode dir | `Activator.getInstance().getStateLocation().toFile() + "/opencode"` | The npm project directory (`package.json` lives here) that `RunOpencodeCommand`/`OpencodeFolderCreatorJob` run `npm exec`/`npm install` in. The export command must run here too, for the same node_modules/opencode-ai binary to be found. |

### 2.4 Git history

No prior commits touch session export in this bundle — this is new territory (confirmed in
triage). The nearest prior art is the `npm exec -- opencode ...` invocation pattern and the
`buildServoyXdgEnv()` override, both introduced for `opencode serve` and reused verbatim here.

### 2.5 Tracking the session actually displayed in the browser

`findLastSessionId` resolves the *most recently modified* session for the active project
directory (`limit=1&roots=true`), which is **not necessarily** whatever session the user
currently has open in the embedded browser — the OpenCode web app is a client-side-routed
SPA, so navigating to a different/older session inside it changes the URL via
`history.pushState`/`popstate` without a full page load, and `LocationListener.changed`
(SWT's browser navigation event) does **not** fire for that kind of client-side-only
navigation.

Per product decision this spec tracks the actual displayed session via a small JS hook
injected into the page that observes the SPA's own URL changes and reports the current
session id to Java through a registered `BrowserFunction` callback (§3.7). "Export session"
therefore always exports what the user is actually looking at. `OpenCodeUtil.findLastSessionId`
is kept as the fallback for the window before that hook has reported anything yet (e.g.
immediately after the view opens, before the SPA has finished its first render, or if the
user is on the "new session" screen with no session id in the URL at all).

## 3. Design

### 3.1 Toolbar contribution on `OpenCodeView`

Add a standard JFace toolbar action via the view's action bars — no change to the browser's
`Composite` layout is needed:

```java
@Override
public void createPartControl(Composite parent) {
    browser = BrowserFactory.createBrowser(parent);
    browser.addLocationListener(new LocationAdapter() {
        @Override
        public void changed(LocationEvent event) {
            browser.execute(INJECT_CSS_JS);
        }
    });
    registerSessionTrackingFunction();
    createExportAction();
    initUrl();
}

private void createExportAction() {
    IAction exportAction = new Action("Export session") {
        @Override
        public void run() {
            exportCurrentSession();
        }
    };
    exportAction.setToolTipText("Export the current opencode session as a JSON transcript");
    exportAction.setImageDescriptor(
        PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_ETOOL_SAVEAS_EDIT));
    getViewSite().getActionBars().getToolBarManager().add(exportAction);
    getViewSite().getActionBars().updateActionBars();
}
```

Reusing a built-in `ISharedImages` icon avoids adding new icon assets. The button is always
visible in the view's toolbar (standard Eclipse discoverability — no context menu digging).

### 3.2 `exportCurrentSession()` — UI-thread precondition checks + file picker

Runs on the UI thread (button click), does only fast/non-blocking checks and the save-file
dialog, then hands off to a background `Job`:

1. Reject with a warning `MessageDialog` if:
   - Login isn't complete, or Servoy AI isn't configured (`isServoyAiConfigured()` — already
     exists), or
   - No active solution (`OpenCodeUtil.getActiveProjectPath() == null`), or
   - The server isn't ready yet (`Activator.getInstance().isServerReady() == false`).
2. Read `this.trackedSessionId` (set by the JS hook, see §3.7) — this is the id of the
   session currently displayed in the browser, or `null` if the hook hasn't reported one yet
   (new-session screen, or hook not fired yet).
3. Open a `FileDialog(shell, SWT.SAVE)` filtering `*.json`, with a default file name
   `opencode-session-export-<yyyyMMdd-HHmmss>.json` and default directory the active
   project's path. If the user cancels, do nothing.
4. Schedule `new ExportSessionJob(opencodeDir, projectPath, port, trackedSessionId, targetFile).schedule()`.

The Job (not this method) resolves a fallback session id via `OpenCodeUtil.findLastSessionId`
if `trackedSessionId` is `null` — that call is blocking HTTP and must not run on the UI thread.

### 3.3 New class `ExportSessionJob` (background `Job`)

Lives in `com.servoy.eclipse.opencode`, follows the same shape as `RunOpencodeCommand`:

```java
class ExportSessionJob extends Job {
    ExportSessionJob(File opencodeDir, String projectPath, int port, String trackedSessionId, File targetFile) {
        super("Exporting opencode session");
        setUser(true); // user-initiated, should be visible/cancelable in Progress view
    }

    protected IStatus run(IProgressMonitor monitor) {
        // 1. Prefer the tracked (actually displayed) session id; fall back to the
        //    most-recently-modified session if the hook hasn't reported one yet.
        String sessionId = trackedSessionId != null
            ? trackedSessionId
            : OpenCodeUtil.findLastSessionId(port, projectPath);
        if (sessionId == null) {
            notifyUi(false, "No opencode session found for this project.", null);
            return Status.OK_STATUS;
        }

        // 2. Build & run: npm exec -- opencode export <sessionId> --sanitize
        com.servoy.eclipse.ngclient.ui.Activator ngActivator = ...; // null-guard like RunOpencodeCommand
        IRunNPMCommand cmd = ngActivator.createNPMCommand(opencodeDir, buildExportCommandArgs(sessionId));
        Map<String, String> env = new HashMap<>(RunOpencodeCommand.buildServoyXdgEnv());
        env.put("PWD", projectPath);
        cmd.setExtraEnvironment(env);

        StringBuilder captured = new StringBuilder();
        cmd.setOutputStream(capturingStream(captured, activator.getConsole().outputStream()));

        cmd.runCommand(monitor); // IOException/InterruptedException -> notifyUi(false, ..., null)

        if (cmd.getExitCode() != 0) {
            notifyUi(false, "opencode export exited with code " + cmd.getExitCode() + ". See Servoy AI Console.", null);
            return Status.OK_STATUS;
        }

        String json = stripNonJsonPreamble(captured.toString());
        Files.writeString(targetFile.toPath(), json, StandardCharsets.UTF_8);
        notifyUi(true, targetFile.getAbsolutePath(), targetFile);
        return Status.OK_STATUS;
    }
}
```

Key points:

- `buildExportCommandArgs(String sessionId)` — package-visible `static`:
  `List.of("exec", "--", "opencode", "export", sessionId, "--sanitize")`. `--sanitize` is
  always passed (per the approved approach) since exported transcripts are meant to be
  shared externally (Jira attachments) and may contain file/tool content.
- Environment parity with `opencode serve`: same `buildServoyXdgEnv()` override plus `PWD`
  set to the active project path, so the CLI resolves the same isolated `~/.servoy` data
  store and the same project context the running server uses.
- `capturingStream(...)` is a small `StringOutputStream` implementation that appends every
  written chunk to the in-memory `StringBuilder` **and** forwards it to the Servoy AI Console
  (so failures/npm noise remain visible there), mirroring the dual-purpose logging already
  used elsewhere in the bundle.
- `stripNonJsonPreamble(String output)` — package-visible `static`, defensive: since the
  captured text is the *entire* merged stdout/stderr of an `npm exec --` invocation (which
  could in rare cases include an npm banner/deprecation line before the actual JSON), this
  trims everything before the first line that starts with `{` or `[`. Pure string logic,
  unit-testable without OSGi (see §3.8).
- `notifyUi(boolean success, String detail, File savedFile)` — wraps
  `Display.getDefault().asyncExec(...)` to call `Activator.getInstance().logToConsole(...)`
  and show a dialog: on failure a plain warning/error `MessageDialog`; on success the
  two-button dialog described in §3.6 (offers "Show in Explorer").

### 3.4 Extract `findLastSessionId` into `OpenCodeUtil`

Move the existing private `OpenCodeView.findLastSessionId(int, String)` (no behavior change)
into `OpenCodeUtil` as a package-visible `static` method, so both `OpenCodeView` (startup
resume) and `ExportSessionJob` (export fallback) call the same implementation.
`OpenCodeView.resolveSessionUrl(...)` is updated to call `OpenCodeUtil.findLastSessionId(...)`
instead of the removed private method.

### 3.5 Manifest / dependencies

No new `Require-Bundle` entries are needed for the toolbar/dialog/file-picker pieces:
`org.eclipse.ui` (already required) transitively provides `org.eclipse.jface.action.Action`,
`org.eclipse.ui.ISharedImages`, and `org.eclipse.swt.widgets.FileDialog`. `org.eclipse.swt`
(pulled in transitively via `com.servoy.eclipse.ui`'s `IBrowser`/SWT usage, already a
compile-time dependency of this bundle through `BrowserFactory`/`IBrowser`) additionally
provides `org.eclipse.swt.program.Program` (used for "Show in Explorer", §3.6) and
`org.eclipse.swt.browser.BrowserFunction` / the Chromium equivalent (used for the session
tracking hook, §3.7) — both already used the same way elsewhere in the codebase
(`ChatView`/`BrowserFunctionWrapper`), so no manifest change is required there either.

### 3.6 Export result dialog — "Show in Explorer"

On success, instead of a plain info `MessageDialog`, show a `MessageDialog` with two custom
button labels so the user can jump straight to the file:

```java
private void notifySuccess(File savedFile) {
    MessageDialog dialog = new MessageDialog(shell, "Export complete", null,
        "Session exported to:\n" + savedFile.getAbsolutePath(),
        MessageDialog.INFORMATION,
        new String[] { "Show in Explorer", "OK" }, 1); // "OK" is default
    if (dialog.open() == 0) { // user picked "Show in Explorer"
        revealInFileExplorer(savedFile);
    }
}

/** Package-visible static so it's unit-testable without opening a real OS file browser. */
static boolean revealInFileExplorer(File file) {
    File target = file.exists() ? file : file.getParentFile();
    if (target == null) return false;
    return org.eclipse.swt.program.Program.launch(target.getAbsolutePath());
}
```

`Program.launch(path)` asks the OS shell to open the given path with its default handler;
for a regular file this typically opens (or focuses) the containing folder with the file
selected on Windows/macOS, and opens the containing folder on Linux file managers that
support it. This mirrors the file/URL "open externally" pattern already available via SWT
and needs no new platform-specific code or dependency. If `Program.launch` returns `false`
(no OS handler resolved it), fall back to a plain info dialog stating the file's saved path
so the user is not left without feedback.

### 3.7 Session-tracking JS hook + `BrowserFunction`

**Goal:** know, in Java, the session id of whatever the embedded SPA is currently showing —
kept up to date across the SPA's own client-side navigation, not just full page loads.

**JS side** (added to `OpenCodeBranding.buildInjectScript()`, so it re-installs itself on
every `LocationListener.changed` re-injection just like the existing branding/observer code):

```js
(function() {
  if (window._svySessionHookInstalled) return;
  window._svySessionHookInstalled = true;

  function currentSessionIdFromPath() {
    var m = /\/session\/([^\/?#]+)/.exec(location.pathname);
    return m ? m[1] : null;
  }

  function report() {
    var id = currentSessionIdFromPath();
    if (id !== window._svyLastReportedSessionId) {
      window._svyLastReportedSessionId = id;
      if (window.__servoySessionChanged) window.__servoySessionChanged(id || '');
    }
  }

  // SPA routers commonly use pushState/replaceState for in-app navigation.
  ['pushState', 'replaceState'].forEach(function(name) {
    var orig = history[name];
    history[name] = function() {
      var ret = orig.apply(this, arguments);
      report();
      return ret;
    };
  });
  window.addEventListener('popstate', report);
  report(); // report the initial URL too
})();
```

This is appended as its own guarded IIFE inside `buildInjectScript()` (parallel to the
existing branding IIFE), reusing the file's existing `toJsString`-free plain-script style
since it contains no dynamic Java values.

**Java side** — a small `BrowserFunction` registered once in `OpenCodeView`, exposing itself
as `window.__servoySessionChanged` to the page:

```java
private volatile String trackedSessionId;

private void registerSessionTrackingFunction() {
    Object browserInstance = browser.getBrowserInstance();
    if (browserInstance instanceof org.eclipse.swt.browser.Browser swtBrowser) {
        new org.eclipse.swt.browser.BrowserFunction(swtBrowser, "__servoySessionChanged") {
            @Override
            public Object function(Object[] arguments) {
                onSessionIdReported(arguments);
                return null;
            }
        };
    } else if (browserInstance instanceof com.equo.chromium.swt.Browser chromiumBrowser) {
        new com.equo.chromium.swt.BrowserFunction(chromiumBrowser, "__servoySessionChanged") {
            @Override
            public Object function(Object[] arguments) {
                onSessionIdReported(arguments);
                return null;
            }
        };
    }
}

/** Package-visible static so the parsing logic is unit-testable without SWT. */
static String parseReportedSessionId(Object[] arguments) {
    if (arguments == null || arguments.length == 0 || !(arguments[0] instanceof String s) || s.isEmpty()) {
        return null;
    }
    return s;
}

private void onSessionIdReported(Object[] arguments) {
    trackedSessionId = parseReportedSessionId(arguments);
}
```

`com.servoy.eclipse.opencode` does not currently depend on `com.servoy.eclipse.servoypilot`
(where `BrowserFunctionWrapper` lives), and this is the *only* browser-function need in this
bundle, so the two-branch dispatch is written directly in `OpenCodeView` rather than adding a
cross-bundle dependency for one call site. `ChatView`'s `BrowserFunctionWrapper` remains the
pattern to point to if a second callback is ever needed here, at which point extracting a
shared wrapper becomes worthwhile.

`BrowserFunction` instances are torn down automatically when their `Browser` widget is
disposed (standard SWT behaviour), so no explicit cleanup is needed in `OpenCodeView.dispose()`.

### 3.8 Testability

New package-visible pure-logic helpers, exercised by a new `ExportSessionJobTest` and
additions/new test class in `com.servoy.eclipse.opencode.tests` (plain JUnit, no OSGi):

- `ExportSessionJob.buildExportCommandArgs(String sessionId)` — asserts the exact argument
  list/order, including `--sanitize`.
- `ExportSessionJob.stripNonJsonPreamble(String raw)` — asserts: text already starting with
  `{`/`[` is returned unchanged; a leading npm banner line before a `{...}` body is stripped;
  input with no JSON start marker is returned unchanged (defensive fallback, better to write
  raw output than silently produce an empty file).
- `OpenCodeView.parseReportedSessionId(Object[])` — asserts: `null` for `null`/empty
  args/non-`String`/empty-string input; the string itself otherwise. (This one needs
  `OpenCodeView` to compile outside OSGi for the test, which it already does today for the
  bundle's other package-visible statics such as `getActiveProjectPath`.)
- A small standalone test for the JS hook's path-matching regex logic is **not** added — the
  regex is simple enough to review by inspection and JS execution isn't testable from the
  plain-JUnit test bundle; this mirrors how `OpenCodeBranding`'s existing injected script is
  only indirectly covered (its *data* — `HIDDEN_TOOL_PREFIXES`, `shouldHideToolCall` — is
  tested, not the injected script's execution itself).

`OpenCodeUtil.findLastSessionId` extraction does not need new tests beyond what already
exercises it indirectly (it has no existing dedicated unit test either, since it requires a
live HTTP server; this remains unchanged).

## 4. Implementation plan

1. `OpenCodeUtil.java` — add `static String findLastSessionId(int port, String projectPath)`,
   moving the body (and its console logging calls) from `OpenCodeView`.
2. `OpenCodeBranding.java` — add the session-tracking JS IIFE (§3.7) to
   `buildInjectScript()`.
3. `OpenCodeView.java`:
   - Remove the private `findLastSessionId` method; update `resolveSessionUrl(int, String)`
     to call `OpenCodeUtil.findLastSessionId(...)`.
   - Add a `volatile String trackedSessionId` field, `registerSessionTrackingFunction()`
     (called from `createPartControl`), the package-visible static
     `parseReportedSessionId(Object[])`, and `onSessionIdReported(Object[])` (§3.7).
   - Add `createExportAction()` (called from `createPartControl`) contributing the toolbar
     action via `getViewSite().getActionBars()`.
   - Add `exportCurrentSession()`: precondition checks, reads `trackedSessionId`,
     `FileDialog`, schedules `ExportSessionJob`.
   - Add `notifySuccess(File)` and the package-visible static `revealInFileExplorer(File)`
     (§3.6).
4. New file `ExportSessionJob.java` (package `com.servoy.eclipse.opencode`): background
   `Job` as described in §3.3, plus the package-visible static helpers
   `buildExportCommandArgs` and `stripNonJsonPreamble`.
5. New test file `ExportSessionJobTest.java` in
   `tests/com.servoy.eclipse.opencode.tests/src/test/java/com/servoy/eclipse/opencode/`
   covering `buildExportCommandArgs` and `stripNonJsonPreamble`.
6. New (or extended, if one already exists for `OpenCodeView`'s other package-visible
   statics) test file covering `OpenCodeView.parseReportedSessionId`.
7. Run `eclipse-ide_getCompilationErrors()` on both `com.servoy.eclipse.opencode` and
   `com.servoy.eclipse.opencode.tests` and fix anything flagged.
8. Run the `com.servoy.eclipse.opencode.tests` suite (`eclipse-ide_runClassTests` /
   `eclipse-ide_runAllTests`) to confirm no regressions and the new tests pass.

## 5. Acceptance criteria

- [ ] `OpenCodeView`'s toolbar shows a discoverable "Export session" action once the view is
      configured and the browser is created.
- [ ] Clicking it while the server isn't ready, no solution is active, or Servoy AI isn't
      configured shows a warning dialog and performs no export.
- [ ] Clicking it with a running server and an active project opens a `SWT.SAVE` file dialog;
      cancelling performs no export.
- [ ] On confirm, a background `Job` resolves the session id actually displayed in the
      browser (via the injected JS hook reporting into `trackedSessionId`), falling back to
      `OpenCodeUtil.findLastSessionId` if the hook hasn't reported one yet; runs
      `npm exec -- opencode export <sessionId> --sanitize` in the managed opencode directory
      with the same XDG/`PWD` environment overrides used for `opencode serve`; and writes the
      resulting JSON to the chosen file.
- [ ] Navigating to a different/older session inside the embedded browser (client-side SPA
      navigation, no full page reload) and then clicking "Export session" exports that
      session, not just the most-recently-modified one.
- [ ] On success, a dialog reports the saved file path and offers a "Show in Explorer"
      button; choosing it invokes `Program.launch(...)` on the saved file/its containing
      folder. The file contains the sanitized JSON transcript (no leading npm noise).
- [ ] On failure (no session found, non-zero exit code, I/O error), a warning/error dialog is
      shown and details are logged to the "Servoy AI Console"; no partial/corrupt file is
      written.
- [ ] `ExportSessionJobTest` (new) and the new `parseReportedSessionId` test pass; all
      existing `com.servoy.eclipse.opencode.tests` pass unchanged.
- [ ] Zero compilation errors in `com.servoy.eclipse.opencode` and
      `com.servoy.eclipse.opencode.tests`.

## 6. Out of scope

- Any `opencode.db` table-crawling logic (the ticket's literal proposal — rejected in triage).
- The REST-based alternative (`GET /session/:id/message` assembled in Java) — noted in triage
  as a reasonable fallback for a future fully server-side/automated flow, not needed here.
- Exposing export as an MCP tool callable by an agent (this spec is for the human-facing
  toolbar button only).
- Making `--sanitize` optional/configurable.
- Extracting a shared `BrowserFunctionWrapper`-style abstraction into a common bundle for
  `com.servoy.eclipse.opencode` — deferred until a second callback need arises here (§3.7).

## 7. Open questions

None outstanding. Both prior open questions are resolved by product decision and folded into
this spec:
- Track the exact session shown in the embedded browser instead of "most recently modified
  session" → implemented via the JS hook + `BrowserFunction` in §3.7.
- Offer a way to jump to the exported file, not just show its path → implemented via the
  "Show in Explorer" button in §3.6.
