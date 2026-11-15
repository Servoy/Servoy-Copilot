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
package com.servoy.eclipse.developer.mcp.plainjunit.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.servers.ServoyDebugServer;
import com.servoy.eclipse.developer.mcp.services.DebugInspectionService;
import com.servoy.eclipse.developer.mcp.services.RunningClientExecutionService;

/**
 * Plain-JUnit (Jupiter) unit tests for {@link ServoyDebugServer}'s <em>own</em>
 * logic - without a live Eclipse workspace or a running debug client.
 * <p>
 * {@code ServoyDebugServer} is a thin tool wrapper: every debug tool simply calls
 * a {@code private final} collaborator ({@link RunningClientExecutionService} for
 * {@code executeInRunningClient}, {@link DebugInspectionService} for everything
 * else) inside a {@code try}/{@code catch}, returning the delegate's result on the
 * happy path and {@code "Error: " + message} when the delegate throws. There is no
 * {@code ServoyModelManager} model grabbed here, so no {@code getServoyModel()} seam
 * is needed; the two {@code private final} collaborators are swapped for Mockito
 * mocks via reflection (so no production visibility is widened and no seam is
 * introduced just for the test). The collaborators are stubbed only at the boundary
 * to drive the server's own branches - the tool-argument {@literal ->} delegate-call
 * mapping, the pass-through of the delegate's result string, and the exception
 * {@literal ->} {@code "Error: "} marshalling that must never let an exception escape
 * a tool. The collaborators' real behaviour (the running-client execution, the
 * Eclipse debug-model inspection) needs the workbench / a launched debug client and
 * stays in the {@code integrationpde} suite.
 * </p>
 *
 * @since 2026.11
 */
public class ServoyDebugServerTest
{

	private final ServoyDebugServer server = new ServoyDebugServer();

	// =======================================================================
	// helpers
	// =======================================================================

	/** Builds a server whose collaborators are the supplied mocks (reflection swap). */
	private static ServoyDebugServer serverWith(RunningClientExecutionService execution, DebugInspectionService inspection)
	{
		ServoyDebugServer s = new ServoyDebugServer();
		setField(s, "executionService", execution);
		setField(s, "inspectionService", inspection);
		return s;
	}

	private static void setField(Object target, String name, Object value)
	{
		try
		{
			Field f = ServoyDebugServer.class.getDeclaredField(name);
			f.setAccessible(true);
			f.set(target, value);
		}
		catch (ReflectiveOperationException e)
		{
			throw new RuntimeException("failed to set field '" + name + "'", e);
		}
	}

	private boolean hasToolNamed(String name)
	{
		return Arrays.stream(ServoyDebugServer.class.getMethods()).filter(m -> m.isAnnotationPresent(Tool.class))
			.anyMatch(m -> name.equals(m.getAnnotation(Tool.class).name()));
	}

	private Method findToolMethod(String toolName)
	{
		return Arrays.stream(ServoyDebugServer.class.getMethods()).filter(m -> m.isAnnotationPresent(Tool.class))
			.filter(m -> toolName.equals(m.getAnnotation(Tool.class).name())).findFirst().orElse(null);
	}

	private boolean paramRequired(Method m, String paramName)
	{
		return Arrays.stream(m.getParameters()).map(p -> p.getAnnotation(ToolParam.class))
			.filter(p -> p != null && paramName.equals(p.name())).map(ToolParam::required).findFirst()
			.orElseThrow(() -> new AssertionError("param not found: " + paramName));
	}

	// =======================================================================
	// annotation / contract
	// =======================================================================

	@Nested
	class AnnotationAndContract
	{
		@Test
		@DisplayName("@McpServer name is 'servoy-debug'")
		void hasCorrectServerAnnotation()
		{
			McpServer ann = ServoyDebugServer.class.getAnnotation(McpServer.class);
			assertNotNull(ann, "ServoyDebugServer must have @McpServer annotation");
			assertEquals("servoy-debug", ann.name());
		}

