# Triage Report — SVY-21709

**Verdict:** PROCEED

## Reported problem
In the Servoy AI chat view (Servoy Developer), a chat message can carry an inline image
attachment (a pasted/uploaded image, or a rendered screenshot). When the user **clicks**
that inline thumbnail, a **new OS window opens completely blank** — the image is never
shown enlarged. The attached screenshot (`openedImageIsBlank.png`) shows the chat message
"this is aa test image" with its small thumbnail, and a separate empty top-level window
(title bar + minimize/maximize/close, no content) that appeared on click.

Reproduction (from the ticket):
1. Attach an image to a message in a session.
2. Click the image in the session.
3. The image view opens blank; the image is not shown.
4. Expected: the image opens and displays.

Environment: Servoy 2026.3.2_LTS, "Servoy View" v2.2.0. The user is on their real Servoy
Developer embedded browser (SWT Browser / Equo Chromium). The user also notes the
OpenChamber browser panel can't reproduce it because that panel simply blocks dialogs/new
windows — so it is not a useful repro surface; the real defect is the blank window in the
embedded Developer browser.

The ticket proposes no solution — it only describes the symptom and the expectation.

## Root-cause assessment
The defect is in the Angular chat frontend (`com.servoy.eclipse.opencode/webui/`), in how
an inline image attachment is made "clickable to enlarge".

`webui/src/app/components/message-item.component.html` (lines 8–12) renders an image
attachment as a plain anchor that opens the image URL in a **new browser window/tab**:

```html
@if (isImageFile(part)) {
  <a class="attachment-image-link" [href]="fileUrl(part)" target="_blank" rel="noopener" [title]="fileName(part)">
    <img class="attachment-image" [src]="fileUrl(part)" [alt]="fileName(part)" />
  </a>
}
```

- `fileUrl(part)` (`message-item.component.ts:113`) returns `part.url`, which for an inline
  image is a **`data:image/...;base64,...` URL**. This is how both just-sent
  (optimistic) attachments and reloaded ones are built:
  - composer: `readImageFile()` uses `FileReader.readAsDataURL(file)` and stores the
    resulting `data:` URL as the attachment `url` (`composer.component.ts:183-194`).
  - reload: `v2-mapping.ts:103-104` builds `url = \`data:${mime};base64,${data}\`` from the
    V2 user message's `files[]` array.
- The "enlarge" interaction is therefore a **top-level navigation of a new window to a
  `data:` URL**, via `target="_blank"`.

Why the window is blank **in the embedded Eclipse browser specifically**:
- `target="_blank"` makes the browser request a **new window**. In the embedded browser
  (SWT `Browser` / Equo Chromium hosted inside Eclipse, via
  `com.servoy.eclipse.ui.browser.IBrowser`), there is **no `OpenWindowListener` /
  new-window handler wired up** in the opencode bundle — a search of
  `com.servoy.eclipse.opencode/src` for `OpenWindowListener`/`addOpenWindowListener`/
  new-window handling found **nothing**. So the embedded browser spawns a bare, unmanaged
  top-level window and nothing navigates it to the target URL → the empty shell the user
  sees.
- Even where a new window is created, modern engines **block/ignore top-level navigation
  to a `data:` URL** (a well-known anti-phishing/anti-abuse restriction), and such URLs can
  be large, so the new window ends up blank rather than showing the image.
- This is why it "works" in a full external browser (which has proper new-tab handling) but
  not in the embedded Developer browser, and why OpenChamber (which blocks new windows
  outright) shows nothing at all. The behaviour is **environment-dependent on the embedded
  browser's new-window handling**, which the current markup relies on.

Net: the click-to-enlarge was implemented as "open the data URL in a new tab, like
OpenChamber" but that approach does not survive the embedded Eclipse browser. The image
itself renders fine inline (the `<img>` thumbnail displays); only the enlarge-on-click is
broken.

## Ticket premise check
The ticket proposes no fix, so there is no proposed approach to challenge — only the
symptom and the expectation ("the image opens and displays"). The symptom is real and the
expectation is reasonable: clicking a thumbnail should enlarge the image. The premise that
this is a Servoy-side (webui) defect holds: it is the chat UI's own markup
(`target="_blank"` on a `data:` URL) that depends on embedded-browser new-window handling
that isn't there.

