# Spec: SVY-21708 — Copy button on code blocks in AI answers

## 1. Goal
Restore the per-code-block "copy" button in the Servoy AI chat UI. Each rendered code
block (`<pre>`) in an assistant answer must show a small copy button in its top-right
corner; clicking it copies that block's code to the clipboard. The affordance existed on
2026.06 (when the embedded view used opencode's built-in web UI) and was lost when the
chat UI was replaced with our own Angular app in SVY-21461. This is a minor-priority,
low-risk, Angular-only change that re-adds a small but expected convenience for the many
code snippets Servoy AI produces (SQL, JS, shell, etc.).

## 2. Background

### 2.1 Where code blocks are rendered
`webui/src/app/components/markdown-renderer.component.ts` (`svy-markdown`) is the single
place code blocks are emitted. It:
1. parses the assistant text with `marked` (`gfm: true, breaks: true`),
2. sanitizes the HTML with DOMPurify,
3. writes the result into a host `<div class="markdown-body">` via `innerHTML`,
4. walks every `pre code` block and runs `hljs.highlightElement(...)` over it.

This all happens inside a single Angular `effect()` that re-runs whenever the `content`
signal changes — which is how streaming works: the effect re-renders as the text grows.
The component uses `ChangeDetectionStrategy.OnPush` and `ViewEncapsulation.None` (its SCSS
is global, hence the `.markdown-body`-scoped selectors). It is the only consumer-facing
markdown renderer; `message-item.component.ts` is its only user (it renders both assistant
text parts and the `execute`/script tool source as `svy-markdown`).

The renderer currently produces plain `<pre><code>…</code></pre>` with **no** copy
affordance — there is no clipboard call anywhere in `webui/src/app`.

### 2.2 Theme tokens
Styling must use the existing CSS custom properties defined in `webui/src/styles.scss`
(`:root` for light, `:root[data-theme='dark']` for dark); never hardcode colors. The theme
is applied in `main.ts` before bootstrap from the `?darkmode=` URL param
(`theme.service.ts`), so a component only has to reference the tokens. Relevant tokens:
`--svy-fg`, `--svy-muted`, `--svy-border`, `--svy-accent`, `--svy-pre-bg`, `--svy-pre-fg`,
`--svy-surface`, `--svy-hover`, `--svy-mono`. The screenshot attached to the ticket
(`copyButtonMissing.png`) shows a dark-theme SQL block with an arrow pointing at the empty
top-right corner — confirming position (top-right, inside the block) and that dark theme
must look right.

### 2.3 Embedded-browser clipboard constraint
The UI runs inside the Eclipse embedded browser (SWT `Browser` / Equo Chromium) served
same-origin over `http://127.0.0.1:<port>/servoy_ai/`. `navigator.clipboard.writeText` is
only available in secure contexts / when the backend grants clipboard permission, which is
not guaranteed in the embedded browser. The copy action therefore needs a fallback
(a hidden `<textarea>` + `document.execCommand('copy')`) when the async Clipboard API is
unavailable or rejects.

### 2.4 Git history
From the SVY-21708 triage report ("Git history findings"):
- `dfb0039` — "SVY-21461 Replace opencode web view with custom Angular chat UI on
  opencode-cli [ai]" (2026-09-15) is the introducing change. It swapped opencode's own
  web UI (which shipped a per-code-block copy button) for this in-repo Angular app and
  removed the OpenCodeBranding CSS/JS injection and the opencode SPA URL scraping.
- `markdown-renderer.component.ts` has existed only since that commit and never contained
  copy-button logic. So this is a **never-ported feature**, not a later breakage; the
  regression window is exactly the 2026.06 → post-SVY-21461 UI swap, matching the
  reporter's "it used to work on 2026.06."

## 3. Design

### 3.1 Where the button is injected
After the existing render step (sanitize → `innerHTML` → highlight), iterate over each
`<pre>` in the host element and attach a copy button to it. Keep this inside the same
`render(...)` path (the effect), so the buttons are (re)applied on every streaming
re-render — the DOM is rebuilt from `innerHTML` each time, so there is no accumulation of
stale handlers; a fresh button is created for each `<pre>` on each render. This is the
natural idempotency: because `innerHTML = clean` replaces the whole subtree, every render
starts from button-less markup and adds exactly one button per block.

To make the button position reliably against the block, give each `<pre>` a wrapper or set
the `<pre>` itself to `position: relative` and absolutely-position the button in its
top-right. Setting `position: relative` directly on the `<pre>` (via SCSS on
`.markdown-body pre`) and appending an absolutely-positioned `<button>` as a child of the
`<pre>` is the simplest approach and avoids restructuring the sanitized DOM. The button is
created programmatically (`document.createElement('button')`), **after** DOMPurify has run,
so it is not subject to sanitization and cannot be stripped.

### 3.2 What gets copied (excluding the label)
The copied text must be the code **only**, never the button's own label. Capture the code
text from the block's `<code>` element (`pre.querySelector('code')?.textContent`, falling
back to `pre.textContent`) **before** appending the button, and close over that captured
string in the click handler. Because the string is captured before the button is added to
the DOM, the button's text can never leak into the copied content even if a future change
reads from the DOM. Use `textContent` (not `innerText`/`innerHTML`) so highlight.js markup
and entities resolve to the raw source.

### 3.3 Clipboard write with fallback
On click:
1. Prefer `navigator.clipboard?.writeText(code)` when available; it returns a promise.
2. On absence or rejection, fall back to a hidden `<textarea>`: set its value to `code`,
   append to `document.body`, `select()`, `document.execCommand('copy')`, then remove it.
