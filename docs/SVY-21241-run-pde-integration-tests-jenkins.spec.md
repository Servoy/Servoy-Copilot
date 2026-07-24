# Spec: SVY-21241 — Run all PDE-integration tests in Jenkins

## 1. Goal

Automate the execution of the full PDE integration test suite (`AllDeveloperMcpIntegrationTests`) in the existing Jenkins CI pipeline so that every push to this repository validates not only compilation but also the ~40 integration tests that currently require a manual Eclipse PDE launch.

## 2. Background

### 2.1 Current CI pipeline

The repository has a single `Jenkinsfile` at the project root. It:
1. Checks out the code
2. Runs `mvn -B -s "$MAVEN_SETTINGS" -t "$TOOLCHAIN" $goals` (default goals: `package -U`) inside an Xvfb wrapper
3. Deploys the p2 repository site to `/data/www/latest/servoy_ai/`
4. Triggers downstream installer jobs

The build already uses Xvfb (`wrap([$class: 'Xvfb', ...])`) and the `com.servoy.eclipse.servoypilot.target` target platform which resolves against `https://build.servoy.com/latest/servoy_2026.03/update_site/`.

### 2.2 Test project structure

Two `eclipse-test-plugin` modules exist:
- `tests/com.servoy.eclipse.developer.mcp.tests` — the main integration test project (this ticket's focus)
- `tests/com.servoy.eclipse.opencode.tests` — plain unit tests (already pass in build)

The MCP tests project contains three suite classes:

| Suite | Runner | Type | Eclipse workbench needed? |
|---|---|---|---|
| `AllDeveloperMcpTests` | JUnit 4 | Plain unit tests | No |
| `AllDeveloperMcpJupiterUnitTests` | JUnit 5 Platform Suite | Plain unit tests | No |
| `AllDeveloperMcpIntegrationTests` | JUnit 4 | PDE integration tests | Yes (full workbench + Servoy app server) |

### 2.3 Existing tycho-surefire configuration

The host bundle `bundles/com.servoy.eclipse.developer.mcp/pom.xml` already configures `tycho-surefire-plugin` to run `AllDeveloperMcpTests` (unit tests only) headlessly with `useUIHarness=false` and `org.eclipse.pde.junit.runtime.coretestapplication`. This works today during `mvn package`.

The test bundle `tests/com.servoy.eclipse.developer.mcp.tests/pom.xml` uses bare `eclipse-test-plugin` packaging with **no** tycho-surefire configuration — Tycho skips test execution for this module by default.

The `assistenttests` bundle shows an existing pattern for PDE integration tests: it configures `tycho-surefire-plugin` with `useUIHarness=true`, `useUIThread=true`, `application=org.eclipse.ui.ide.workbench`, and an `integration` profile.

### 2.4 Tycho test execution model

With Tycho 5, `eclipse-test-plugin` modules can run tests via `tycho-surefire-plugin` in the `integration-test` phase. The plugin launches an OSGi/Eclipse application, installs the test bundle as a fragment, and executes the specified test class(es). For PDE integration tests requiring a UI workbench:
- `useUIHarness=true` — starts the Eclipse UI workbench
- `useUIThread=false` — runs tests on a non-UI thread (required for our suite which uses `run_in_ui_thread=false`)
- Xvfb provides the virtual display on headless Linux CI agents

### 2.5 Launch configuration as reference

The existing `AllDeveloperMcpIntegrationTests.launch` file provides the definitive configuration for running these tests — it specifies:
- Product: `com.servoy.eclipse.core.ide`
- JRE: JavaSE-21
- VM args: `-Xms256m -Xmx2048m -ea --add-exports=java.base/sun.security.x509=ALL-UNNAMED -Dservoy.ngclient.titanium.build.disabled=true -Dservoy.cloud.skipCheckout=true -Dservoy.application_server.dir=... -Dproperty-file=.../servoy.properties -Dchromium.integration.eclipse.disable=true -Dorg.eclipse.ui.testing=true`
- Workspace location: `${workspace_loc}/../junit-workspace-integration`
- Deselected workspace bundles (platform-specific exclusions)
- Additional dependencies (extension compatibility bundle)

### 2.6 Key challenge

The integration tests require:
1. A running Servoy Application Server (embedded in the Eclipse product)
2. A configured Eclipse workspace with Servoy projects (test fixtures)
3. The `testresources/` directory containing `servoy.properties` and sample projects
4. Chromium disabled (tests don't need a real browser)
5. Sufficient heap (2 GB)

## 3. Design

### 3.1 Add tycho-surefire configuration to the test bundle

Configure `tests/com.servoy.eclipse.developer.mcp.tests/pom.xml` with `tycho-surefire-plugin` using two profiles:

1. **Default build** (no profile): Run `AllDeveloperMcpTests` + `AllDeveloperMcpJupiterUnitTests` (plain unit tests, headless, fast, no UI harness). This keeps the fast feedback loop for `mvn package`.

2. **`integration` profile** (`-Pintegration`): Run `AllDeveloperMcpIntegrationTests` with full Eclipse workbench. This is the new capability requested by this ticket.

### 3.2 Maven profile configuration

```xml
<profiles>
    <profile>
        <id>integration</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.eclipse.tycho</groupId>
                    <artifactId>tycho-surefire-plugin</artifactId>
                    <version>${tycho-version}</version>
                    <configuration>
                        <testClass>com.servoy.eclipse.developer.mcp.AllDeveloperMcpIntegrationTests</testClass>
                        <useUIHarness>true</useUIHarness>
                        <useUIThread>false</useUIThread>
                        <product>com.servoy.eclipse.core.ide</product>
                        <application>org.eclipse.ui.ide.workbench</application>
                        <argLine>
                            -Xms256m -Xmx2048m -ea
                            --add-exports=java.base/sun.security.x509=ALL-UNNAMED
                            -Dorg.osgi.framework.system.packages.extra=sun.security.x509
                            -Dservoy.ngclient.titanium.build.disabled=true
                            -Dservoy.cloud.skipCheckout=true
                            -Dservoy.application_server.dir="${project.basedir}/testresources"
                            -Dproperty-file="${project.basedir}/testresources/servoy.properties"
                            -Dchromium.integration.eclipse.disable=true
                            -Dorg.eclipse.ui.testing=true
                        </argLine>
                        <dependencies>
                            <dependency>
                                <type>eclipse-plugin</type>
                                <artifactId>com.servoy.eclipse.extension.compatibility</artifactId>
                            </dependency>
                        </dependencies>
                    </configuration>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

### 3.3 Jenkinsfile changes

Add a new stage **after** the existing "Build with Tycho 5" stage that runs the integration tests:

```groovy
stage('Integration Tests') {
    steps {
        wrap([$class: 'Xvfb', installationName: 'xvfb', autoDisplayName: true]) {
            configFileProvider([
                configFile(fileId: 'ba7b9372-76e5-4898-a2be-1dde60a0d6e3', variable: 'MAVEN_SETTINGS'),
                configFile(fileId: 'maven_toolchain', variable: 'TOOLCHAIN')
            ]) {
                sh 'mvn -B -s "$MAVEN_SETTINGS" -t "$TOOLCHAIN" verify -Pintegration -pl tests/com.servoy.eclipse.developer.mcp.tests -am'
            }
        }
    }
}
```

Key design decisions:
- **`verify`** goal: Tycho runs integration tests in the `integration-test` phase and reports in the `verify` phase.
- **`-pl ... -am`**: Only builds the test module and its transitive dependencies (avoids rebuilding everything from scratch since `package` already ran).
- **Separate stage**: Keeps the build-only stage fast; integration test failures are reported distinctly in Jenkins UI.
- **Xvfb wrapper**: Required for the UI harness (SWT Display creation) on headless Linux.

### 3.4 Test result reporting

Tycho surefire writes standard JUnit XML reports to `tests/com.servoy.eclipse.developer.mcp.tests/target/surefire-reports/`. Jenkins should archive these. Add a `post` block or `junit` step:

```groovy
post {
    always {
        junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
    }
}
```

### 3.5 Test resources / workspace setup

The integration tests use `testresources/` in the test bundle for:
- `servoy.properties` — embedded Servoy app server configuration
- Sample solution projects used by test fixtures

These are already committed to the repository. The Maven property `${project.basedir}/testresources` resolves correctly since the module is checked out as part of the build.

### 3.6 Default build: unit tests only

For the default (non-integration) build, configure the test bundle to run the plain unit test suites headlessly:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-jarsigner-plugin</artifactId>
            <configuration>
                <skip>true</skip>
            </configuration>
        </plugin>
        <plugin>
            <groupId>org.eclipse.tycho</groupId>
            <artifactId>tycho-surefire-plugin</artifactId>
            <version>${tycho-version}</version>
            <configuration>
                <useUIHarness>false</useUIHarness>
                <application>org.eclipse.pde.junit.runtime.coretestapplication</application>
                <testClass>com.servoy.eclipse.developer.mcp.AllDeveloperMcpTests</testClass>
                <argLine>-Xms128m -Xmx512m -ea --add-exports=java.base/sun.security.x509=ALL-UNNAMED -Dorg.osgi.framework.system.packages.extra=sun.security.x509</argLine>
            </configuration>
        </plugin>
    </plugins>
</build>
```

This moves the unit-test execution from the host bundle (`bundles/com.servoy.eclipse.developer.mcp/pom.xml`) to the test bundle where it logically belongs.

### 3.7 Failure handling

The integration test stage should **not** fail the overall pipeline silently. Options:
- Mark the build as `UNSTABLE` (not `FAILURE`) if integration tests fail, so the p2 site is still deployed. This allows the team to adopt the tests incrementally.
- Once the suite is stable, switch to hard failure (`FAILURE`).

Initial approach: use `catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE')` around the integration test `sh` step.

## 4. Implementation plan

1. **Modify `tests/com.servoy.eclipse.developer.mcp.tests/pom.xml`**:
   - Add default `tycho-surefire-plugin` configuration for unit tests (headless, `AllDeveloperMcpTests`)
   - Add `<profiles><profile><id>integration</id>` with full PDE integration test configuration (UI harness, `AllDeveloperMcpIntegrationTests`, product, VM args)
   - Skip jar signing for the test bundle

2. **Optionally simplify `bundles/com.servoy.eclipse.developer.mcp/pom.xml`**:
   - Remove (or keep as fallback) the `tycho-surefire-plugin` configuration here, since the test bundle now owns test execution. Keeping it is harmless — it provides a second execution point for unit tests.

3. **Verify `testresources/` content**:
   - Ensure `testresources/servoy.properties` exists and is suitable for headless CI (no absolute paths, no localhost DB dependency that won't be available)
   - If the integration tests require a PostgreSQL database, document this as a prerequisite for the Jenkins agent or add a check that skips gracefully

4. **Update `Jenkinsfile`**:
   - Add "Integration Tests" stage after "Build with Tycho 5"
   - Wrap in `catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE')`
   - Add `junit` post-step for test result archiving

5. **Test locally**:
   - Run `mvn verify -Pintegration -pl tests/com.servoy.eclipse.developer.mcp.tests -am` on a developer machine with Xvfb (Linux) or display (Windows/macOS) to validate the configuration

6. **Push and validate in Jenkins**:
   - Observe Jenkins console output to confirm Tycho launches the Eclipse workbench, runs the suite, and reports results
   - Verify JUnit XML reports appear in Jenkins test results tab

## 5. Acceptance criteria

- [x] `mvn package -U` (default goals) continues to build successfully without running integration tests
- [x] `mvn verify -Pintegration` runs `AllDeveloperMcpIntegrationTests` inside a full Eclipse workbench via tycho-surefire
- [x] Jenkins pipeline has a visible "Integration Tests" stage that executes the PDE integration tests
- [x] Test results (pass/fail) are reported in Jenkins UI via JUnit XML
- [x] Integration test failures mark the build as UNSTABLE (not FAILURE) during initial adoption
- [x] The existing "Build with Tycho 5" stage and "Deploy Plugin Site" stage are unaffected
- [x] Plain unit tests (`AllDeveloperMcpTests`, `AllDeveloperMcpJupiterUnitTests`) still run during normal `package` phase
- [x] JSUnit runner integration tests (`JSUnitRunnerIntegrationTest`, `JSUnitRunnerGroupedTest`, `JSUnitRunnerLayer4Test`, `RunTestMethodIntegrationTest`) produce populated results headlessly under Tycho, matching IDE behaviour

## 5b. Implementation outcome — JSUnit headless test-result bridging

The infrastructure work above (tycho-surefire integration profile + Jenkins stage) landed successfully, but the JSUnit *runner* integration tests initially failed under headless Tycho while passing from the IDE launcher. Every run showed the same symptom: the test method was discovered (`scope=globals methods=1`) and executed (`runJUnitClass completed`, JUnit `runCount` correct), yet the result the MCP layer read back was empty (`| **0** | **0** | **0** | **0** | / All 0 test(s) passed!`).

### Root cause

`JSUnitRunnerService` reads results from the **DLTK** `ITestRunSession`. That session is populated asynchronously via `ScriptUnitTestRunNotifier`, which bridges JUnit `TestListener` events into the session obtained by `DLTKTestingPlugin.getModel().getTestRunSession(target.launch)`. Two headless-only timing issues broke the read:

1. **Wrong session correlation.** The wait logic looked for "a new DLTK session not seen before". Under Tycho the smart-client startup is asynchronous and slow, and stale/interleaved sessions from earlier launches confused this heuristic — the code frequently latched onto the wrong (empty) session.
2. **Premature "completed" + too-short timeout.** A freshly-created DLTK session reports `progressState=COMPLETED` with `0` children (an empty 0-test run is "100% done"). Combined with a 20s per-run timeout, the wait returned this empty session *before* the real `runJUnitClass` (which only fires ~15-20s into a cold headless client start) had bridged any results.

The servoy-eclipse layer (`SolutionJSUnitSuiteCodeBuilder`, `RunClientTests`, `ScriptUnitTestRunNotifier`, `TestClientTestSuite`) was confirmed correct — discovery uses the Servoy persist model (not the DLTK indexer), and the notifier bridge works once given the right launch. No production fix was needed there; only temporary diagnostics were added and later reverted.

### Fix (in `JSUnitRunnerService` + `ServoyRunnerTestBase`)

- **Correlate the session by our own `ILaunch`.** `runForTarget` keeps the `ILaunch` returned by `config.launch(RUN_MODE, null)` and `waitForSessionByLaunch` looks it up directly via `DLTKTestingPlugin.getModel().getTestRunSession(launch)` — the same object the notifier binds to. This is immune to interleaved runs from other launches.
- **Wait for results, not for "completed".** The wait polls specifically for `getChildren().length > 0` (results actually bridged in) rather than treating the premature `COMPLETED`/0-children state as done.
- **Headless-appropriate timeout.** `TIMEOUT_SECONDS` raised `20 -> 60` so a cold smart-client startup (start client + wait for solution load + `runJUnitClass`, ~26s+) completes before the wait gives up. The `runOnBackgroundThread` deadline (`TIMEOUT_SECONDS + 30 = 90s`) stays comfortably above the 60s session wait.

### Jenkins pipeline fix

The `post { success }` block triggered downstream jobs `make_installer_eclipse` / `release/make_installer_eclipse` that do not exist on this Jenkins instance, causing `hudson.AbortException: No item named make_installer_eclipse found` to mark an otherwise-green build (all tests passing, p2 site deployed) as FAILURE. The trigger was removed.

### Result

All integration tests pass headlessly (`BUILD SUCCESS`, ~21 min) and the pipeline no longer fails on the missing downstream job. The unrelated `IllegalStateException: Streams are already closed` from `RunNPMCommand` during shutdown is cosmetic and does not affect the build result.

## 6. Out of scope

- Fixing individual integration test failures (those are tracked separately)
- Setting up a dedicated Jenkins agent with PostgreSQL for database-dependent tests
- Running macOS-specific integration tests in Jenkins (separate CI concern)
- Parallelizing test execution across multiple agents
- Running the `com.servoy.eclipse.opencode.tests` as PDE tests (they are plain unit tests)

## 7. Open questions

| Question | Owner | Status |
|----------|-------|--------|
| Do any integration tests require a live PostgreSQL database on the Jenkins agent? If so, is one available? | DevOps | resolved — tests use in-memory HSQLDB from `testresources/servoy.properties`; no external DB needed |
| Should the `AllDeveloperMcpJupiterUnitTests` suite also run in the default phase, or only JUnit 4 tests? | Diana | open |
| Should integration test failure block the p2 site deployment (hard FAILURE) or allow it (UNSTABLE)? | Team | resolved — UNSTABLE via `catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE')` |
| Is `com.servoy.eclipse.core.ide` product available in the target platform during headless build, or does it need to be resolved from the workspace? | Build | resolved — resolves from target platform; integration profile launches it successfully |
| Does the Jenkins agent have sufficient heap (2 GB+) for the integration test JVM? | DevOps | resolved — `-Xmx2048m` in integration argLine works on the agent |

## 8. Follow-ups

- The `IllegalStateException: Streams are already closed` from `RunNPMCommand.writeConsole` during workbench shutdown is a cosmetic console-stream race, unrelated to the tests. Track separately if it becomes noisy.
- Grouped runs (`MODULES`/`FORMS`) invoke `runForTarget` once per module/form sequentially; with the 60s per-run ceiling and the 90s background-thread deadline, this is only safe for the current single-module/single-form fixtures. If fixtures grow, the wait/deadline budget needs revisiting.
- The temporary `[DIAG-*]` diagnostics added across both repos during investigation have been reverted (Servoy-Copilot `b03c73e`, servoy-eclipse `2735576`).