		@Test
		void hasNoArgConstructorForE4Di() throws Exception
		{
			assertNotNull(ServoyDebugServer.class.getDeclaredConstructor(),
				"ServoyDebugServer must have a no-arg constructor for E4 DI");
		}

		@Test
		void ping_returnsPong()
		{
			assertEquals("pong", server.ping());
		}

		@Test
		void hasExecuteInRunningClientTool()
		{
			assertTrue(hasToolNamed("executeInRunningClient"),
				"ServoyDebugServer must expose the 'executeInRunningClient' tool");
		}

		@Test
		void executeInRunningClient_hasFourParams()
		{
			Method m = findToolMethod("executeInRunningClient");
			assertNotNull(m);
			assertEquals(4, m.getParameterCount(), "executeInRunningClient must have script, methodName, args, timeoutSeconds");
		}

		@Test
		void executeInRunningClient_returnsString()
		{
			Method m = findToolMethod("executeInRunningClient");
			assertNotNull(m);
			assertEquals(String.class, m.getReturnType());
		}

		@Test
		void executeInRunningClient_allParamsHaveToolParam()
		{
			Method m = findToolMethod("executeInRunningClient");
			assertNotNull(m);
			long annotated = Arrays.stream(m.getParameters()).filter(p -> p.isAnnotationPresent(ToolParam.class)).count();
			assertEquals(4, annotated, "all executeInRunningClient params must carry @ToolParam");
		}

		@Test
		@DisplayName("timeoutSeconds is declared as an integer JSON-schema type")
		void executeInRunningClient_timeoutParamIsInteger()
		{
			Method m = findToolMethod("executeInRunningClient");
			assertNotNull(m);
			ToolParam timeout = Arrays.stream(m.getParameters()).map(p -> p.getAnnotation(ToolParam.class))
				.filter(p -> p != null && "timeoutSeconds".equals(p.name())).findFirst().orElse(null);
			assertNotNull(timeout, "timeoutSeconds @ToolParam must exist");
			assertEquals("integer", timeout.type(), "timeoutSeconds must be an integer schema type");
		}

		@Test
		void executeInRunningClient_scriptAndMethodAreOptional()
		{
			Method m = findToolMethod("executeInRunningClient");
			assertNotNull(m);
			assertFalse(paramRequired(m, "script"), "'script' must be optional (either script or methodName suffices)");
			assertFalse(paramRequired(m, "methodName"), "'methodName' must be optional");
			assertFalse(paramRequired(m, "args"), "'args' must be optional");
		}

		@Test
		@DisplayName("tool description states it runs in the RUNNING client and returns result + output")
		void executeInRunningClient_descriptionIsDiscoverable()
		{
			Method m = findToolMethod("executeInRunningClient");
			assertNotNull(m);
			String desc = m.getAnnotation(Tool.class).description();
			assertTrue(desc.contains("RUNNING") || desc.contains("running"),
				"description must make clear this targets the running client");
			assertTrue(desc.contains("application.output") || desc.contains("console"),
				"description must mention the captured console/application.output text");
		}
	}

	// =======================================================================
	// executeInRunningClient - argument mapping + pass-through + error wrap
	// =======================================================================

	@Nested
	class ExecuteInRunningClient
	{
		@Test
		@DisplayName("executeInRunningClient forwards its exact arguments to the execution service and returns its result")
		void delegatesAndPassesThrough()
		{
			RunningClientExecutionService execution = mock(RunningClientExecutionService.class);
			when(execution.execute("forms.a.m()", "forms.a.m", "[1,2]", 45)).thenReturn("=> 42");
			ServoyDebugServer s = serverWith(execution, mock(DebugInspectionService.class));

			assertEquals("=> 42", s.executeInRunningClient("forms.a.m()", "forms.a.m", "[1,2]", 45));
			verify(execution).execute("forms.a.m()", "forms.a.m", "[1,2]", 45);
		}

