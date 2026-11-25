# Triage Report — SVY-21788

**Verdict:** PROCEED

## Reported problem
The Servoy AI view (embedded browser hosting the Angular chat UI at `/servoy_ai/`) behaves like a web browser:

1. Right-click anywhere shows the native Chromium context menu (Back / Forward / Print... / View page source). See attachment `image-20261112-155317.png`.
2. (Comment) The mouse back/forward side buttons (buttons 3/4) navigate the embedded page back/forward.
3. (Comment) Spell check is on: misspelled words in the composer get a red underline, and right-click offers correction options. See attachment `image-20261123-141332.png`.

The ticket has no proposed solution. It only says the menu is "unnecessary".

## Root-cause assessment
All three symptoms are default browser-engine behaviour of the embedded Chromium page. Nothing in our code turns them on, and nothing turns them off:

- `OpenCodeView` creates the browser via `BrowserFactory.createBrowser(parent)` and only uses `IBrowser` calls (`setUrl`, `setText`, ...). It sets no context menu and no browser settings. `IBrowser` has no capability for disabling the context menu, and that abstraction is owned by `com.servoy.eclipse.ui` in servoy-eclipse, not this repo.
- The Angular app (`bundles/com.servoy.eclipse.opencode/webui`) has no global `contextmenu` handler. The only `(contextmenu)` bindings are in `session-list.component.html`, for the custom session menu and its backdrop. That custom menu already calls `preventDefault`, so it works. Everywhere else the native menu appears.
- The composer `<textarea class="input">` in `composer.component.html` (line 48) has no `spellcheck="false"`. Chromium therefore spell-checks it by default.
- Nothing intercepts the mouse back/forward buttons. In Chromium these buttons trigger history navigation. The page is a SPA with `baseHref=/servoy_ai/`. Navigating "back" can leave or reload the app, and it loses UI state.

The problem is in this repo's front-end (`webui/`), and a front-end fix is enough. The screenshot shows Chromium (Equo), but a DOM-level fix also covers the SWT-browser backend.

I did not run the app. This is based on code reading and the screenshots. I could not view the downloaded images (they are saved in the temp dir as `a1.png` and `a2.png`), so I relied on the reporter's text and annotations for their content.

## Ticket premise check
- The problem is real and is in this project's UI, not user-side. It is minor (Minor priority), not a bug in the logic.
- There is no proposed solution to challenge. The scope is wider than the title, because the comment adds the back/forward buttons and spell check. The same fix should cover all three.
- Possible design question for the implementer: should right-click still offer Cut/Copy/Paste in the composer and on selected text? A blanket `preventDefault` removes those too, and users expect copy/paste there. The sensible default is to suppress the menu everywhere except editable fields and non-empty text selections. If even that menu is unwanted, suppress it as well. This is a small UX choice and does not block the work.

## Approaches considered
1. **Front-end (webui) fix — recommended.**
   - Add a document-level `contextmenu` listener (installed in `main.ts`/the root component, before bootstrap or in the root component's init).
     - Call `preventDefault()` unless the target is an editable element (`textarea`, `input`, `contenteditable`) or there is a non-empty text selection.
     - Skip it when the event is already `defaultPrevented`, or the session-list custom menu handles it (that menu already prevents default).
   - Add a document-level `mouseup`/`auxclick`/`pointerup` handler that calls `preventDefault()` for `button === 3 || button === 4`. Also consider `popstate` guards if needed.
   - Add `spellcheck="false"` on the composer textarea, and on the other text inputs (`form-card`, the session rename input).
   - Pros: all code is in this repo, it is backend-agnostic (Chromium and SWT), and it is testable with Vitest. It is small and low risk.
   - Cons: it is a page-level mitigation, and the browser still has the capability. DevTools or keyboard shortcuts such as Alt+Left are separate paths. Alt+Left/Right history navigation should also be checked and can be handled in a `keydown` listener.
2. **IBrowser / Chromium settings fix in servoy-eclipse (`com.servoy.eclipse.ui`).** Extend `IBrowser` and both wrappers (`SwtBrowserWrapper`, `ChromiumWrapper`) with a "disable context menu / spell check / history navigation" capability.
   - Pros: it removes the behaviour at the source for all embedded browser users.
   - Cons: it crosses repos and needs a servoy-eclipse change plus a coordinated release. It would also affect other browser views (designer, etc.), which is risky. It is overkill for a Minor case.
3. **No code change.**
   - Pros: nothing to maintain.
   - Cons: the view feels like a browser, "View page source" and "Print" leak, and back/forward can destroy the chat UI state. The reporter considers this a defect, so this is not recommended.

## Recommendation
Go with approach 1, as a webui-only change:

- Add a small `suppress-native-browser-ui` helper, wired once at app start, with Vitest tests. Cover: the contextmenu event on a non-editable target is prevented; on a textarea, input, or active selection it is not prevented; button 3/4 is prevented; the session-list custom menu is unaffected.
- Add `spellcheck="false"` on the composer textarea (and other free-text inputs, as a judgement call).
- Verify manually in the embedded view with the dev watch build (`ng build --watch --configuration development`): right-click on blank area, in the sidebar, and in the composer; a mouse with back/forward buttons; a misspelled word in the composer.
- Check `npm test` in `webui/` before committing.

Before editing, read `session-list.component.ts` `onContextMenu` and its spec so the global handler does not conflict with it. No Java change is needed.

## Git history findings
- `composer.component.html` last changed in `303ef76` (SVY-21848, /svy command menu) and before that in SVY-21461 work. None of these touched spellcheck or context-menu handling, so this is not a regression. The native behaviour has been there since the chat UI was introduced (SVY-21461).
- The `(contextmenu)` handlers in `session-list.component.html` come from the session sidebar lifecycle feature (`e85f599`, SVY-21461).
- No prior spec in `docs/` exists for SVY-21788.
