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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.eclipse.developer.mcp.McpServerBuiltins;
import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;

@DisplayName("ServoyI18nServer")
public class ServoyI18nServerTest {

	private final ServoyI18nServer server = new ServoyI18nServer();

	@Nested
	@DisplayName("Annotations")
	class Annotations {

		@Test
		@DisplayName("has @McpServer annotation with name 'servoy-i18n'")
		void hasCorrectMcpServerAnnotation() {
			McpServer ann = ServoyI18nServer.class.getAnnotation(McpServer.class);
			assertAll(() -> assertNotNull(ann, "ServoyI18nServer must have @McpServer annotation"),
					() -> assertEquals("servoy-i18n", ann.name()));
		}

		@Test
		@DisplayName("i18nListTables method has @Tool annotation with correct name")
		void listTablesHasToolAnnotation() throws Exception {
			Method method = ServoyI18nServer.class.getMethod("i18nListTables");
			Tool tool = method.getAnnotation(Tool.class);
			assertAll(() -> assertNotNull(tool, "i18nListTables must have @Tool annotation"),
					() -> assertEquals("i18n_listTables", tool.name()));
		}

		@Test
		@DisplayName("i18nListTables has no parameters")
		void listTablesHasNoParams() throws Exception {
			Method method = ServoyI18nServer.class.getMethod("i18nListTables");
			assertEquals(0, method.getParameterCount());
		}

		@Test
		@DisplayName("i18nListTables returns String")
		void listTablesReturnsString() throws Exception {
			Method method = ServoyI18nServer.class.getMethod("i18nListTables");
			assertEquals(String.class, method.getReturnType());
		}

		@Test
		@DisplayName("i18nSetTable method has @Tool annotation with correct name")
		void setTableHasToolAnnotation() throws Exception {
			Method method = ServoyI18nServer.class.getMethod("i18nSetTable", String.class, String.class, String.class);
			Tool tool = method.getAnnotation(Tool.class);
			assertAll(() -> assertNotNull(tool, "i18nSetTable must have @Tool annotation"),
					() -> assertEquals("i18n_setTable", tool.name()));
		}

		@Test
		@DisplayName("i18nSetTable has three String parameters")
		void setTableHasThreeParams() throws Exception {
			Method method = ServoyI18nServer.class.getMethod("i18nSetTable", String.class, String.class, String.class);
			assertEquals(3, method.getParameterCount());
			for (Parameter param : method.getParameters()) {
				assertEquals(String.class, param.getType());
			}
		}

		@Test
		@DisplayName("i18nSetTable returns String")
		void setTableReturnsString() throws Exception {
			Method method = ServoyI18nServer.class.getMethod("i18nSetTable", String.class, String.class, String.class);
			assertEquals(String.class, method.getReturnType());
		}

		@Test
		@DisplayName("i18nSetTable has @ToolParam annotations on all parameters")
		void setTableHasToolParamAnnotations() throws Exception {
			Method method = ServoyI18nServer.class.getMethod("i18nSetTable", String.class, String.class, String.class);
			for (Parameter param : method.getParameters()) {
				assertNotNull(param.getAnnotation(ToolParam.class),
						"Parameter " + param.getName() + " must have @ToolParam annotation");
			}
		}

		@Test
		@DisplayName("i18nSetTable serverName param is required")
		void setTableServerNameParamIsRequired() throws Exception {
			ToolParam param = findToolParam("i18nSetTable", "serverName");
			assertAll(() -> assertNotNull(param, "serverName @ToolParam must exist"),
					() -> assertTrue(param.required(), "serverName must be required"));
		}

		@Test
		@DisplayName("i18nSetTable tableName param is required")
		void setTableTableNameParamIsRequired() throws Exception {
			ToolParam param = findToolParam("i18nSetTable", "tableName");
			assertAll(() -> assertNotNull(param, "tableName @ToolParam must exist"),
					() -> assertTrue(param.required(), "tableName must be required"));
		}

		@Test
		@DisplayName("i18nSetTable createIfMissing param is not required")
		void setTableCreateIfMissingParamIsOptional() throws Exception {
			ToolParam param = findToolParam("i18nSetTable", "createIfMissing");
			assertAll(() -> assertNotNull(param, "createIfMissing @ToolParam must exist"),
					() -> assertTrue(!param.required(), "createIfMissing must not be required"));
		}

		@Test
		@DisplayName("i18nListTables tool description mentions i18n-compatible columns")
		void listTablesDescriptionMentionsColumns() throws Exception {
			Method method = ServoyI18nServer.class.getMethod("i18nListTables");
			Tool tool = method.getAnnotation(Tool.class);
			assertAll(
					() -> assertTrue(tool.description().contains("message_key"),
							"description should mention message_key"),
					() -> assertTrue(tool.description().contains("message_value"),
							"description should mention message_value"),
					() -> assertTrue(tool.description().contains("message_language"),
							"description should mention message_language"));
		}

