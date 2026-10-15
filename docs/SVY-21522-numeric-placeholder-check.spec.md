# Spec: SVY-21522 — MCP: validateFormat accepts garbage NUMBER/INTEGER patterns as "Valid"

## 1. Goal

The MCP `validateFormat` tool must stop reporting semantically useless NUMBER/INTEGER
format patterns as **Valid**. Patterns such as `@@@bad`, `nonsense`, and `ZZZZ` are legal
`DecimalFormat` strings (they build a formatter that emits that literal text for every
value), so the current "construct the formatter and treat no-exception as valid" check lets
them through. The tool's own description promises it "catch[es] invalid patterns early",
yet it green-lights numeric formats that would render as literal text at runtime. The fix
adds a numeric-placeholder sanity check so a NUMBER/INTEGER pattern with no digit
placeholder (`0` or `#`) outside quoted literals is reported **Invalid** with a clear
message, making the tool's promise honest and giving AI agents an accurate pre-flight
signal.

## 2. Background

`FormatValidatorService.validateFormat(format, dataType)`
(`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/FormatValidatorService.java`)
parses the Servoy format property with `FormatParser`, extracts the display and edit
patterns, and (for non-`i18n:` patterns) calls the private `validatePattern(pattern,
columnType, role)` helper (lines 202–223). That helper validates purely by constructing a
JDK formatter:

- **DATETIME** → `new SimpleDateFormat(pattern)` — throws `IllegalArgumentException` on an
  unknown pattern letter, so garbage (e.g. `qqzz!!`) is correctly rejected.
- **NUMBER / INTEGER** → `new DecimalFormat(pattern, RoundHalfUpDecimalFormat.getDecimalFormatSymbols(Locale.getDefault()))`.
- **TEXT / MEDIA** → no JVM-level pattern validation.

`DecimalFormat` is **not** a strict grammar validator. Its only special pattern symbols are
`0 # . , - ; E % ‰ ¤` (plus single-quote quoting). Every other character is accepted as
literal prefix/suffix text. So `@@@bad`, `nonsense`, and `ZZZZ` are all perfectly legal
`DecimalFormat` patterns — none throws — which is why NUMBER/INTEGER validation is weaker
than DATETIME. `DecimalFormat` only throws on genuinely malformed grammar (e.g. two decimal
separators, `0.##.##`), which is exactly and only what the existing tests
`testNumber_invalidPattern_isInvalid` / `testInteger_invalidPattern_isInvalid` catch.

The empty/blank format case is handled earlier (lines 102–106): `null`/blank format means
"no format" and is always Valid. This matches `ServoyFormBuilder`'s own
`format.length() > 0` guard and is **correct** — it must stay Valid.

### Prior art / platform parity

The service was deliberately written to replicate `ServoyFormBuilder`'s build-time check
verbatim (`servoy/master` → `com.servoy.eclipse.model/.../builder/ServoyFormBuilder.java`,
lines 2139–2160): parse with `FormatParser`, then `new SimpleDateFormat(...)` for DATETIME
and `new DecimalFormat(..., RoundHalfUpDecimalFormat...)` for INTEGER/NUMBER, flagging a
marker only when construction throws. In other words, Servoy Developer itself also accepts
`@@@bad` on a numeric field without a build marker. The leniency is inherited from that
design.

This fix therefore makes the MCP tool **deliberately stricter than the platform builder**.
That is an accepted, defensible divergence: the MCP validator exists specifically as a
pre-flight check to give AI agents better guidance than a silent runtime literal. Approach 1
from the triage report was approved on this basis.

## 3. Design

### 3.1 Numeric-placeholder check (the fix)

After the existing `new DecimalFormat(...)` construction succeeds for NUMBER/INTEGER,
additionally require the pattern to contain **at least one digit placeholder (`0` or `#`)
outside of quoted literals**. If it contains none, return an error — the pattern would
render as literal text for every value.

Scope the check narrowly:

- It applies **only** to NUMBER and INTEGER column types. DATETIME keeps its
  `SimpleDateFormat` validation; TEXT/MEDIA keep "no JVM validation" (so the existing
  `testText_displayFormat_isValid` case `(###) ###-####` on TEXT must stay Valid — the
  placeholder check never runs for TEXT).
- It runs only when `DecimalFormat` construction already succeeded (keep the existing
  `IllegalArgumentException` path intact so genuinely malformed grammar still reports its
  JDK message).
- The empty/blank format "no format" case is handled before `validatePattern` is ever
  called, so it is unaffected and stays Valid.

### 3.2 Quoted-literal handling

The "outside quoted literals" qualifier matters. In a `DecimalFormat` pattern a
single-quote starts/ends a quoted literal, and `''` is an escaped literal apostrophe.
A pattern like `'abc'0.00` is a legitimate numeric format — the `0` outside the quotes is a
real placeholder. Conversely, `'0#'` has a `0` and `#` only *inside* a quoted literal, so
it has **no** real placeholder and must be reported Invalid.

The check must scan the pattern tracking quote state and look for a `0` or `#` that occurs
while **not** inside a quote:

- Track an `inQuote` boolean.
- A `'` toggles quote state. (The `''` escaped-apostrophe sequence toggles in then out, so
  it correctly contributes no out-of-quote placeholder; a lone `0`/`#` between real quotes
  stays ignored, which is the intended behaviour.)
- When not in quote, encountering `0` or `#` means a placeholder is present → pass.
- If the whole scan finds no out-of-quote `0`/`#` → report the error.

A small private helper (e.g. `boolean hasNumericPlaceholder(String pattern)`) keeps
`validatePattern` readable.

### 3.3 Error message

When the pattern has no numeric placeholder, return a message consistent with the existing
`validatePattern` error style (which prefixes the role and echoes the pattern), carrying the
rationale. For example:

