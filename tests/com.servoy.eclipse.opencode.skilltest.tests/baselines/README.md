# Skill-test baselines

This folder holds the golden-file fixtures for the Servoy AI skill regression
suite (SVY-21366). Each sub-folder is one baseline:

- `export.json` — a native `opencode export` of a known-good session. It is the
  source of both the prompt to replay and the expected MCP tool calls.
- `baseline.json` — a small sidecar describing how to run and compare (id,
  title, active flag, prompt override, agent/model, attempts, timeout,
  precondition solution, and the `compare` block: `ordered`, `strict`,
  `forbidden`, `argOverrides`).

## Placeholder notice

The `export.json` files committed here are **minimal placeholders**. They are
hand-written well-formed skeletons so the loader/parser and comparator have
something to work against; they are **not** real recordings. Re-record each one
against a live run before relying on it as a regression baseline:

1. Open the Servoy AI view and wait for the server to become ready.
2. Use the "Record baseline…" toolbar action (or
   `SkillTestRunner.recordBaseline(...)`), entering the baseline id and prompt.
3. Review the produced `export.json`, then commit it in place of the
   placeholder.

## Running the suite

The suite is opt-in. Run it via the PDE plug-in test launcher with the guard
property and required keys set:

```
-Dservoy.ai.skilltests=true -DGENAI_API_KEY=... -DSERVOY_SKILLS_ZIP=...
```

Without the guard property (and both keys) the `@TestFactory` in
`SkillScenarioIntegrationTest` emits **zero** dynamic tests by design — an honest
no-op that reports nothing and costs nothing, so normal builds stay green and
free.

## How to run in Servoy Developer

The runnable suite is `SkillScenarioIntegrationTest` (the spec calls it
`SkillScenarioTests`). It needs an OSGi/workbench runtime and a live embedded
server, so it is a JUnit **Plug-in** test — not the plain JUnit launcher.

1. **Open Servoy Developer with a solution active** and the Servoy AI view
   working, so the embedded opencode server + skills are configured. This
   requires `GENAI_API_KEY` + `SERVOY_SKILLS_ZIP` (set by the product when
   Servoy AI is enabled).
2. **Record baselines first.** The committed `create-valuelist-basic` is a
   placeholder. Capture real goldens via the Servoy AI view "Record baseline…"
   toolbar action, or drop baseline folders under
   `~/.servoy/opencode/skilltests/` (the suite resolves the fragment's
   `baselines/` folder first, then that user directory).
3. **Run the suite**, either:
   - the "Run Servoy AI skill tests" toolbar action in the Servoy AI view
     (results go to the Servoy AI Console), **or**
   - run `SkillScenarioIntegrationTest` as a JUnit **Plug-in** Test (tooling:
     `eclipse-pde_runJUnitPluginTestClass`) with VM arg
     `-Dservoy.ai.skilltests=true`. `GENAI_API_KEY` / `SERVOY_SKILLS_ZIP` are
     already set by the product when Servoy AI is enabled; pass them as `-D` for
     a bare PDE launch.
4. **Opt-in behaviour:** without `-Dservoy.ai.skilltests=true` (and both keys and
   at least one active baseline) the factory produces zero tests by design — no
   skip, no false green, no cost.