		@Test
		@DisplayName("i18nSetTable tool description mentions createIfMissing")
		void setTableDescriptionMentionsCreateIfMissing() throws Exception {
			Method method = ServoyI18nServer.class.getMethod("i18nSetTable", String.class, String.class, String.class);
			Tool tool = method.getAnnotation(Tool.class);
			assertTrue(tool.description().contains("createIfMissing"),
					"description should mention createIfMissing behavior");
		}
	}

	@Nested
	@DisplayName("Parameter validation (i18nSetTable)")
	class ParameterValidation {

		@ParameterizedTest(name = "serverName=''{0}'' returns error")
		@NullAndEmptySource
		@ValueSource(strings = { "   ", "\t" })
		@DisplayName("rejects null, empty, or blank serverName")
		void rejectsInvalidServerName(String serverName) {
			String result = server.i18nSetTable(serverName, "messages", null);
			assertAll(() -> assertNotNull(result),
					() -> assertTrue(result.startsWith("Error:"), "Should return error for invalid serverName"),
					() -> assertTrue(result.contains("serverName"), "Error message should mention serverName"));
		}

		@ParameterizedTest(name = "tableName=''{0}'' returns error")
		@NullAndEmptySource
		@ValueSource(strings = { "   ", "\t" })
		@DisplayName("rejects null, empty, or blank tableName")
		void rejectsInvalidTableName(String tableName) {
			String result = server.i18nSetTable("myServer", tableName, null);
			assertAll(() -> assertNotNull(result),
					() -> assertTrue(result.startsWith("Error:"), "Should return error for invalid tableName"),
					() -> assertTrue(result.contains("tableName"), "Error message should mention tableName"));
		}

		// NOTE: cases that pass a valid serverName AND tableName (so execution proceeds
		// past the null/blank guards into ServoyModelManager /
		// ApplicationServerRegistry)
		// are NOT unit-testable here: without a running workbench the
		// ServoyModelManager
		// static initializer throws ExceptionInInitializerError (an Error, not an
		// Exception), which the production catch(Exception) cannot intercept. That real
		// behaviour (valid params, invalid/missing server, createIfMissing handling) is
		// covered by ServoyI18nServerIntegrationTest under the PDE launcher instead.
	}

	@Nested
	@DisplayName("Registration")
	class Registration {

		@Test
		@DisplayName("ServoyI18nServer is registered in McpServerBuiltins")
		void registeredInBuiltins() {
			boolean found = false;
			for (Class<?> cls : McpServerBuiltins.BUILT_IN_SERVER_CLASSES) {
				if (cls == ServoyI18nServer.class) {
					found = true;
					break;
				}
			}
			assertTrue(found, "ServoyI18nServer must be registered in McpServerBuiltins");
		}

		@Test
		@DisplayName("can be instantiated via default constructor")
		void canBeInstantiated() {
			ServoyI18nServer instance = new ServoyI18nServer();
			assertNotNull(instance);
		}
	}

	@Nested
	@DisplayName("i18nSearchMessages")
	class SearchMessages {

		@Test
		@DisplayName("has @Tool annotation with correct name")
		void hasToolAnnotation() throws Exception {
			Method method = ServoyI18nServer.class.getMethod("i18nSearchMessages", String.class);
			Tool tool = method.getAnnotation(Tool.class);
			assertAll(() -> assertNotNull(tool, "i18nSearchMessages must have @Tool annotation"),
					() -> assertEquals("i18n_searchMessages", tool.name()));
		}

		@ParameterizedTest(name = "searchValue=''{0}'' returns error")
		@NullAndEmptySource
		@ValueSource(strings = { "   ", "\t" })
		@DisplayName("rejects null, empty, or blank searchValue")
		void rejectsInvalidSearchValue(String searchValue) {
			String result = server.i18nSearchMessages(searchValue);
			assertAll(() -> assertNotNull(result),
					() -> assertTrue(result.startsWith("Error:"), "Should return error for invalid searchValue"));
		}

		@Test
		@DisplayName("returns an error for a blank searchValue before touching the model")
		void blankSearchValueReturnsErrorWithoutModel() {
			// searchValue guard runs before any ServoyModelManager access, so this is
			// safe to assert without a workbench. A populated-search (which reaches the
			// solution model) is covered by ServoyI18nServerIntegrationTest.
			String result = server.i18nSearchMessages("   ");
			assertNotNull(result);
			assertTrue(result.startsWith("Error:"), "Blank searchValue should return an error");
		}
	}

	private ToolParam findToolParam(String methodName, String paramName) throws Exception {
		Method method = ServoyI18nServer.class.getMethod(methodName, String.class, String.class, String.class);
		for (Parameter param : method.getParameters()) {
			ToolParam tp = param.getAnnotation(ToolParam.class);
			if (tp != null && paramName.equals(tp.name())) {
				return tp;
			}
		}
		return null;
	}
}
