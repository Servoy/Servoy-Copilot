# Spec: SVY-21296 — Extract Cypress testing code from AI plugin into standalone plugin

## 1. Goal

Create a new Eclipse plugin `com.servoy.eclipse.cypress` that contains all Cypress test-running infrastructure (UI command handlers, results view, execution engine, test discovery, headless CI runner). This allows users to run Cypress form tests and E2E tests without installing the AI/MCP plugin, while preserving the MCP plugin's ability to delegate Cypress tool calls to the extracted code.

## 1.1 Repository scope

This work spans **two repositories**:

| Repository | Location | Changes |
|---|---|---|
| `servoy-eclipse` | `C:\Users\vosti\git_2026.3\servoy-eclipse` | New plugin `com.servoy.eclipse.cypress`, feature.xml update, parent pom update |
| `Servoy-Copilot` | `C:\Users\vosti\git_2026.3\Servoy-Copilot` | MCP plugin trimming (remove Cypress extensions from plugin.xml, add Require-Bundle, update imports in ServoyTestingServer) |

## 2. Background

### 2.1 Current state

All Cypress test-running code lives in `com.servoy.eclipse.developer.mcp`. It was placed there historically because the AI agent was the first consumer of Cypress testing (SVY-21025 → SVY-21170 → SVY-21174 → SVY-21173). None of the Cypress classes have technical dependencies on MCP infrastructure — the coupling is purely organizational.

### 2.2 Dependencies of Cypress classes

The Cypress code depends on:
- Eclipse platform APIs (ResourcesPlugin, UI, JFace, Console, Equinox Application)
- Servoy model/core/ui (ServoyModelFinder, ServoyProject, ServoyLog)
- Servoy ngclient.ui (Activator — Node.js path resolution)
- Servoy exporter (AbstractWorkspaceExporter — headless runner)
- Jackson (ObjectMapper)
- Sablo websocket APIs (headless form preview)

No imports of `io.modelcontextprotocol.*`, MCP server classes, or the MCP plugin's Activator.

### 2.3 MCP plugin's consumption of Cypress services

`ServoyTestingServer` (the MCP tool server) uses:
- `FormSpecRunner` — runs Cypress form specs
- `FormSpecGenerator` — generates form spec files
- `CypressLoginSupport` — injects login support into test files
- `CypressTestDiscoveryService` — discovers test files (used by `HeadlessFormTestExecutor`)

After extraction, these will be consumed via Import-Package from the new plugin's exported packages.

### 2.4 Git history

- `6244a8e` SVY-21170 — introduced RunCypressFormTestHandler, CypressConsoleUtil, discovery service, property testers
- `56c5bed` SVY-21171 — moved form test files out of media folder
- `f0ed01f` SVY-21174 — added CypressTestResultsView, session manager, output parser
- `c88782d` SVY-21102 — added E2E test support, RunAllE2ETestsCommandHandler
- `b65979d` SVY-21173 — added headless CypressFormTestRunner application

## 3. Design

### 3.1 New plugin structure (in `servoy-eclipse` repository)

Bundle symbolic name: `com.servoy.eclipse.cypress`
Bundle version: `2026.3.2.qualifier`

Package layout:
```
com.servoy.eclipse.cypress/
├── META-INF/MANIFEST.MF
├── plugin.xml
├── build.properties
├── pom.xml
└── src/com/servoy/eclipse/cypress/
    ├── Activator.java          (minimal plugin activator)
    ├── actions/
    │   ├── CypressConsoleUtil.java
    │   ├── CypressEditorInputPropertyTester.java
    │   ├── CypressFormTestTarget.java
    │   ├── CypressTestAdapterFactory.java
    │   ├── CypressTestPropertyTester.java
    │   ├── CypressTestResult.java
    │   ├── CypressTestSessionManager.java
    │   ├── RunAllCypressFormTestsHandler.java
    │   ├── RunAllE2ETestsCommandHandler.java
    │   ├── RunAllE2ETestsHandler.java
    │   ├── RunCypressFormTestHandler.java
    │   └── RunSingleTestHandler.java
    ├── services/
    │   ├── CypressLoginSupport.java
    │   ├── CypressOutputParser.java
    │   ├── CypressTestDiscoveryService.java
    │   ├── FormSpecGenerator.java
    │   └── FormSpecRunner.java
    ├── views/
    │   └── CypressTestResultsView.java
    └── headless/
        ├── CypressFormTestArgumentChest.java
        ├── CypressFormTestRunner.java
        ├── FormTestResult.java
        ├── HeadlessFormTestExecutor.java
        └── JUnitXmlReporter.java
```

