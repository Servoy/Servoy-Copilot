# Triage Report — SVY-21839

**Verdict:** PROCEED

## Reported problem
When an agent passes an argument that a Servoy MCP tool does not declare (e.g. `servoy-workspace_findFiles({pattern: "*ticket*"})`), the argument is silently dropped. The tool then answers as if no filter was given and returns an unfiltered result, which fills the agent's context with irrelevant output (about 52 KB in one recorded run). The ticket also notes that the argument names for the same idea differ per tool (`fileNamePatterns` / `containingText` / `pattern`).

The ticket proposes two things:
1. Reject calls with unknown arguments, with an error naming the argument and the accepted ones.
2. Align argument names across the search tools, keeping the old names as aliases.

## Root-cause assessment
The symptom is real and is caused by this project's code. Evidence:

- `McpServerFactory.createToolSpecifications` (the call handler, ~line 177) only runs `validateRequiredParams(tool, args)`. That method checks the schema's `required` list for missing keys. It never compares `args.keySet()` with the declared `properties`.
- `ToolExecutor.mapArguments` (~line 79) iterates the method's declared parameters and does `argMap.get(name)` for each. Keys that match no parameter are never looked at, so they are dropped silently. A missing optional parameter becomes `null`/`0`/`false`.
- `extractAnnotatedTools` (~line 245) builds the input schema with `type`, `properties` and `required` only. It has no `additionalProperties: false`. Schema-level validation is therefore not an option anyway. The code comment says validation is deliberately done at handler level so that tool errors are recoverable and not protocol-level exceptions.
- In `ServoyIdeServer`, `findFiles` declares only `fileNamePatterns` and `maxResults`. `fileSearch` declares `containingText`, `fileNamePatterns` and `maxResults`. The report's example is therefore reproducible: `pattern` is ignored and `fileNamePatterns` is null, so the tool defaults to `*`.

There is a single root cause, so the early divergence test does not apply and NEEDS_INPUT is not needed.

## Ticket premise check
- **Part 1 (reject unknown arguments): correct and low risk.** The fix fits next to `validateRequiredParams` and reuses its error format ("Error: ..." text with `isError(true)`). It should list the unknown keys and the accepted parameter names, and it must return no result. The acceptance criteria are clear and testable as plain JUnit. `validateRequiredParams` is already `public static` and takes `Tool` plus `Map`, so a sibling method can be unit-tested without an MCP runtime.
  - Caution: a strict check could break clients that send extra, harmless keys. I did not verify what the chat UI or opencode actually send. The MCP `_meta` field is not part of `arguments`, so it is not affected. This should be checked during implementation, e.g. by running the existing plainjunit and integration suites that call tools through the factory.
  - Null-valued extra keys should probably still count as unknown.
- **Part 2 (align argument names): questionable as stated, so treat it as optional or a separate case.**
  - The three names in the ticket are not all the same concept. `fileNamePatterns` is a comma-separated glob list, `containingText` is a plain substring, and `pattern` in `fileSearchRegExp` is a regex. Calling all of them `pattern` would blur the semantics. `findFiles` and `fileSearch` already share `fileNamePatterns`.
  - Aliases would also need schema changes: either duplicate properties, or tool-level alias handling in `ToolExecutor`. That conflicts with the goal of a strict, explicit schema.
  - Once Part 1 is in place, a wrong name produces an explicit error that lists the accepted names, so the agent can self-correct on the next call. That removes most of the harm the ticket describes. I recommend deferring renames and aliases unless the ticket owner insists. A limited, explicit alias map for the one known case (`pattern` on `findFiles`) would be possible, but it is not required by the acceptance criteria.
- The acceptance criteria only cover Part 1. Part 2 is a proposal and is not part of them.

## Approaches considered
1. **Handler-level unknown-argument check (recommended).** Add a static `validateUnknownParams(Tool, Map)` in `McpServerFactory`, called in the call handler after or alongside `validateRequiredParams`. It compares `args.keySet()` with `schema.get("properties").keySet()`. On a mismatch it returns an `isError` result naming the unknown keys and listing the accepted parameters. Pros: small, consistent with the existing pattern, recoverable error, easy to unit-test. Cons: it could reject clients that send extra keys, which is the intended behaviour.
2. **Add `additionalProperties: false` to the schema.** Let the SDK's JSON schema validator reject extra arguments. Cons: the SDK reports this as a protocol-level error, which is the behaviour the existing code deliberately avoids. The message would also not be controllable. Not recommended.
3. **Option 1 plus renaming and aliasing the search tool parameters.** This gives more consistency but a larger blast radius: schema changes, alias handling, and updates to docs and agent skills. Defer or split into a separate case.
4. **No code change needed.** The `servoy-guard` workaround in the agent pack already refuses `findFiles` with `pattern`. Pros: no risk. Cons: it covers one tool only, and every other tool and every other typo still fails silently. The problem remains in this project's code, so this is not sufficient.

## Recommendation
PROCEED with Approach 1. Implement it in `McpServerFactory` with plainjunit tests, using Mockito or hand-built `Tool` objects with no OSGi runtime:
- an unknown argument returns an error naming it and the accepted parameters;
- only declared arguments return null (no error);
- the `required` check still works when both problems occur;
- null or empty `args` is handled.

Run the existing suites to confirm that no internal caller sends undeclared arguments. Keep Part 2 out of scope, or raise it as a follow-up case.

## Git history findings
- `McpServerFactory.java` was last touched by `2727d89` (SVY-21303, error redaction), `abb4af0` (SVY-21618, lenient parsing for `getConsoleOutput`) and earlier changes for SVY-21460 and SVY-21284. None of these made lenient handling of unknown arguments a design decision.
- The in-code comment explains that required-parameter validation was moved to handler level to produce recoverable tool errors. This is the design intent to follow for the new check.
- SVY-21618 added deliberate leniency for argument types (`acceptString`). That is about value types, not unknown names, so it does not conflict.
- I did not run `git blame` line by line. No existing `docs/` spec for this ticket exists.
