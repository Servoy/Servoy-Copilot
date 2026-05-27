## Knowledge Harvest

- **Project-action:** developer-mcp-add-servoy-context
- **Date:** 2026-05-18
- **Domain:** servoy-developer-mcp-jvm
- **Source:** review of developer-mcp-add-servoy-context implementation

### Existing Skills to Update

**skill: mcp-dependency-analysis** — Add the following note:

> **Servoy Developer MCP runs in a separate JVM from the Eclipse IDE.**
> `com.servoy.eclipse.developer.mcp` is deployed inside Servoy Developer (an Eclipse RCP application running from sources), NOT inside the Eclipse IDE used for development. This means:
> - `ResourcesPlugin.getWorkspace()` inside `developer.mcp` sees **Servoy solution projects** (e.g. `Example_AI_Plugin`), not Eclipse IDE workspace projects (e.g. `j2db_server`, `Servoy-Copilot`)
> - Testing MCP tools that use Eclipse workspace APIs must use project names from the **Servoy Developer workspace**, not the Eclipse IDE workspace
> - The AssistAI MCP server (port 8124) runs inside the Eclipse IDE JVM; the developer.mcp server (port 8183) runs inside Servoy Developer JVM — they are completely separate

### New Skill Candidates

**Candidate: servoy-developer-mcp-testing**

When testing `developer.mcp` endpoints via curl:
- Port is 8183 (Servoy Developer embedded Tomcat)
- Project names must be Servoy solution projects open in Servoy Developer, not Eclipse IDE workspace projects
- File paths are relative to the project root within the Servoy workspace (e.g. `forms/myForm.js`, not full Eclipse paths)
- Eclipse Local History (`IFileState`) is only populated for files that have been edited through the Servoy Developer IDE — newly created files have no history

**Candidate: target-platform-directory-safety**

The `/Volumes/ServoyWork/TargetDefinitions/Master/plugins/` directory is a **shared exported target platform directory** used by all Eclipse instances in the workspace. It is NOT safe to delete files from it programmatically:
- It contains hundreds of Eclipse platform JARs that are not declared in any `.target` file (they come from the Eclipse release train p2 repository)
- Deleting "duplicates" by version sort can remove newer versions if sort is not strictly semantic
- Always verify file count before and after any bulk delete operation
- Recovery requires full re-export of both target platforms (with clear destination)
- **Never use `rm` on this directory without explicit user confirmation and a backup strategy**
