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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.McpServerFactory;
import com.servoy.eclipse.developer.mcp.ToolExecutor;
import com.servoy.eclipse.developer.mcp.servers.ServoyIdeServer;
import com.servoy.eclipse.developer.mcp.services.IdeStateService;
import com.servoy.eclipse.developer.mcp.services.MarkdownService;
import com.servoy.eclipse.developer.mcp.services.ProjectService;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson2.JacksonJsonSchemaValidatorSupplier;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Plain-JUnit tests for SVY-21618 that drive the <em>real</em> call path of
 * {@code getConsoleOutput}, not just its annotations: the tool schema is built
 * by {@link McpServerFactory}, the arguments are checked by the same Jackson
 * {@link JsonSchemaValidator} the factory wires into the server, and the
 * accepted arguments are dispatched through {@link ToolExecutor} into a real
 * {@link ServoyIdeServer} whose {@link IdeStateService} collaborator is mocked
 * at the boundary.
 * <p>
 * Limit: the validator is invoked directly against the published tool schema.
 * The MCP SDK's own request dispatch (which calls the same validator before the
 * handler) is not started here.
 * </p>
 */
class ServoyIdeServerCallPathTest
{
	private IdeStateService ideStateService;
	private ServoyIdeServer server;
	private McpSyncServer syncServer;
	private Map<String, Object> consoleSchema;
	private final JsonSchemaValidator validator = new JacksonJsonSchemaValidatorSupplier().get();

	@BeforeEach
	void setUp()
	{
		ideStateService = mock(IdeStateService.class);
		when(ideStateService.getConsoleOutput(any(), anyInt(), anyBoolean(), anyBoolean())).thenReturn("console-text");
		server = new ServoyIdeServer(mock(ProjectService.class), mock(WorkspaceService.class), mock(MarkdownService.class), ideStateService);

		HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
			.jsonMapper(new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier().get())
			.mcpEndpoint("/test/svy-21618").build();
		syncServer = McpServerFactory.getInstance().createSyncServer(server, transport);

		McpSchema.Tool tool = syncServer.listTools().stream().filter(t -> "getConsoleOutput".equals(t.name())).findFirst().orElse(null);
		assertNotNull(tool, "getConsoleOutput must be published by the factory");
		consoleSchema = tool.inputSchema();
	}

	@AfterEach
	void tearDown()
	{
		syncServer.closeGracefully();
	}

	private boolean accepted(Map<String, Object> args)
	{
		return validator.validate(consoleSchema, args).valid();
	}

	private Object call(Map<String, Object> args) throws Exception
	{
		return new ToolExecutor(server).call("getConsoleOutput", args).get();
	}

	@Test
	@DisplayName("a numeric maxLines passes schema validation and reaches the service as that line count")
	void numericMaxLines_isAcceptedAndReachesService() throws Exception
	{
		Map<String, Object> args = Map.of("maxLines", 30);
		assertTrue(accepted(args), "numeric maxLines must pass the published schema (SVY-21618)");
		call(args);
		verify(ideStateService).getConsoleOutput(null, 30, false, false);
	}

	@Test
	@DisplayName("native boolean flags pass schema validation and reach the service")
	void booleanFlags_areAcceptedAndReachService() throws Exception
	{
		Map<String, Object> args = Map.of("includeAllConsoles", true, "clear", false);
		assertTrue(accepted(args), "boolean flags must pass the published schema (SVY-21618)");
		call(args);
		verify(ideStateService).getConsoleOutput(null, 100, true, false);
	}

	@Test
	@DisplayName("no arguments keep the defaults: 100 lines, no all-consoles, no clear")
	void noArguments_useDefaults() throws Exception
	{
		assertTrue(accepted(Map.of()), "an empty argument map must pass the schema");
		call(Map.of());
		verify(ideStateService).getConsoleOutput(null, 100, false, false);
	}

	@Test
	@DisplayName("a string maxLines is rejected by the published schema")
	void stringMaxLines_isRejectedBySchema()
	{
		assertFalse(accepted(Map.of("maxLines", "30")), "schema now declares integer, so the string \"30\" is rejected");
	}

	@Test
	@DisplayName("string boolean flags are rejected by the published schema")
	void stringBooleanFlags_areRejectedBySchema()
	{
		assertFalse(accepted(Map.of("clear", "false")), "schema now declares boolean, so the string \"false\" is rejected");
		assertFalse(accepted(Map.of("includeAllConsoles", "true")), "schema now declares boolean, so the string \"true\" is rejected");
	}

	/**
	 * Characterisation test: records the CURRENT behaviour, it does not endorse it.
	 * {@code 30.0} is a valid JSON-schema integer, but {@code ToolExecutor} turns it
	 * into the string "30.0" and {@code Integer.parseInt} in
	 * {@code ServoyIdeServer.getConsoleOutput} throws. The tool logs an ERROR line
	 * for this expected failure, so a stack trace in the test output is not a real
	 * failure. If {@code maxLines} parsing ever becomes lenient, this test must be
	 * updated to assert that {@code 30.0} is accepted (SVY-21618 follow-up).
	 */
	@Test
	@DisplayName("a float maxLines such as 30.0 passes the schema but fails when parsed by the tool")
	void floatMaxLines_passesSchemaButFailsInTool()
	{
		Map<String, Object> args = Map.of("maxLines", 30.0);
		assertTrue(accepted(args), "30.0 is a valid JSON-schema integer");
		assertThrows(ExecutionException.class, () -> call(args), "\"30.0\" reaches Integer.parseInt and fails");
	}
}
