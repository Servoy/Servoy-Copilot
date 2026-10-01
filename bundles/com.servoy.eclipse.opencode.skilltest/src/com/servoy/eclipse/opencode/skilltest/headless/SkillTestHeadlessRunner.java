/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
*/

package com.servoy.eclipse.opencode.skilltest.headless;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.osgi.framework.FrameworkUtil;

import com.servoy.eclipse.model.util.ModelUtils;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.opencode.skilltest.Baseline;
import com.servoy.eclipse.opencode.skilltest.BaselineLoader;
import com.servoy.eclipse.opencode.skilltest.SkillTestPaths;
import com.servoy.eclipse.opencode.skilltest.SkillTestResult;
import com.servoy.eclipse.opencode.skilltest.SkillTestRunner;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;
import com.servoy.j2db.server.starter.IWebServerStarter;
import com.servoy.j2db.util.Settings;

/**
 * Headless Eclipse application that replays Servoy AI skill-test baselines in CI
 * without the Developer IDE UI.
 * <p>
 * This is a plain {@link IApplication} (it deliberately does <b>not</b> extend
 * {@code AbstractWorkspaceExporter}). It performs the minimal Servoy headless
 * bootstrap itself - disables the UI, loads the settings file, starts the
 * embedded application server + Servoy model - and then, <b>per baseline</b>,
 * cleans that baseline's solution project and imports its {@code setup.servoy}
 * initial state before replaying it. Each baseline is therefore self-contained:
 * the starting workspace state is part of the baseline, not something the CI job
 * must pre-populate.
 * </p>
 * <p>
 * Per run it: logs in to Servoy Cloud (for the opencode/kiro credentials),
 * starts the embedded Tomcat, ensures the opencode server is up, then for each
 * active baseline runs setup ({@link SolutionSetup}) + replay
 * ({@link SkillTestRunner}), and writes a JUnit XML report.
 * </p>
 *
 * <pre>
 * ./servoy_developer -application com.servoy.eclipse.opencode.skilltest.skillTestRunner \
 *     -data /workspace -as /path/to/application_server -o /path/to/test-results \
 *     -cloudUser ci@servoy -cloudPass ****   (or -DGENAI_API_KEY=...)
 * </pre>
 *
 * Exit codes: {@code 0} = all baselines passed (or none active), {@code 1} = one
 * or more diverged, {@code 2} = infrastructure error, {@code 3} = invalid args.
 */
public class SkillTestHeadlessRunner implements IApplication {

	private static final Integer EXIT_OK = IApplication.EXIT_OK;
	private static final Integer EXIT_TESTS_FAILED = Integer.valueOf(1);
	private static final Integer EXIT_INFRA_FAILED = Integer.valueOf(2);
	private static final Integer EXIT_INVALID_ARGS = Integer.valueOf(3);

	/** How long to wait for the embedded Tomcat to accept HTTP connections. */
	private static final long WEB_SERVER_TIMEOUT_MS = 120_000L;

	/** Poll interval while waiting for the embedded web server. */
	private static final long WEB_SERVER_POLL_INTERVAL_MS = 1_000L;

	/** How long to wait for the workbench to start the Servoy application server. */
	private static final long APP_SERVER_WAIT_MS = 180_000L;

	/** How long to wait for the opencode server to come up. */
	private static final long OPENCODE_SERVER_WAIT_MS = 180_000L;


	private Integer exitCode = EXIT_OK;

	@Override
	public Object start(IApplicationContext context) throws Exception {
		// This bare-application entry no longer starts the workbench, so MCP servers
		// (which the skill test needs) would not come up. The runner is instead
		// launched as the product/workbench with SkillTestStartup firing the run once
		// the workbench - and therefore core + app server + MCP - is up. Kept only so
		// the application id still resolves; the real entry is runInWorkbench().
		outputError("Run the skill tests via the product/workbench launch "
				+ "(-Dservoy.skilltest.run=true), not this bare application.");
		return EXIT_INVALID_ARGS;
	}

	@Override
	public void stop() {
		// nothing to do - the run is synchronous within runInWorkbench()
	}