```
Invalid <role> format pattern '<pattern>': pattern has no numeric placeholder (0 or #) —
it would render as literal text for every value.
```

Keep the `<role>` prefix (`display` / `edit`) and the quoted pattern so the existing
`error().contains("display")` / `error().contains("edit")` test assertions keep working for
the edit-format path, and the message tells the agent exactly what is wrong and how to fix
it (add a `0`/`#` placeholder).

### 3.4 Javadoc

Update the `validatePattern` Javadoc (and, if useful, the class-level Javadoc note about
replicating `ServoyFormBuilder`) to state that NUMBER/INTEGER validation additionally
requires at least one digit placeholder, so the tool is intentionally stricter than the
platform builder for numeric patterns.

### 3.5 Git history

`validatePattern` and the whole `FormatValidatorService` were introduced in a single commit
`11d4d997c0f457a9191c4f8f52dcb4f417639fcc`
(`SVY-21147 [MCP] Add MCP endpoint that can validate our Form/Code formatting. [ai]`,
2026-07-01) and have never been modified since (confirmed by `git log` on the file). This is
**not a regression** — the leniency has been present since the validator was first written,
copied from the platform builder. No dependency or target-platform version change is
involved.

## 4. Implementation plan

1. In `FormatValidatorService.validatePattern(...)`, after the successful `new
   DecimalFormat(...)` construction for NUMBER/INTEGER, call a new private helper
   `hasNumericPlaceholder(pattern)`. If it returns `false`, return the "no numeric
   placeholder" error (role-prefixed, pattern-quoted) instead of `null`. Leave the existing
   `IllegalArgumentException` catch and the DATETIME / TEXT / MEDIA paths unchanged.
2. Add the private static helper `hasNumericPlaceholder(String pattern)` that scans the
   pattern tracking single-quote state and returns `true` as soon as it finds a `0` or `#`
   outside a quoted literal, `false` otherwise.
3. Update the Javadoc on `validatePattern` (and optionally the class-level Javadoc) to
   document that NUMBER/INTEGER additionally require a digit placeholder, making the tool
   deliberately stricter than `ServoyFormBuilder`.
4. Extend `FormatValidatorServiceTest`
   (`tests/com.servoy.eclipse.developer.mcp.tests/src/test/java/com/servoy/eclipse/developer/mcp/services/FormatValidatorServiceTest.java`)
   with:
   - False-pass regression tests (now Invalid): `@@@bad` / NUMBER, `nonsense` / INTEGER,
     `ZZZZ` / INTEGER — assert `!valid()` and a non-null error.
   - A quoted-literal-only negative test: `'0#'` / NUMBER is Invalid (placeholders only
     inside quotes don't count).
   - Valid-format regression guards (stay Valid): `0.##`, `#,##0`, `#,###.00`,
     `¤#,##0.00` (currency), and `'lit'0.00` (literal prefix + real placeholder).
   - Keep the empty-string "no format" case Valid (already covered by
     `testBlankFormat_isValid` / `testEmptyString_isValid`; add an explicit `"" / NUMBER`
     assertion if desired).
   `FormatValidatorServiceTest` is already registered in the `AllDeveloperMcpJupiterUnitTests`
   suite and the `pom.xml` `<test>` list, so no new registration is needed — only extend the
   existing class.
5. Run the post-modification compile loop (`getCompilationErrors`) and the unit test to
   confirm green: the three reported inputs now fail validation and all legitimate numeric
   formats still pass.

## 5. Acceptance criteria

- [ ] `validateFormat("@@@bad", "NUMBER")` returns `valid=false` with a non-null, clear error.
- [ ] `validateFormat("nonsense", "INTEGER")` returns `valid=false`.
- [ ] `validateFormat("ZZZZ", "INTEGER")` returns `valid=false`.
- [ ] `validateFormat("'0#'", "NUMBER")` returns `valid=false` (placeholders only inside a quoted literal don't count).
- [ ] `validateFormat("", "NUMBER")` and blank/null formats remain `valid=true` ("no format").
- [ ] `validateFormat("0.##", "NUMBER")` remains `valid=true`.
- [ ] `validateFormat("#,##0", "INTEGER")` remains `valid=true`.
- [ ] `validateFormat("#,###.00", "NUMBER")` remains `valid=true`.
- [ ] `validateFormat("¤#,##0.00", "NUMBER")` (currency) remains `valid=true`.
- [ ] `validateFormat("'lit'0.00", "NUMBER")` (literal prefix + real placeholder) remains `valid=true`.
- [ ] DATETIME validation is unchanged: `qqzz!!` / DATETIME stays Invalid; valid datetime patterns stay Valid.
- [ ] TEXT display formats are unchanged: `(###) ###-####` / TEXT stays Valid (placeholder check never runs for TEXT).
- [ ] The error message for a placeholder-less numeric pattern keeps the role prefix (`display`/`edit`) and echoes the offending pattern.
- [ ] The bundle compiles with zero errors and the full `FormatValidatorServiceTest` passes.

## 6. Out of scope

- Changing the platform `ServoyFormBuilder` build-time check in `servoy/master` to match
  (the divergence is intentional and local to the MCP tool).
- Introducing a separate `warnings` channel on `ValidationResult` (Approach 2 — rejected in
  triage in favour of a hard Invalid verdict).
- Documentation-only wording change with no behavioral fix (Approach 3 — rejected).
- Any change to DATETIME, TEXT, or MEDIA validation behaviour.
- Validating i18n-prefixed patterns (`i18n:...`) — still skipped by design, since the
  resolved value is only known at runtime.

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| None — approach approved in triage; empty-string stays Valid; stricter-than-platform divergence accepted. | — | Resolved |
