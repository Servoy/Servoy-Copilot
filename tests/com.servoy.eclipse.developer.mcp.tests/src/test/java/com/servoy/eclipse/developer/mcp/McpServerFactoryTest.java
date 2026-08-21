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
package com.servoy.eclipse.developer.mcp;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson2.JacksonJsonSchemaValidatorSupplier;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * JUnit 4 tests for {@link McpServerFactory}.
 */
public class McpServerFactoryTest {

	private static Map<String, Object> schemaAsMap(McpSchema.JsonSchema schema) {
		if (schema == null) return null;
		Map<String, Object> map = new HashMap<>();
		if (schema.type() != null) map.put("type", schema.type());
		if (schema.properties() != null) map.put("properties", schema.properties());
		if (schema.required() != null) map.put("required", schema.required());
		return map;
	}

	@McpServer(name = "factory-test")
	public static class ValidServer {
		@Tool(name = "echo", description = "Echoes input", type = "object")
		public String echo(@ToolParam(name = "input", description = "Text to echo", required = true) String input) {
			return input;
		}
	}

	public static class NoAnnotationServer {
		@Tool(name = "noop", description = "Does nothing", type = "object")
		public String noop() {
			return "";
		}
	}

	@McpServer(name = "empty-server")
	public static class EmptyServer {
	}

	@Test
	public void testGetInstance_notNull() {
		assertNotNull(McpServerFactory.getInstance());
	}

	@Test
	public void testGetInstance_sameInstance() {
		assertTrue(McpServerFactory.getInstance() == McpServerFactory.getInstance());
	}

	@Test
	public void testCreateSyncServer_validServer() {
		HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier().get())
				.mcpEndpoint("/test/factory-test").build();

