# Triage Report — SVY-21708

**Verdict:** PROCEED

## Reported problem
Code blocks in the Servoy AI chat answers no longer have a "copy" button. On
2026.06 a copy button existed on each rendered code block; on the reported build
(2026.3.2_LTS, Servoy AI v2.2.0) it is gone. The reporter wants a copy button in
the top-right corner of each code block that copies the block's code when clicked.

The reporter's proposed solution (add a copy button in the top-right of each code
block) is the symptom-driven request, not an independent diagnosis — noted and
evaluated below.

## Root-cause assessment
The missing button is a direct, expected consequence of an intentional UI
replacement, not a defect that crept into existing code.

- Until mid-September 2026 the embedded "Servoy AI" view pointed at **opencode's
  own built-in web UI**, which ships its own markdown renderer with a per-code-block
  copy button. That is the "it used to work on 2026.06" behaviour.
- Commit **`dfb0039` — "SVY-21461 Replace opencode web view with custom Angular chat
  UI on opencode-cli [ai]"** (2026-09-15) replaced that web UI with the in-repo
  Angular chat app (`bundles/com.servoy.eclipse.opencode/webui/`). The embedded
  browser now loads our own app served by `OpencodeChatServlet`, so opencode's
  built-in copy button is no longer in play.
- Our custom renderer is `webui/src/app/components/markdown-renderer.component.ts`.
  It parses markdown with `marked`, sanitizes with DOMPurify, writes the HTML into a
  host `<div>`, and runs `highlight.js` over each `pre code` block. It produces plain
  `<pre><code>` blocks with **no copy affordance** — there is no copy button, no
  clipboard call, and nothing resembling "copy" anywhere in the `webui` source
  (`grep -i copy` across `webui/src/app` returns only unrelated comments). The
  renderer is the single place that emits code blocks (`svy-markdown`, used only by
  `message-item.component.ts`).

So the feature was never ported into the new Angular UI. The regression window is
exactly SVY-21461, and the fix belongs entirely in the Angular `webui` subtree — no
Java/Eclipse code is involved.

## Ticket premise check
The ticket's premise holds up. This is a genuine regression caused by our own change
(SVY-21461), the symptom is correctly located in the chat code-block rendering, and
the proposed approach — add a copy button to each rendered code block — is the
correct and minimal fix. The only refinement is *where* to implement it: it must be
added to our `MarkdownRendererComponent`, the single component that renders code
blocks, rather than anywhere opencode-side (opencode's UI is no longer used).

## Approaches considered
1. **Add a copy button in `MarkdownRendererComponent` (recommended)** — after
   rendering, wrap/decorate each `pre` with a top-right "copy" button that writes the
   block's text to the clipboard (`navigator.clipboard.writeText`, with a
   `document.execCommand('copy')` or textarea fallback for non-secure contexts), with
   a brief "Copied" state.
   - Pros: single, well-scoped component; matches the reporter's request exactly;
     consistent styling via the component's SCSS and theme variables; streaming-safe
     because buttons are (re)applied in the same `effect` that re-renders; stays
     entirely in the Angular subtree.
   - Cons: must handle re-render/streaming idempotency (don't attach duplicate
     buttons as content grows); must not let the button text leak into the copied
     code; clipboard API needs a fallback in case the embedded browser blocks it.
2. **Re-enable / point back at opencode's built-in web UI** — revert to opencode's
   own renderer so its copy button returns.
   - Pros: no new UI code; restores old behaviour wholesale.
   - Cons: reverts the deliberate SVY-21461 decision (custom theming, same-origin BFF,
     directory injection, debug overlay, etc.); throws away a large, intentional
     feature to regain one small affordance. Not acceptable.
3. **No code change** — tell the reporter to select-and-copy manually.
   - Pros: zero effort.
   - Cons: a real, acknowledged regression from 2026.06 that the reporter explicitly
     wants restored; the feature is small and low-risk. Doing nothing is not justified.

## Recommendation
**PROCEED** with approach 1: add a per-code-block copy button to
`webui/src/app/components/markdown-renderer.component.ts` (plus its SCSS), placed in
the top-right corner of each rendered `<pre>` code block and copying that block's
code on click. Implement it so it is idempotent across streaming re-renders and the
copied text excludes the button label, with a clipboard fallback for the embedded
browser. Alternatives 2 (revert to opencode's web UI) and 3 (no change) are rejected:
2 reverts an intentional architectural decision and 3 leaves a confirmed regression
unaddressed.

This is Angular-only work in the `webui/` subtree — use the generic file tools and
`npm`/`vitest`/`ng`, not the Eclipse JDT/PDE tools. A unit test
(`markdown-renderer.component.spec.ts` already exists) should cover that a code block
gets a copy button and that clicking it copies the block's text.

## Git history findings
- `dfb0039` "SVY-21461 Replace opencode web view with custom Angular chat UI on
  opencode-cli [ai]" (2026-09-15) is the introducing change: it added the Angular
  chat UI and reworked `OpenCodeView` to host our servlet URL instead of opencode's
  SPA, removing reliance on opencode's built-in web UI (and therefore its copy
  button). The commit body explicitly notes it "delete[s] OpenCodeBranding CSS/JS
  injection and opencode SPA URL/session scraping."
- `markdown-renderer.component.ts` has existed only since that UI was added and has
  never contained copy-button logic — so this is a never-ported feature, not a later
  breakage. The regression window is precisely the 2026.06 → post-SVY-21461 UI swap,
  consistent with the reporter's "it used to work on 2026.06."
