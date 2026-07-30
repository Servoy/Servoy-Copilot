# Spec: SVY-21173 — Cypress Headless Launcher Script

## 1. Goal

Provide OS-level launcher scripts (`run_cypress_form_tests.sh` for Linux/macOS and
`run_cypress_form_tests.bat` for Windows) that let users and CI pipelines invoke the
headless Cypress form test runner without knowing Eclipse internals.  The scripts are
the counterpart of the existing `war_export.sh` / `war_export.bat` launchers and serve
as the documented entry point for headless Cypress form testing.

---

## 2. Background

`CypressFormTestRunner` (registered as Eclipse application
`com.servoy.eclipse.developer.mcp.cypressFormTestRunner` in `plugin.xml`) was already
implemented as part of SVY-21173.  It bootstraps a headless Servoy Developer JVM,
starts the embedded Tomcat, activates a solution, discovers `*.form.spec.cy.js` files,
runs them via Cypress, and writes a JUnit XML report.

Currently the only way to invoke this application is:

```
servoy_developer -application com.servoy.eclipse.developer.mcp.cypressFormTestRunner ...
```

That requires knowing the full internal application ID and all JVM flags.  CI pipelines
need a self-contained, discoverable script — analogous to `war_export.bat/.sh` — that
hides boilerplate and documents the right defaults.

### 2.1 Existing launcher structure

The three existing exporters (`export`, `mobile_export`, `war_export`) all follow the
same 13-line pattern.  The scripts live in the **`build` repository** under
`eclipse_build/build/<platform>/exporter/`, not in `Servoy-Copilot`.  The new scripts
go in exactly the same place.

**Linux `war_export.sh` (reference):**

```bash
#!/bin/bash
# sample launch: ./war_export.sh -s _START_HERE -o /home/user/temp -data /home/user/servoy_workspace -verbose
eclipsehome=`dirname $BASH_SOURCE`/..;
cp=$(find $eclipsehome -name "org.eclipse.equinox.launcher_*.jar" | sort | tail -1);
JAVA=$(find $eclipsehome -name "com.servoy.eclipse.jre*" | sort | head -1)
$JAVA/jre/bin/java -Xms40m -Xmx512m -Djava.awt.headless=true -cp "$cp" org.eclipse.equinox.launcher.Main -application com.servoy.eclipse.exporter.war.application "$@"
```

**Windows `war_export.bat` (reference):**

```bat
@echo off
set ECLIPSEHOME=..
for /f "delims= tokens=1" %%c in ('dir /B /S /OD %ECLIPSEHOME%\plugins\org.eclipse.equinox.launcher_*.jar') do set EQUINOXJAR=%%c
for /f "delims= tokens=1" %%c in ('dir /B /S /OD ..\plugins\com.servoy.eclipse.jre.win32.x86_64_*') do set JAVA=%%c
%JAVA%\jre\bin\java -Xms40m -Xmx512m -Djava.awt.headless=true -jar "%EQUINOXJAR%" -application com.servoy.eclipse.exporter.war.application %*
```

Note: Linux uses `-cp "$cp" org.eclipse.equinox.launcher.Main`; Windows uses `-jar "%EQUINOXJAR%"`.
The macOS script is identical to Linux except the JRE binary path is `$JAVA/jre/Contents/Home/bin/java`.

---

## 3. Design

### 3.1 Script behaviour

The scripts mirror `war_export.sh` / `war_export.bat` exactly in structure, with three differences:

1. **Application ID** — `com.servoy.eclipse.developer.mcp.cypressFormTestRunner`
2. **Memory** — `-Xms256m -Xmx2048m` instead of `-Xms40m -Xmx512m` (Cypress + Servoy bootstrap needs more heap)
3. **Extra JVM flags** — three additional flags required for headless Cypress execution (see table below)

**Linux `run_cypress_form_tests.sh`:**

```bash
#!/bin/bash
# sample launch: ./run_cypress_form_tests.sh -data /home/user/servoy_workspace -s myapp -as /srv/application_server -o /home/user/test-results
eclipsehome=`dirname $BASH_SOURCE`/..;
cp=$(find $eclipsehome -name "org.eclipse.equinox.launcher_*.jar" | sort | tail -1);
JAVA=$(find $eclipsehome -name "com.servoy.eclipse.jre*" | sort | head -1)
$JAVA/jre/bin/java -Xms256m -Xmx2048m -Djava.awt.headless=true -Dservoy.cloud.skipCheckout=true -Dchromium.integration.eclipse.disable=true --add-exports=java.base/sun.security.x509=ALL-UNNAMED -cp "$cp" org.eclipse.equinox.launcher.Main -application com.servoy.eclipse.developer.mcp.cypressFormTestRunner -noSplash -consolelog "$@"
```

