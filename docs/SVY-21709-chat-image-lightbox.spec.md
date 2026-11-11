# Spec: SVY-21709 — Attached image opens blank when clicked in a session

## 1. Goal
In the Servoy AI chat (Servoy Developer), clicking an inline image attachment in a message
currently opens a **blank new OS window** instead of showing the image enlarged. Replace the
open-in-a-new-tab behaviour with an **in-app lightbox/overlay**: clicking the thumbnail shows
the image enlarged on a dimmed backdrop, inside the same Angular document, dismissible by
clicking the backdrop or pressing Escape. This fixes the symptom at its root — no new OS
window is spawned and no top-level `data:` URL navigation occurs — so enlarge-on-click works
identically across the SWT Browser, Equo Chromium, external browsers, and OpenChamber.

## 2. Background

### 2.1 Current behaviour (the defect)
`message-item.component.html` (lines 8–12) renders an image attachment as a plain anchor that
opens the image URL in a **new browser window/tab**:

```html
@if (isImageFile(part)) {
  <a class="attachment-image-link" [href]="fileUrl(part)" target="_blank" rel="noopener" [title]="fileName(part)">
    <img class="attachment-image" [src]="fileUrl(part)" [alt]="fileName(part)" />
  </a>
}
```

`fileUrl(part)` (`message-item.component.ts:113`) returns `part.url`, which for an inline image
is a **`data:image/...;base64,...` URL** (built by the composer's `FileReader.readAsDataURL`
paste/upload path and by the reload path in `v2-mapping.ts`). So the "enlarge" interaction is a
**top-level navigation of a new window to a `data:` URL** via `target="_blank"`.

### 2.2 Why the window is blank in the embedded Eclipse browser
- `target="_blank"` asks the browser for a **new window**. The embedded browser (SWT `Browser`
  / Equo Chromium, via `com.servoy.eclipse.ui.browser.IBrowser`) has **no new-window handler**
  (`OpenWindowListener`) wired up in the opencode bundle, so it spawns a bare, unmanaged
  top-level window that nothing navigates → the empty shell the user sees.
- Even where a new window is created, modern engines **block/ignore top-level navigation to a
  `data:` URL** (anti-phishing restriction), so the window stays blank.
- This is environment-dependent on the embedded browser's new-window handling, which the
  current markup relies on — it happens to "work" in a full external browser and shows nothing
  at all in OpenChamber (which blocks new windows outright).

The image itself renders fine inline (the `<img>` thumbnail displays). Only the
enlarge-on-click is broken.

### 2.3 Git history
- The clickable image attachment was introduced in commit **`1614a0f`**
  ("SVY-21461 show image/file attachments in the transcript [ai]", 2026-10-09). Its message
  states the design: *"message-item renders an image attachment as a compact thumbnail above the
  message (clickable to open full size, like OpenChamber)"*. So the broken behaviour is the
  **original implementation** of this feature, not a later regression — "open full size" was
  coded as an open-in-new-tab anchor from the start.
- The `data:` URL construction it relies on comes from the same commit (`v2-mapping.ts` reload
  path) and from the composer's `readAsDataURL` paste/upload path.
- No prior spec for SVY-21709 exists in `docs/`. No dependency bump / regression window is
  involved — this is a design choice in the feature's own first version, exposed by the embedded
  Eclipse browser's lack of new-window handling.

### 2.4 Project conventions for the Angular chat UI (`webui/`)
Angular 22, **standalone** components, **zoneless** change detection (no reliance on zone.js),
**signals** everywhere, `ChangeDetectionStrategy.OnPush`, SCSS, **Vitest** for unit tests. Not
part of the Eclipse/JDT/OSGi world — use the Angular CLI, generic file tools, and
`npm`/`vitest`. The `MessageItemComponent` already holds collapse/expand UI state in a private
`signal<Set<string>>` and exposes `is*`/`toggle*` helpers the template binds to — the lightbox
follows the same pattern.

## 3. Design

### 3.1 Where the lightbox lives and how its state is held
Keep the change **confined to `message-item`** (the only place inline image attachments are
rendered), rendering the overlay **inline in `message-item.component.html`** (gated by a signal)
rather than introducing a new shared component. This keeps the blast radius minimal and matches
how the component already owns its own interaction state.

Open state is a **signal on `MessageItemComponent`** holding the URL (and alt text / filename)
of the image currently enlarged, or `null` when the lightbox is closed:

