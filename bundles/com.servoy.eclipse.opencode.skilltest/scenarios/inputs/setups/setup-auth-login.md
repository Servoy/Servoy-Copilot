# Solution setup: `setup-auth-login`

An input artifact for the SVY-21659 scenario matrix. This file describes, in
prose, the shape the `setup-auth-login` setup prompt should produce, so a tester
can verify the saved solution before reusing it for the described-problem fix
scenario. The full setup prompt is at the bottom and is also carried (verbatim)
in [`../../SCENARIO-MATRIX.md`](../../SCENARIO-MATRIX.md).

## Identity

- **Setup id:** `setup-auth-login`
- **Solution name:** `aiAuthLogin`
- **Database:** bound to `db:/example_data`
- **Purpose:** a small solution with authentication wired but **broken on
  purpose**, for the described-problem fix scenario (not a stacktrace, not a
  ticket).
- **Scenarios that start from it:** `fix-authenticator-not-called`.

## Expected shape

### Authentication

- The main solution is configured as an **AUTHENTICATOR** (solution type 3).
- A separate **authenticator module** named `aiAuthLogin_auth`, added as a
  dependency of the main solution.
- The module's `globals.js` holds a correct `onSolutionOpen(arg, queryParams)`
  that:
  - reads `queryParams.username` and `queryParams.password`,
  - validates them against the `example_data` user store the idiomatic way
    (fetch by username, verify a PBKDF2 hash),
  - calls `security.login()` on success,
  - returns an `errorMessage` object on failure.

### The planted defect

- The module's `solution_settings.obj` **does NOT set `onSolutionOpenMethodID`**
  to that function's UUID. The `onSolutionOpen` function **exists**, but because
  it is never wired as the module's `onSolutionOpen` handler, the runtime never
  calls it on login, so login silently fails. This is the "function exists but is
  never called by the runtime" trap described in the authentication guide — the
  fix scenario must diagnose this, not just edit code.

### Form

- A main-solution CSS-position form named **`home`** bound to
  `db:/example_data/customers`, showing the company name, so there is a landing
  form after login.

## Verification checklist (for the tester)

- [ ] Solution `aiAuthLogin` exists, bound to `db:/example_data`, type
      AUTHENTICATOR.
- [ ] Module `aiAuthLogin_auth` exists and is a dependency of the main solution.
- [ ] The module's `globals.js` has `onSolutionOpen(arg, queryParams)` validating
      credentials and calling `security.login()`.
- [ ] The module's `solution_settings.obj` leaves `onSolutionOpenMethodID`
      **unset** (the deliberate defect is present — the handler is NOT wired).
- [ ] Main-solution form `home` exists, CSS-position, bound to
      `db:/example_data/customers`, shows the company name.

## Full setup prompt

Paste this into the Servoy AI import/record wizard against a fresh `empty`
solution, let the agent finish, verify against the checklist above (in
particular that `onSolutionOpenMethodID` is left unset), then **save** the
solution and note its location.

```
Build a small authenticated starting solution in the active (empty) solution, bound to the
example_data database. Configure stateless authentication of the AUTHENTICATOR kind: create
a separate authenticator module, add it as a dependency of the main solution, and in that
module create a globals.js with an onSolutionOpen(arg, queryParams) function that reads
queryParams.username and queryParams.password, validates them against the example_data user
store the idiomatic way (fetch by username, verify a PBKDF2 hash) and calls security.login()
on success, returning an errorMessage object on failure. IMPORTANT — leave the module's
onSolutionOpen event handler UNSET: create the function but do NOT set the module's
onSolutionOpenMethodID to its UUID, so the function exists but the runtime never calls it on
login. Also create a main-solution CSS-position form named "home" bound to
db:/example_data/customers showing the company name, so there is a landing form after login.
If any detail is unspecified, choose sensible real values and continue without asking. Do
not write any tests. Try to do this in a single run without stopping to ask questions.
```
