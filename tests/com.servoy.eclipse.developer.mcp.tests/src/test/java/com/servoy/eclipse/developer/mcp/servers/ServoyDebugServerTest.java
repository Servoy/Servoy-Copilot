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
package com.servoy.eclipse.developer.mcp.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;

/**
 * Jupiter (JUnit 6) unit tests for the thin MCP tool layer {@link ServoyDebugServer}
 * (SVY-21443). These verify the {@code @McpServer}/{@code @Tool}/{@code @ToolParam}
 * contract and the error-to-string marshalling that must never let an exception
 * escape the tool, mirroring the {@code ServoyDevServerTest} style.
 */
public class ServoyDebugServerTest {

	private final ServoyDebugServer server = new ServoyDebugServer();

	// -----------------------------------------------------------------------
	// annotation / contract
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("@McpServer name is 'servoy-debug'")
	void hasCorrectServerAnnotation() {
		McpServer ann = ServoyDebugServer.class.getAnnotation(McpServer.class);
		assertNotNull(ann, "ServoyDebugServer must have @McpServer annotation");
		assertEquals("servoy-debug", ann.name());
	}

	@Test
	void hasNoArgConstructorForE4Di() throws Exception {
		assertNotNull(ServoyDebugServer.class.getDeclaredConstructor(),
				"ServoyDebugServer must have a no-arg constructor for E4 DI");
	}

	@Test
	void ping_returnsPong() {
		assertEquals("pong", server.ping());
	}

	@Test
	void hasExecuteInRunningClientTool() {
		assertTrue(hasToolNamed("executeInRunningClient"),
				"ServoyDebugServer must expose the 'executeInRunningClient' tool");
	}

	@Test
	void executeInRunningClient_hasFourParams() {
		Method m = findToolMethod("executeInRunningClient");
		assertNotNull(m);
		assertEquals(4, m.getParameterCount(), "executeInRunningClient must have script, methodName, args, timeoutSeconds");
	}

	@Test
	void executeInRunningClient_returnsString() {
		Method m = findToolMethod("executeInRunningClient");
		assertNotNull(m);
		assertEquals(String.class, m.getReturnType());
	}

	@Test
	void executeInRunningClient_allParamsHaveToolParam() {
		Method m = findToolMethod("executeInRunningClient");
		assertNotNull(m);
		long annotated = Arrays.stream(m.getParameters()).filter(p -> p.isAnnotationPresent(ToolParam.class)).count();
		assertEquals(4, annotated, "all executeInRunningClient params must carry @ToolParam");
	}

	@Test
	@DisplayName("timeoutSeconds is declared as an integer JSON-schema type")
	void executeInRunningClient_timeoutParamIsInteger() {
		Method m = findToolMethod("executeInRunningClient");
		assertNotNull(m);
		ToolParam timeout = Arrays.stream(m.getParameters()).map(p -> p.getAnnotation(ToolParam.class))
				.filter(p -> p != null && "timeoutSeconds".equals(p.name())).findFirst().orElse(null);
		assertNotNull(timeout, "timeoutSeconds @ToolParam must exist");
		assertEquals("integer", timeout.type(), "timeoutSeconds must be an integer schema type");
	}

	@Test
	void executeInRunningClient_scriptAndMethodAreOptional() {
		Method m = findToolMethod("executeInRunningClient");
		assertNotNull(m);
		assertFalse(paramRequired(m, "script"), "'script' must be optional (either script or methodName suffices)");
		assertFalse(paramRequired(m, "methodName"), "'methodName' must be optional");
		assertFalse(paramRequired(m, "args"), "'args' must be optional");
	}

	@Test
	@DisplayName("tool description states it runs in the RUNNING client and returns result + output")
	void executeInRunningClient_descriptionIsDiscoverable() {
		Method m = findToolMethod("executeInRunningClient");
		assertNotNull(m);
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue(desc.contains("RUNNING") || desc.contains("running"),
				"description must make clear this targets the running client");
		assertTrue(desc.contains("application.output") || desc.contains("console"),
				"description must mention the captured console/application.output text");
	}

	// -----------------------------------------------------------------------
	// error-to-string marshalling (delegates to the service; without a live
	// client it must return an actionable string, never throw)
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("with neither script nor methodName the tool returns the 'required' guard, not a crash")
	void executeInRunningClient_emptyRequest_returnsRequiredMessage() {
		String result = server.executeInRunningClient(null, null, null, 30);
		assertNotNull(result, "tool must never return null");
		assertTrue(result.contains("at least one of 'script' or 'methodName' is required"),
				"empty request must surface the documented guard message: " + result);
	}

	@Test
	@DisplayName("a valid request with no client is reported as an actionable message, never as a tool failure")
	void executeInRunningClient_noClient_returnsActionableMessage() {
		String result = server.executeInRunningClient("application.output('x')", null, null, 5);
		assertNotNull(result);
		assertTrue(
				result.contains("No running Servoy client") || result.contains("debug environment is not available"),
				"without a client the tool must return an actionable message: " + result);
	}

	// -----------------------------------------------------------------------
	// helpers
	// -----------------------------------------------------------------------

	private boolean hasToolNamed(String name) {
		return Arrays.stream(ServoyDebugServer.class.getMethods()).filter(m -> m.isAnnotationPresent(Tool.class))
				.anyMatch(m -> name.equals(m.getAnnotation(Tool.class).name()));
	}

	private Method findToolMethod(String toolName) {
		return Arrays.stream(ServoyDebugServer.class.getMethods()).filter(m -> m.isAnnotationPresent(Tool.class))
				.filter(m -> toolName.equals(m.getAnnotation(Tool.class).name())).findFirst().orElse(null);
	}

	private boolean paramRequired(Method m, String paramName) {
		return Arrays.stream(m.getParameters()).map(p -> p.getAnnotation(ToolParam.class))
				.filter(p -> p != null && paramName.equals(p.name())).map(ToolParam::required).findFirst()
				.orElseThrow(() -> new AssertionError("param not found: " + paramName));
	}
}
