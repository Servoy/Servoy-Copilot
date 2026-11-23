# Review Summary — SVY-21522: MCP validateFormat accepts garbage NUMBER/INTEGER patterns as "Valid"

**Risk: LOW** — worst surviving finding: the batch `validateFormElementFormat` tool can now flag existing placeholder-less numeric formats (`%`, `¤`, literal-only) as Invalid, though the platform accepts them. This is an intended stricter verdict in an AI-agent pre-flight tool.

**Scope reviewed:** Servoy-Copilot, commit `147e418` on `master`.

**Release target:** Fix Version 2026.12 — MATCH (master is 2026.12.0 rc).

## Manual test plan

**Verifying the fix**
1. Against a running Developer, call `validateFormat` with NUMBER `@@@bad`, INTEGER `nonsense`, INTEGER `ZZZZ`: each Invalid with the "no numeric placeholder" message.
2. NUMBER `0.##`, `#,##0`, `¤#,##0.00`, `0.00;(0.00)`: all Valid.
3. NUMBER with empty pattern: Valid (no format).
4. NUMBER `'0#'` (placeholders only quoted): Invalid.

**Regression checks**
1. DATETIME `qqzz!!`: still Invalid with the JDK message.
2. NUMBER `0.##.##`: still the JDK grammar error, not the placeholder message.
3. Run `validateFormElementFormat` on a real solution; review any new NUMBER/INTEGER failures.
4. Display pattern `0.##|nonsense`: the edit role reported Invalid.

Automated: `FormatValidatorServiceTest` (plainjunit), 56/56 passing.

## Possible improvements / follow-ups
- The `validateFormat` tool description (`ServoyDevServer.java` ~1378-1383) does not mention that numeric patterns need a `0` or `#`.
- Confirm the product decision that the MCP verdict is stricter than the platform `ServoyFormBuilder`.
- Confirm blank/empty NUMBER pattern staying Valid matches the reporter's intent.
- Not checked: whether `FormatParser` can yield an edit pattern with no `0`/`#`.
- `docs/SVY-21522-numeric-placeholder-check.spec.md` is stale: it gives the test path as `src/test/java/...` (real: `plainjunit/services`) and says the test is registered in `AllDeveloperMcpJupiterUnitTests`, which no longer exists.