3. Wrap the whole thing defensively — a copy failure must never throw into the render path.

Provide brief visual feedback: on success, swap the button label/icon to a "Copied!"
state for ~1.5s, then revert (guard the revert so a re-render that removes the button does
not error). Feedback is best-effort and must not depend on Angular change detection (the
handler runs on a plain DOM event, outside Angular) — mutate the button's own
text/attribute directly.

### 3.4 Styling (SCSS, theme-aware)
In `markdown-renderer.component.scss`:
- `.markdown-body pre { position: relative; }` so the button anchors to the block. (The
  existing `pre` rule already sets padding/overflow/border; extend it, keep current
  values.)
- A `.svy-copy-btn` rule: absolutely positioned `top`/`right` (small offset, e.g.
  `0.4rem`), small padding, `--svy-mono`/system font, `font-size` ~`0.72rem`, subtle
  background from a theme token (e.g. `--svy-surface` or a translucent `--svy-hover`),
  `color: var(--svy-muted)`, `border: 1px solid var(--svy-border)`, small `border-radius`,
  `cursor: pointer`. Reduced opacity at rest, full opacity on `pre:hover` / button `:hover`
  / `:focus-visible` so it is unobtrusive but always reachable (and keyboard-focusable).
- A `.svy-copy-btn.copied` (or `[data-copied]`) variant using `--svy-accent` for the
  success state.
- Must read correctly in both light and dark themes purely through the tokens — no
  hardcoded hex. Verify against the attached dark-theme screenshot layout (button sits in
  the top-right padding area, not overlapping the first line of code).

### 3.5 Accessibility
- Render a real `<button type="button">` with an accessible name (`aria-label="Copy code"`
  and/or visible "Copy" text). Keyboard operable (Enter/Space) by virtue of being a button.
- `:focus-visible` styling so keyboard users see focus.

## 4. Implementation plan

1. **`webui/src/app/components/markdown-renderer.component.ts`** — in the private
   `render(...)` method, after the existing `querySelectorAll('pre code')` highlight loop,
   add a step that iterates `el.querySelectorAll('pre')` and, for each:
   - capture the code text from its `<code>` child (`textContent`) before touching the DOM;
   - create a `<button type="button" class="svy-copy-btn" aria-label="Copy code">` with a
     "Copy" label;
   - attach a `click` handler that copies the captured text via
     `navigator.clipboard.writeText` with a `<textarea>` + `execCommand('copy')` fallback,
     wrapped in try/catch, and briefly shows a "Copied!" state;
   - append the button to the `<pre>`.
   Keep it a small private helper (e.g. `addCopyButtons(el)` / `copyText(code)`), all inside
   the component; no new services. No change to the sanitize/highlight order.
2. **`webui/src/app/components/markdown-renderer.component.scss`** — add
   `position: relative` to the `.markdown-body pre` rule and add the `.svy-copy-btn` (+
   `.copied`/`[data-copied]`, `:hover`, `:focus-visible`) rules using the existing theme
   tokens.
3. **`webui/src/app/components/markdown-renderer.component.spec.ts`** — add Vitest cases:
   - a rendered fenced code block gets exactly one `.svy-copy-btn`;
   - clicking the button copies the block's code text (stub
     `navigator.clipboard.writeText` and assert it was called with the block's source,
     and that the copied string does **not** contain the button label);
   - idempotency: after a streaming re-render (content changes), there is still exactly one
     copy button per block (no duplicates);
   - (optional) the fallback path is exercised when `navigator.clipboard` is absent —
     stub `document.execCommand` and assert it is called with `'copy'`.
4. **Verify**: `npm test` (Vitest) from `webui/`, then a clean production build
   (`npm run build`) so `webui-dist` is not left as a dev bundle.

## 5. Acceptance criteria
- [ ] Every rendered `<pre>` code block in an assistant answer shows a copy button in its
      top-right corner.
- [ ] Clicking the button copies that block's code to the clipboard.
- [ ] The copied text is the code only — it never includes the button's "Copy"/"Copied!"
      label.
- [ ] Copy works in the embedded browser: the async Clipboard API is used when available,
      with a `document.execCommand('copy')` + hidden-`<textarea>` fallback otherwise, and a
      copy failure never breaks rendering.
- [ ] The button is idempotent across streaming re-renders — exactly one button per block,
      no duplicates as the answer grows.
- [ ] The button looks correct in both light and dark themes, styled only via the existing
      `--svy-*` theme tokens (verified against the ticket's dark-theme screenshot).
- [ ] A real, keyboard-operable `<button>` with an accessible name; visible focus.
- [ ] Vitest: the existing `markdown-renderer.component.spec.ts` plus new cases pass;
      `npm run build` succeeds.

## 6. Out of scope
- Any opencode-side change or reverting to opencode's built-in web UI (SVY-21461 decision
  stands).
- Copy buttons for inline `code` spans, tables, or any non-`<pre>` content — only fenced
  code blocks.
- A per-message "copy whole answer" button, export changes, or any Java/Eclipse code.
- Changing the markdown parser, sanitizer, or highlight.js configuration.
- Line numbers, language labels, or other code-block chrome beyond the copy button.

## 7. Open questions
| Question | Owner | Status |
|----------|-------|--------|
| Exact success feedback: label swap "Copy" → "Copied!" vs. an icon-only state, and the revert delay (~1.5s assumed). | reporter / PM | open (assumed: text label with ~1.5s revert) |
| Whether to show a copy icon (SVG) instead of/in addition to the text "Copy" to better match the old opencode affordance. | reporter / PM | open (assumed: text label is sufficient) |