## Approaches considered
1. **In-app lightbox / overlay (recommended)** — replace the open-in-new-tab anchor with a
   click handler that shows the image enlarged in an **overlay inside the same Angular
   document** (a modal/lightbox: a full-size `<img>` on a dimmed backdrop, dismissible by
   click/Escape). No new window, no top-level `data:` navigation, so it is independent of
   the embedded browser's new-window handling and works identically in SWT Browser, Equo
   Chromium, external browsers, and OpenChamber.
   - Pros: fixes the symptom at its source; consistent across all browser backends; keeps
     everything same-origin/same-document; small, self-contained change in `webui/`
     (`message-item` + a tiny overlay, or a shared component); accessible (focus trap,
     Escape, alt text already present).
   - Cons: a little new UI code and a Vitest test; need to handle Escape/backdrop
     dismissal and `OnPush`/signal state for "which image is open".
2. **Wire an `OpenWindowListener` / new-window handler into the embedded browser** so
   `target="_blank"` opens a real, navigated window (add the capability to `IBrowser` and
   both wrappers in `com.servoy.eclipse.ui`).
   - Pros: would make new-window links generally work in the embedded browser.
   - Cons: much larger blast radius (touches the browser abstraction used well beyond this
     feature); still runs into engines blocking top-level `data:` navigation, so the window
     could stay blank anyway; crosses out of the webui into shared Eclipse UI code for what
     is really a UI-interaction choice. Wrong layer for this bug.
3. **Open the enlarged image in a new same-origin route served by the BFF** (e.g. navigate
   to a servlet URL that streams the image) instead of a `data:` URL.
   - Pros: avoids the `data:`-URL navigation block.
   - Cons: still relies on new-window handling in the embedded browser (the blank-window
     half of the bug); needs new BFF plumbing to stash/stream the bytes; heavier than an
     in-document overlay for no extra user benefit.
4. **No code change needed** — declare it a limitation of the embedded browser.
   - Pros: zero work.
   - Cons: the symptom is a real, reproducible defect the user hit in the shipping product;
     the feature ("clickable to open full size") is advertised in the code comments but
     doesn't work in the actual runtime browser. Leaving it blank-on-click is a broken
     feature, not acceptable expected behaviour. Rejected.

## Recommendation
**PROCEED** with **Approach 1 — an in-app lightbox/overlay** in the Angular `webui/`.
Replace the `target="_blank"` anchor in `message-item.component.html` with a click that
opens the image enlarged in an in-document overlay (dimmed backdrop, dismiss on
click/Escape), so no new OS window and no top-level `data:` navigation are involved. This
fixes the root cause (reliance on embedded-browser new-window handling) and behaves the
same across SWT Browser, Equo Chromium, external browsers, and OpenChamber.

Alternatives, ranked: Approach 3 (same-origin served image) only if a non-overlay,
full-window view is specifically desired — but it still needs new-window handling, so it
doesn't fully solve the blank window; Approach 2 (browser-abstraction new-window listener)
is the wrong layer and highest risk. Approach 4 (no change) is rejected.

Scope note: keep the change confined to `webui/` (use `angular-cli_*` tools + generic file
tools + Vitest, per project conventions — the Eclipse JDT/PDE tools do not apply in this
subtree). Add/adjust a Vitest spec in `message-item.component.spec.ts` to cover the new
open/close behaviour.

## Git history findings
- The clickable image attachment was introduced in commit **`1614a0f`**
  ("SVY-21461 show image/file attachments in the transcript [ai]", Johan Compagner,
  2026-10-09). Its message explicitly states the design: *"message-item renders an image
  attachment as a compact thumbnail above the message (clickable to open full size, like
  OpenChamber)"*. So the broken behaviour is the original implementation of this feature,
  not a later regression — the "open full size" was coded as an open-in-new-tab anchor from
  the start.
- The `data:` URL construction it relies on comes from the same commit
  (`v2-mapping.ts` reload path) and from the composer's `readAsDataURL` paste/upload path.
- No prior spec for SVY-21709 exists in `docs/`. No regression window / dependency bump is
  involved — this is a design choice in the feature's own first version, exposed by the
  embedded Eclipse browser's lack of new-window handling.
- A search of `com.servoy.eclipse.opencode/src` confirms **no `OpenWindowListener` or
  new-window handler** is wired for the embedded browser, consistent with the blank-window
  symptom.
