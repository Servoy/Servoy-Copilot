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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.developer.mcp.McpServerBuiltins;
import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.servers.ServoyI18nServer;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.repository.EclipseMessages;
import com.servoy.j2db.persistence.I18NUtil;
import com.servoy.j2db.persistence.Solution;

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

	// -----------------------------------------------------------------------
	// Seam-driven unit tests: the Servoy model boundary is mocked through the
	// protected getServoyModel() seam, so this server's own validation/guard/
	// dispatch/formatting branches run without a workbench. The platform
	// ResourceBundle (com.servoy.j2db.messages) is a real, stable resource on the
	// test classpath, so i18nSearchMessages' platform-defaults formatting is also
	// exercised here. The core's behaviour itself is NOT under test (that belongs
	// in servoy-eclipse / ServoyI18nServerIntegrationTest).
	// -----------------------------------------------------------------------

	/** A {@link ServoyI18nServer} whose model seam returns the supplied mock. */
	private static ServoyI18nServer serverWith(IDeveloperServoyModel model) {
		return new ServoyI18nServer() {
			@Override
			protected IDeveloperServoyModel getServoyModel() {
				return model;
			}
		};
	}

	@Nested
	@DisplayName("i18nSearchMessages with a mocked model")
	class SearchMessagesMocked {

		/**
		 * A known platform default key/value present in the
		 * {@code com.servoy.j2db.messages} ResourceBundle. Used to assert the
		 * platform-defaults branch of i18nSearchMessages formats a real match.
		 */
		private static final String PLATFORM_VALUE = "Cancel";

		@Test
		@DisplayName("searches platform defaults and reports 'No active solution' when there is no active project")
		void platformMatchesNoActiveSolution() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(null);

			String result = serverWith(model).i18nSearchMessages(PLATFORM_VALUE);

			assertAll(() -> assertNotNull(result),
					() -> assertTrue(result.startsWith("Platform defaults ("),
							"should lead with the platform-defaults section; got: " + result),
					() -> assertTrue(result.contains("Solution keys ("),
							"should contain the solution-keys section; got: " + result),
					() -> assertTrue(result.contains("No active solution"),
							"solution note should say there is no active solution; got: " + result));
		}

		@Test
		@DisplayName("notes 'No solution i18n configured' when the active solution has no i18nDataSource")
		void noI18nDataSourceConfigured() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			when(model.getActiveProject()).thenReturn(project);
			when(project.getSolution()).thenReturn(solution);
			when(solution.getI18nDataSource()).thenReturn(null);

			String result = serverWith(model).i18nSearchMessages(PLATFORM_VALUE);

			assertTrue(result.contains("No solution i18n configured"),
					"should note that no solution i18n is configured; got: " + result);
		}

		@Test
		@DisplayName("formats solution-key matches from the messages manager")
		void formatsSolutionMatches() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			EclipseMessages messagesManager = mock(EclipseMessages.class);
			when(model.getActiveProject()).thenReturn(project);
			when(project.getSolution()).thenReturn(solution);
			when(solution.getI18nDataSource()).thenReturn("db:/example/messages");
			when(model.getMessagesManager()).thenReturn(messagesManager);

			TreeMap<String, I18NUtil.MessageEntry> messages = new TreeMap<>();
			messages.put("en.greeting", new I18NUtil.MessageEntry("en", "greeting", "Please cancel"));
			messages.put("en.other", new I18NUtil.MessageEntry("en", "other", "nothing to see"));
			when(messagesManager.getDatasourceMessages("db:/example/messages")).thenReturn(messages);

			String result = serverWith(model).i18nSearchMessages(PLATFORM_VALUE);

			assertAll(
					() -> assertTrue(result.contains("greeting=Please cancel"),
							"matching solution key should be listed; got: " + result),
					() -> assertFalse(result.contains("other=nothing to see"),
							"non-matching solution value should be omitted; got: " + result));
		}

		@Test
		@DisplayName("reports no matches when neither platform nor solution contains the value")
		void noMatches() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(null);

			String result = serverWith(model).i18nSearchMessages("zzz-no-such-i18n-value-zzz");

			assertEquals("No matching i18n keys found", result);
		}

		@Test
		@DisplayName("captures a model failure into the solution note rather than throwing")
		void modelFailureBecomesNote() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenThrow(new RuntimeException("boom"));

			String result = serverWith(model).i18nSearchMessages(PLATFORM_VALUE);

			assertTrue(result.contains("Could not search solution keys"),
					"a model failure should surface as a solution note; got: " + result);
		}
	}

	@Nested
	@DisplayName("i18nSetTable with a mocked model")
	class SetTableMocked {

		@Test
		@DisplayName("reports no active solution when there is no active project")
		void noActiveProject() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(null);

			String result = serverWith(model).i18nSetTable("myServer", "messages", null);

			assertAll(() -> assertNotNull(result),
					() -> assertTrue(result.startsWith("Error: No active solution"),
							"should report no active solution; got: " + result));
		}

		@Test
		@DisplayName("surfaces a model failure through the catch block as an error")
		void modelFailureBecomesError() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenThrow(new RuntimeException("kaboom"));

			String result = serverWith(model).i18nSetTable("myServer", "messages", "false");

			assertAll(() -> assertNotNull(result),
					() -> assertTrue(result.startsWith("Error:"), "should return an error; got: " + result),
					() -> assertTrue(result.contains("kaboom"),
							"the error message should carry the cause; got: " + result));
		}

		@Test
		@DisplayName("validates blank params before the model is ever consulted")
		void blankParamsShortCircuitBeforeModel() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			ServoyI18nServer serverUnderTest = serverWith(model);

			assertAll(
					() -> assertTrue(serverUnderTest.i18nSetTable("  ", "messages", null).contains("serverName")),
					() -> assertTrue(serverUnderTest.i18nSetTable("myServer", "  ", null).contains("tableName")));
			lenient().when(model.getActiveProject()).thenReturn(null);
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