		McpSyncServer syncServer = McpServerFactory.getInstance().createSyncServer(new ValidServer(), transport);
		assertNotNull(syncServer);
		syncServer.closeGracefully();
	}

	@Test
	public void testCreateSyncServer_noAnnotation_throws() {
		HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier().get())
				.mcpEndpoint("/test/no-ann").build();

		try {
			McpServerFactory.getInstance().createSyncServer(new NoAnnotationServer(), transport);
			fail("Should throw IllegalArgumentException for missing @McpServer");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("missing @McpServer"));
		}
	}

	@Test
	public void testCreateSyncServer_emptyServer() {
		HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier().get())
				.mcpEndpoint("/test/empty-server").build();

		McpSyncServer syncServer = McpServerFactory.getInstance().createSyncServer(new EmptyServer(), transport);
		assertNotNull(syncServer);
		syncServer.closeGracefully();
	}

	@Test
	public void testCreateSyncServer_withExcludedTools() {
		HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier().get())
				.mcpEndpoint("/test/excluded").build();

		McpSyncServer syncServer = McpServerFactory.getInstance().createSyncServer(new ValidServer(), transport,
				java.util.List.of("echo"));
		assertNotNull(syncServer);
		syncServer.closeGracefully();
	}

	// --- SVY-21124: Required parameter validation tests ---

	@Test
	public void testValidateRequiredParams_allPresent_returnsNull() {
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
				"object", Map.of("projectName", Map.of("type", "string"), "filePath", Map.of("type", "string"),
						"content", Map.of("type", "string")),
				List.of("projectName", "filePath", "content"), false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("replaceFileContent", "replaceFileContent", "Replaces file content",
				schemaAsMap(schema), null, null, null);

		Map<String, Object> args = new HashMap<>();
		args.put("projectName", "myProject");
		args.put("filePath", "src/Main.java");
		args.put("content", "public class Main {}");

		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNull("No validation error expected when all required params present", result);
	}

	@Test
	public void testValidateRequiredParams_missingContent_returnsError() {
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
				"object", Map.of("projectName", Map.of("type", "string"), "filePath", Map.of("type", "string"),
						"content", Map.of("type", "string")),
				List.of("projectName", "filePath", "content"), false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("replaceFileContent", "replaceFileContent", "Replaces file content",
				schemaAsMap(schema), null, null, null);

		Map<String, Object> args = new HashMap<>();
		args.put("projectName", "myProject");
		args.put("filePath", "src/Main.java");

		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNotNull("Should detect missing 'content' parameter", result);
		assertTrue("Error should name the missing parameter", result.contains("content"));
		assertTrue("Error should name the tool", result.contains("replaceFileContent"));
	}

	@Test
	public void testValidateRequiredParams_nullArgs_returnsError() {
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object",
				Map.of("projectName", Map.of("type", "string")), List.of("projectName"), false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("createFile", "createFile", "Creates a file", schemaAsMap(schema), null, null,
				null);

		String result = McpServerFactory.validateRequiredParams(tool, null);
		assertNotNull("Should detect missing params when args is null", result);
		assertTrue("Error should name the missing parameter", result.contains("projectName"));
	}

	@Test
	public void testValidateRequiredParams_nullValueForRequired_returnsError() {
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object",
				Map.of("projectName", Map.of("type", "string"), "content", Map.of("type", "string")),
				List.of("projectName", "content"), false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("createFile", "createFile", "Creates a file", schemaAsMap(schema), null, null,
				null);

		Map<String, Object> args = new HashMap<>();
		args.put("projectName", "myProject");
		args.put("content", null);

		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNotNull("Should detect null value for required param", result);
		assertTrue("Error should name the missing parameter", result.contains("content"));
	}

	@Test
	public void testValidateRequiredParams_noRequiredParams_returnsNull() {
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of("optional", Map.of("type", "string")),
				List.of(), false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("noRequired", "noRequired", "No required params", schemaAsMap(schema), null, null,
				null);

		Map<String, Object> args = new HashMap<>();
		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNull("No error expected when tool has no required params", result);
	}

	@Test
	public void testValidateRequiredParams_multipleMissing_reportsAll() {
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
				"object", Map.of("projectName", Map.of("type", "string"), "filePath", Map.of("type", "string"),
						"content", Map.of("type", "string")),
				List.of("projectName", "filePath", "content"), false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("replaceFileContent", "replaceFileContent", "Replaces file content",
				schemaAsMap(schema), null, null, null);

		Map<String, Object> args = new HashMap<>();
		args.put("projectName", "myProject");

		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNotNull("Should detect multiple missing params", result);
		assertTrue("Error should mention filePath", result.contains("filePath"));
		assertTrue("Error should mention content", result.contains("content"));
	}

	@Test
	public void testValidateRequiredParams_listsReceivedParams() {
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
				"object", Map.of("projectName", Map.of("type", "string"), "filePath", Map.of("type", "string"),
						"content", Map.of("type", "string")),
				List.of("projectName", "filePath", "content"), false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("replaceFileContent", "replaceFileContent", "Replaces file content",
				schemaAsMap(schema), null, null, null);

		Map<String, Object> args = new HashMap<>();
		args.put("projectName", "myProject");
		args.put("filePath", "src/Main.java");

		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNotNull(result);
		assertTrue("Error should list received params", result.contains("projectName"));
		assertTrue("Error should list received params", result.contains("filePath"));
		assertTrue("Error should instruct to re-call", result.toLowerCase().contains("re-call")
				|| result.toLowerCase().contains("recall") || result.contains("Please"));
	}

	// --- SVY-21124: Additional edge-case tests for validateRequiredParams ---

	@Test
	public void testValidateRequiredParams_emptyStringValue_returnsNull() {
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of("content", Map.of("type", "string")),
				List.of("content"), false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("createFile", "createFile", "Creates a file", schemaAsMap(schema), null, null,
				null);

		Map<String, Object> args = new HashMap<>();
		args.put("content", "");

		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNull("Empty string is a valid value for a required param", result);
	}

	@Test
	public void testValidateRequiredParams_whitespaceOnlyValue_returnsNull() {
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of("content", Map.of("type", "string")),
				List.of("content"), false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("createFile", "createFile", "Creates a file", schemaAsMap(schema), null, null,
				null);

		Map<String, Object> args = new HashMap<>();
		args.put("content", "   \t\n  ");

		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNull("Whitespace-only string is still a present value", result);
	}

	@Test
	public void testValidateRequiredParams_specialCharsInParamNames() {
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object",
				Map.of("file-path.v2", Map.of("type", "string"), "content#1", Map.of("type", "string")),
				List.of("file-path.v2", "content#1"), false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("weirdTool", "weirdTool", "Tool with special param names", schemaAsMap(schema),
				null, null, null);

		Map<String, Object> args = new HashMap<>();
		args.put("file-path.v2", "some/path");

		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNotNull(result);
		assertTrue("Error should contain the special-char param name", result.contains("content#1"));
	}

	@Test
	public void testValidateRequiredParams_veryLongParamName() {
		String longName = "a".repeat(500);
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(longName, Map.of("type", "string")),
				List.of(longName), false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("longParamTool", "longParamTool", "Tool with long param name", schemaAsMap(schema),
				null, null, null);

		Map<String, Object> args = new HashMap<>();

		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNotNull(result);
		assertTrue("Error should contain the long param name", result.contains(longName));
	}

	@Test
	public void testValidateRequiredParams_emptyArgsMap_returnsError() {
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object",
				Map.of("projectName", Map.of("type", "string"), "filePath", Map.of("type", "string")),
				List.of("projectName", "filePath"), false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("readFile", "readFile", "Reads a file", schemaAsMap(schema), null, null, null);

		Map<String, Object> args = new HashMap<>();

		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNotNull("Empty map should fail for tools with required params", result);
		assertTrue(result.contains("projectName"));
		assertTrue(result.contains("filePath"));
	}

	@Test
	public void testValidateRequiredParams_nullSchema_returnsNull() {
		McpSchema.Tool tool = new McpSchema.Tool("noSchema", "noSchema", "No schema tool", Map.of(), null, null, null);

		Map<String, Object> args = new HashMap<>();
		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNull("Null schema should be treated as no required params", result);
	}

	@Test
	public void testValidateRequiredParams_nullRequiredList_returnsNull() {
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of("optional", Map.of("type", "string")),
				null, false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("nullRequired", "nullRequired", "Null required list", schemaAsMap(schema), null,
				null, null);

		Map<String, Object> args = new HashMap<>();
		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNull("Null required list should be treated as no required params", result);
	}

	@Test
	public void testValidateRequiredParams_nonStringValuePresent_returnsNull() {
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of("count", Map.of("type", "integer")),
				List.of("count"), false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("countTool", "countTool", "Tool with integer param", schemaAsMap(schema), null,
				null, null);

		Map<String, Object> args = new HashMap<>();
		args.put("count", Integer.valueOf(42));

		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNull("Non-string value (Integer) should be valid", result);
	}

	@Test
	public void testValidateRequiredParams_extraParamsIgnored_returnsNull() {
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object",
				Map.of("projectName", Map.of("type", "string")), List.of("projectName"), false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("readFile", "readFile", "Reads a file", schemaAsMap(schema), null, null, null);

		Map<String, Object> args = new HashMap<>();
		args.put("projectName", "myProject");
		args.put("unknownExtra", "value");
		args.put("anotherExtra", "value2");

		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNull("Extra parameters should not cause validation failure", result);
	}

	@Test
	public void testValidateRequiredParams_singleMissingAmongMany_reportsOnlyMissing() {
		McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
				"object", Map.of("a", Map.of("type", "string"), "b", Map.of("type", "string"), "c",
						Map.of("type", "string"), "d", Map.of("type", "string")),
				List.of("a", "b", "c", "d"), false, null, null);
		McpSchema.Tool tool = new McpSchema.Tool("multiTool", "multiTool", "Multi param tool", schemaAsMap(schema), null, null,
				null);

		Map<String, Object> args = new HashMap<>();
		args.put("a", "v1");
		args.put("b", "v2");
		args.put("d", "v4");

		String result = McpServerFactory.validateRequiredParams(tool, args);
		assertNotNull(result);
		assertTrue("Should report missing 'c'", result.contains("c"));
		assertTrue("Should name the tool", result.contains("multiTool"));
	}

	// --- SVY-21124: JSON-schema output validation is still enforced (proposal 1) ---

	@McpServer(name = "structured-output-server")
	public static class StructuredOutputServer {
		/**
		 * Tool whose implementation always returns a plain string, never structured
		 * JSON, so any structuredContent seen by the framework validator comes only
		 * from an explicit {@code structuredContent} argument passed by the caller.
		 */
		@Tool(name = "echoStructured", description = "Echoes input", type = "object")
		public String echoStructured(
			@ToolParam(name = "input", description = "Text to echo", required = true) String input) {
			return input;
		}
	}

	private HttpServletStreamableServerTransportProvider newTestTransport(String endpoint) {
		return HttpServletStreamableServerTransportProvider.builder()
			.jsonMapper(new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier().get())
			.mcpEndpoint(endpoint)
			.build();
	}

	@Test
	public void testCreateSyncServer_usesRealJacksonSchemaValidator_notNoOp() {
		// This is the regression guard for SVY-21124: the no-op validator that was in
		// the WIP would report *any* content as valid, including malformed JSON /
		// content that violates the schema. The real Jackson-backed validator (as
		// wired by McpServerFactory) must actually detect an invalid structured
		// output against a schema.
		JsonSchemaValidator validator = new JacksonJsonSchemaValidatorSupplier().get();

		Map<String, Object> schema = Map.of(
			"type", "object",
			"properties", Map.of("count", Map.of("type", "integer")),
			"required", List.of("count"));

		// Malformed / schema-violating structured output: "count" must be an integer,
		// but a string is supplied instead.
		Map<String, Object> invalidStructuredContent = Map.of("count", "not-a-number");

		JsonSchemaValidator.ValidationResponse response = validator.validate(schema, invalidStructuredContent);

		assertTrue("Real validator must reject content that violates the schema", !response.valid());
		assertNotNull("Invalid response should carry an error message", response.errorMessage());
	}

	@Test
	public void testCreateSyncServer_realValidator_acceptsValidStructuredContent() {
		JsonSchemaValidator validator = new JacksonJsonSchemaValidatorSupplier().get();

		Map<String, Object> schema = Map.of(
			"type", "object",
			"properties", Map.of("count", Map.of("type", "integer")),
			"required", List.of("count"));

		Map<String, Object> validStructuredContent = Map.of("count", 42);

		JsonSchemaValidator.ValidationResponse response = validator.validate(schema, validStructuredContent);

		assertTrue("Real validator must accept content that matches the schema", response.valid());
	}

	@Test
	public void testCreateSyncServer_toolWithOutputSchema_rejectsMalformedStructuredContent() {
		// End-to-end-ish check at the McpServerFactory level: build a real server for
		// a tool whose declared outputSchema requires an integer 'count', and confirm
		// the schema validator McpServerFactory wires in would flag content built
		// with the wrong type - i.e. schema validation is not silently bypassed.
		HttpServletStreamableServerTransportProvider transport = newTestTransport("/test/structured-output");

		McpSyncServer syncServer = McpServerFactory.getInstance()
			.createSyncServer(new StructuredOutputServer(), transport);
		try {
			assertNotNull(syncServer);

			JsonSchemaValidator validator = new JacksonJsonSchemaValidatorSupplier().get();
			Map<String, Object> outputSchema = Map.of(
				"type", "object",
				"properties", Map.of("count", Map.of("type", "integer")),
				"required", List.of("count"));

			JsonSchemaValidator.ValidationResponse response = validator.validate(outputSchema,
				Map.of("count", "oops-a-string"));

			assertTrue("Schema validation for the tool's outputSchema must still catch type mismatches",
				!response.valid());
		} finally {
			syncServer.closeGracefully();
		}
	}
}
