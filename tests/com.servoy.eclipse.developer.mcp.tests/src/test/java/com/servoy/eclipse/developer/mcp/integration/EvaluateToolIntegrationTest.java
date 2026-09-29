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

import static com.servoy.eclipse.developer.mcp.junit.Assert.assertFalse;
import static com.servoy.eclipse.developer.mcp.junit.Assert.assertNotNull;
import static com.servoy.eclipse.developer.mcp.junit.Assert.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.developer.mcp.services.ExpressionEvaluationService;
import com.servoy.eclipse.developer.mcp.services.ExpressionEvaluationService.EvaluationResult;

/**
 * PDE plug-in integration tests for the SVY-21473 {@code evaluate} tool
 * ({@link ServoyDevServer#evaluate(String, String, String)} /
 * {@link ExpressionEvaluationService}).
 * <p>
 * These run inside a PDE-launched Eclipse with the Servoy application server
 * and an active solution, but <b>no running debug client</b>: the PDE test
 * harness does not Run/Debug a solution the way a developer does. So the
 * deterministic, always-reproducible behaviour exercised here is the <b>no
 * running debug client</b> path (spec 3.2 / AC "No running client -&gt; a
 * named, actionable message; never a timeout or a silently started client")
 * together with the argument-validation and target-resolution paths that go
 * through the live Servoy model.
 * <p>
 * The happy-path acceptance criteria (scopes/forms/foundset resolution,
 * {@code application.output} console capture, a {@code throw} yielding a
 * solution-relative frame, a foundset return being described) require an
 * actually-running debug client and are covered by the reflection-based unit
 * tests in {@code EvaluateToolTest} for the pure-logic parts; the end-to-end
 * evaluation against a live client is validated manually / on a developer
 * machine, as the harness cannot launch one.
 */
public class EvaluateToolIntegrationTest extends TestUtilitiesClass {

	private static final String TEST_SOLUTION = "test_evaluate_tool";
	private static final String RESOURCES_PRJ = "servoy_resources";

	private final ServoyDevServer server = new ServoyDevServer();
	private final ExpressionEvaluationService service = new ExpressionEvaluationService();

	public EvaluateToolIntegrationTest() {
		super(TEST_SOLUTION, RESOURCES_PRJ);
	}

	@BeforeAll
	public static void deleteProjectsBeforeClass() throws Exception {
		deleteProjects(TEST_SOLUTION, RESOURCES_PRJ);
		waitForWorkspaceBuildJobs();
	}

	@BeforeEach
	public void setUp() throws Exception {
		waitForAppServer();
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();
	}

	// -----------------------------------------------------------------------
	// No running debug client -> named, actionable message (never a timeout)
	// -----------------------------------------------------------------------

	@Test
	public void testEvaluate_noRunningClient_returnsNamedActionableMessage() throws Exception {
		// The harness has an active solution but never Run/Debugs it, so there is no
		// debug client.
		EvaluationResult result = service.evaluate("1 + 1", null, 5);

		assertNotNull("evaluate must never return null", result);
		assertNotNull("with no client running, the error slot must carry the actionable message", result.error);
		assertTrue("the message must state there is no running debug client: " + result.error,
				result.error.contains("No running debug client"));
		assertTrue("the message must name the target solution: " + result.error, result.error.contains(TEST_SOLUTION));
		assertTrue("the message must name the action to take (Run/Debug the solution): " + result.error,
				result.error.contains("Run/Debug"));
		assertFalse("the no-client path must NOT be a timeout: " + result.error, result.error.contains("timed out"));
	}

	@Test
	public void testEvaluate_noRunningClient_valueIsNull() throws Exception {
		EvaluationResult result = service.evaluate("scopes.globals.foo()", null, 5);
		assertNotNull(result);
		assertTrue("no value should be produced without a client", result.value == null);
	}

	@Test
	public void testEvaluate_unknownSolution_listsRunningClientsOrNamesTarget() throws Exception {
		// A solution name that no client has loaded: still the actionable message,
		// naming the requested target.
		EvaluationResult result = service.evaluate("1", "no_such_solution_XYZ", 5);
		assertNotNull(result);
		assertNotNull(result.error);
		assertTrue("must name the requested target solution: " + result.error,
				result.error.contains("no_such_solution_XYZ"));
	}

	// -----------------------------------------------------------------------
	// Argument validation (goes through the tool method, returns JSON string)
	// -----------------------------------------------------------------------

	@Test
	public void testEvaluate_blankExpression_returnsExpressionRequired() throws Exception {
		String json = server.evaluate("   ", null, null);
		assertNotNull(json);
		assertTrue("blank expression must be reported as required: " + json, json.contains("expression is required"));
	}

	@Test
	public void testEvaluate_nullExpression_returnsExpressionRequired() throws Exception {
		String json = server.evaluate(null, null, null);
		assertNotNull(json);
		assertTrue("null expression must be reported as required: " + json, json.contains("expression is required"));
	}

	@Test
	public void testEvaluate_tool_returnsJsonWithErrorSlot() throws Exception {
		// End-to-end through the @Tool method: with no client the JSON carries the
		// actionable message in "error".
		String json = server.evaluate("1 + 1", null, "5");
		assertNotNull(json);
		assertTrue("tool output must be a JSON object", json.startsWith("{") && json.endsWith("}"));
		assertTrue("tool output must contain an error field", json.contains("\"error\":"));
		assertTrue("tool output must contain a value field", json.contains("\"value\":"));
		assertTrue("tool output must contain a console field", json.contains("\"console\":"));
		assertTrue("with no client the JSON error must be the actionable message: " + json,
				json.contains("No running debug client"));
	}

	@Test
	public void testEvaluate_unparseableTimeout_stillReturnsResult() throws Exception {
		// An unparseable timeout must fall back to the default (15) rather than
		// throwing.
		String json = server.evaluate("1 + 1", null, "not-a-number");
		assertNotNull(json);
		assertTrue("must still produce the JSON result: " + json, json.contains("\"error\":"));
	}
}
