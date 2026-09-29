# Project Context — Servoy Copilot (Eclipse-OSGi Java bundles)

This project is **Servoy Copilot** — the AI assistant integration for the Servoy Developer
IDE. It is a multi-module Maven/Tycho build whose Java side is a set of Eclipse-OSGi plugin
bundles. It also contains one Angular frontend (`com.servoy.eclipse.opencode/webui`) which is
handled by the separate `/sdd-angular` command.

## SDD variant

The Java/Eclipse bundles use the **sdd-java-eclipse** shared skill (Eclipse-OSGi/Tycho).
Invoke it with the `/sdd-java` command. (The Angular `webui` frontend uses `sdd-angular` via
`/sdd-angular`.)

## Technology stack

| Aspect | Value |
|--------|-------|
| Java version | 21 |
| Build system | Maven + Eclipse Tycho |
| Module system | OSGi (each bundle has `META-INF/MANIFEST.MF`) |
| Deps | Target platform + MANIFEST `Require-Bundle`/`Import-Package` (NOT plugin pom.xml) |

## Bundles (bundles/)

| Bundle | Purpose |
|--------|---------|
| `com.servoy.eclipse.servoypilot` | Servoy AI assistant (Pilot) UI |
| `com.servoy.eclipse.servoypilot.langchain4j` | LangChain4j integration for the assistant |
| `com.servoy.eclipse.servoypilot.knowledgebase` | Knowledge base / retrieval support |
| `com.servoy.eclipse.opencode` | Embedded OpenCode integration (Java side; its `webui/` Angular frontend uses /sdd-angular) |
| `com.servoy.eclipse.developer.mcp` | MCP server exposing Eclipse/Servoy tools |
| `org.eclipse.jface` | Patched/repackaged JFace bundle |

Tests live under `tests/` (e.g. `com.servoy.eclipse.developer.mcp.tests`).

## Eclipse plugin development essentials

You are writing **OSGi bundles**, not plain Java:
- Dependencies via `META-INF/MANIFEST.MF` (`Require-Bundle` / `Import-Package`), resolved from
  the active target platform. If a dependency is missing from the target, add it to the
  `.target` file's Maven dependencies and reload the target — then reference it in MANIFEST.MF.
- Export public API packages in MANIFEST.MF; keep internal packages unexported.
- Register extension-point contributions in `plugin.xml`.
- Prefer OSGi Declarative Services over a `BundleActivator` for service registration.
- **Plugin pom.xml is NOT for dependencies** — only build config; runtime deps come from
  MANIFEST.MF + target platform.

## Code conventions

- Follow existing patterns in neighboring files.
- try-with-resources for all `Closeable`; `volatile`/proper synchronization for shared state.
- Log via the plugin's `ILog` or SLF4J — no `System.out.println`.
- SWT threading: UI code must run on the SWT display thread (`Display.getDefault().asyncExec/syncExec`).
- Servoy has its own `com.servoy.j2db.util.UUID` — do not use `java.util.UUID` where the Servoy type is expected.

## Testing

- Unit tests (pure logic): a `<bundle>.tests` fragment, `eclipse-plugin` packaging,
  `eclipse-ide_runClassTests`, class suffix `*Test`.
- Plugin/integration tests (needs OSGi/workspace): `eclipse-test-plugin` packaging under
  `tests/`, `eclipse-pde_runJUnitPluginTestClass`, class suffix `*IntegrationTest`.
- See `AGENTS.md` `## Testing` for the catalogue of existing test classes.

## AGENTS.md

Always read `AGENTS.md` at the repo root at the start — tool policy, workflow, post-edit
checklist, and the `[ai]` + Jira-key commit-subject convention.

## Gotchas

- **MANIFEST.MF:** strict 72-byte line-length limits — use eclipse-coder tools / `formatFile`.
- **Target platform is the source of truth for deps** — not Maven Central; not the plugin pom.
- **build.properties:** new folders must be listed in `bin.includes` or they won't ship in the JAR.
- **This repo also has an Angular frontend** at `bundles/com.servoy.eclipse.opencode/webui` —
  do NOT treat that as a Java bundle; it is handled by `/sdd-angular`.
- **No JUnit in production MANIFEST:** test deps belong only in test bundles.
