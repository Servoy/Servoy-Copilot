# Triage Report — SVY-21525

**Verdict:** PROCEED

## Reported problem

The `testE2E` and `showAndTestE2E` MCP tool descriptions (and their `targetForm` `@ToolParam` help text) state they look for a `.cy.js` file, while `generateCypressE2ETest` writes `.cy.ts` files. Read literally, the descriptions tell AI agents the runner can never find the spec its sibling tool generates — leading agents to route around the runner to bash.

The ticket itself notes the defect is in the docs/descriptions, not in runtime behaviour.

## Root-cause assessment

The runtime code is correct. `FormSpecRunner.runE2ECypressTests()` (in `com.servoy.eclipse.cypress`, `servoy-eclipse` repo) resolves specs in this order:

1. Exact match on the target form path
2. `<targetForm>.cy.js`
3. `<targetForm>.cy.ts`
4. Recursive walk matching either extension

Similarly, `listE2ETests` already filters both `.cy.js` and `.cy.ts` (line 325 in `ServoyTestingServer.java`).

The problem is exclusively in the `@Tool` and `@ToolParam` `description` strings in `ServoyTestingServer.java`, which only mention `.cy.js`:

| Tool | Location (ServoyTestingServer.java) | Text that says `.cy.js` |
|------|-------------------------------------|-------------------------|
| `listE2ETests` | Line 303, `@Tool description` | `"Lists all Cypress E2E test files (.cy.js)"` |
| `testE2E` | Line 359, `@Tool description` | `"Looks for a .cy.js file in jenkins-custom/..."` |
| `testE2E` | Line 363, `@ToolParam description` | `"Looks for <formName>.cy.js in the E2E directory."` |
| `showAndTestE2E` | Line 379, `@Tool description` | `"Looks for a .cy.js file in jenkins-custom/..."` |
| `showAndTestE2E` | Line 383, `@ToolParam description` | `"Looks for <formName>.cy.js in the E2E directory."` |

These descriptions were introduced in commit `b3aa83d86` (2026-08-12, SVY-21339 — `@ToolParam` annotation fix-up). That commit bulk-rewrote annotations across all MCP servers but copied the original `.cy.js`-only wording without updating it for the `.cy.ts` support that already existed in `FormSpecRunner`.

## Ticket premise check

The ticket's premise is accurate. It correctly identifies this as a documentation/description mismatch rather than a runtime bug. The proposed fix direction (update the descriptions) is the right one.

## Approaches considered

1. **Update `@Tool` and `@ToolParam` description strings** — Change all five occurrences in `ServoyTestingServer.java` from `.cy.js` to `.cy.js/.cy.ts` (or `.cy.ts/.cy.js`). This aligns what AI agents read with what the runner actually does.
   - Pros: Minimal, surgical, zero runtime risk. Directly solves the problem.
   - Cons: None significant.

2. **No code change** — The runtime already works correctly; agents that actually try will succeed.
   - Pros: Zero risk.
   - Cons: Agents that trust tool descriptions (as they should) will believe `.cy.ts` files can't be found and route around the runner to bash. This undermines the tool's purpose.

## Recommendation

**Approach 1: Update the description strings.** Five string literal edits in `ServoyTestingServer.java`:

- `listE2ETests` `@Tool`: `(.cy.js)` → `(.cy.js / .cy.ts)`
- `testE2E` `@Tool`: `"Looks for a .cy.js file"` → `"Looks for a .cy.js or .cy.ts file"`
- `testE2E` `@ToolParam`: `"Looks for <formName>.cy.js"` → `"Looks for <formName>.cy.js or .cy.ts"`
- `showAndTestE2E` `@Tool`: `"Looks for a .cy.js file"` → `"Looks for a .cy.js or .cy.ts file"`
- `showAndTestE2E` `@ToolParam`: `"Looks for <formName>.cy.js"` → `"Looks for <formName>.cy.js or .cy.ts"`

An existing test in `ServoyTestingServerTest` (line 806) already asserts `generateCypressE2ETest`'s description mentions `cy.ts`. A symmetric test should be added asserting the `testE2E` / `showAndTestE2E` / `listE2ETests` descriptions mention both extensions, to prevent regression.

## Git history findings

- **Commit `b3aa83d86`** (Johan Compagner, 2026-08-12, SVY-21339): Bulk `@ToolParam` annotation fix-up across all MCP servers. This is where the `.cy.js`-only wording was set on `testE2E` and `showAndTestE2E`. The original wording pre-dated `.cy.ts` support.
- **Commit `f51f46bf4e`** (lvostinar, 2026-08-14): Updated `FormSpecRunner.runE2ECypressTests` in servoy-eclipse to resolve both `.cy.js` and `.cy.ts`. The descriptions in Servoy-Copilot were not updated to match.
- The `generateCypressE2ETest` tool (line 892–956) already correctly mentions `.cy.ts` in its description and defaults output to `.cy.ts`.
