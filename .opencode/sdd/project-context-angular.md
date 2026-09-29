# Project Context — Servoy Copilot OpenCode web UI (Angular)

This is the **Angular frontend** (`servoy-ai-chat`) embedded in the `com.servoy.eclipse.opencode`
bundle of the Servoy Copilot repo — the custom OpenCode web chat front end. It lives at
`bundles/com.servoy.eclipse.opencode/webui/`. The rest of the repo is Eclipse-OSGi Java
bundles, handled by the separate `/sdd-java` command.

## SDD variant

This frontend uses the **sdd-angular** shared skill. Invoke it with `/sdd-angular`.
(The Java/Eclipse bundles use `sdd-java-eclipse` via `/sdd-java`.)

## Working directory

Frontend commands run from `bundles/com.servoy.eclipse.opencode/webui/`. The git repository
root is the Servoy-Copilot repo root, so specs and triage reports go in the repo-root `docs/`
(`git rev-parse --show-toplevel`), NOT inside `webui/`.

## Technology stack

| Aspect | Value |
|--------|-------|
| Name | `servoy-ai-chat` |
| Framework | Angular 22 (standalone components, zoneless) |
| Build tool | Angular CLI |
| Test runner | **Vitest 4** (via `ng test`) |

## Commands (run from `webui/`)

| Task | Command |
|------|---------|
| Tests (Vitest) | `ng test` (single file: `npx ng test --include="**/x.spec.ts" --no-watch`) |
| Build | `ng build` |
| Lint | if an ESLint config is present, run it and require zero warnings |

## Frontend architecture

- **Standalone components**, bootstrapped in `main.ts` — no NgModules.
- **Zoneless** change detection (`provideZonelessChangeDetection()`); never import Zone.js or
  use `NgZone`. Use signals for reactivity.
- Angular **Signals** (`signal()`, `computed()`) for state.
- This is a chat UI embedded in the Eclipse bundle; it communicates with the Java side of the
  `com.servoy.eclipse.opencode` bundle.

## Testing

- **Vitest 4** with Angular TestBed. `import { describe, it, expect, vi, beforeEach } from 'vitest';`
- Mock with `vi.fn()` / `vi.spyOn()` — NOT `jasmine.createSpyObj`.
- Tests live next to the source (`foo.component.ts` → `foo.component.spec.ts`).
- Guard NG0600: `expect(() => fixture.detectChanges()).not.toThrow()` after setting signals.

## Code conventions

- Signals for reactive state; never write to a signal during template rendering (NG0600).
- `readonly` signal properties; prefer `inject()` over constructor injection.
- Single quotes; no comments unless asked; follow neighboring-file patterns.

## Gotchas

- **Zoneless:** no Zone.js / `NgZone`; use signals or `markForCheck()` for async state changes.
- **Vitest, not Karma/Jasmine:** use the Vitest API.
- **docs/ at git root:** specs/triage reports go in the repo-root `docs/`, not under `webui/`.