### 3.2 MANIFEST.MF for new plugin

Key properties:
- `Bundle-SymbolicName: com.servoy.eclipse.cypress;singleton:=true`
- `Bundle-Activator: com.servoy.eclipse.cypress.Activator`
- `Bundle-ActivationPolicy: lazy`
- `Export-Package: com.servoy.eclipse.cypress.services, com.servoy.eclipse.cypress.headless, com.servoy.eclipse.cypress.actions`
- `Require-Bundle` will include the same Servoy/Eclipse dependencies currently used by the Cypress classes: `org.eclipse.core.runtime`, `org.eclipse.ui`, `org.eclipse.jface`, `org.eclipse.ui.ide`, `org.eclipse.core.resources`, `org.eclipse.ui.console`, `com.servoy.eclipse.core`, `com.servoy.eclipse.model`, `com.servoy.eclipse.ngclient.ui`, `com.servoy.eclipse.ui`, `com.servoy.eclipse.exporter.solution`, `servoy_shared`, `servoy_ngclient`, `j2db_server`, `servoy_base`, `org.eclipse.core.expressions`, `org.eclipse.core.filesystem`
- `Import-Package` for Jackson, commons-io, sablo, log4j, osgi (same version ranges as current MCP manifest)

The new plugin must NOT require `com.servoy.eclipse.developer.mcp` or `com.servoy.eclipse.opencode`.

### 3.3 plugin.xml for new plugin

All Cypress-related extensions currently in the MCP plugin's `plugin.xml` (lines 9–241) move here with updated class references (new package names). This includes:
- `org.eclipse.core.runtime.applications` (cypressFormTestRunner)
- `org.eclipse.ui.commands` (runCypressFormTest, runAllCypressFormTests, runAllE2ETests)
- `org.eclipse.ui.handlers` (3 handlers)
- `org.eclipse.ui.menus` (3 menu contributions)
- `org.eclipse.core.runtime.adapters` (CypressTestAdapterFactory)
- `org.eclipse.core.expressions.definitions` (2 definitions)
- `org.eclipse.core.expressions.propertyTesters` (2 property testers)
- `org.eclipse.ui.views` (CypressTestResultsView + category)
- `org.eclipse.ui.perspectiveExtensions` (view in Servoy perspective)

ID namespace changes from `com.servoy.eclipse.developer.mcp` to `com.servoy.eclipse.cypress` for all commands, definitions, property testers, and view IDs.

### 3.4 MCP plugin changes (in `Servoy-Copilot` repository)

After extraction, the MCP plugin's `plugin.xml` retains only:
- `org.eclipse.ui.startup` (McpStartup)
- `com.servoy.eclipse.opencode.mcpEndpoint` (McpEndpointProvider)
- `org.apache.tomcat.serviceprovider` (McpServiceProvider)

The MCP plugin's `MANIFEST.MF` adds:
```
Require-Bundle: ..., com.servoy.eclipse.cypress
```
(or alternatively `Import-Package: com.servoy.eclipse.cypress.services, com.servoy.eclipse.cypress.headless`)

`ServoyTestingServer` continues to use `FormSpecRunner`, `FormSpecGenerator`, `CypressLoginSupport`, and `CypressTestDiscoveryService` via updated import statements pointing to `com.servoy.eclipse.cypress.services`.

### 3.5 Feature inclusion (in `servoy-eclipse`)

Add a `<plugin>` entry to `com.servoy.eclipse.feature/feature.xml` (located in `servoy-eclipse`):
```xml
<plugin
      id="com.servoy.eclipse.cypress"
      version="2026.3.2.qualifier"/>
```

### 3.6 Build system (Tycho, in `servoy-eclipse`)