**macOS `run_cypress_form_tests.sh`:**

```bash
#!/bin/bash
# sample launch: ./run_cypress_form_tests.sh -data /home/user/servoy_workspace -s myapp -as /srv/application_server -o /home/user/test-results
eclipsehome=`dirname $BASH_SOURCE`/..;
cp=$(find $eclipsehome -name "org.eclipse.equinox.launcher_*.jar" | sort | tail -1);
JAVA=$(find $eclipsehome -name "com.servoy.eclipse.jre*" | sort | head -1)
$JAVA/jre/Contents/Home/bin/java -Xms256m -Xmx2048m -Djava.awt.headless=true -Dservoy.cloud.skipCheckout=true -Dchromium.integration.eclipse.disable=true --add-exports=java.base/sun.security.x509=ALL-UNNAMED -cp "$cp" org.eclipse.equinox.launcher.Main -application com.servoy.eclipse.developer.mcp.cypressFormTestRunner -noSplash -consolelog "$@"
```

**Windows `run_cypress_form_tests.bat`:**

```bat
@echo off
rem ## sample launch: run_cypress_form_tests.bat -data C:\ci\workspace -s myapp -as C:\ci\application_server -o C:\ci\test-results
set ECLIPSEHOME=..
for /f "delims= tokens=1" %%c in ('dir /B /S /OD %ECLIPSEHOME%\plugins\org.eclipse.equinox.launcher_*.jar') do set EQUINOXJAR=%%c
for /f "delims= tokens=1" %%c in ('dir /B /S /OD ..\plugins\com.servoy.eclipse.jre.win32.x86_64_*') do set JAVA=%%c
%JAVA%\jre\bin\java -Xms256m -Xmx2048m -Djava.awt.headless=true -Dservoy.cloud.skipCheckout=true -Dchromium.integration.eclipse.disable=true "--add-exports=java.base/sun.security.x509=ALL-UNNAMED" -jar "%EQUINOXJAR%" -application com.servoy.eclipse.developer.mcp.cypressFormTestRunner -noSplash -consolelog %*
```

**Extra JVM flag rationale:**

| Flag | Why required |
|---|---|
| `-Dservoy.cloud.skipCheckout=true` | Prevents the cloud plugin from attempting an automatic Git checkout during headless bootstrap. Always included — harmless no-op on non-cloud workspaces, but required on cloud workspaces to avoid blocking the bootstrap waiting for user interaction. Every headless `.launch` file in the workspace includes it. |
| `-Dchromium.integration.eclipse.disable=true` | Disables the Chromium browser integration that fails without a display |
| `--add-exports=java.base/sun.security.x509=ALL-UNNAMED` | Required by Servoy TLS code on Java 17+. **Bare on Unix** (bash does not misparse `=` in arguments). **Double-quoted on Windows** (`"--add-exports=..."`) because cmd.exe can misparse the embedded `=` sign. |
| `-noSplash` | Suppresses the Eclipse splash screen (no display available) |
| `-consolelog` | Redirects Eclipse log output to stdout, essential for CI log capture |

### 3.2 Arguments passed through

All arguments after the fixed JVM/launcher flags are forwarded verbatim to
`CypressFormTestArgumentChest`:

| Argument | Required | Description |
|---|---|---|
| `-data <workspace-path>` | Yes | Path to the Eclipse/Servoy workspace |
| `-s <solution-name>` | Yes | Name of the solution whose form tests should run |
| `-as <app-server-dir>` | Yes | Path to the Servoy `application_server/` directory |
| `-o <output-path>` | Recommended | Output path for JUnit XML report |
| `-outputDir <path>` | No | Explicit output directory for JUnit XML; overrides `-o` if both given |
| `-timeout <seconds>` | No | Per-test timeout in seconds (default: 120) |
| `-forms <form1,form2,...>` | No | Comma-separated list of form names to test (default: all discovered) |
| `-generateMissing` | No | Auto-generate Cypress spec files for forms that lack them |
| `-cypressArgs "<string>"` | No | Extra arguments forwarded verbatim to the Cypress CLI |
| `-license.company_name <n>` | No | Client license company name |
| `-license.code <code>` | No | Client license code |
| `-license.licenses <n\|SERVER>` | No | Number of client licenses or `SERVER` |
| `-verbose` | No | Enables verbose output from the headless bootstrap |

