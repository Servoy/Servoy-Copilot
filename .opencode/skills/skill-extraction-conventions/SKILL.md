---
name: skill-extraction-conventions
description: Rules for the skills extraction pipeline — what counts as domain knowledge vs behavioral content, where researcher and developer outputs go, and how to avoid placement mistakes.
---

## Two-layer separation

Every piece of content must be classified before writing it anywhere:

| Layer | What it is | Where it goes |
|-------|-----------|---------------|
| Domain knowledge | Facts, specs, API references, constants, patterns, catalogues | Skill file |
| Behavioral/procedural | Step-by-step workflows, tool-call order, output format rules, error handling specific to one agent | Agent prompt |

When in doubt: if the content would be useful to *any* agent working in this domain, it is domain knowledge. If it only makes sense in the context of one agent's execution flow, it is behavioral.

## Output locations

| Agent | Writes to |
|-------|-----------|
| `extract-skills-researcher` | `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/sessions/<project-action>/skills-research.md` |
| `extract-skills-developer` | `/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/skills/<skill-name>/SKILL.md` |

Skills that are **Servoy domain knowledge** (file formats, APIs, enums, conventions) → project-scoped: `Servoy-Copilot/.opencode/skills/`

Skills that are **pipeline/tooling meta-knowledge** (extraction rules, agent authoring conventions) → global: `~/.config/opencode/skills/`

## Progress file format

`/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/sessions/<project-action>/skills-progress.md` must use this structure:

```
## Skills Progress

### Completed
- <skill-name>: <one line description>

### In Progress
- <skill-name>: <current status>

### Pending
- <skill-name>

### Blockers
- <any issues>
```

A `### Pending` section with only a comment like `(none)` means all skills are done. An **empty** `### Pending` section header with no items below it is ambiguous — the developer must explicitly write `(none)` to signal completion.

## Verification rule for orchestrators

After the developer agent completes, verify skill files exist **on disk**, not just that `skills-progress.md` claims they do. The two can diverge if the agent updates the progress file without writing the actual files.

```
ls /Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/skills/
```

Expected count must match the number of skills listed as Completed in `skills-progress.md`.