		@Test
		@DisplayName("executeInRunningClient marshals a thrown exception into an 'Error: <message>' string, never rethrows")
		void wrapsExceptionAsErrorString()
		{
			RunningClientExecutionService execution = mock(RunningClientExecutionService.class);
			when(execution.execute(eq("boom()"), eq((String)null), eq((String)null), eq(30)))
				.thenThrow(new RuntimeException("no client"));
			ServoyDebugServer s = serverWith(execution, mock(DebugInspectionService.class));

			assertEquals("Error: no client", s.executeInRunningClient("boom()", null, null, 30));
		}
	}

	// =======================================================================
	// debug inspection tools - pass-through + error wrap for each
	// =======================================================================

	@Nested
	class DebugInspectionTools
	{
		@Test
		@DisplayName("debugStatus returns the inspection service's status string")
		void statusPassesThrough()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.status()).thenReturn("Suspended at forms/main.js:12");
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Suspended at forms/main.js:12", s.debugStatus());
			verify(inspection).status();
		}

		@Test
		@DisplayName("debugStatus marshals a thrown exception into 'Error: <message>'")
		void statusWrapsException()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.status()).thenThrow(new RuntimeException("model gone"));
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Error: model gone", s.debugStatus());
		}

		@Test
		@DisplayName("debugGetVariables forwards frameIndex/maxDepth and returns the service result")
		void getVariablesPassesThrough()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.getVariables(1, 3)).thenReturn("vars...");
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("vars...", s.debugGetVariables(1, 3));
			verify(inspection).getVariables(1, 3);
		}

		@Test
		@DisplayName("debugGetVariables marshals a thrown exception into 'Error: <message>'")
		void getVariablesWrapsException()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.getVariables(0, 2)).thenThrow(new RuntimeException("bad frame"));
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Error: bad frame", s.debugGetVariables(0, 2));
		}

		@Test
		@DisplayName("debugResume returns the service result")
		void resumePassesThrough()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.resume()).thenReturn("Resumed");
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Resumed", s.debugResume());
			verify(inspection).resume();
		}

		@Test
		@DisplayName("debugResume marshals a thrown exception into 'Error: <message>'")
		void resumeWrapsException()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.resume()).thenThrow(new RuntimeException("not suspended"));
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Error: not suspended", s.debugResume());
		}

		@Test
		@DisplayName("debugStep forwards its mode and returns the service result")
		void stepPassesThrough()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.step("into")).thenReturn("Stepped into forms/main.js:13");
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Stepped into forms/main.js:13", s.debugStep("into"));
			verify(inspection).step("into");
		}

		@Test
		@DisplayName("debugStep marshals a thrown exception into 'Error: <message>'")
		void stepWrapsException()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.step("over")).thenThrow(new RuntimeException("cannot step"));
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Error: cannot step", s.debugStep("over"));
		}

		@Test
		@DisplayName("debugEval forwards expression/frameIndex and returns the service result")
		void evalPassesThrough()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.eval("diff", 0)).thenReturn("diff = 5");
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("diff = 5", s.debugEval("diff", 0));
			verify(inspection).eval("diff", 0);
		}

		@Test
		@DisplayName("debugEval marshals a thrown exception into 'Error: <message>'")
		void evalWrapsException()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.eval("x", 0)).thenThrow(new RuntimeException("no frame"));
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Error: no frame", s.debugEval("x", 0));
		}

		@Test
		@DisplayName("debugSetVariable forwards name/value/frameIndex and returns the service result")
		void setVariablePassesThrough()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.setVariable("x", "5", 0)).thenReturn("x = 5");
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("x = 5", s.debugSetVariable("x", "5", 0));
			verify(inspection).setVariable("x", "5", 0);
		}

		@Test
		@DisplayName("debugSetVariable marshals a thrown exception into 'Error: <message>'")
		void setVariableWrapsException()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.setVariable("x", "5", 0)).thenThrow(new RuntimeException("readonly"));
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Error: readonly", s.debugSetVariable("x", "5", 0));
		}

		@Test
		@DisplayName("debugLaunchClient forwards timeoutSeconds and returns the service result")
		void launchClientPassesThrough()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.launchDebugClient(60)).thenReturn("Launched");
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Launched", s.debugLaunchClient(60));
			verify(inspection).launchDebugClient(60);
		}

		@Test
		@DisplayName("debugLaunchClient marshals a thrown exception into 'Error: <message>'")
		void launchClientWrapsException()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.launchDebugClient(60)).thenThrow(new RuntimeException("no solution"));
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Error: no solution", s.debugLaunchClient(60));
		}

		@Test
		@DisplayName("debugBreakOnMethod forwards the method address and returns the service result")
		void breakOnMethodPassesThrough()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.breakOnMethod("forms.main.daysLeft")).thenReturn("Breakpoint set");
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Breakpoint set", s.debugBreakOnMethod("forms.main.daysLeft"));
			verify(inspection).breakOnMethod("forms.main.daysLeft");
		}

		@Test
		@DisplayName("debugBreakOnMethod marshals a thrown exception into 'Error: <message>'")
		void breakOnMethodWrapsException()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.breakOnMethod("forms.x.y")).thenThrow(new RuntimeException("not found"));
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Error: not found", s.debugBreakOnMethod("forms.x.y"));
		}

		@Test
		@DisplayName("debugSetBreakpoint forwards filePath/line and returns the service result")
		void setBreakpointPassesThrough()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.setBreakpoint("/smp/forms/main.js", 12)).thenReturn("Breakpoint at line 12");
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Breakpoint at line 12", s.debugSetBreakpoint("/smp/forms/main.js", 12));
			verify(inspection).setBreakpoint("/smp/forms/main.js", 12);
		}

		@Test
		@DisplayName("debugSetBreakpoint marshals a thrown exception into 'Error: <message>'")
		void setBreakpointWrapsException()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.setBreakpoint("/smp/forms/main.js", 12)).thenThrow(new RuntimeException("no file"));
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Error: no file", s.debugSetBreakpoint("/smp/forms/main.js", 12));
		}

		@Test
		@DisplayName("debugClearBreakpoint forwards filePath/line and returns the service result")
		void clearBreakpointPassesThrough()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.clearBreakpoint("/smp/forms/main.js", 0)).thenReturn("Cleared all");
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Cleared all", s.debugClearBreakpoint("/smp/forms/main.js", 0));
			verify(inspection).clearBreakpoint("/smp/forms/main.js", 0);
		}

		@Test
		@DisplayName("debugClearBreakpoint marshals a thrown exception into 'Error: <message>'")
		void clearBreakpointWrapsException()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.clearBreakpoint("/smp/forms/main.js", 5)).thenThrow(new RuntimeException("bad path"));
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Error: bad path", s.debugClearBreakpoint("/smp/forms/main.js", 5));
		}

		@Test
		@DisplayName("debugEnd returns the service result")
		void endPassesThrough()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.end()).thenReturn("Resumed 1 thread, cleared 2 breakpoints");
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Resumed 1 thread, cleared 2 breakpoints", s.debugEnd());
			verify(inspection).end();
		}

		@Test
		@DisplayName("debugEnd marshals a thrown exception into 'Error: <message>'")
		void endWrapsException()
		{
			DebugInspectionService inspection = mock(DebugInspectionService.class);
			when(inspection.end()).thenThrow(new RuntimeException("cleanup failed"));
			ServoyDebugServer s = serverWith(mock(RunningClientExecutionService.class), inspection);

			assertEquals("Error: cleanup failed", s.debugEnd());
		}
	}
}