```ts
/** The image currently shown enlarged in the lightbox, or null when closed. */
private readonly lightbox = signal<{ url: string; alt: string } | null>(null);

/** The open image for the template (null = lightbox closed). */
readonly lightboxImage = computed(() => this.lightbox());

/** Open the lightbox for an image attachment part. */
openLightbox(part: Part): void {
  this.lightbox.set({ url: this.fileUrl(part), alt: this.fileName(part) });
}

/** Close the lightbox. */
closeLightbox(): void {
  this.lightbox.set(null);
}
```

A single `lightbox` signal is sufficient — only one image is enlarged at a time, and it is
scoped to this message item. Because the component is `OnPush` + signal-driven, setting/clearing
the signal updates the view without any zone.js reliance (zoneless-safe).

### 3.2 Markup change (replace the anchor)
Replace the `target="_blank"` anchor with a **`<button>`** (keyboard-focusable, no navigation
semantics) wrapping the thumbnail, whose click opens the lightbox. Keep the thumbnail `<img>`
markup/classes as-is so the inline appearance does not change:

```html
@if (isImageFile(part)) {
  <button
    type="button"
    class="attachment-image-link"
    [title]="fileName(part)"
    (click)="openLightbox(part)"
  >
    <img class="attachment-image" [src]="fileUrl(part)" [alt]="fileName(part)" />
  </button>
}
```

The overlay is rendered once per message item, after the parts loop, only when `lightboxImage()`
is non-null:

```html
@if (lightboxImage(); as img) {
  <div
    class="image-lightbox"
    role="dialog"
    aria-modal="true"
    [attr.aria-label]="img.alt"
    (click)="closeLightbox()"
    (keydown.escape)="closeLightbox()"
    tabindex="-1"
    #lightboxEl
  >
    <!-- stopPropagation so a click on the image itself does not dismiss -->
    <img class="image-lightbox-img" [src]="img.url" [alt]="img.alt" (click)="$event.stopPropagation()" />
    <button
      type="button"
      class="image-lightbox-close"
      aria-label="Close image"
      (click)="closeLightbox()"
    >✕</button>
  </div>
}
```

Notes:
- Dismissal: click on the **backdrop** (the overlay container) closes it; a click on the image
  itself is stopped from propagating so it does not close. A dedicated **close button** (✕) and
  **Escape** also close it.
- `(keydown.escape)` on the overlay element handles Escape when the overlay holds focus; see
  §3.3 for ensuring it receives focus.
