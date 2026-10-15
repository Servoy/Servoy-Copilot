# Triage Report — SVY-21522

**Verdict:** PROCEED

## Reported problem

The MCP `validateFormat` tool reports garbage NUMBER/INTEGER format patterns as
**"Valid"**. The tool's own description promises it "compiles the display/edit pattern
with `SimpleDateFormat` (DATETIME) or `DecimalFormat` (NUMBER/INTEGER) to catch invalid
patterns early." The DATETIME half rejects garbage; the NUMBER/INTEGER half does not.

Reporter's reproduction against a running Developer:

- `validateFormat("@@@bad", "NUMBER")` → **Valid** (expected invalid)
- `validateFormat("nonsense", "INTEGER")` → **Valid** (expected invalid)
- `validateFormat("ZZZZ", "INTEGER")` → **Valid** (expected invalid)
- `validateFormat("", "NUMBER")` → **Valid**
- (control) `validateFormat("qqzz!!", "DATETIME")` → correctly Invalid ("Illegal pattern character")

The ticket proposes no specific solution — it only reports the symptom.

## Root-cause assessment

The symptom is real and lives in this project's code, in
`FormatValidatorService.validatePattern(...)`
(`bundles/com.servoy.eclipse.developer.mcp/src/com/servoy/eclipse/developer/mcp/services/FormatValidatorService.java`,
lines 202–223). For NUMBER/INTEGER it validates purely by constructing a formatter:

```java
new DecimalFormat(pattern, RoundHalfUpDecimalFormat.getDecimalFormatSymbols(Locale.getDefault()));
```

and treats "no `IllegalArgumentException`" as "valid".

The root cause is that **`DecimalFormat` is not a strict pattern grammar validator**. Its
only special pattern symbols are `0 # . , - ; E % ‰ ¤` (and quoting). Every other
character is accepted as **literal prefix/suffix text**. So `@@@bad`, `nonsense`, and
`ZZZZ` are all perfectly legal `DecimalFormat` patterns — each builds a formatter that
emits that literal string for every number, and none throws. `DecimalFormat` only throws
on genuinely malformed grammar (e.g. two decimal separators, `0.##.##`), which is exactly
and only what the current tests catch (`testNumber_invalidPattern_isInvalid`,
`testInteger_invalidPattern_isInvalid`). This contrasts with `SimpleDateFormat`, which
throws `IllegalArgumentException` on unknown pattern letters — hence DATETIME works and
NUMBER/INTEGER does not.

So this is a genuine false-pass: a numeric field assigned `@@@bad` would render the literal
text `@@@bad` for every value at runtime, yet the tool green-lights it.

**The empty-string case is NOT a bug.** `validateFormat("", "NUMBER")` → Valid is correct
by the service's documented contract (line 103: null/blank format means "no format",
always valid) and matches `ServoyFormBuilder`'s own `format.length() > 0` guard. It should
stay valid.

**Not a regression.** The validator was introduced whole in commit `11d4d99`
(`SVY-21147 [MCP] Add MCP endpoint that can validate our Form/Code formatting. [ai]`,
2026-07-01) and `validatePattern` has never been touched since (git blame confirms a single
authoring commit). No dependency/target-platform bump is involved.

## Ticket premise check

The ticket's premise — that NUMBER/INTEGER validation is weaker than DATETIME and lets
garbage through — is **correct and confirmed in code**.

Important nuance for whoever implements the fix: the MCP validator deliberately
**replicates `ServoyFormBuilder`'s build-time check verbatim**. The platform builder
(`servoy/master` →
`com.servoy.eclipse.model/.../builder/ServoyFormBuilder.java`, lines 2139–2160) does the
exact same thing: parse with `FormatParser`, then `new SimpleDateFormat(...)` for DATETIME
and `new DecimalFormat(..., RoundHalfUpDecimalFormat...)` for INTEGER/NUMBER, flagging a
marker only when construction throws. In other words, **Servoy Developer itself also
accepts `@@@bad` on a numeric field without a build marker** — the MCP tool is faithfully
as lenient as the platform. The bug is therefore in the tool's *promise* ("catch invalid
patterns early") outrunning what `DecimalFormat`-construction can detect, shared with the
platform builder.

This means the fix is a choice between (a) making the MCP tool *stricter than the
platform* (catches the garbage, but a numeric format the Servoy builder accepts would now
be reported invalid by the tool — a deliberate, defensible divergence since the MCP tool
exists to give agents better guidance than a silent runtime literal), or (b) keeping
platform parity and only correcting the tool's wording/signal. Both are legitimate; this
is the real design decision.

