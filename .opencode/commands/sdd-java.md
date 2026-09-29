---
description: Run the SDD pipeline for the Servoy Copilot Eclipse-OSGi Java bundles.
agent: build
---

Run the full Spec-Driven Development pipeline for the **Java / Eclipse-OSGi bundles** of this
repository (`bundles/com.servoy.eclipse.servoypilot*`, `com.servoy.eclipse.opencode` Java side,
`com.servoy.eclipse.developer.mcp`, etc.).

These are **Eclipse-OSGi/Tycho plugin** bundles, so load and follow the **sdd-java-eclipse**
skill (call the `skill` tool with id `sdd-java-eclipse`). That skill is the orchestrator; it
defines every phase and the human approval gates.

As the skill's `PROJECT_CONTEXT`, read the repository-local file
`.opencode/sdd/project-context-java.md` (relative to the current working directory) with the
`read` tool. Pass its full contents into every phase subagent — the phase subagents start with
fresh context and cannot see the repo otherwise. If that file is missing, tell the user this
repo has not been onboarded to SDD and stop.

Note: the Angular web UI at `bundles/com.servoy.eclipse.opencode/webui` is NOT covered here —
use `/sdd-angular` for that.

User input (Jira issue key/URL, optionally followed by extra context): $ARGUMENTS
