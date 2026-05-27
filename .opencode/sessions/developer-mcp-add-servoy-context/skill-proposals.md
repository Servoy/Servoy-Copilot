# Skill Proposals

## Proposal: target-platform-directory-safety

- **Name:** target-platform-directory-safety
- **Description:** Safety rules for the shared exported target platform directory at /Volumes/ServoyWork/TargetDefinitions/Master/plugins/ — never delete files programmatically, covers duplicate bundle causes, recovery procedure, and why bulk rm is dangerous.
- **Scope:** project (lives under Servoy-Copilot/.opencode/skills/)
- **Assign to agents:** developer, reviewer, endpoint-tester
- **Why needed:** During session 1, bulk deletion of "duplicate" JARs from this directory deleted ~317 essential Eclipse bundle JARs, breaking Servoy Developer. This skill prevents future agents from making the same mistake.

### Status: WRITTEN
File: /Volumes/ServoyWork/git/master/Servoy-Copilot/.opencode/skills/target-platform-directory-safety/SKILL.md

## Existing Skills Updated

1. **mcp-dependency-analysis** — Added "Servoy Developer MCP JVM separation" section explaining the two-JVM architecture and correct project names for testing.

2. **pde-plugin-testing** — Added "`eclipse-pde_runJUnitPluginTestClass` MCP tool limitation" section documenting that the tool forces `uitestapplication` and fails silently with duplicate OSGi bundles.