### 3.3 Exit codes

The scripts propagate the JVM exit code unchanged:

| Code | Meaning |
|---|---|
| `0` | All tests passed, or no tests were discovered |
| `1` | One or more form tests failed |
| `2` | Infrastructure error (workspace import failed, solution not found, Tomcat did not start, etc.) |

### 3.4 File locations

Scripts are placed alongside the existing exporter scripts in the **`build` repository**:

| Platform | Path |
|---|---|
| Linux | `eclipse_build/build/linux_files/cypress_runner/run_cypress_form_tests.sh` |
| macOS | `eclipse_build/build/macosx_files/cypress_runner/run_cypress_form_tests.sh` |
| Windows | `eclipse_build/build/windows_files/cypress_runner/run_cypress_form_tests.bat` |

The existing `war_export.sh` files are committed without a special executable-bit step —
the build packaging system already handles `chmod +x` for all `.sh` files under `exporter/`
the same way it does for `war_export.sh`, `export.sh`, and `mobile_export.sh`.
No extra packaging step is needed.

---

## 4. Implementation plan

1. **Create `eclipse_build/build/linux_files/cypress_runner/run_cypress_form_tests.sh`**
   Copy `war_export.sh`, change the application ID, update memory to `-Xms256m -Xmx2048m`,
   add the three extra JVM flags (bare, no quoting needed on Unix), add `-noSplash -consolelog`.

2. **Create `eclipse_build/build/macosx_files/cypress_runner/run_cypress_form_tests.sh`**
   Same as Linux but use `$JAVA/jre/Contents/Home/bin/java` (macOS bundle JRE path).

3. **Create `eclipse_build/build/windows_files/cypress_runner/run_cypress_form_tests.bat`**
   Copy `war_export.bat`, change the application ID, update memory, add the three extra
   JVM flags. Quote `--add-exports` with double quotes:
   `"--add-exports=java.base/sun.security.x509=ALL-UNNAMED"`.

4. **No packaging changes needed** — the build system already `chmod +x`s all `.sh` files
   under `exporter/`.

---

## 5. Acceptance criteria

1. `run_cypress_form_tests.sh` (Linux/macOS) and `run_cypress_form_tests.bat` (Windows)
   exist in the distribution's `exporter/` directory after packaging.
2. Running the script with valid `-data`, `-s`, `-as`, `-o` arguments against a solution
   with passing form tests exits with code `0` and writes a JUnit XML file.
3. If a form test fails the script exits with code `1`; the JUnit XML contains a
   `<failure>` element.
4. If the workspace or solution is invalid the script exits with code `2`.
5. Without a display (headless Linux CI agent, no `$DISPLAY`) the script completes
   without AWT or Chromium errors.
6. The `-verbose` flag causes the headless bootstrap to emit additional progress lines
   to stdout/stderr.

---

## 6. Out of scope

- Changes to `CypressFormTestRunner.java` or `CypressFormTestArgumentChest.java` —
  already implemented.
- Docker image or Jenkins pipeline template — follow-on work.
- A graphical UI launcher inside Servoy Developer — already covered by the existing
  IDE handlers.

---

## 7. Open questions — resolved

| # | Question | Resolution |
|---|---|---|
| 1 | Executable bit in packaging | **Non-issue.** The build system already `chmod +x`s all `.sh` files under `exporter/` — same as `war_export.sh`. No extra step needed. |
| 2 | `-Dservoy.cloud.skipCheckout=true` necessity | **Always include.** Present in every headless `.launch` file and the CI `pom.xml`. No-op on non-cloud workspaces; required on cloud workspaces to prevent blocking bootstrap. |
| 3 | Script name | **`run_cypress_form_tests`** (chosen by Diana Bunaciu). |
| 4 | Windows `--add-exports` quoting | **Double-quote on Windows** (`"--add-exports=java.base/sun.security.x509=ALL-UNNAMED"`); bare on Unix. Consistent with how Eclipse `.launch` files and Maven handle this on each platform. |
