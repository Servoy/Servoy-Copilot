# Spec: SVY-21788 — Block native browser UI in the Servoy AI view

## 1. Goal
The Servoy AI view must not behave like a web browser. Inside the embedded page:
- No native context menu appears anywhere, including the composer and over text selections.
- The mouse back/forward side buttons (buttons 3 and 4) do not navigate the page.
- Spell check is off in the composer and other free-text inputs.
- The session-list custom context menu keeps working. Ctrl+C/V/X keep working.

## 2. Background
Triage (`docs/SVY-21788-triage.md`) found that all three symptoms are default Chromium behaviour. Nothing in `webui/` turns them off.
- There is no global `contextmenu` handler. The only `(contextmenu)` bindings are in `session-list.component.html`: the custom menu (`onContextMenu`, which already calls `event.preventDefault()`) and the backdrop (`$event.preventDefault(); closeMenu()`).
- `composer.component.html` has `<textarea #textarea class="input" ...>` with no `spellcheck` attribute. Other free-text inputs are in `form-card.component.html` (several `<input>`) and the session rename `<input class="rename-input">` in `session-list.component.html`. `<input type="file" hidden>` and `type="checkbox"` need no change.
- Nothing intercepts mouse buttons 3/4, so Chromium navigates history.

The approved approach is authoritative: a webui-only fix, with no Java and no servoy-eclipse change. It blocks the native menu everywhere, with no Cut/Copy/Paste menu and no replacement menu. This deliberately overrides the triage suggestion to keep the menu in editable fields and over selections.

## 3. Design

### 3.1 New helper `services/native-ui-guard.ts`
A plain function `installNativeUiGuard(target: Document = document): () => void` that registers listeners and returns a disposer. It does not use an Angular service, so it can be installed in `main.ts` before bootstrap, like `ThemeService.applyResolved()`.

Listeners, all on `target`:
1. `contextmenu`: call `event.preventDefault()`. It is registered in the bubble phase on `document`. Component handlers on elements run first, so the session-list `onContextMenu` still runs and opens its custom menu. Calling `preventDefault` a second time is harmless. Do NOT call `stopPropagation` or `stopImmediatePropagation`.
2. `mouseup` and `auxclick` with `event.button === 3 || event.button === 4`: call `preventDefault()`. Register `mousedown` and `pointerup` the same way for the same buttons. Use the capture phase for these listeners.
3. `keydown`: if `event.altKey && !ctrlKey && !metaKey && !shiftKey` and `key` is `ArrowLeft` or `ArrowRight`, call `preventDefault()` (see the judgement call below).

Listeners that call `preventDefault` must not be passive.

### 3.2 Judgement call: Alt+Left/Alt+Right
Chromium uses these shortcuts for history back/forward. They are the keyboard equivalent of the side buttons, so the spec blocks them. Risk: the composer textarea does not use Alt+Arrow for word movement on Windows (that is Ctrl+Arrow), and macOS Option+Arrow is `altKey` but is a text-navigation gesture. To avoid breaking macOS word navigation, skip the block when the event target is an editable element (`textarea`, `input`, `[contenteditable]`) AND `navigator.platform` indicates macOS. Simpler alternative if the reviewer prefers: block unconditionally on Windows/Linux only. See open questions. The default implemented behaviour: block Alt+Left/Right except in editable fields on macOS.

### 3.3 Spell check
Add `spellcheck="false"` to:
- the composer `<textarea #textarea ...>` in `composer.component.html`
- the session rename `<input #renameInput type="text" ...>` in `session-list.component.html`
- every free-text `<input>` in `form-card.component.html` (text-like types only; skip checkboxes)

### 3.4 Wiring
In `main.ts`, call `installNativeUiGuard()` right after `new ThemeService().applyResolved()`, before `bootstrapApplication`. The disposer is not needed in production, but it is returned so tests can clean up.

### 3.5 Keyboard shortcuts
Ctrl+C/V/X are `keydown` events and are never touched, since the guard only handles `contextmenu`, mouse buttons 3/4 and Alt+Arrow. Cut/Copy/Paste work from the keyboard only, which is the intended consequence of blocking the menu everywhere.