	/**
	 * Entry point for the workbench {@code IStartup} path
	 * ({@code SkillTestStartup}): the workbench is already up (so
	 * {@code com.servoy.eclipse.core} + app server + model + MCP are starting), the
	 * UI is <b>not</b> disabled (MCP requires it), config comes from
	 * {@code -Dservoy.skilltest.*} properties, and the app server is waited-for
	 * (not hand-bootstrapped). Returns the skill-test exit code (0 pass, 1
	 * diverged, 2 infra).
	 */
	public int runInWorkbench() {
		SkillTestArgumentChest configuration = SkillTestArgumentChest.fromSystemProperties();
		try {
			loadSettings(configuration);
			if (!waitForApplicationServer()) {
				return EXIT_INFRA_FAILED;
			}
			disableAutoBuild();
			runSkillTests(configuration);
		} catch (Exception e) {
			ServoyLog.logError("Skill test run failed.", e);
			outputError("Skill test run failed: " + e.getMessage());
			exitCode = EXIT_INFRA_FAILED;
		}
		return exitCode;
	}

	/**
	 * Waits for the Servoy application server to be started by the running
	 * workbench (it starts core + app server + model + MCP asynchronously). Unlike
	 * the old bare-app path, this does not hand-bootstrap the server.
	 *
	 * @return {@code true} once the application server is available
	 */
	private boolean waitForApplicationServer() throws InterruptedException {
		output("[diag] UI disabled? " + ModelUtils.isUIDisabled() + " | workbench running? " + isWorkbenchRunning());
		long deadline = System.currentTimeMillis() + OPENCODE_SERVER_WAIT_MS;
		while (System.currentTimeMillis() < deadline) {
			if (ApplicationServerRegistry.get() != null) {
				output("[diag] application server is up. workbench running? " + isWorkbenchRunning());
				return true;
			}
			Thread.sleep(1_000L);
		}
		outputError("Application server did not start within " + (OPENCODE_SERVER_WAIT_MS / 1000) + "s.");
		return false;
	}

	// --- settings --------------------------------------------------------------

	private void loadSettings(SkillTestArgumentChest configuration) {
		if (configuration.getSettingsFileName() != null) {
			File f = new File(configuration.getSettingsFileName());
			if (f.exists()) {
				try {
					Settings s = Settings.getInstance();
					s.loadFromFile(f);
					FrameworkUtil.getBundle(getClass()).getBundleContext().registerService(Settings.class, s, null);
				} catch (IOException e) {
					ServoyLog.logError(e);
					outputError("Failed to load settings: " + e.getMessage());
				}
			}
		}
		if (configuration.getAppServerDir() != null) {
			File f = new File(configuration.getAppServerDir());
			if (f.exists() && f.isDirectory()) {
				Settings.getInstance().put(com.servoy.j2db.J2DBGlobals.SERVOY_APPLICATION_SERVER_DIRECTORY_KEY,
						f.getAbsolutePath());
			} else {
				outputError("Incorrect application server location: " + configuration.getAppServerDir());
			}
		}
	}

