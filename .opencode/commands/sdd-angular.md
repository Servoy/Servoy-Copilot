---
description: Run the SDD pipeline for the Servoy Copilot OpenCode web UI (Angular, webui).
agent: build
---

Run the full Spec-Driven Development pipeline for the **Angular web UI** of this repository —
the OpenCode chat front end at `bundles/com.servoy.eclipse.opencode/webui` (`servoy-ai-chat`).

This is an **Angular** project, so load and follow the **sdd-angular** skill (call the `skill`
tool with id `sdd-angular`). That skill is the orchestrator; it defines every phase and the
human approval gates. Frontend commands run from the `webui/` directory.

As the skill's `PROJECT_CONTEXT`, read the repository-local file
`.opencode/sdd/project-context-angular.md` (relative to the current working directory) with
the `read` tool. Pass its full contents into every phase subagent — the phase subagents start
with fresh context and cannot see the repo otherwise. If that file is missing, tell the user
this repo has not been onboarded to SDD and stop.

Note: the Eclipse-OSGi Java bundles are NOT covered here — use `/sdd-java` for those.

User input (Jira issue key/URL, optionally followed by extra context): $ARGUMENTS
