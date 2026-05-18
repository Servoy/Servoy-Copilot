---
name: opencode-agent-authoring
description: Structural conventions for writing OpenCode agent prompts and skill files — path rules, frontmatter requirements, scope (global vs project), and known pitfalls.
---

## Skill file structure

Every skill requires a directory named exactly after the skill, containing a single `SKILL.md`:

```
<skills-root>/<skill-name>/SKILL.md
```

`SKILL.md` must start with YAML frontmatter:

```yaml
---
name: <skill-name>
description: <one sentence, 1-1024 chars>
---
```

**`name` rules:**
- Must exactly match the directory name
- Lowercase alphanumeric, single hyphens only
- No leading/trailing hyphens, no `--`
- Regex: `^[a-z0-9]+(-[a-z0-9]+)*$`

## Skill scope — global vs project

| Scope | Location | Loaded when |
|-------|----------|-------------|
| Global | `~/.config/opencode/skills/<name>/SKILL.md` | Any OpenCode session |
| Project | `<repo>/.opencode/skills/<name>/SKILL.md` | Session CWD is inside that repo |

Use **global** for pipeline/tooling meta-knowledge that applies across projects.
Use **project-scoped** for domain knowledge specific to one codebase or product.

Never put project-specific domain knowledge in the global location. If an agent writes to the wrong location (e.g. global instead of project), skills will be visible everywhere and pollute unrelated sessions.

**For Servoy-Copilot:** all skills live at:
```
/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/skills/<name>/SKILL.md
```

## Agent prompt location

For Servoy-Copilot, custom agents live at:
```
/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/agents/<agent-name>.md
```

Frontmatter fields used in practice:
```yaml
---
description: <shown in agent picker>
model: kiro-auth/auto
mode: subagent
---
```

Available models (kiro-auth provider):
- `kiro-auth/auto` — default, cost-effective
- `kiro-auth/claude-opus-4-7` — best reasoning, use for architect (2.2×)
- `kiro-auth/claude-sonnet-4-6` — good reasoning, use for validator (1.3×)

## opencode.json — registering agents

Custom subagents must be declared in the project-local `opencode.json` to be invokable:
```
/Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/opencode.json
```

```json
{
  "agent": {
    "<agent-name>": {
      "model": "kiro-auth/auto",
      "mode": "subagent"
    }
  }
}
```

Without this entry the agent prompt file is ignored.

## Known path pitfall — stale volume mounts

Agent prompts may reference `/Volumes/<name>/...` paths from older macOS volume mounts. If that volume is not mounted, the agent will silently fail or fall back to a default location (e.g. `~/.config/opencode/skills/`) rather than erroring.

Always use absolute `/Volumes/ServoyWork/...` paths for this workspace, not `~/.config/opencode/...` paths.
