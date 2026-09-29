/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.RunningClientExecutionService;
import com.servoy.j2db.IApplication;
import com.servoy.j2db.IDebugClient;
import com.servoy.j2db.IDebugClientHandler;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * PDE plug-in integration test for the {@code servoy-debug}
 * {@link RunningClientExecutionService} (SVY-21443). Runs inside a PDE-launched
 * Eclipse with a live Servoy Application Server and an activated solution.
 * <p>
 * <b>Two-part strategy.</b> A genuinely debug-ready client
 * ({@code getDebugReadyClient() != null}) only exists when a solution has been
 * launched under the DBGP debugger and
 * {@code RemoteDebugScriptEngine.isConnected(0)} is true - a state the shared
 * PDE test harness does <em>not</em> create. Rather than skip (which
 * {@code @Disabled}/{@code Assumptions} would do), this test:
 * <ol>
 * <li>Always asserts the deterministic, client-independent behaviour reachable
 * in the harness: the guard messages ("at least one of script or methodName is
 * required") and the actionable "no running client" / "debug environment is not
 * available" message - and that a missing client is <em>never</em> misreported
 * as a timeout.</li>
 * <li>When a debug-ready client <em>is</em> present (e.g. a developer/CI leaves
 * one running), the {@link #executeInLiveClient_scriptReturnsValueAndCapturesOutput()}
 * and {@link #executeInLiveClient_namedMethodReturnsValue()} tests assert the
 * full contract - evaluated value, captured {@code application.output}, named
 * method invocation - and <em>fail hard</em> if the client is present but
 * misbehaves. When no client is present they assert the accurate
 * no-client fallback instead, so they are never green-for-nothing.</li>
 * </ol>
 * Run via {@code eclipse-pde_runJUnitPluginTestClass} using an integration
 * launch config.
 */
public class RunningClientExecutionIntegrationTest extends AbstractIntegrationTest {

	private static final String TEST_SOLUTION = "test_debug_exec_suite";
	private static final String RESOURCES_PRJ = "servoy_resources";

	private RunningClientExecutionService service;

	public RunningClientExecutionIntegrationTest() {
		super(TEST_SOLUTION, RESOURCES_PRJ);
	}

	@BeforeAll
	public static void deleteProjectsBeforeClass() throws Exception {
		deleteProjects(TEST_SOLUTION, RESOURCES_PRJ);
		waitForWorkspaceBuildJobs();
	}

	@BeforeEach
	public void setUp() throws Exception {
		service = new RunningClientExecutionService();

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");
		waitForAppServer();

		ensureTestSolutionInWorkspace(null, (solProject, monitor) -> {
			try {
				// A global function we can invoke by name and that produces console output,
				// so a live-client run has something deterministic to return and capture.
				writeProjectFile(solProject, "globals.js",
						"/**\n * @properties={typeid:24,uuid:\"a1b2c3d4-e5f6-7890-abcd-ef0123456789\"}\n */\n"
								+ "function addAndTrace(a, b) {\n"
								+ "\tapplication.output('adding ' + a + ' and ' + b);\n"
								+ "\treturn a + b;\n"
								+ "}\n",
						monitor);
			} catch (CoreException e) {
				fail("Can't write globals.js: " + e.getMessage());
			}
		});

		ensureActiveProject();
	}

	private static IDebugClient debugReadyClientOrNull() {
		if (!ApplicationServerRegistry.exists()) return null;
		IDebugClientHandler handler = ApplicationServerRegistry.get().getDebugClientHandler();
		if (handler == null) return null;
		IApplication client = handler.getDebugReadyClient();
		return client instanceof IDebugClient dc ? dc : null;
	}

	// -----------------------------------------------------------------------
	// Always-deterministic guard behaviour (reachable in the harness)
	// -----------------------------------------------------------------------

	@Test
	public void execute_neitherScriptNorMethod_returnsRequiredMessage() {
		String result = service.execute(null, null, null, 10);
		assertNotNull(result, "execute must never return null");
		assertTrue(result.contains("at least one of 'script' or 'methodName' is required"),
				"empty request must surface the documented 'required' guard: " + result);
	}

	@Test
	public void execute_noClient_returnsActionableMessage_notTimeout() {
		if (debugReadyClientOrNull() != null) {
			// A client is running in this environment - the no-client path is unreachable,
			// so this specific assertion does not apply. The live-client tests below cover
			// the running-client behaviour and fail hard on misbehaviour.
			return;
		}
		String result = service.execute("application.output('probe')", null, null, 5);
		assertNotNull(result);
		assertTrue(result.contains("No running Servoy client")
				|| result.contains("debug environment is not available"),
				"without a client execute must return an actionable message: " + result);
		assertFalse(result.contains("Timed out"),
				"a missing client must NOT be misreported as a timeout: " + result);
	}

	// -----------------------------------------------------------------------
	// Live-client behaviour: full contract when a debug-ready client exists,
	// accurate no-client fallback otherwise (never green-for-nothing).
	// -----------------------------------------------------------------------

	@Test
	public void executeInLiveClient_scriptReturnsValueAndCapturesOutput() {
		IDebugClient client = debugReadyClientOrNull();
		String result = service.execute("application.output('captured-marker'); 7 + 5", null, null, 30);
		assertNotNull(result, "execute must never return null");

		if (client == null) {
			assertTrue(result.contains("No running Servoy client")
					|| result.contains("debug environment is not available"),
					"with no live client, the result must be the actionable no-client message: " + result);
			return;
		}

		// A real debug-ready client is present: assert the full contract.
		assertTrue(result.contains("**servoy-debug: executeInRunningClient**"),
				"result blob must carry the servoy-debug header: " + result);
		assertFalse(result.contains("Timed out"),
				"a healthy script run must not report a timeout: " + result);
		assertTrue(result.contains("Result:\n12") || result.contains("Result: 12"),
				"the evaluated value (12) must be reported in the Result section: " + result);
		assertTrue(result.contains("captured-marker"),
				"the application.output text produced during the run must be captured: " + result);
	}

	@Test
	public void executeInLiveClient_namedMethodReturnsValue() {
		IDebugClient client = debugReadyClientOrNull();
		String result = service.execute(null, "scopes.globals.addAndTrace", "[2, 3]", 30);
		assertNotNull(result);

		if (client == null) {
			assertTrue(result.contains("No running Servoy client")
					|| result.contains("debug environment is not available"),
					"with no live client, the named-method call must return the no-client message: " + result);
			return;
		}

		assertFalse(result.contains("Timed out"),
				"a healthy named-method run must not report a timeout: " + result);
		assertTrue(result.contains("Result:\n5") || result.contains("Result: 5"),
				"the named method's return value (5) must be reported: " + result);
		assertTrue(result.contains("adding 2 and 3"),
				"output produced by the named method must be captured: " + result);
	}

	@Test
	public void executeInLiveClient_thrownError_isReadableNotACrash() {
		IDebugClient client = debugReadyClientOrNull();
		String result = service.execute("thisReferenceDoesNotExist()", null, null, 30);
		assertNotNull(result);

		if (client == null) {
			assertTrue(result.contains("No running Servoy client")
					|| result.contains("debug environment is not available"),
					"with no live client, the erroring script must return the no-client message: " + result);
			return;
		}

		assertTrue(result.contains("Error:"),
				"a thrown JS error must be reported as a readable Error, not a crash: " + result);
		assertTrue(result.contains("thisReferenceDoesNotExist") || result.contains("is not defined"),
				"the ReferenceError should name the missing reference: " + result);
		assertFalse(result.contains("Timed out"),
				"an error must not be misreported as a timeout: " + result);
	}
}