Create `com.servoy.eclipse.cypress/pom.xml` with `eclipse-plugin` packaging, inheriting from the `servoy-eclipse` parent POM. Add the new module to the `servoy-eclipse` parent/aggregator `pom.xml`.

## 4. Implementation plan

### In `servoy-eclipse` repository (`C:\Users\vosti\git_2026.3\servoy-eclipse`)

1. Create the new plugin project `com.servoy.eclipse.cypress` with `META-INF/MANIFEST.MF`, `build.properties`, `pom.xml`, and minimal `Activator.java`.

2. Copy Cypress classes from the MCP plugin (in `Servoy-Copilot`) to the new plugin, updating package declarations:
   - `com.servoy.eclipse.developer.mcp.actions` → `com.servoy.eclipse.cypress.actions` (12 classes)
   - `com.servoy.eclipse.developer.mcp.services` → `com.servoy.eclipse.cypress.services` (5 classes: `FormSpecRunner`, `FormSpecGenerator`, `CypressOutputParser`, `CypressTestDiscoveryService`, `CypressLoginSupport`)
   - `com.servoy.eclipse.developer.mcp.views` → `com.servoy.eclipse.cypress.views` (1 class)
   - `com.servoy.eclipse.developer.mcp.headless` → `com.servoy.eclipse.cypress.headless` (5 classes)

3. Create `plugin.xml` for the new plugin with all Cypress UI contributions (commands, handlers, menus, views, property testers, adapters, expressions, perspective extensions, headless application). Update all ID namespaces and class references.

4. Add `<plugin id="com.servoy.eclipse.cypress" version="2026.3.2.qualifier"/>` to `com.servoy.eclipse.feature/feature.xml`.

5. Add `com.servoy.eclipse.cypress` as a module in the `plugins` profile of the `servoy-eclipse` parent `pom.xml`.

6. Create a test project `com.servoy.eclipse.cypress.tests` (fragment host: `com.servoy.eclipse.cypress`, packaging: `eclipse-test-plugin`). Move the following test classes from `Servoy-Copilot/tests/com.servoy.eclipse.developer.mcp.tests` updating package declarations:
   - `actions/` — `CypressConsoleUtilTest`, `CypressEditorInputPropertyTesterTest`, `CypressTestAdapterFactoryTest`, `CypressTestPropertyTesterTest`, `CypressTestResultTest`, `CypressTestSessionManagerTest`, `RunAllCypressFormTestsHandlerTest`, `RunCypressFormTestHandlerTest`
   - `services/` — `CypressLoginSupportTest`, `CypressOutputParserTest`, `CypressTestDiscoveryServiceTest`, `FormSpecGeneratorTest`, `FormSpecRunnerTest`, `RunCypressFormTestsLauncherTest`
   - `headless/` — `CypressFormTestArgumentChestTest`, `JUnitXmlReporterTest`
   - `servers/` — `DiscoverCypressHelpersTest`
   - `integration/` — `CypressConsoleUtilIntegrationTest`, `CypressFormTestingIntegrationTest`, `E2EToolsIntegrationTest`

7. Add `com.servoy.eclipse.cypress.tests` as a module in the `plugins` profile of the `servoy-eclipse` parent `pom.xml`.

8. Add a test stage to the `servoy-eclipse` `Jenkinsfile` (mirroring Servoy-Copilot's pattern):
   ```groovy
   stage('Cypress Plugin Tests') {
       steps {
           wrap([$class: 'Xvfb', installationName: 'xvfb', autoDisplayName: true]) {
               configFileProvider([
                   configFile(fileId: 'master_mvn_repo', variable: 'MAVEN_SETTINGS'),
                   configFile(fileId: 'maven_toolchain', variable: 'TOOLCHAIN')
               ]) {
                   catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                       sh 'mvn -B -s "$MAVEN_SETTINGS" -t "$TOOLCHAIN" verify -pl com.servoy.eclipse.cypress.tests -am'
                   }
               }
           }
       }
       post {
           always {
               junit allowEmptyResults: true, testResults: 'com.servoy.eclipse.cypress.tests/target/surefire-reports/*.xml'
           }
       }
   }
   ```

### In `Servoy-Copilot` repository (`C:\Users\vosti\git_2026.3\Servoy-Copilot`)

6. Update MCP plugin's `plugin.xml` — remove all Cypress-related extensions (commands, handlers, menus, views, property testers, adapters, expressions, perspective extensions, headless application).

7. Update MCP plugin's `MANIFEST.MF` — add `Require-Bundle: com.servoy.eclipse.cypress` (or `Import-Package` for the new plugin's exported packages).