- `role="dialog"` + `aria-modal="true"` + `aria-label` (from the image's alt/filename) describe
  the modal to assistive tech. The enlarged `<img>` keeps its `alt`.

### 3.3 Focus management and accessibility
- When the lightbox opens, move focus to the overlay (or its close button) so Escape works and
  focus is not left behind on the now-hidden thumbnail. Use a `viewChild`/`effect` or an
  `afterNextRender`-style hook that focuses the overlay element once it is in the DOM. Because
  the overlay has `tabindex="-1"`, it is programmatically focusable.
- When the lightbox closes, return focus to the triggering thumbnail button (best-effort — track
  the last trigger element, or re-focus is acceptable to drop if it complicates the zoneless
  path; focus-return is a nice-to-have, Escape/backdrop dismissal and open-focus are the
  must-haves).
- The overlay listens for Escape via Angular's `(keydown.escape)` host-less binding on the
  focused element. (A document-level listener is an acceptable alternative if simpler, but keep
  it zoneless-safe and torn down when closed — the template `@if` already removes the element and
  its bindings on close, so the template binding is preferred.)
- The close button and backdrop are both operable by keyboard (button is focusable; Escape
  covers dismissal).

### 3.4 Styling (`message-item.component.scss`)
Add a fixed, full-viewport overlay drawn above everything, with a dimmed backdrop and the image
centered and scaled to fit:

```scss
.image-lightbox {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 2rem;
  background: rgba(0, 0, 0, 0.75);
  cursor: zoom-out;
}

.image-lightbox-img {
  max-width: 100%;
  max-height: 100%;
  width: auto;
  height: auto;
  object-fit: contain;
  border-radius: 8px;
  cursor: default;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.5);
}

.image-lightbox-close {
  position: fixed;
  top: 1rem;
  right: 1rem;
  // transparent button, theme colors via the existing --svy-* variables
  ...
}
```

Also change the thumbnail wrapper styling so the `.attachment-image-link` button has no default
button chrome (reset `border`, `background`, `padding`; keep `cursor: pointer` and
`display: inline-block; line-height: 0`), preserving the current thumbnail look now that it is a
`<button>` rather than an `<a>`. Update the stale SCSS comment that says "clickable to open the
full image in a new tab".

### 3.5 What does NOT change
- `fileUrl`, `fileName`, `isImageFile`, `isFile` keep their current behaviour and signatures.
- The inline thumbnail appearance (size, border, radius) is unchanged.
- Non-image attachment chips are untouched.
- No new window, no `target="_blank"`, no top-level navigation to a `data:` URL anywhere for
  inline images.

## 4. Implementation plan
1. **`message-item.component.ts`** — add the private `lightbox` signal, the `lightboxImage`
   computed, and the `openLightbox(part)` / `closeLightbox()` methods. Add focus management for
   opening the overlay (and best-effort focus-return on close). Keep `OnPush` and avoid any
   zone.js reliance.
2. **`message-item.component.html`** — replace the `target="_blank"` anchor with a focusable
   `<button>` that calls `openLightbox(part)`; add the conditional overlay block
   (`role="dialog"`, `aria-modal`, backdrop click + close button + Escape to dismiss, image
   click `stopPropagation`).
3. **`message-item.component.scss`** — add `.image-lightbox`, `.image-lightbox-img`,
   `.image-lightbox-close` styles; reset the `.attachment-image-link` button chrome so the
   thumbnail looks unchanged; fix the stale "new tab" comment.
4. **`message-item.component.spec.ts`** — add a Vitest suite covering open-on-click, close on
   backdrop click, close on Escape, and that no `target="_blank"`/anchor new-window navigation
   remains for inline images (see §5).
5. **Build** — produce a clean production build (`npm run build` in `webui/`, output to
   `../webui-dist`) once implemented; run `npm test` (Vitest) to verify green.

## 5. Acceptance criteria
- [ ] Clicking an inline image thumbnail in a message opens the image **enlarged in an in-document
      overlay** (dimmed backdrop), with **no new OS window** and **no top-level navigation** to a
      `data:` URL.
- [ ] The overlay is dismissed by **clicking the backdrop** and by **pressing Escape**; a visible
      **close button** also dismisses it. Clicking the enlarged image itself does **not** dismiss it.
- [ ] The inline image markup no longer uses `target="_blank"` (nor any anchor that opens a new
      window) for image attachments; non-image attachment chips are unchanged.
- [ ] The overlay is accessible: `role="dialog"`, `aria-modal="true"`, an `aria-label`/alt from
      the image, focus moves into the overlay on open, and it is keyboard-dismissible (Escape).
- [ ] The inline thumbnail's visual appearance (size, border, radius) is unchanged.
- [ ] The behaviour is identical across SWT Browser, Equo Chromium, external browsers, and
      OpenChamber (verified by the design: no new-window / `data:`-navigation dependency).
- [ ] A **Vitest spec** in `message-item.component.spec.ts` covers: (a) clicking the thumbnail
      opens the lightbox (overlay present in the DOM with the image `src`); (b) clicking the
      backdrop closes it (overlay removed); (c) pressing Escape closes it; (d) the rendered image
      attachment contains **no** element with `target="_blank"` / no new-window anchor.
- [ ] `npm run build` (production) succeeds and `npm test` (Vitest) is green; the change is
      confined to `webui/`.

## 6. Out of scope
- Wiring an `OpenWindowListener`/new-window handler into the embedded browser (`IBrowser` and its
  wrappers) — wrong layer, larger blast radius, still hits the `data:`-navigation block.
- Serving the enlarged image from a same-origin BFF route instead of a `data:` URL — still relies
  on new-window handling; not needed for an in-document overlay.
- Pan/zoom, carousel/multi-image navigation, download button, or any gallery feature — a single
  enlarged image with dismiss is sufficient for this bug.
- Any change to non-image file attachments, the composer, or the reload/`data:` URL construction.
- Server-side / Java changes of any kind.

## 7. Open questions
| Question | Owner | Status |
|----------|-------|--------|
| Should focus return to the triggering thumbnail on close, or is open-focus + Escape/backdrop dismissal enough? (Spec treats focus-return as a nice-to-have.) | Dev/Reviewer | open |
| Is a document-level Escape listener preferred over the template `(keydown.escape)` binding if the overlay focus hook proves awkward in the zoneless setup? | Dev | open |
