# Spec: SVY-21169 — Login handling for MCP-generated Cypress tests

> Part of SVY-21169 ("investigate how AI can know where a form is in a full flow of an
> application, e.g. generate a Cypress script"). This is the login sub-feature; the
> navigation-graph sub-feature is specified in `SVY-21169-form-navigation-graph.spec.md`.

## 1. Goal

When the MCP generates Cypress tests (form or E2E) for a solution that requires authentication, the generated tests should be able to get past the login screen automatically. Today a generated test that hits a login wall fails immediately and produces no value.

The feature: **detect** that the active solution requires login, **ask once** for the test-user credentials and login target, **generate a reusable `cy.login()` helper** built on `cy.session()`, and have generated specs call it in a `beforeEach`. Credentials are stored in a **gitignored env file**, never inlined into committed spec files.

## 2. Background

### 2.1 Current state

The Cypress test generation lives in `com.servoy.eclipse.developer.mcp`:

- `ServoyTestingServer.generateFormSpec(...)` — MCP tool that generates a single-form test via `FormSpecGenerator`.
- `ServoyTestingServer.generateCypressE2ETest(...)` — MCP tool that generates a multi-form E2E navigation test via `NavigationService`, and scaffolds:
  - `jenkins-custom/e2e-test-scripts/cypress.config.js` (only if missing)
  - `jenkins-custom/e2e-test-scripts/cypress/support/commands.js` (stub, only if missing)
  - `jenkins-custom/e2e-test-scripts/cypress/support/e2e.js` (`import './commands'`)
- `generateCypressE2ETest` already calls `discoverCypressHelpers(supportDir)` and returns the existing custom commands to the AI so it reuses them instead of writing raw commands.
- `FormSpecGenerator.generateCypressSpecContent(...)` builds form-spec bodies; it visits form URLs ending in `/index.html` so `IndexPageFilter` **bypasses stateless login** for single-form rendering.

### 2.2 The problem

- Single-form specs bypass login via the `/index.html` trick, but **E2E tests navigate the real app** and hit the solution's login screen.
- Generated E2E tests have a `beforeEach` that just visits the app — no authentication — so any solution with login fails at step one.
- There is no shared login helper, no place to put test-user credentials, and no detection of whether a solution needs auth.

### 2.3 Servoy authentication context

Servoy solutions can require login in several ways (e.g. built-in Servoy auth / stateless login, or a custom login form/solution). The relevant signals available in the model:
- The solution's `loginFormName` / `loginSolutionName` properties (a solution that declares a login form requires authentication).
- `mustAuthenticate` / authenticator type on the solution.
- Stateless login (the `svyLogin` / OAuth flows) — detectable via solution auth settings.

The detection logic must read these from the active `ServoyProject` / `Solution` model rather than guessing.

## 3. Design

### 3.1 Auth detection

Add a helper (in a new `CypressLoginSupport` service, or on `NavigationService`) that inspects the active solution and returns an `AuthRequirement`:

```java
public record AuthRequirement(
    boolean required,        // does the solution need login?
    AuthKind kind,           // NONE, LOGIN_FORM, STATELESS, UNKNOWN
    String loginFormName,    // if a login form is declared
    String loginSolutionName // if a login solution is declared
) {
    public enum AuthKind { NONE, LOGIN_FORM, STATELESS, UNKNOWN }
}
```

Detection reads solution properties from the model:
- `Solution.getLoginFormID()` / `getLoginSolutionName()` → `LOGIN_FORM`.
- `Solution.getMustAuthenticate()` and stateless-login settings → `STATELESS`.
- Neither → `NONE`.

### 3.2 One-time credential prompt

When `generateCypressE2ETest` (and optionally `generateFormSpec`) runs and `AuthRequirement.required` is true, prompt **once per generation session** (not per test). Because MCP tools are non-interactive, the prompt is surfaced through the **calling agent** — the tool returns a structured "needs login info" response the first time, and accepts the answers as tool parameters on the next call. Concretely, add optional params to the generation tools:

- `loginUrl` — where the login form lives (defaults to the app base URL).
- `testUsername` — the test user to log in as.
- `testPassword` — the test user's password.
- `loginSuccessSelector` — a selector/element that proves login succeeded (e.g. a main-menu element).

If the solution requires auth and these are absent, the tool returns a clear message:
> "This solution requires login (LOGIN_FORM: '<loginForm>'). Re-run generateCypressE2ETest with loginUrl, testUsername, testPassword, and loginSuccessSelector so I can generate a reusable login step. Credentials will be written to a gitignored cypress.env.json, not into the test files."

This keeps the "ask once" UX: the agent asks the user, then re-invokes with the values.

### 3.3 Generated `cy.login()` helper (cy.session-based)

On generation (when auth is required), write/append a `login` command to `cypress/support/commands.js` (or `.ts`), using `cy.session()` so the login runs once and is cached across specs:

```js
// Auto-generated by Servoy MCP — reusable login using cy.session (cached per user).
Cypress.Commands.add('login', (username, password) => {
  username = username || Cypress.env('TEST_USERNAME');
  password = password || Cypress.env('TEST_PASSWORD');
  cy.session([username], () => {
    cy.visit(Cypress.env('LOGIN_URL') || '/');
    // Selectors are best-effort; adjust to match the solution's login form.
    cy.get('input[name="username"], input[type="email"], #username').type(username);
    cy.get('input[name="password"], input[type="password"], #password').type(password, { log: false });
    cy.get('button[type="submit"], input[type="submit"], .login-button').click();
    cy.get(Cypress.env('LOGIN_SUCCESS_SELECTOR') || 'body').should('exist');
  });
});
```

- Never hardcode credentials — read from `Cypress.env(...)`.
- `cy.session` caches by username, so subsequent specs reuse the authenticated session.
- The helper is written only if a `login` command does not already exist (respect an existing custom one).

### 3.4 Credential storage (gitignored env file)

Write the provided values to `jenkins-custom/e2e-test-scripts/cypress.env.json`:

```json
{
  "LOGIN_URL": "http://localhost:8183/solutions/<solution>/index.html",
  "TEST_USERNAME": "testuser",
  "TEST_PASSWORD": "***",
  "LOGIN_SUCCESS_SELECTOR": "#main-menu"
}
```

- Ensure `cypress.env.json` is listed in the `e2e-test-scripts/.gitignore` (create/append the entry if missing). This is the one place we touch a repo file, and only to prevent secrets being committed.
- Cypress automatically loads `cypress.env.json` into `Cypress.env()`.
- If the file exists, merge/update keys rather than overwriting unrelated ones.

### 3.5 Generated spec integration

When auth is required, generated specs get a `beforeEach` that logs in before visiting:

```js
beforeEach(() => {
  cy.login();          // uses cached session; logs in on first use
  cy.visit('/');       // now authenticated
});
```

For solutions that don't require auth, generation is unchanged (no login helper, no env file, no `.gitignore` edit).

### 3.6 Non-mutating principle for the repo

Consistent with the SVY-21174 decision to not modify repo-managed config:
- The **only** repo files this feature writes are the support-file `commands.js` (scaffolding the reusable command — this is generated test infrastructure, not source config) and `.gitignore` (to protect secrets).
- `cypress.env.json` is generated but gitignored, so it never enters version control.
- We do **not** modify `cypress.config.ts`.

## 4. Implementation plan

1. **Create `CypressLoginSupport`** service in `c.s.e.d.mcp.services` — `detectAuth(ServoyProject)` returning `AuthRequirement`; helpers to write the `login` command, `cypress.env.json`, and `.gitignore` entry.
2. **Auth detection** — read `Solution` login form / login solution / mustAuthenticate from the active project model.
3. **Extend `generateCypressE2ETest`** (and `generateFormSpec` if it targets the real app) with optional `loginUrl`, `testUsername`, `testPassword`, `loginSuccessSelector` params.
4. **Gate logic** — if auth required and creds missing, return the "needs login info" message; if provided, generate the helper + env file + `.gitignore` entry and add `cy.login()` to the spec's `beforeEach`.
5. **Idempotency** — don't overwrite an existing `login` command; merge env keys; add `.gitignore` entry only if absent.
6. **Secret hygiene** — never echo `testPassword` back in tool output; `{ log: false }` on the password type in the helper.
7. **Unit tests** — auth detection for LOGIN_FORM / STATELESS / NONE; env-file merge; `.gitignore` idempotency; helper written only when absent.

## 5. Acceptance criteria

- [ ] Generation detects whether the active solution requires login (login form, stateless, or none).
- [ ] When login is required and credentials are not yet provided, the tool returns a clear one-time request for `loginUrl`, `testUsername`, `testPassword`, `loginSuccessSelector`.
- [ ] When credentials are provided, a `cy.login()` command using `cy.session()` is written to `cypress/support/commands.js` (only if not already present).
- [ ] Credentials are written to a gitignored `cypress.env.json`; `cypress.env.json` is added to `.gitignore` if not already there.
- [ ] Generated specs call `cy.login()` in `beforeEach` before visiting the app.
- [ ] The password is never written into a spec file and never echoed in tool output.
- [ ] Solutions that do not require login are generated exactly as before (no helper, no env file, no `.gitignore` change).
- [ ] `cypress.config.ts` is never modified.
- [ ] Re-running generation is idempotent (no duplicate login command, env keys merged).

## 6. Out of scope

- OAuth / third-party IdP interactive login flows (only form-based and stateless username/password).
- Multi-user / role-switching within a single spec (single cached test user).
- Encrypting the stored credentials (gitignore is the protection; use a dedicated throwaway test user).
- Auto-detecting the exact login form field selectors (best-effort selectors + configurable success selector; developer may adjust).

## 7. Open questions

| Question | Owner | Status | Resolution |
|----------|-------|--------|------------|
| Should `generateFormSpec` also inject login, or keep the `/index.html` bypass for single-form tests? | — | Resolved | No — form tests bypass login via `/index.html` + `IndexPageFilter`. Only E2E tests need `cy.login()`. |
| Preferred location/format for test creds if teams object to `cypress.env.json` (e.g. system env vars)? | — | Resolved | `cypress.env.json` (gitignored) is the Cypress standard. Teams can alternatively pass `--env` flags on the CLI — `cy.login()` reads from `Cypress.env()` either way. |
| Exact model API for detecting stateless login on the active solution | — | Resolved | Uses `Solution.getMustAuthenticate()`, `Solution.getLoginFormID()`, and `Solution.getLoginSolutionName()` — the standard auth properties from the solution settings. |