## Approaches considered

1. **Add a numeric-placeholder check for NUMBER/INTEGER and report garbage as Invalid
   (recommended).** After `DecimalFormat` construction succeeds, additionally require the
   pattern to contain at least one digit placeholder (`0` or `#`) outside of quoted
   literals; if it contains none, return an error ("pattern has no numeric placeholder —
   it would render as literal text for every value"). Cleanly catches `@@@bad`, `nonsense`,
   `ZZZZ` while still accepting `0.##`, `#,##0`, `#,###.00`, `¤#,##0.00`, i18n patterns,
   and the empty "no format" case.
   - Pros: fixes exactly the reported false-passes; precise, low-risk heuristic; keeps the
     existing valid/invalid test cases green; makes the tool's promise honest.
   - Cons: makes the MCP tool stricter than `ServoyFormBuilder` (a format the platform
     accepts could be flagged invalid by the tool). Needs the quoted-literal stripping to
     be correct (e.g. `'abc'0.00` is valid — the `0` outside quotes counts).

2. **Report garbage as a Warning rather than Invalid; keep `valid=true`.** Add a `warnings`
   notion (or inline note) when a NUMBER/INTEGER pattern has no numeric placeholder, but
   leave the valid/invalid verdict matching the platform.
   - Pros: preserves exact platform parity on the valid/invalid verdict; still surfaces the
     problem to the agent.
   - Cons: `ValidationResult` has no warnings channel today, so it's a larger change
     (record field + tool-output formatting + tests); a bare "Valid" with a buried warning
     still reads as a pass to many callers.

3. **Only fix the tool's description/Javadoc, no behavioral change.** Reword "catch invalid
   patterns early" to state that NUMBER/INTEGER validation only catches grammatically
   malformed `DecimalFormat` patterns (mirroring the platform builder), not semantically
   useless ones.
   - Pros: trivially correct; zero behavioral risk; honest.
   - Cons: does not satisfy the reporter's expectation that `@@@bad` be flagged; the tool
     stays a weak signal for agents choosing numeric formats.

4. **No code change.** Treat the behavior as by-design because it mirrors
   `ServoyFormBuilder` exactly.
   - Pros: perfect platform parity; nothing to maintain.
   - Cons: leaves a confirmed false-pass that misleads AI agents into applying broken
     numeric formats; the tool's description remains inaccurate. Not satisfactory given the
     tool exists specifically to pre-validate formats for agents.

## Recommendation

**PROCEED with Approach 1** — add a numeric-placeholder sanity check for NUMBER/INTEGER on
top of the existing `DecimalFormat` construction, so a pattern containing no `0`/`#` digit
placeholder (outside quoted literals) is reported Invalid with a clear message. This
directly fixes the three reported false-passes (`@@@bad`, `nonsense`, `ZZZZ`) and makes the
tool's promise accurate, at the deliberate and defensible cost of the MCP tool being
slightly stricter than the platform builder (which is the point of a pre-flight validator
for agents). Keep the empty-string case Valid (it is correct). Add unit tests for the three
reported inputs plus a regression guard that legitimate numeric formats
(`0.##`, `#,##0`, `#,###.00`, `¤#,##0.00`, `'lit'0.00`) stay Valid.

Alternatives if the team prefers strict platform parity: Approach 2 (warn, don't fail) or
Approach 3 (documentation-only). Approach 4 (do nothing) is not recommended.

## Git history findings

- `validatePattern` and the whole `FormatValidatorService` were introduced in a single
  commit `11d4d997c0f457a9191c4f8f52dcb4f417639fcc`
  (`SVY-21147 [MCP] Add MCP endpoint that can validate our Form/Code formatting. [ai]`,
  cPecican, 2026-07-01) and have not been modified since — not a regression.
- The NUMBER/INTEGER validation was intentionally copied from the platform builder
  `ServoyFormBuilder.java` (lines 2139–2160 in `servoy/master`), which uses the identical
  `new DecimalFormat(pattern, RoundHalfUpDecimalFormat.getDecimalFormatSymbols(...))`
  construction and the same i18n skip. The leniency is inherited from that design, so the
  fix is a deliberate choice to make the MCP tool stricter (or to adjust its wording),
  not a revert of a bug.
- No dependency or target-platform version change is involved.