	/** Reports whether the Eclipse workbench is up (MCP + core depend on it). */
	private static boolean isWorkbenchRunning() {
		try {
			return org.eclipse.ui.PlatformUI.isWorkbenchRunning();
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Logs why the MCP servers are (or are not) available: whether the developer
	 * MCP bundle is active, whether the workbench is up, and whether the
	 * {@code /dev_mcp/servoy-dev} endpoint answers over HTTP. This pinpoints where
	 * MCP startup stalls without changing behavior.
	 */
	private void logMcpDiagnostics(int webPort) {
		try {
			org.osgi.framework.Bundle mcpBundle = org.eclipse.core.runtime.Platform
					.getBundle("com.servoy.eclipse.developer.mcp"); //$NON-NLS-1$
			String state = mcpBundle == null ? "<not found>" : bundleStateName(mcpBundle.getState());
			output("[diag] developer.mcp bundle state: " + state + " | workbench running? " + isWorkbenchRunning());
		} catch (Throwable t) {
			output("[diag] developer.mcp bundle not resolvable: " + t);
		}
		// Probe the MCP endpoint: any HTTP response = servlets registered; a
		// connection refusal / 404 = MCP servers did not register.
		String url = "http://127.0.0.1:" + webPort + "/dev_mcp/servoy-dev";
		try {
			HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
			c.setRequestMethod("GET");
			c.setConnectTimeout(2000);
			c.setReadTimeout(2000);
			int code = c.getResponseCode();
			c.disconnect();
			output("[diag] MCP endpoint " + url + " -> HTTP " + code
					+ (code == 404 ? " (servlet NOT registered - MCP servers did not start)" : " (servlet present)"));
		} catch (IOException e) {
			output("[diag] MCP endpoint " + url + " not reachable: " + e.getMessage());
		}
	}

	private static String bundleStateName(int state) {
		return switch (state) {
			case org.osgi.framework.Bundle.ACTIVE -> "ACTIVE";
			case org.osgi.framework.Bundle.STARTING -> "STARTING";
			case org.osgi.framework.Bundle.RESOLVED -> "RESOLVED";
			case org.osgi.framework.Bundle.INSTALLED -> "INSTALLED";
			case org.osgi.framework.Bundle.STOPPING -> "STOPPING";
			case org.osgi.framework.Bundle.UNINSTALLED -> "UNINSTALLED";
			default -> "0x" + Integer.toHexString(state);
		};
	}

	private void disableAutoBuild() {
		try {
			IEclipsePreferences node = InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES);
			node.putBoolean(ResourcesPlugin.PREF_AUTO_BUILDING, false);
			node.flush();
		} catch (Exception e) {
			ServoyLog.logWarning("Could not disable auto-build.", e);
		}
	}

	// --- the skill-test run ----------------------------------------------------

	private void runSkillTests(SkillTestArgumentChest configuration) throws Exception {
		// 1. Cloud login -> GENAI_API_KEY / SERVOY_SKILLS_ZIP (unless pre-set via -D).
		output("[diag] step 1/5: Servoy Cloud login...");
		CloudLogin.loginAndSetProperties(configuration.getCloudUser(), configuration.getCloudPass());
		output("Servoy Cloud login OK (opencode credentials set).");

		// 2. Start the embedded Tomcat web server (opencode + MCP servlets need it).
		output("[diag] step 2/5: starting embedded web server...");
		int webPort = startAndWaitForWebServer();
		output("Embedded web server ready on port " + webPort + ".");
		logMcpDiagnostics(webPort);

		// 3. Ensure the embedded opencode server is running.
		output("[diag] step 3/5: starting opencode server...");
		com.servoy.eclipse.opencode.Activator opencode = com.servoy.eclipse.opencode.Activator.getInstance();
		if (opencode == null) {
			outputError("opencode Activator not available - cannot start the opencode server.");
			exitCode = EXIT_INFRA_FAILED;
			return;
		}
		// Node.js is normally extracted by the build/UI path; headless we trigger it so
		// the opencode npm launch does not block forever on the extraction latch.
		com.servoy.eclipse.ngclient.ui.Activator ngActivator = com.servoy.eclipse.ngclient.ui.Activator.getInstance();
		if (ngActivator != null) {
			ngActivator.extractNode();
		} else {
			outputError("ngclient.ui Activator not available - opencode npm launch may hang.");
		}
		opencode.ensureServerStarting();
		if (!opencode.waitForServer(OPENCODE_SERVER_WAIT_MS)) {
			outputError("opencode server did not become ready within " + (OPENCODE_SERVER_WAIT_MS / 1000) + "s.");
			exitCode = EXIT_INFRA_FAILED;
			return;
		}
		output("opencode server ready on port " + opencode.getServerPort() + ".");
		output("[diag] step 4/5: loading baselines...");

		// 4. Load active baselines (with their source folders, to find setup.servoy).
		File baselinesRoot = configuration.getBaselinesDir();
		if (baselinesRoot == null) {
			baselinesRoot = SkillTestPaths.baselinesRoot();
		}
		List<BaselineLoader.Entry> entries = new ArrayList<>();
		for (BaselineLoader.Entry entry : BaselineLoader.loadAllEntries(baselinesRoot)) {
			if (entry.baseline().active()) {
				entries.add(entry);
			}
		}
		output("Loaded " + entries.size() + " active baseline(s) from " + baselinesRoot);

		// 5. For each baseline: setup (clean workspace + materialize the declared
		// git/folder initial state, then activate via the exporter's headless Servoy
		// model) then replay.
		SkillTestRunner runner = new SkillTestRunner(this::output);
		SolutionSetup setup = new SolutionSetup(this::output, new McpToolClient(webPort, this::output));
		List<SkillTestResult> results = new ArrayList<>();
		for (BaselineLoader.Entry entry : entries) {
			Baseline baseline = entry.baseline();
			try {
				setup.prepare(baseline);
				results.add(runner.runBaseline(baseline));
			} catch (Exception setupEx) {
				ServoyLog.logError("Setup failed for baseline " + baseline.id(), setupEx);
				output("[skilltest] " + baseline.id() + " setup failed: " + setupEx.getMessage());
				results.add(SkillTestResult.error(baseline.id(), 0, "setup failed: " + setupEx.getMessage()));
			}
		}

		// 6. Always write a report so the CI JUnit publisher finds a file.
		Path outputDir = configuration.getOutputDir();
		Path reportFile = JUnitXmlReporter.writeReport(outputDir, "skilltest", results); //$NON-NLS-1$
		output("JUnit XML report written to: " + reportFile.toAbsolutePath());
		output(runner.formatResults(results));

		if (results.isEmpty()) {
			output("No active skill-test baselines - nothing to run (vacuous pass).");
			return;
		}
		long passed = results.stream().filter(SkillTestResult::isPass).count();
		long failed = results.stream().filter(r -> r.getStatus() == SkillTestResult.Status.FAIL).count();
		long errored = results.stream().filter(r -> r.getStatus() == SkillTestResult.Status.ERROR).count();
		output("Skill tests: " + passed + "/" + results.size() + " passed, " + failed + " failed, " + errored
				+ " error.");
		if (errored > 0) {
			outputError("One or more baselines could not be executed.");
			exitCode = EXIT_INFRA_FAILED;
		} else if (failed > 0) {
			outputError("One or more baselines diverged from their golden.");
			exitCode = EXIT_TESTS_FAILED;
		}
	}

	// --- embedded web server ---------------------------------------------------

	private int startAndWaitForWebServer() throws InterruptedException {
		IWebServerStarter webStarter = ApplicationServerRegistry.getService(IWebServerStarter.class);
		if (webStarter == null) {
			throw new IllegalStateException("No IWebServerStarter registered - cannot start the embedded web server.");
		}
		output("Starting embedded web server...");
		webStarter.startWebServer();

		long deadline = System.currentTimeMillis() + WEB_SERVER_TIMEOUT_MS;
		int port = ApplicationServerRegistry.get().getWebServerPort();
		while (port <= 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(WEB_SERVER_POLL_INTERVAL_MS);
			port = ApplicationServerRegistry.get().getWebServerPort();
		}
		if (port <= 0) {
			throw new IllegalStateException(
					"Web server port not available within " + (WEB_SERVER_TIMEOUT_MS / 1000) + " seconds");
		}
		while (System.currentTimeMillis() < deadline) {
			if (isHttpServing(port)) {
				return port;
			}
			Thread.sleep(WEB_SERVER_POLL_INTERVAL_MS);
		}
		throw new IllegalStateException("Web server on port " + port + " did not accept HTTP connections within "
				+ (WEB_SERVER_TIMEOUT_MS / 1000) + " seconds");
	}

	private static boolean isHttpServing(int port) {
		HttpURLConnection connection = null;
		try {
			URL url = URI.create("http://localhost:" + port + "/").toURL();
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(2000);
			connection.setReadTimeout(2000);
			connection.setInstanceFollowRedirects(false);
			return connection.getResponseCode() > 0;
		} catch (IOException notReadyYet) {
			return false;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	// --- output ----------------------------------------------------------------

	private void output(String msg) {
		System.out.println(msg);
	}

	private void outputError(String msg) {
		System.err.println(msg);
	}
}