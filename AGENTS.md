# Agent Guidelines for Servoy Copilot Codebase

Welcome, AI Agent! This repository contains the **Servoy AI Copilot** plugin for the Servoy Developer IDE. It is an Eclipse PDE (Plugin Development Environment) project built with Tycho/Maven. The **active focus** of this repository is the `com.servoy.eclipse.developer.mcp` bundle — the Eclipse IDE MCP server that exposes IDE tools to AI agents. Several other bundles exist in the repository but are no longer actively developed; they are kept for reference only. To ensure safety, consistency, and proper integration with the Eclipse workspace environment, you must adhere strictly to the following developer and automation workflows.

---

## 1. Repository Projects Analysis

This Git repository contains the following core projects/plugins:

### 1. `com.servoy.eclipse.developer.mcp` ⬅ ACTIVE FOCUS
- **Type:** Eclipse Plugin / OSGi Bundle (`eclipse-plugin`)
- **Main Role:** Eclipse IDE MCP server (AssistAI) — exposes IDE tools to AI agents.
- **Key Focus:** Implements the MCP server that runs inside the Eclipse JVM and exposes Eclipse IDE operations (file read/write, search, compilation, git, PDE tests) as MCP tools callable by external AI agents.
- **Crucial Detail:** Two-JVM architecture — this bundle runs inside Eclipse; the MCP client runs in the agent's JVM. Changes here affect what tools are available to all AI agents working in this workspace.

> The following bundles are **no longer actively developed**. They are kept in the repository for reference only. Do not make changes to them unless explicitly instructed.

### 2. `com.servoy.eclipse.servoypilot` _(reference only — not active)_
- **Type:** Eclipse Plugin / OSGi Bundle (`eclipse-plugin`)
- **Main Role:** Main plugin — AI assistant UI, tools, chat, and completion.
- **Key Focus:** Entry point for all AI-assisted developer features. Integrates the chat UI, code completion hooks, and orchestrates calls to the LLM and knowledge base bundles.
- **Crucial Detail:** Depends on `com.servoy.eclipse.servoypilot.langchain4j` and `com.servoy.eclipse.servoypilot.knowledgebase`. All user-facing AI features live here.

### 3. `com.servoy.eclipse.servoypilot.langchain4j` _(reference only — not active)_
- **Type:** Eclipse Plugin / OSGi Bundle (`eclipse-plugin`)
- **Main Role:** LangChain4j wrapper bundle — AI/LLM integration library.
- **Key Focus:** Wraps the LangChain4j library for use inside OSGi. Provides the LLM client abstraction used by the main plugin.
- **Crucial Detail:** Acts as a library bundle. Do not add UI or Eclipse-specific logic here.

### 4. `com.servoy.eclipse.servoypilot.knowledgebase` _(reference only — not active)_
- **Type:** Eclipse Plugin / OSGi Bundle (`eclipse-plugin`)
- **Main Role:** Knowledge base indexing, ONNX embeddings, and RAG (Retrieval-Augmented Generation).
- **Key Focus:** Indexes Servoy project sources and documentation into a vector store using ONNX-based embeddings. Provides retrieval APIs consumed by the main plugin.
- **Crucial Detail:** Heavy dependency on ONNX runtime and PDFBox/Tika for document parsing.

### 5. `com.servoy.eclipse.servoypilot.assistenttests` _(reference only — not active)_
- **Type:** Eclipse Plugin / OSGi Bundle (`eclipse-plugin`)
- **Main Role:** Servoy Developer tools for AI-assisted test generation and execution.
- **Key Focus:** Provides tooling to generate and run tests with AI assistance inside the Servoy Developer environment.

> The following are **active** build and distribution artifacts.

### 6. `com.servoy.eclipse.servoypilot.feature`
- **Type:** Eclipse Feature (`eclipse-feature`)
- **Main Role:** Feature packaging — bundles all plugins and platform-specific fragments for distribution.

### 7. `repository.site_aiplugin`
- **Type:** Eclipse Repository (`eclipse-repository`)
- **Main Role:** P2 update site for distribution of the Servoy Copilot feature.

### 8. `launch_target_aiplugin`
- **Type:** Target Definition
- **Main Role:** Target platform definition for building and running the plugin.
- **Crucial Detail:** Resolves against `https://build.servoy.com/latest/servoy_release/update_site/` plus Maven Central and the Servoy Maven repo. Dependencies already provided by the Servoy target must NOT be duplicated here.

---

## 2. Prioritize Eclipse MCP Tools Over Standard Tools

Since this workspace is a complex, multi-project Eclipse environment, **always prioritize Eclipse-specific MCP/PDE tools** over standard, general-purpose command-line or filesystem tools. This ensures that the Eclipse index, builder, and classpath are kept in sync.

- **File Reading:** Use `eclipse-ide_readProjectResource` instead of the generic `read` tool.
- **File Writing & Creating:** Use `eclipse-coder_createFile` or `eclipse-coder_replaceFileContent` instead of the generic `write` tool.
- **File Editing:** Use `eclipse-coder_applyPatch`, `eclipse-coder_insertIntoFile`, `eclipse-coder_replaceString`, or `eclipse-coder_deleteLinesInFile` instead of the generic `edit` tool.
- **File / Class Searching:** Use `eclipse-ide_fileSearch`, `eclipse-ide_fileSearchRegExp`, or `eclipse-ide_findFiles` instead of generic `grep` or `glob`.
- **Git Operations:** Use `eclipse-git_*` tools instead of standard shell `git` commands in `bash`.
- **Testing:** Prefer `eclipse-ide_runAllTests`, `eclipse-ide_runClassTests`, `eclipse-ide_runTestMethod`, or `eclipse-pde_runJUnitPluginTests` over generic shell test commands.

---

## 3. Commit Message Convention `[ai]`

To maintain clarity and transparency about the origin of codebase changes, any Git commit consisting primarily of AI-generated or AI-assisted changes must follow this rule:
- **The commit subject line must end with ` [ai]`** (case-insensitive, space followed by bracketed `ai`). Examples: `Fix NullPointerException during client initialization [ai]` or `Implement support for modern TLS protocols in server connection [ai]`
- **Commit messages for cases:** When a commit is related to a Jira case, the case number (e.g. `SVY-123`, `SVYX-456`, `SERVOY-293`) must be included in the commit subject line. Example: `SERVOY-293 fix NPE in WAR export copyRequiredBundles [ai]`

---

## 4. Post-Modification Compilation & Quick-Fix Loop

After making any code modifications or creating files using the Eclipse MCP tools, you must execute a self-verification compile loop:

1. **Check for errors:** Call `eclipse-ide_getCompilationErrors()` immediately to check the build state.
2. **Review quick fixes:** If any compilation errors are introduced or identified, look at the returned quick fixes list.
3. **Apply quick fixes:** If a quick fix is applicable and safe, immediately apply it using `eclipse-ide_executeQuickFix` by passing the corresponding `markerId` and `proposalIndex`.
4. **Re-check:** Verify compilation again to ensure the workspace is clean.

---

## 5. Spotbugs Error Resolution

Spotbugs is used to find bugs in Java code. You must pay special attention to Spotbugs issues:
- **Identify Spotbugs Errors:** Spotbugs errors of the **two highest severity levels** are treated as blocking errors.
- **Proactive Fixing:** Always try to fix these Spotbugs errors in any new or modified code to keep the codebase robust and clean.

---


*Thank you for keeping the Servoy Copilot codebase healthy, compilation-error free, and highly consistent!*