### 3.6 Git history
- `composer.component.html` was last changed in `303ef76` (SVY-21848, command menu) and earlier in SVY-21461 work. Neither touched spell check or the context menu, so this is not a regression.
- The `(contextmenu)` handlers come from `e85f599` (SVY-21461, session sidebar lifecycle). Preserve their behaviour.
- No prior spec exists for SVY-21788.

## 4. Implementation plan
1. Create `bundles/com.servoy.eclipse.opencode/webui/src/app/services/native-ui-guard.ts` per 3.1 and 3.2. No code comments; follow the file style of neighbouring services.
2. Edit `webui/src/main.ts`: import and call `installNativeUiGuard()` before bootstrap.
3. Add `spellcheck="false"` to the composer textarea, the rename input and the free-text inputs in `form-card.component.html` (3.3).
4. Add `services/native-ui-guard.spec.ts` (Vitest, matching the existing `*.spec.ts` style). Each test installs the guard on `document`, dispatches events, and disposes in `afterEach`:
   - a cancelable `contextmenu` on a div is `defaultPrevented`
   - a `contextmenu` on a `textarea` and on an `input` is `defaultPrevented`
   - a `contextmenu` with a non-empty text selection is `defaultPrevented`
   - a child element's own `contextmenu` listener still fires, and propagation is not stopped
   - `mouseup`/`auxclick`/`mousedown`/`pointerup` with button 3 and 4 are prevented; buttons 0, 1 and 2 are not
   - Alt+ArrowLeft and Alt+ArrowRight are prevented; ArrowLeft alone, Ctrl+ArrowLeft and Ctrl+C/V/X `keydown` are not
   - the disposer removes all listeners: after disposal nothing is prevented
5. Extend `composer.component.spec.ts`: the textarea has `spellcheck="false"`. Extend `form-card.component.spec.ts` if the text inputs are easily reachable; add a check that the rename input has `spellcheck="false"` if a session-list spec exists (there is none today, so skip it if it would need heavy setup).
6. Verify from `bundles/com.servoy.eclipse.opencode/webui/`:
   - `npm test`
   - `ng lint` (if configured)
   - `ng build --configuration production`
7. Manual check in the Servoy AI view (dev watch build per AGENTS.md): right-click on a blank area, the sidebar item (custom menu opens, no native menu), the composer, selected text and a link; a mouse with back/forward buttons; Alt+Left; a misspelled word in the composer (no red underline); Ctrl+C/V/X in the composer.

## 5. Acceptance criteria
- [ ] Right-click anywhere in the chat UI shows no native Chromium menu (no Back/Forward/Print/View source, no Cut/Copy/Paste, no spelling suggestions).
- [ ] Right-click on a session item still opens the custom session menu, and the backdrop still closes it.
- [ ] Mouse buttons 3 and 4 do not navigate the page.
- [ ] Alt+Left/Alt+Right do not navigate history (except the macOS editable-field exception).
- [ ] The composer, the rename input and the form-card text inputs have `spellcheck="false"`; no red underlines.
- [ ] Ctrl+C, Ctrl+V and Ctrl+X still work in the composer.
- [ ] New Vitest tests pass, the full `npm test` suite passes, and the production build succeeds.
- [ ] No Java or servoy-eclipse files changed.

## 6. Out of scope
- Any change to `OpenCodeView`, `IBrowser`, or the Chromium/SWT wrappers (servoy-eclipse).
- A custom replacement context menu or Cut/Copy/Paste menu.
- Blocking DevTools, F5/Ctrl+R reload, or other browser shortcuts.
- The `?debug_view=true` overlay and the theme code.

## 7. Open questions
| Question | Owner | Status |
|---|---|---|
| Alt+Left/Right: block unconditionally, or keep the macOS editable-field exception? | Reviewer | Open (default: exception as in 3.2) |
| With no Cut/Copy/Paste menu, mouse-only users cannot paste; accepted by the approved approach? | Product | Accepted (approved approach) |
