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
import java.lang.management.ManagementFactory;
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
import com.servoy.eclipse.opencode.skilltest.RunHistory;
import com.servoy.eclipse.opencode.skilltest.SkillTestPaths;
import com.servoy.eclipse.opencode.skilltest.SkillTestResult;
import com.servoy.eclipse.opencode.skilltest.SkillTestRunner;
import com.servoy.eclipse.opencode.skilltest.TrendAlerts;
import com.servoy.eclipse.opencode.skilltest.TrendReportHtml;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;
import com.servoy.j2db.server.starter.IWebServerStarter;
import com.servoy.j2db.util.Settings;

/**
 * Headless Eclipse application that replays Servoy AI skill-test baselines in
 * CI without the Developer IDE UI.
 * <p>
 * This is a plain {@link IApplication} (it deliberately does <b>not</b> extend
 * {@code AbstractWorkspaceExporter}). It performs the minimal Servoy headless
 * bootstrap itself - disables the UI, loads the settings file, starts the
 * embedded application server + Servoy model - and then, <b>per baseline</b>,
 * cleans that baseline's solution project and imports its {@code setup.servoy}
 * initial state before replaying it. Each baseline is therefore self-contained:
 * the starting workspace state is part of the baseline, not something the CI
 * job must pre-populate.
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
 * Exit codes: {@code 0} = all baselines passed (or none active), {@code 1} =
 * one or more diverged, {@code 2} = infrastructure error, {@code 3} = invalid
 * args.
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

	/**
	 * How long to wait for the workbench to start the Servoy application server.
	 */
	private static final long APP_SERVER_WAIT_MS = 180_000L;

	/**
	 * How long to wait for the opencode server to come up. The first CI run has to
	 * download node + {@code npm install} the pinned opencode cli, which can exceed
	 * the old 180s. Overridable with {@code -Dservoy.skilltest.opencodeWaitMs} so
	 * CI can tune it without a rebuild; defaults to 420s.
	 */
	private static final long OPENCODE_SERVER_WAIT_MS = Long.getLong("servoy.skilltest.opencodeWaitMs", 420_000L); //$NON-NLS-1$

	/**
	 * Hard wall-clock limit for ONE baseline (setup + replay + verify). A baseline
	 * that overruns is stopped and recorded as an ERROR, and the run continues.
	 * Overridable with {@code -Dservoy.skilltest.baselineTimeoutSeconds}; defaults
	 * to 1200s.
	 */
	private static final long BASELINE_TIMEOUT_MS = Long.getLong("servoy.skilltest.baselineTimeoutSeconds", 1_200L) //$NON-NLS-1$
			* 1_000L;

	/**
	 * Time budget for the whole run, counted from JVM start. Baselines that no
	 * longer fit are SKIPPED, so the report, history and trend are still written
	 * before the forked-process timeout (tests pom, 3600s) kills the JVM. Must stay
	 * below that timeout. Overridable with
	 * {@code -Dservoy.skilltest.runBudgetSeconds}; defaults to 2900s.
	 */
	private static final long RUN_BUDGET_MS = Long.getLong("servoy.skilltest.runBudgetSeconds", 2_900L) * 1_000L; //$NON-NLS-1$

	/** A baseline is not started when less than this is left of the run budget. */
	private static final long MIN_BASELINE_MS = 120_000L;

	/** How long a timed-out baseline gets to stop after being cancelled/interrupted. */
	private static final long STOP_GRACE_MS = 30_000L;

	private Integer exitCode = EXIT_OK;

	/** Identity + id shared by every history line of this run (created on first use). */
	private RunHistory.RunIdentity runIdentity;
	private String runId;

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
		// Probe the MCP endpoint with the session bearer token. The auth filter answers
		// an unauthenticated (or wrongly authenticated) request with a bare 404 so the
		// endpoint looks unmapped; without the token every probe read as "not
		// registered". With it, any non-404 (the transport rejects a plain GET with a
		// 4xx) means registered, and a 404 really means no servlet. No SSE Accept
		// header, so the GET does not open a long-lived event stream.
		String url = "http://127.0.0.1:" + webPort + "/dev_mcp/servoy-dev";
		String token = McpToolClient.readSessionAuthToken();
		try {
			HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
			c.setRequestMethod("GET");
			if (token != null && !token.isBlank()) {
				c.setRequestProperty("Authorization", "Bearer " + token);
			}
			c.setConnectTimeout(2000);
			c.setReadTimeout(2000);
			int code = c.getResponseCode();
			c.disconnect();
			String meaning;
			if (code != 404) {
				meaning = " (servlet present)";
			} else if (token == null || token.isBlank()) {
				meaning = " (inconclusive: no session token to authenticate the probe)";
			} else {
				meaning = " (servlet NOT registered - MCP servers did not start)";
			}
			output("[diag] MCP endpoint " + url + " -> HTTP " + code + meaning);
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
			// CRITICAL: under tycho-surefire ModelUtils.isTestRunning() is true, so
			// ngclient.ui.Activator starts with
			// nodeExtractionAndTitaniumBuildDisabled=true.
			// In that state extractNode() returns IMMEDIATELY WITHOUT counting down its
			// nodeReady latch - so OpencodeFolderCreatorJob's createNPMCommand() ->
			// waitForNodeExtraction() blocks forever and opencode never starts ("opencode
			// server did not become ready"). The skill test genuinely needs node (opencode
			// is a node app), so re-enable extraction before triggering it. The setter is
			// provided for exactly this ("each test class can change it at will").
			com.servoy.eclipse.ngclient.ui.Activator.setNodeExtractionAndTitaniumBuildDisabled(false);
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
		Path outputDir = configuration.getOutputDir();
		List<SkillTestResult> results = new ArrayList<>();
		// Two guards so one wedged baseline cannot eat the whole forked-process timeout
		// (build 94: a hung JSUnit verify burned 42 minutes until Maven killed the fork,
		// so no history, trend report or p2 deploy was produced):
		// - every baseline runs under a hard per-baseline limit (BaselineWatchdog);
		// - the whole loop has a time budget; baselines that no longer fit are SKIPPED.
		// The run history + trend report are written after EVERY baseline, so even a
		// hard kill leaves everything that finished.
		// The budget counts from JVM start (the fork timeout does too), so a slow
		// workbench/opencode startup eats into it instead of pushing the run past the kill.
		long budgetLeftMs = Math.max(0L, RUN_BUDGET_MS - ManagementFactory.getRuntimeMXBean().getUptime());
		long runDeadlineNanos = System.nanoTime() + budgetLeftMs * 1_000_000L;
		String stopReason = null;
		for (int i = 0; i < entries.size(); i++) {
			BaselineLoader.Entry entry = entries.get(i);
			Baseline baseline = entry.baseline();
			long remainingMs = (runDeadlineNanos - System.nanoTime()) / 1_000_000L;
			if (stopReason == null && remainingMs < MIN_BASELINE_MS) {
				stopReason = "not reached: the run time budget (" + (RUN_BUDGET_MS / 1000) //$NON-NLS-1$
						+ "s) was used up before this baseline"; //$NON-NLS-1$
			}
			if (stopReason != null) {
				output("[skilltest] " + baseline.id() + " skipped - " + stopReason); //$NON-NLS-1$ //$NON-NLS-2$
				SkillTestResult skipped = SkillTestResult.skipped(baseline.id(), stopReason);
				results.add(skipped);
				recordHistoryAndTrends(List.of(skipped), entries, outputDir);
				continue;
			}
			// Incremental report: before each baseline, rewrite TEST-skilltest.xml with the
			// finished results, THIS baseline as an ERROR placeholder ("did not finish")
			// and
			// the rest as SKIPPED. If the run is killed mid-baseline (hang -> Maven/Jenkins
			// timeout), CI still publishes everything that ran, and names the baseline that
			// was running when it died. The next write replaces the placeholder.
			writeInterimReport(outputDir, entries, results, i);
			long limitMs = Math.min(BASELINE_TIMEOUT_MS, remainingMs);
			BaselineWatchdog.Outcome<SkillTestResult> outcome = BaselineWatchdog.run(baseline.id(), cancelled -> {
				setup.prepare(baseline);
				// Pass the baseline's ACTUAL on-disk folder (entry.folder(), from the CI
				// -Dservoy.skilltest.baselines dir) so JSUnit verify scripts resolve there.
				// Without it the runner recomputes the folder from the workspace baselines
				// root, which doesn't match in CI -> "verify script not found".
				return runner.runBaseline(baseline, cancelled, entry.folder());
			}, limitMs, STOP_GRACE_MS);
			SkillTestResult result;
			switch (outcome.state()) {
			case COMPLETED -> {
				Throwable failure = outcome.failure();
				if (failure != null) {
					ServoyLog.logError("Setup failed for baseline " + baseline.id(),
							failure instanceof Exception ex ? ex : new RuntimeException(failure));
					output("[skilltest] " + baseline.id() + " setup failed: " + failure.getMessage());
					result = SkillTestResult.error(baseline.id(), 0, "setup failed: " + failure.getMessage());
				} else if (outcome.value() == null) {
					result = SkillTestResult.error(baseline.id(), 0, "the baseline produced no result"); //$NON-NLS-1$
				} else {
					result = outcome.value();
				}
			}
			case TIMED_OUT_STOPPED -> {
				output("[skilltest] " + baseline.id() + " TIMED OUT after " + (limitMs / 1000) //$NON-NLS-1$ //$NON-NLS-2$
						+ "s and was stopped; continuing with the next baseline."); //$NON-NLS-1$
				result = SkillTestResult.error(baseline.id(), 0, "timed out after " + (limitMs / 1000) //$NON-NLS-1$
						+ "s (per-baseline limit) and was stopped"); //$NON-NLS-1$
			}
			default -> {
				// The worker ignored cancel + interrupt: it may still be driving the
				// workbench, so starting the next baseline against it would be unsafe.
				output("[skilltest] " + baseline.id() + " TIMED OUT after " + (limitMs / 1000) //$NON-NLS-1$ //$NON-NLS-2$
						+ "s and could NOT be stopped; the remaining baselines are skipped."); //$NON-NLS-1$
				result = SkillTestResult.error(baseline.id(), 0, "timed out after " + (limitMs / 1000) //$NON-NLS-1$
						+ "s (per-baseline limit) and could not be stopped"); //$NON-NLS-1$
				stopReason = "not reached: baseline '" + baseline.id() //$NON-NLS-1$
						+ "' hung and could not be stopped, so the workbench is not safe to reuse"; //$NON-NLS-1$
			}
			}
			results.add(result);
			// Persist this result NOW (history line + trend report), so a later hang or a
			// hard kill of the fork still leaves everything that finished.
			recordHistoryAndTrends(List.of(result), entries, outputDir);
		}

		// 6. Final report (all baselines done). Carry any WARN-only budget warnings
		// (SVY-21660) into <system-out>; they never change failure/error counts.
		Path reportFile = JUnitXmlReporter.writeReport(outputDir, "skilltest", results, //$NON-NLS-1$
				budgetWarnings(entries, results));
		output("JUnit XML report written to: " + reportFile.toAbsolutePath());
		output(runner.formatResults(results));

		// 6b. Coverage report (SVY-21711) - informational, best-effort. Written into
		// the SAME outputDir as the JUnit XML so Jenkins archives it via the existing
		// skilltest-reports/** glob (no Jenkinsfile change). emitCoverage never throws:
		// a failure here must not fail the run or change the exit code.
		// Fixed CI file name ("coverage.html") so each build overwrites it: the
		// archived skilltest-reports dir keeps a single, latest coverage report
		// instead of accumulating one timestamped file per build (the CI workspace
		// is not cleaned between runs).
		com.servoy.eclipse.opencode.skilltest.coverage.CoverageReportHtml.emitCoverage(results, null, outputDir,
				"coverage.html", this::output); //$NON-NLS-1$

		// 6c. Run history + trend alerts + trend report (SVY-21661) - best-effort,
		// never touches exitCode.
		// The results were already appended one by one inside the loop, so this final
		// call appends nothing and only refreshes alerts.json / trend.html.
		recordHistoryAndTrends(List.of(), entries, outputDir);

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

	/**
	 * Appends the completed run to the run history (SVY-21661), copies the history
	 * into {@code outputDir} for archiving when it lives elsewhere, then evaluates
	 * the trend alerts and writes {@code alerts.json}, {@code trend.html} and
	 * {@code skilltest-report.css} into {@code outputDir}. Best-effort: nothing
	 * here throws or changes the exit code; the pipeline decides UNSTABLE from
	 * {@code alerts.json}.
	 */
	private void recordHistoryAndTrends(List<SkillTestResult> results, List<BaselineLoader.Entry> entries,
			Path outputDir) {
		try {
			java.util.Map<String, String> models = new java.util.HashMap<>();
			java.util.Map<String, List<Baseline.Budget>> budgets = new java.util.HashMap<>();
			for (BaselineLoader.Entry entry : entries) {
				Baseline b = entry.baseline();
				try {
					String model = SkillTestRunner.resolveModel(b);
					if (model != null) {
						models.put(b.id(), model);
					}
				} catch (RuntimeException e) {
					// unknown model -> field omitted
				}
				if (b.budgets() != null && !b.budgets().isEmpty()) {
					budgets.put(b.id(), b.budgets());
				}
			}
			File historyFile = SkillTestPaths.historyFile();
			// One identity / run id for the whole run: the history is written after every
			// baseline, and all those lines must belong to the same run.
			if (runIdentity == null) {
				runIdentity = RunHistory.RunIdentity.ci();
				runId = RunHistory.runId(runIdentity);
			}
			RunHistory.append(historyFile, results, runIdentity, runId, models::get, this::output);
			copyHistoryIntoOutputDir(historyFile, outputDir);
			if (outputDir == null) {
				return;
			}
			List<RunHistory.Entry> history = RunHistory.read(historyFile);
			TrendAlerts.AlertConfig cfg = TrendAlerts.AlertConfig.fromSystem();
			List<TrendAlerts.Alert> alerts = TrendAlerts.evaluate(history, runId, budgets, cfg);
			TrendAlerts.writeAlertsJson(outputDir.resolve(TrendAlerts.ALERTS_FILE).toFile(), runId, alerts,
					this::output);
			TrendReportHtml.writeReport(outputDir.toFile(), TrendReportHtml.render(history, alerts, cfg.window()),
					this::output);
			if (alerts.isEmpty()) {
				output("[skilltest] no trend alerts."); //$NON-NLS-1$
			}
			for (TrendAlerts.Alert a : alerts) {
				output("[skilltest] TREND ALERT " + a.rule() + " " + a.baselineId() + ": " + a.message() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						+ (a.likelyCause() != null ? " (likely cause: " + a.likelyCause() + ")" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
		} catch (RuntimeException | LinkageError e) {
			output("[skilltest] run history / trend report failed: " + e); //$NON-NLS-1$
		}
	}

	/**
	 * Copies the history file to {@code outputDir/run-history.jsonl} when it lives
	 * outside {@code outputDir}, so the per-build archive holds a snapshot.
	 * Best-effort.
	 */
	private void copyHistoryIntoOutputDir(File historyFile, Path outputDir) {
		if (historyFile == null || outputDir == null || !historyFile.isFile()) {
			return;
		}
		try {
			Path target = outputDir.resolve(SkillTestPaths.HISTORY_FILE);
			Path source = historyFile.toPath();
			if (java.nio.file.Files.exists(target) && java.nio.file.Files.isSameFile(source, target)) {
				return;
			}
			if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
				return;
			}
			java.nio.file.Files.createDirectories(outputDir);
			java.nio.file.Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			output("[skilltest] could not copy run history into " + outputDir + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/**
	 * Rewrites {@code TEST-skilltest.xml} with the run's progress so far, so a run
	 * killed mid-baseline (hang, Maven/Jenkins timeout) still leaves a report of
	 * everything that ran. Finished baselines keep their real result and metrics;
	 * the baseline at {@code runningIndex} is written as an ERROR ("did not
	 * finish") and the ones after it as SKIPPED. Best-effort: a write failure is
	 * logged and never stops the run.
	 *
	 * @param outputDir    the report directory
	 * @param entries      all active baselines, in run order
	 * @param finished     the results of the baselines that have completed
	 * @param runningIndex the index in {@code entries} of the baseline about to run
	 */
	private void writeInterimReport(Path outputDir, List<BaselineLoader.Entry> entries, List<SkillTestResult> finished,
			int runningIndex) {
		List<SkillTestResult> interim = new ArrayList<>(finished);
		for (int j = runningIndex; j < entries.size(); j++) {
			String id = entries.get(j).baseline().id();
			interim.add(j == runningIndex
					? SkillTestResult.error(id, 0,
							"did not finish: the run was stopped while this baseline was running") //$NON-NLS-1$
					: SkillTestResult.skipped(id, "not reached: the run was stopped before this baseline")); //$NON-NLS-1$
		}
		try {
			JUnitXmlReporter.writeReport(outputDir, "skilltest", interim, budgetWarnings(entries, finished)); //$NON-NLS-1$
		} catch (IOException | RuntimeException e) {
			output("[skilltest] interim report write failed: " + e.getMessage()); //$NON-NLS-1$
		}
	}

	/**
	 * WARN-only budget warnings (SVY-21660) for the baselines that have a result
	 * with metrics; never changes failure/error counts.
	 */
	private static java.util.Map<String, String> budgetWarnings(List<BaselineLoader.Entry> entries,
			List<SkillTestResult> results) {
		java.util.Map<String, String> warnings = new java.util.HashMap<>();
		for (BaselineLoader.Entry entry : entries) {
			SkillTestResult result = results.stream().filter(res -> entry.baseline().id().equals(res.getBaselineId()))
					.findFirst().orElse(null);
			if (entry.baseline().hasBudget() && result != null && result.getMetrics() != null) {
				com.servoy.eclipse.opencode.skilltest.BudgetEvaluator
						.evaluateAll(entry.baseline().budgets(), result.getMetrics())
						.ifPresent(w -> warnings.put(entry.baseline().id(), w));
			}
		}
		return warnings;
	}

	// --- embedded web server ---------------------------------------------------

	private int startAndWaitForWebServer() throws InterruptedException {
		// The Servoy app server may have already started the embedded Tomcat during
		// solution/workbench load - it binds the fixed web-server port. Calling
		// startWebServer() a second time then tries to bind the same port and throws
		// "java.net.BindException: Address already in use", logging a SEVERE connector
		// failure plus a cascade of "naming context ... Context is read only" SEVEREs,
		// even though the first instance is serving fine. Reuse the running server when
		// its port is already live instead of starting a second one (SVY-21366).
		int running = ApplicationServerRegistry.get().getWebServerPort();
		if (running > 0) {
			output("Embedded web server already running on port " + running + " - reusing it.");
			return running;
		}

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