8. Update `ServoyTestingServer.java` imports from `com.servoy.eclipse.developer.mcp.services.*` / `com.servoy.eclipse.developer.mcp.headless.*` to `com.servoy.eclipse.cypress.services.*` / `com.servoy.eclipse.cypress.headless.*`.

9. Delete the moved Cypress source files from the MCP plugin (the originals in `actions/`, `services/`, `views/`, `headless/` packages that are now in the new plugin).

10. Delete the moved test files from `tests/com.servoy.eclipse.developer.mcp.tests` (the 21 test classes that moved to `com.servoy.eclipse.cypress.tests`).

11. Update remaining tests in `com.servoy.eclipse.developer.mcp.tests` that still reference Cypress classes (e.g. `ServoyTestingServerTest`, `AllDeveloperMcpTests` suites) — update their imports to point to `com.servoy.eclipse.cypress.*` packages.

### Verification (both repos)

12. Verify compilation: zero errors across both plugins, test projects, and the feature build.
13. Run `com.servoy.eclipse.cypress.tests` and confirm all moved tests pass.
14. Run remaining `com.servoy.eclipse.developer.mcp.tests` and confirm no regressions.

## 5. Acceptance criteria

- [ ] A new plugin `com.servoy.eclipse.cypress` exists in `servoy-eclipse` and compiles cleanly
- [ ] A new test project `com.servoy.eclipse.cypress.tests` exists in `servoy-eclipse` and all tests pass
- [ ] The `servoy-eclipse` Jenkinsfile includes a test stage for `com.servoy.eclipse.cypress.tests` with JUnit result archiving
- [ ] All Cypress form test commands (Run Cypress Form Test, Run All Cypress Form Tests, Run Solution E2E Tests) work from context menus without the AI/MCP plugin installed
- [ ] The Cypress Test Results view opens and displays results without the AI/MCP plugin
- [ ] The headless `CypressFormTestRunner` application works from the new plugin's extension
- [ ] MCP plugin's `ServoyTestingServer` Cypress tools (testForm, showAndTest, testE2E, listE2ETests, etc.) continue to work when the new plugin is present
- [ ] `com.servoy.eclipse.feature/feature.xml` includes the new plugin
- [ ] No circular dependencies between `com.servoy.eclipse.cypress` and `com.servoy.eclipse.developer.mcp`
- [ ] The new plugin does NOT depend on `com.servoy.eclipse.developer.mcp` or `com.servoy.eclipse.opencode`
- [ ] Existing Cypress-related tests continue to pass (moved tests in new project + remaining MCP tests with updated imports)

## 6. Out of scope

- Changing the Cypress test execution logic or test file format
- Adding new Cypress features or test types
- Moving non-Cypress code out of the MCP plugin (JSUnit runner, code analysis, etc.)
- Renaming command IDs for backward compatibility with keybindings (old IDs become dead; acceptable for a new extraction)
- Generating or authoring Cypress tests (that remains an AI/MCP tool responsibility)

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Should old command IDs (`com.servoy.eclipse.developer.mcp.commands.runCypressFormTest` etc.) get alias declarations for backward compatibility with user keybindings? | Architect | open |
| ~~Should the `com.servoy.eclipse.servoypilot.feature` also include the new plugin?~~ **Resolved:** No — the new plugin is a core Servoy plugin included only in `com.servoy.eclipse.feature`. The Copilot feature just consumes it via Require-Bundle. | Product | resolved |
| ~~Should Cypress test-related integration tests move to a new test project?~~ **Resolved:** Yes — create `com.servoy.eclipse.cypress.tests` in `servoy-eclipse`, add to pom.xml modules, add a test stage to the servoy-eclipse Jenkinsfile (mirroring Servoy-Copilot's approach). | Dev | resolved |
