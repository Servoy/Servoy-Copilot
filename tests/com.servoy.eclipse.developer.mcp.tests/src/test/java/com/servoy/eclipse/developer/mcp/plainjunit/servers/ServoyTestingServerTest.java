package com.servoy.eclipse.developer.mcp.plainjunit.servers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.cypress.services.CypressTestDiscoveryService;
import com.servoy.eclipse.cypress.services.FormSpecGenerator;
import com.servoy.eclipse.cypress.services.FormSpecRunner;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServer;
import com.servoy.eclipse.developer.mcp.services.FormInteractiveElementsService;
import com.servoy.eclipse.developer.mcp.services.FormLocatorService;
import com.servoy.eclipse.developer.mcp.services.FormNavigationGraphService;
import com.servoy.eclipse.developer.mcp.services.FormPreviewService;
import com.servoy.eclipse.developer.mcp.services.FormSpecModelBuilder;
import com.servoy.eclipse.developer.mcp.services.JSUnitCoverageService;
import com.servoy.eclipse.developer.mcp.services.JSUnitRunnerService;
import com.servoy.eclipse.developer.mcp.services.NavigationEdge;
import com.servoy.eclipse.developer.mcp.services.NavigationGraph;
import com.servoy.eclipse.developer.mcp.services.ServoySolutionService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Solution;

public class ServoyTestingServerTest {
	@Test
	public void testServoyTestingServer_hasCorrectAnnotation() {
		com.servoy.eclipse.developer.mcp.annotations.McpServer ann = ServoyTestingServer.class
				.getAnnotation(com.servoy.eclipse.developer.mcp.annotations.McpServer.class);
		assertNotNull(ann, "ServoyTestingServer must have @McpServer annotation");
		assertEquals("servoy-test", ann.name());
	}

	@Test
	public void testServoyTestingServer_registeredInBuiltins() {
		boolean found = false;
		for (Class<?> cls : com.servoy.eclipse.developer.mcp.McpServerBuiltins.BUILT_IN_SERVER_CLASSES) {
			if (cls == ServoyTestingServer.class) {
				found = true;
				break;
			}
		}
		assertTrue(found, "ServoyTestingServer must be registered in McpServerBuiltins");
	}

	@Test
	public void testServoyTestingServer_hasTestFormTool() {
		assertTrue(hasToolNamed("testForm"), "ServoyTestingServer must have a 'testForm' tool");
	}

	@Test
	public void testServoyTestingServer_hasShowAndTestTool() {
		assertTrue(hasToolNamed("showAndTest"), "ServoyTestingServer must have a 'showAndTest' tool");
	}

	@Test
	public void testServoyTestingServer_hasGenerateFormSpecTool() {
		assertTrue(hasToolNamed("generateFormSpec"), "ServoyTestingServer must have a 'generateFormSpec' tool");
	}

	@Test
	public void testServoyTestingServer_hasRunJsUnitTestsTool() {
		assertTrue(hasToolNamed("runJsUnitTests"), "ServoyTestingServer must have a 'runJsUnitTests' tool");
	}

	@Test
	public void testServoyTestingServer_hasShowFormInBrowserTool() {
		assertTrue(hasToolNamed("showFormInBrowser"), "ServoyTestingServer must have a 'showFormInBrowser' tool");
	}

	@Test
	public void testServoyTestingServer_hasScreenshotFormTool() {
		assertTrue(hasToolNamed("screenshotForm"), "ServoyTestingServer must have a 'screenshotForm' tool");
	}

	@Test
	public void testServoyTestingServer_hasCheckNGClientStatusTool() {
		assertTrue(hasToolNamed("checkNGClientStatus"), "ServoyTestingServer must have a 'checkNGClientStatus' tool");
	}

	@Test
	public void testServoyTestingServer_allToolsHaveDescriptions() {
		List<Method> toolMethods = Arrays.stream(ServoyTestingServer.class.getMethods())
				.filter(m -> m.isAnnotationPresent(Tool.class)).collect(Collectors.toList());

		for (Method m : toolMethods) {
			Tool tool = m.getAnnotation(Tool.class);
			assertFalse(tool.description().isEmpty(), "Tool '" + tool.name() + "' must have a non-empty description");
		}
	}

	@Test
	public void testServoyTestingServer_allToolsReturnString() {
		List<Method> toolMethods = Arrays.stream(ServoyTestingServer.class.getMethods())
				.filter(m -> m.isAnnotationPresent(Tool.class)).collect(Collectors.toList());

		for (Method m : toolMethods) {
			assertEquals(String.class, m.getReturnType(), "Tool method '" + m.getName() + "' must return String");
		}
	}

	@Test
	public void testServoyTestingServer_testFormHasFormNameParam() {
		Method method = findToolMethod("testForm");
		assertNotNull(method);
		assertEquals(1, method.getParameterCount(), "testForm must have 1 parameter");
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull(param, "testForm parameter must have @ToolParam");
		assertFalse(param.description().isEmpty(), "testForm @ToolParam must have description");
	}

	@Test
	public void testServoyTestingServer_showAndTestHasFormNameParam() {
		Method method = findToolMethod("showAndTest");
		assertNotNull(method);
		assertEquals(1, method.getParameterCount(), "showAndTest must have 1 parameter");
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull(param, "showAndTest parameter must have @ToolParam");
	}

	@Test
	public void testServoyTestingServer_generateFormSpecHasFormNameParam() {
		// SVY-21619: generateFormSpec gained a second optional 'viewportWidths'
		// parameter.
		Method method = findToolMethod("generateFormSpec");
		assertNotNull(method);
		assertEquals(2, method.getParameterCount(),
				"generateFormSpec must have 2 parameters (formName + viewportWidths)");
		ToolParam formName = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull(formName, "generateFormSpec first parameter must have @ToolParam");
		assertEquals("formName", formName.name(), "first parameter must be formName");
		assertTrue(formName.required(), "formName must be required");
	}

	@Test
	@DisplayName("generateFormSpec exposes an optional viewportWidths @ToolParam (SVY-21619)")
	public void testServoyTestingServer_generateFormSpecHasViewportWidthsParam() {
		Method method = findToolMethod("generateFormSpec");
		assertNotNull(method);
		assertEquals(2, method.getParameterCount());
		ToolParam viewportWidths = method.getParameters()[1].getAnnotation(ToolParam.class);
		assertNotNull(viewportWidths, "second parameter must have @ToolParam");
		assertEquals("viewportWidths", viewportWidths.name(), "second parameter must be viewportWidths");
		assertFalse(viewportWidths.required(), "viewportWidths must be optional (required = false)");
		assertFalse(viewportWidths.description().isBlank(), "viewportWidths must have a non-empty description");
	}

	@Test
	@DisplayName("generateFormSpec description documents the always-on reporting and the viewportWidths cross-check")
	public void testGenerateFormSpec_descriptionMentionsViewportMeasurement() {
		Method m = findToolMethod("generateFormSpec");
		assertNotNull(m);
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue(desc.contains("viewportWidths"), "description must name the viewportWidths param: " + desc);
		assertTrue(desc.contains("innerWidth"),
				"description must mention the always-on window.innerWidth reporting: " + desc);
	}

	@Test
	@DisplayName("generateFormSpec rejects an invalid viewportWidths token with an Error: string naming the token (SVY-21619)")
	public void testGenerateFormSpec_invalidViewportWidths_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.generateFormSpec("anyForm", "375,abc");
		assertNotNull(result);
		assertTrue(result.startsWith("Error"), "an invalid token must yield an Error: string: " + result);
		assertTrue(result.contains("abc"), "the error must name the offending token: " + result);
	}

	@Test
	public void testServoyTestingServer_runJsUnitTestsHasTwoParams() {
		Method method = findToolMethod("runJsUnitTests");
		assertNotNull(method);
		assertEquals(2, method.getParameterCount(), "runJsUnitTests must have 2 parameters");
	}

	@Test
	public void testServoyTestingServer_screenshotFormHasTwoParams() {
		Method method = findToolMethod("screenshotForm");
		assertNotNull(method);
		assertEquals(2, method.getParameterCount(), "screenshotForm must have 2 parameters");
	}

	@Test
	public void testServoyTestingServer_checkNGClientStatusHasNoParams() {
		Method method = findToolMethod("checkNGClientStatus");
		assertNotNull(method);
		assertEquals(0, method.getParameterCount(), "checkNGClientStatus must have 0 parameters");
	}

	@Test
	public void testServoyTestingServer_canBeInstantiated() {
		ServoyTestingServer server = new ServoyTestingServer();
		assertNotNull(server, "ServoyTestingServer must be instantiable via no-arg constructor");
	}

	@Test
	public void testServoyTestingServer_showFormInBrowserDescriptionMentionsSpec() {
		Method method = findToolMethod("showFormInBrowser");
		assertNotNull(method);
		Tool tool = method.getAnnotation(Tool.class);
		assertTrue(tool.description().contains("spec"),
				"showFormInBrowser description should mention spec auto-generation");
	}

	@Test
	public void testServoyTestingServer_hasCreateTestFileTool() {
		assertTrue(hasToolNamed("createTestFile"), "ServoyTestingServer must have a 'createTestFile' tool");
	}

	@Test
	public void testServoyTestingServer_hasAddTestMethodTool() {
		assertTrue(hasToolNamed("addTestMethod"), "ServoyTestingServer must have an 'addTestMethod' tool");
	}

	@Test
	public void testServoyTestingServer_hasGenerateTestCasesTool() {
		assertTrue(hasToolNamed("generateTestCases"), "ServoyTestingServer must have a 'generateTestCases' tool");
	}

	@Test
	public void testServoyTestingServer_hasAnalyzeCodeForTestingTool() {
		assertTrue(hasToolNamed("analyzeCodeForTesting"),
				"ServoyTestingServer must have an 'analyzeCodeForTesting' tool");
	}

	@Test
	public void testServoyTestingServer_createTestFileHasTwoParams() {
		Method method = findToolMethod("createTestFile");
		assertNotNull(method);
		assertEquals(2, method.getParameterCount(), "createTestFile must have 2 parameters");
	}

	@Test
	public void testServoyTestingServer_addTestMethodHasThreeParams() {
		Method method = findToolMethod("addTestMethod");
		assertNotNull(method);
		assertEquals(3, method.getParameterCount(), "addTestMethod must have 3 parameters");
	}

	@Test
	public void testServoyTestingServer_generateTestCasesHasTwoParams() {
		Method method = findToolMethod("generateTestCases");
		assertNotNull(method);
		assertEquals(2, method.getParameterCount(), "generateTestCases must have 2 parameters");
	}

	@Test
	public void testServoyTestingServer_analyzeCodeForTestingHasOneParam() {
		Method method = findToolMethod("analyzeCodeForTesting");
		assertNotNull(method);
		assertEquals(1, method.getParameterCount(), "analyzeCodeForTesting must have 1 parameter");
	}

	@Test
	public void testServoyTestingServer_createTestFile_validatesPrefix() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.createTestFile("invalid_name.js", "someSolution");
		assertTrue(result.contains("Error") && result.contains("test_"),
				"createTestFile must reject names not starting with 'test_'");
	}

	@Test
	public void testServoyTestingServer_createTestFile_validatesExtension() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.createTestFile("test_something.txt", "someSolution");
		assertTrue(result.contains("Error") && result.contains(".js"),
				"createTestFile must reject names not ending with '.js'");
	}

	@Test
	public void testServoyTestingServer_generateTestCases_returnsMarkdown() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.generateTestCases("function calculateTotal(price, qty) { return price * qty; }",
				"calculateTotal");
		assertTrue(result.contains("calculateTotal"), "generateTestCases must return markdown with function name");
		assertTrue(result.contains("Happy Path"), "generateTestCases must include happy path section");
		assertTrue(result.contains("Edge Case"), "generateTestCases must include edge cases section");
	}

	@Test
	public void testServoyTestingServer_generateTestCases_extractsParams() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.generateTestCases("function add(a, b) { return a + b; }", "add");
		assertTrue(result.contains("a") && result.contains("b"), "generateTestCases must list parameters");
		assertTrue(result.contains("nullParameters"), "generateTestCases must suggest null parameter tests");
	}

	@Test
	public void testServoyTestingServer_analyzeCodeForTesting_detectsFunction() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.analyzeCodeForTesting("function myHelper(x, y) { return x + y; }");
		assertTrue(result.contains("myHelper"), "analyzeCodeForTesting must detect function name");
		assertTrue(result.contains("x") && result.contains("y"), "analyzeCodeForTesting must list parameters");
	}

	@Test
	public void testServoyTestingServer_analyzeCodeForTesting_handlesNoFunction() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.analyzeCodeForTesting("var x = 42;");
		assertTrue(result.contains("no clear function"), "analyzeCodeForTesting must indicate no function detected");
	}

	@Test
	public void testServoyTestingServer_analyzeCodeForTesting_rejectsNull() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.analyzeCodeForTesting(null);
		assertTrue(result.contains("Error"), "analyzeCodeForTesting must return error for null");
	}

	@Test
	public void testServoyTestingServer_analyzeCodeForTesting_rejectsEmpty() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.analyzeCodeForTesting("   ");
		assertTrue(result.contains("Error"), "analyzeCodeForTesting must return error for blank");
	}

	private boolean hasToolNamed(String name) {
		return Arrays.stream(ServoyTestingServer.class.getMethods()).filter(m -> m.isAnnotationPresent(Tool.class))
				.anyMatch(m -> name.equals(m.getAnnotation(Tool.class).name()));
	}

	private Method findToolMethod(String toolName) {
		return Arrays.stream(ServoyTestingServer.class.getMethods()).filter(m -> m.isAnnotationPresent(Tool.class))
				.filter(m -> toolName.equals(m.getAnnotation(Tool.class).name())).findFirst().orElse(null);
	}

	// -----------------------------------------------------------------------
	// Structure tests for tools that require Servoy runtime (no method calls)
	// -----------------------------------------------------------------------

	@Test
	public void testScreenshotForm_hasCorrectParams() {
		Method m = findToolMethod("screenshotForm");
		assertNotNull(m);
		assertEquals(2, m.getParameterCount());
		assertTrue(m.getParameters()[0].isAnnotationPresent(ToolParam.class));
		assertTrue(m.getParameters()[1].isAnnotationPresent(ToolParam.class));
	}

	@Test
	public void testShowAndTest_descriptionMentionsCypress() {
		Method m = findToolMethod("showAndTest");
		assertNotNull(m);
		Tool tool = m.getAnnotation(Tool.class);
		assertTrue(tool.description().contains("Cypress"));
	}

	@Test
	public void testTestForm_descriptionMentionsCypress() {
		Method m = findToolMethod("testForm");
		assertNotNull(m);
		Tool tool = m.getAnnotation(Tool.class);
		assertTrue(tool.description().contains("Cypress"));
	}

	@Test
	public void testFormSpecTools_descriptionsMentionTypeScriptSpecs() {
		for (String toolName : List.of("generateFormSpec", "testForm", "showFormInBrowser", "showAndTest")) {
			Method m = findToolMethod(toolName);
			assertNotNull(m, toolName + " must be a registered tool");
			assertTrue(m.getAnnotation(Tool.class).description().contains(".spec.cy.ts"),
					toolName + " description must tell the model that a form test may be a .spec.cy.ts file");
		}
	}

	@Test
	public void testGenerateFormSpec_descriptionMentionsSpecCy() {
		Method m = findToolMethod("generateFormSpec");
		assertNotNull(m);
		Tool tool = m.getAnnotation(Tool.class);
		assertTrue(tool.description().contains(".spec.cy.js"));
		// SVY-21171: description must reference the new workspace-relative location
		assertTrue(tool.description().contains("jenkins-custom/e2e-test-scripts/cypress/cy-form"),
				"generateFormSpec description must reference the new cy-form location");
		assertTrue(!tool.description().contains("medias/tests"),
				"generateFormSpec description must no longer reference medias/tests");
	}

	@Test
	public void testShowFormInBrowser_descriptionMentionsBrowser() {
		Method m = findToolMethod("showFormInBrowser");
		assertNotNull(m);
		Tool tool = m.getAnnotation(Tool.class);
		assertTrue(tool.description().contains("browser"));
	}

	@Test
	public void testCreateTestFile_descriptionMentionsJSUnit() {
		Method m = findToolMethod("createTestFile");
		assertNotNull(m);
		Tool tool = m.getAnnotation(Tool.class);
		assertTrue(tool.description().contains("JSUnit"));
	}

	@Test
	public void testAddTestMethod_descriptionMentionsReplace() {
		Method m = findToolMethod("addTestMethod");
		assertNotNull(m);
		Tool tool = m.getAnnotation(Tool.class);
		assertTrue(tool.description().contains("replace"));
	}

	@Test
	public void testGenerateTestCases_nullCode_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.generateTestCases(null, "funcName");
		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("no clear function"),
				"generateTestCases should return error for null code: " + result);
	}

	@Test
	public void testGenerateTestCases_emptyCode_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.generateTestCases("", "funcName");
		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("no clear function"),
				"generateTestCases should return error for empty code: " + result);
	}

	@Test
	public void testGenerateTestCases_nullFunctionName_returnsResult() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.generateTestCases("function doWork(x) { return x * 2; }", null);
		assertNotNull(result);
		assertTrue(result.contains("Suggested Test Cases") || result.contains("test_"),
				"generateTestCases should return suggestions: " + result);
	}

	@Test
	public void testGenerateTestCases_multipleParams() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.generateTestCases("function process(a, b, c) { return a + b + c; }", "process");
		assertNotNull(result);
		assertTrue(result.contains("a") && result.contains("b") && result.contains("c"), "Should list all params");
	}

	@Test
	public void testCheckNGClientStatus_hasNoParams() {
		Method method = findToolMethod("checkNGClientStatus");
		assertNotNull(method);
		assertEquals(0, method.getParameterCount());
	}

	@Test
	public void testRunJsUnitTests_hasCorrectSignature() {
		Method method = findToolMethod("runJsUnitTests");
		assertNotNull(method);
		assertEquals(2, method.getParameterCount());
	}

	@Test
	public void testExecuteTestSetup_descriptionMentionsJDBC() {
		Method m = findToolMethod("executeTestSetup");
		assertNotNull(m);
		Tool tool = m.getAnnotation(Tool.class);
		assertTrue(tool.description().contains("JDBC"));
	}

	@Test
	public void testExecuteTestTeardown_descriptionMentionsJDBC() {
		Method m = findToolMethod("executeTestTeardown");
		assertNotNull(m);
		Tool tool = m.getAnnotation(Tool.class);
		assertTrue(tool.description().contains("JDBC"));
	}

	// -----------------------------------------------------------------------
	// executeTestSetup tool tests
	// -----------------------------------------------------------------------

	@Test
	public void testServoyTestingServer_hasExecuteTestSetupTool() {
		assertTrue(hasToolNamed("executeTestSetup"), "ServoyTestingServer must have an 'executeTestSetup' tool");
	}

	@Test
	public void testExecuteTestSetup_hasCorrectParams() {
		Method m = findToolMethod("executeTestSetup");
		assertNotNull(m, "executeTestSetup method must exist");
		assertEquals(3, m.getParameterCount(), "executeTestSetup must have 3 parameters");

		java.lang.reflect.Parameter[] params = m.getParameters();
		assertTrue(params[0].isAnnotationPresent(ToolParam.class), "first param must have @ToolParam");
		assertTrue(params[1].isAnnotationPresent(ToolParam.class), "second param must have @ToolParam");
		assertTrue(params[2].isAnnotationPresent(ToolParam.class), "third param must have @ToolParam");

		assertEquals(String.class, params[0].getType(), "first param type should be String");
		assertEquals(String.class, params[1].getType(), "second param type should be String");
		assertEquals(String.class, params[2].getType(), "third param type should be String (columnValues JSON)");
	}

	@Test
	public void testExecuteTestSetup_nullServerName_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestSetup(null, "table", "{\"col\":\"val\"}");
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for null serverName: " + result);
	}

	@Test
	public void testExecuteTestSetup_nullTableName_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestSetup("server", null, "{\"col\":\"val\"}");
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for null tableName: " + result);
	}

	@Test
	public void testExecuteTestSetup_nullColumnValues_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestSetup("server", "table", null);
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for null columnValues: " + result);
	}

	@Test
	public void testExecuteTestSetup_emptyColumnValues_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestSetup("server", "table", "");
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for empty columnValues: " + result);
	}

	@Test
	public void testExecuteTestSetup_invalidJson_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestSetup("server", "table", "not valid json");
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for invalid JSON: " + result);
	}

	@Test
	public void testExecuteTestSetup_invalidServer_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestSetup("nonexistent_xyz_999", "table", "{\"col\":\"val\"}");
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for invalid server: " + result);
	}

	// -----------------------------------------------------------------------
	// executeTestTeardown tool tests
	// -----------------------------------------------------------------------

	@Test
	public void testServoyTestingServer_hasExecuteTestTeardownTool() {
		assertTrue(hasToolNamed("executeTestTeardown"), "ServoyTestingServer must have an 'executeTestTeardown' tool");
	}

	@Test
	public void testExecuteTestTeardown_hasCorrectParams() {
		Method m = findToolMethod("executeTestTeardown");
		assertNotNull(m, "executeTestTeardown method must exist");
		assertEquals(4, m.getParameterCount(), "executeTestTeardown must have 4 parameters");

		java.lang.reflect.Parameter[] params = m.getParameters();
		assertTrue(params[0].isAnnotationPresent(ToolParam.class), "first param must have @ToolParam");
		assertTrue(params[1].isAnnotationPresent(ToolParam.class), "second param must have @ToolParam");
		assertTrue(params[2].isAnnotationPresent(ToolParam.class), "third param must have @ToolParam");
		assertTrue(params[3].isAnnotationPresent(ToolParam.class), "fourth param must have @ToolParam");

		for (java.lang.reflect.Parameter p : params) {
			assertEquals(String.class, p.getType(), "all params should be String");
		}
	}

	@Test
	public void testExecuteTestTeardown_nullServerName_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestTeardown(null, "table", "col", "val");
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for null serverName: " + result);
	}

	@Test
	public void testExecuteTestTeardown_nullTableName_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestTeardown("server", null, "col", "val");
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for null tableName: " + result);
	}

	@Test
	public void testExecuteTestTeardown_nullWhereColumn_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestTeardown("server", "table", null, "val");
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for null whereColumn: " + result);
	}

	@Test
	public void testExecuteTestTeardown_nullWhereValue_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestTeardown("server", "table", "col", null);
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for null whereValue: " + result);
	}

	@Test
	public void testExecuteTestTeardown_invalidServer_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestTeardown("nonexistent_xyz_999", "table", "col", "val");
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for invalid server: " + result);
	}

	@Test
	public void testServoyTestingServer_hasGenerateCypressE2ETestTool() {
		assertTrue(hasToolNamed("generateCypressE2ETest"), "Expected generateCypressE2ETest tool");
	}

	@Test
	public void testGenerateCypressE2ETest_hasTenParams() {
		// SVY-21619: a 10th optional 'viewportWidths' parameter was added after
		// loginSuccessSelector.
		Method m = findToolMethod("generateCypressE2ETest");
		assertNotNull(m, "generateCypressE2ETest tool not found");
		assertEquals(10, m.getParameterCount(), "Expected 10 parameters (9 original + viewportWidths)");
	}

	@Test
	@DisplayName("generateCypressE2ETest's 10th parameter is the optional viewportWidths @ToolParam (SVY-21619)")
	public void testGenerateCypressE2ETest_hasViewportWidthsParam() {
		Method m = findToolMethod("generateCypressE2ETest");
		assertNotNull(m, "generateCypressE2ETest tool not found");
		assertEquals(10, m.getParameterCount());
		ToolParam viewportWidths = m.getParameters()[9].getAnnotation(ToolParam.class);
		assertNotNull(viewportWidths, "the 10th parameter must have @ToolParam");
		assertEquals("viewportWidths", viewportWidths.name(), "the 10th parameter must be viewportWidths");
		assertFalse(viewportWidths.required(), "viewportWidths must be optional (required = false)");
		assertFalse(viewportWidths.description().isBlank(), "viewportWidths must have a non-empty description");
	}

	@Test
	@DisplayName("generateCypressE2ETest description documents the always-on reporting and the viewportWidths cross-check")
	public void testGenerateCypressE2ETest_descriptionMentionsViewportMeasurement() {
		Method m = findToolMethod("generateCypressE2ETest");
		assertNotNull(m);
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue(desc.contains("viewportWidths"), "description must name the viewportWidths param: " + desc);
		assertTrue(desc.contains("innerWidth"),
				"description must mention the always-on window.innerWidth reporting: " + desc);
	}

	@Test
	public void testGenerateCypressE2ETest_descriptionMentionsMedias() {
		Method m = findToolMethod("generateCypressE2ETest");
		assertNotNull(m);
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue(desc.contains("jenkins-custom/e2e-test-scripts/cypress/e2e/"),
				"Description should mention the E2E test directory");
	}

	@Test
	public void testGenerateCypressE2ETest_returnsString() {
		Method m = findToolMethod("generateCypressE2ETest");
		assertNotNull(m);
		assertEquals(String.class, m.getReturnType(), "Return type should be String");
	}

	// -----------------------------------------------------------------------
	// getFormNavigationGraph tool tests (SVY-21169)
	// -----------------------------------------------------------------------

	@Test
	public void testServoyTestingServer_hasGetFormNavigationGraphTool() {
		assertTrue(hasToolNamed("getFormNavigationGraph"), "Expected getFormNavigationGraph tool");
	}

	@Test
	public void testServoyTestingServer_hasGetNavigationPathTool() {
		assertTrue(hasToolNamed("getNavigationPath"), "Expected getNavigationPath tool");
	}

	@Test
	public void testGetFormNavigationGraph_hasTwoParams() {
		Method m = findToolMethod("getFormNavigationGraph");
		assertNotNull(m, "getFormNavigationGraph tool not found");
		assertEquals(2, m.getParameterCount(), "Expected 2 parameters (optional formName, summaryOnly)");
		assertTrue(m.getParameters()[0].isAnnotationPresent(ToolParam.class), "First parameter must have @ToolParam");
		assertTrue(m.getParameters()[1].isAnnotationPresent(ToolParam.class), "Second parameter must have @ToolParam");
	}

	@Test
	public void testGetNavigationPath_hasTwoParams() {
		Method m = findToolMethod("getNavigationPath");
		assertNotNull(m, "getNavigationPath tool not found");
		assertEquals(2, m.getParameterCount(), "Expected 2 parameters (targetForm, fromForm)");
		assertTrue(m.getParameters()[0].isAnnotationPresent(ToolParam.class), "First parameter must have @ToolParam");
		assertTrue(m.getParameters()[1].isAnnotationPresent(ToolParam.class), "Second parameter must have @ToolParam");
	}

	@Test
	public void testGetFormNavigationGraph_descriptionMentionsCypress() {
		Method m = findToolMethod("getFormNavigationGraph");
		assertNotNull(m);
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue(desc.contains("Cypress"), "Description should mention Cypress");
	}

	@Test
	public void testGetNavigationPath_descriptionMentionsCypress() {
		Method m = findToolMethod("getNavigationPath");
		assertNotNull(m);
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue(desc.contains("Cypress"), "Description should mention Cypress");
	}

	@Test
	public void testGetFormNavigationGraph_returnsString() {
		Method m = findToolMethod("getFormNavigationGraph");
		assertNotNull(m);
		assertEquals(String.class, m.getReturnType(), "Return type should be String");
	}

	@Test
	public void testGetNavigationPath_returnsString() {
		Method m = findToolMethod("getNavigationPath");
		assertNotNull(m);
		assertEquals(String.class, m.getReturnType(), "Return type should be String");
	}

	@Test
	public void testGetNavigationPath_nullTarget_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.getNavigationPath(null, null);
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for null targetForm: " + result);
	}

	@Test
	public void testGetNavigationPath_emptyTarget_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.getNavigationPath("  ", null);
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for blank targetForm: " + result);
	}

	// -----------------------------------------------------------------------
	// runTestMethod tool tests (SVY-21189)
	// -----------------------------------------------------------------------

	@Test
	public void testRunTestMethod_isAnnotated() {
		Method method = findToolMethod("runTestMethod");
		assertNotNull(method, "runTestMethod tool must exist on ServoyTestingServer");
		Tool tool = method.getAnnotation(Tool.class);
		assertNotNull(tool, "runTestMethod must have @Tool annotation");
		assertEquals("runTestMethod", tool.name());
	}

	@Test
	public void testRunTestMethod_hasCorrectParams() {
		Method method = findToolMethod("runTestMethod");
		assertNotNull(method, "runTestMethod tool must exist");
		assertEquals(3, method.getParameterCount(), "runTestMethod must have 3 parameters");
		assertEquals(String.class, method.getParameterTypes()[0], "first param must be String");
		assertEquals(String.class, method.getParameterTypes()[1], "second param must be String");
		assertEquals(int.class, method.getParameterTypes()[2], "third param must be int");
	}

	@Test
	public void testRunTestMethod_descriptionMentionsScope() {
		Method method = findToolMethod("runTestMethod");
		assertNotNull(method, "runTestMethod tool must exist");
		Tool tool = method.getAnnotation(Tool.class);
		assertTrue(tool.description().contains("scopeOrAll"), "runTestMethod description must contain 'scopeOrAll'");
	}

	@Test
	public void testRunTestMethod_nullMethodName_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.runTestMethod(null, "ALL", 30);
		assertNotNull(result);
		assertTrue(result.startsWith("Error:"),
				"runTestMethod must return an error string for null testMethodName: " + result);
	}

	@Test
	public void testRunTestMethod_blankMethodName_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.runTestMethod("   ", "ALL", 30);
		assertNotNull(result);
		assertTrue(result.startsWith("Error:"),
				"runTestMethod must return an error string for blank testMethodName: " + result);
	}

	// -----------------------------------------------------------------------
	// screenshotForm description tests (SVY-21195)
	// -----------------------------------------------------------------------

	@Test
	public void testScreenshotForm_descriptionMentionsPropertyTypeMismatchGate() {
		Method m = findToolMethod("screenshotForm");
		assertNotNull(m);
		Tool tool = m.getAnnotation(Tool.class);
		assertTrue(tool.description().contains("property type mismatches"),
				"screenshotForm description should name property type mismatches as the blocking gate: "
						+ tool.description());
	}

	@Test
	public void testScreenshotForm_descriptionMentionsMarkers() {
		Method m = findToolMethod("screenshotForm");
		assertNotNull(m);
		Tool tool = m.getAnnotation(Tool.class);
		assertTrue(tool.description().contains("markers"),
				"screenshotForm description should mention markers: " + tool.description());
	}

	@Test
	public void testScreenshotForm_descriptionMentionsTextError() {
		Method m = findToolMethod("screenshotForm");
		assertNotNull(m);
		Tool tool = m.getAnnotation(Tool.class);
		assertTrue(tool.description().contains("text error"),
				"screenshotForm description should mention returning text error: " + tool.description());
	}

	/**
	 * Problem markers no longer gate the screenshot. The description must say so,
	 * and must not claim any file's markers block it.
	 */
	@Test
	public void testScreenshotForm_descriptionSaysMarkersDoNotBlock() {
		Method m = findToolMethod("screenshotForm");
		assertNotNull(m);
		Tool tool = m.getAnnotation(Tool.class);
		assertTrue(tool.description().contains("do not block the screenshot"),
				"screenshotForm description should state problem markers do not block the screenshot: "
						+ tool.description());
		assertFalse(
				tool.description().contains(".frm or .js") || tool.description().contains(".js files")
						|| tool.description().contains("markers on its .frm file"),
				"screenshotForm description must not claim .frm/.js markers block the screenshot: "
						+ tool.description());
	}

	// -----------------------------------------------------------------------
	// testE2E and showAndTestE2E tool tests (SVY-21102)
	// -----------------------------------------------------------------------

	@Test
	public void testServoyTestingServer_hasTestE2ETool() {
		assertTrue(hasToolNamed("testE2E"), "ServoyTestingServer must have a 'testE2E' tool");
	}

	@Test
	public void testServoyTestingServer_hasShowAndTestE2ETool() {
		assertTrue(hasToolNamed("showAndTestE2E"), "ServoyTestingServer must have a 'showAndTestE2E' tool");
	}

	@Test
	public void testTestE2E_hasOneParam() {
		Method m = findToolMethod("testE2E");
		assertNotNull(m, "testE2E tool not found");
		assertEquals(1, m.getParameterCount(), "testE2E must have 1 parameter");
		assertNotNull(m.getParameters()[0].getAnnotation(ToolParam.class), "testE2E parameter must have @ToolParam");
	}

	@Test
	public void testShowAndTestE2E_hasOneParam() {
		Method m = findToolMethod("showAndTestE2E");
		assertNotNull(m, "showAndTestE2E tool not found");
		assertEquals(1, m.getParameterCount(), "showAndTestE2E must have 1 parameter");
		assertNotNull(m.getParameters()[0].getAnnotation(ToolParam.class),
				"showAndTestE2E parameter must have @ToolParam");
	}

	// -----------------------------------------------------------------------
	// generateCypressE2ETest .cy.ts extension tests (SVY-21102)
	// -----------------------------------------------------------------------

	@Test
	public void testGenerateCypressE2ETest_outputFileNameParamMentionsCyTs() {
		Method m = findToolMethod("generateCypressE2ETest");
		assertNotNull(m, "generateCypressE2ETest tool not found");
		// outputFileName is the 4th parameter (index 3): targetForm, scenario,
		// fromForm, outputFileName, ...
		ToolParam param = m.getParameters()[3].getAnnotation(ToolParam.class);
		assertNotNull(param, "outputFileName parameter must have @ToolParam");
		assertTrue(param.description().contains(".cy.ts"),
				"outputFileName @ToolParam description must mention .cy.ts: " + param.description());
	}

	@Test
	public void testGenerateCypressE2ETest_descriptionMentionsCyTs() {
		Method m = findToolMethod("generateCypressE2ETest");
		assertNotNull(m, "generateCypressE2ETest tool not found");
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue(desc.contains("cy.ts") || desc.contains(".cy.ts"),
				"generateCypressE2ETest @Tool description must mention cy.ts: " + desc);
	}

	// --- SVY-21102: behavioral tests for resolveE2EFileName and specPattern ---

	@Test
	public void testResolveE2EFileName_nullOutputFileName_defaultsToCyTs() {
		String result = ServoyTestingServer.resolveE2EFileName(null, "myForm");
		assertEquals("myForm.cy.ts", result);
	}

	@Test
	public void testResolveE2EFileName_blankOutputFileName_defaultsToCyTs() {
		String result = ServoyTestingServer.resolveE2EFileName("  ", "orderForm");
		assertEquals("orderForm.cy.ts", result);
	}

	@Test
	public void testResolveE2EFileName_explicitCyTs_passesThrough() {
		String result = ServoyTestingServer.resolveE2EFileName("custom_flow.cy.ts", "myForm");
		assertEquals("custom_flow.cy.ts", result);
	}

	@Test
	public void testResolveE2EFileName_explicitCyJs_passesThrough() {
		String result = ServoyTestingServer.resolveE2EFileName("legacy_flow.cy.js", "myForm");
		assertEquals("legacy_flow.cy.js", result);
	}

	@Test
	public void testResolveE2EFileName_plainJsExtension_normalizedToCyTs() {
		String result = ServoyTestingServer.resolveE2EFileName("flow.js", "myForm");
		assertEquals("flow.cy.ts", result);
	}

	@Test
	public void testResolveE2EFileName_plainTsExtension_normalizedToCyTs() {
		String result = ServoyTestingServer.resolveE2EFileName("flow.ts", "myForm");
		assertEquals("flow.cy.ts", result);
	}

	@Test
	public void testCypressE2ESpecPattern_containsCyTs() {
		assertTrue(ServoyTestingServer.CYPRESS_E2E_SPEC_PATTERN.contains("cy.ts"),
				"CYPRESS_E2E_SPEC_PATTERN must include cy.ts");
	}

	@Test
	public void testCypressE2ESpecPattern_containsSpecCyJs() {
		assertTrue(ServoyTestingServer.CYPRESS_E2E_SPEC_PATTERN.contains("spec.cy.js"),
				"CYPRESS_E2E_SPEC_PATTERN must include spec.cy.js for form specs");
	}

	@Test
	public void testCypressE2ESpecPattern_containsCyJs() {
		assertTrue(ServoyTestingServer.CYPRESS_E2E_SPEC_PATTERN.contains("cy.js"),
				"CYPRESS_E2E_SPEC_PATTERN must include cy.js for backward compatibility");
	}

	// -----------------------------------------------------------------------
	// E2E tool descriptions must mention both .cy.js and .cy.ts (SVY-21525)
	// -----------------------------------------------------------------------

	@Test
	public void testListE2ETests_descriptionMentionsBothExtensions() {
		Method m = findToolMethod("listE2ETests");
		assertNotNull(m, "listE2ETests tool not found");
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue(desc.contains(".cy.js"), "listE2ETests @Tool description must mention .cy.js: " + desc);
		assertTrue(desc.contains(".cy.ts"), "listE2ETests @Tool description must mention .cy.ts: " + desc);
	}

	@Test
	public void testTestE2E_descriptionMentionsBothExtensions() {
		Method m = findToolMethod("testE2E");
		assertNotNull(m, "testE2E tool not found");
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue(desc.contains(".cy.js"), "testE2E @Tool description must mention .cy.js: " + desc);
		assertTrue(desc.contains(".cy.ts"), "testE2E @Tool description must mention .cy.ts: " + desc);
	}

	@Test
	public void testTestE2E_targetFormParamMentionsBothExtensions() {
		Method m = findToolMethod("testE2E");
		assertNotNull(m, "testE2E tool not found");
		ToolParam param = m.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull(param, "testE2E targetForm parameter must have @ToolParam");
		assertTrue(param.description().contains(".cy.js"),
				"testE2E targetForm @ToolParam description must mention .cy.js: " + param.description());
		assertTrue(param.description().contains(".cy.ts"),
				"testE2E targetForm @ToolParam description must mention .cy.ts: " + param.description());
	}

	@Test
	public void testShowAndTestE2E_descriptionMentionsBothExtensions() {
		Method m = findToolMethod("showAndTestE2E");
		assertNotNull(m, "showAndTestE2E tool not found");
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue(desc.contains(".cy.js"), "showAndTestE2E @Tool description must mention .cy.js: " + desc);
		assertTrue(desc.contains(".cy.ts"), "showAndTestE2E @Tool description must mention .cy.ts: " + desc);
	}

	@Test
	public void testShowAndTestE2E_targetFormParamMentionsBothExtensions() {
		Method m = findToolMethod("showAndTestE2E");
		assertNotNull(m, "showAndTestE2E tool not found");
		ToolParam param = m.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull(param, "showAndTestE2E targetForm parameter must have @ToolParam");
		assertTrue(param.description().contains(".cy.js"),
				"showAndTestE2E targetForm @ToolParam description must mention .cy.js: " + param.description());
		assertTrue(param.description().contains(".cy.ts"),
				"showAndTestE2E targetForm @ToolParam description must mention .cy.ts: " + param.description());
	}

	// -----------------------------------------------------------------------
	// getFormInteractiveElements / locateForm and bug-triage descriptions
	// (SVY-21573)
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("getFormInteractiveElements is registered")
	void getFormInteractiveElements_isRegistered() {
		assertTrue(hasToolNamed("getFormInteractiveElements"), "Expected getFormInteractiveElements tool");
	}

	@Test
	@DisplayName("getFormInteractiveElements has exactly three parameters, all annotated with @ToolParam")
	void getFormInteractiveElements_hasThreeAnnotatedParams() {
		Method m = findToolMethod("getFormInteractiveElements");
		assertNotNull(m);
		assertEquals(3, m.getParameterCount());
		for (java.lang.reflect.Parameter p : m.getParameters()) {
			assertNotNull(p.getAnnotation(ToolParam.class), "Parameter " + p.getName() + " must have @ToolParam");
		}
	}

	@Test
	@DisplayName("getFormInteractiveElements: formName is required, includeStatic and maxElements are optional")
	void getFormInteractiveElements_requiredFlags() {
		Method m = findToolMethod("getFormInteractiveElements");
		assertNotNull(m);
		ToolParam formName = m.getParameters()[0].getAnnotation(ToolParam.class);
		ToolParam includeStatic = m.getParameters()[1].getAnnotation(ToolParam.class);
		ToolParam maxElements = m.getParameters()[2].getAnnotation(ToolParam.class);
		org.junit.jupiter.api.Assertions.assertAll(() -> assertEquals("formName", formName.name()),
				() -> assertTrue(formName.required(), "formName must be required"),
				() -> assertEquals("includeStatic", includeStatic.name()),
				() -> assertFalse(includeStatic.required(), "includeStatic must be optional"),
				() -> assertEquals("boolean", includeStatic.type()),
				() -> assertEquals("maxElements", maxElements.name()),
				() -> assertFalse(maxElements.required(), "maxElements must be optional"),
				() -> assertEquals("integer", maxElements.type()));
	}

	@Test
	@DisplayName("getFormInteractiveElements: every @ToolParam has a non-empty description")
	void getFormInteractiveElements_paramDescriptionsNotEmpty() {
		Method m = findToolMethod("getFormInteractiveElements");
		assertNotNull(m);
		for (java.lang.reflect.Parameter p : m.getParameters()) {
			assertFalse(p.getAnnotation(ToolParam.class).description().isBlank(),
					"@ToolParam description must not be empty for " + p.getAnnotation(ToolParam.class).name());
		}
	}

	@Test
	@DisplayName("getFormInteractiveElements description mentions data-cy, findForm and Cypress")
	void getFormInteractiveElements_descriptionKeywords() {
		Method m = findToolMethod("getFormInteractiveElements");
		assertNotNull(m);
		String desc = m.getAnnotation(Tool.class).description();
		org.junit.jupiter.api.Assertions.assertAll(
				() -> assertTrue(desc.contains("data-cy"), "should mention data-cy: " + desc),
				() -> assertTrue(desc.contains("findForm"), "should mention findForm: " + desc),
				() -> assertTrue(desc.contains("Cypress"), "should mention Cypress: " + desc));
	}

	@Test
	@DisplayName("getFormInteractiveElements returns String")
	void getFormInteractiveElements_returnsString() {
		Method m = findToolMethod("getFormInteractiveElements");
		assertNotNull(m);
		assertEquals(String.class, m.getReturnType());
	}

	@Test
	@DisplayName("getFormInteractiveElements(null, false, 0) returns the exact formName error")
	void getFormInteractiveElements_nullFormName() {
		assertEquals("Error: formName parameter is required",
				new ServoyTestingServer().getFormInteractiveElements(null, false, 0));
	}

	@Test
	@DisplayName("getFormInteractiveElements with a blank formName returns the exact formName error")
	void getFormInteractiveElements_blankFormName() {
		assertEquals("Error: formName parameter is required",
				new ServoyTestingServer().getFormInteractiveElements("  ", false, 0));
	}

	@Test
	@DisplayName("getFormNavigationGraph description mentions bug triage")
	void getFormNavigationGraph_descriptionMentionsBugTriage() {
		Method m = findToolMethod("getFormNavigationGraph");
		assertNotNull(m);
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue(desc.contains("bug triage"), "Description should mention bug triage: " + desc);
	}

	@Test
	@DisplayName("getNavigationPath description mentions bug triage")
	void getNavigationPath_descriptionMentionsBugTriage() {
		Method m = findToolMethod("getNavigationPath");
		assertNotNull(m);
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue(desc.contains("bug triage"), "Description should mention bug triage: " + desc);
	}

	@Test
	@DisplayName("findForm description points to getFormInteractiveElements and locateForm and no longer names showFormInBrowser")
	void findForm_descriptionPointsToNewTools() {
		Method m = findToolMethod("findForm");
		assertNotNull(m);
		String desc = m.getAnnotation(Tool.class).description();
		org.junit.jupiter.api.Assertions.assertAll(
				() -> assertTrue(desc.contains("getFormInteractiveElements"),
						"should name getFormInteractiveElements: " + desc),
				() -> assertTrue(desc.contains("locateForm"), "should name locateForm: " + desc),
				() -> assertFalse(desc.contains("showFormInBrowser"),
						"must not name the deprecated showFormInBrowser: " + desc));
	}

	@Test
	@DisplayName("locateForm description mentions Cypress, bug triage and the ambiguous status")
	void locateForm_descriptionKeywords() {
		Method m = findToolMethod("locateForm");
		assertNotNull(m);
		String desc = m.getAnnotation(Tool.class).description();
		org.junit.jupiter.api.Assertions.assertAll(
				() -> assertTrue(desc.contains("Cypress"), "should mention Cypress: " + desc),
				() -> assertTrue(desc.contains("bug triage"), "should mention bug triage: " + desc),
				() -> assertTrue(desc.contains("'ambiguous'"), "should explain the 'ambiguous' status: " + desc),
				() -> assertTrue(desc.contains("never guesses"), "should state that it never guesses: " + desc));
	}

	@Test
	@DisplayName("locateForm is registered")
	void locateForm_isRegistered() {
		assertTrue(hasToolNamed("locateForm"), "Expected locateForm tool");
	}

	@Test
	@DisplayName("locateForm has query (required) and includeElements (optional boolean)")
	void locateForm_params() {
		Method m = findToolMethod("locateForm");
		assertNotNull(m);
		assertEquals(2, m.getParameterCount());
		ToolParam query = m.getParameters()[0].getAnnotation(ToolParam.class);
		ToolParam includeElements = m.getParameters()[1].getAnnotation(ToolParam.class);
		assertNotNull(query);
		assertNotNull(includeElements);
		org.junit.jupiter.api.Assertions.assertAll(() -> assertEquals("query", query.name()),
				() -> assertTrue(query.required(), "query must be required"),
				() -> assertFalse(query.description().isBlank()),
				() -> assertEquals("includeElements", includeElements.name()),
				() -> assertFalse(includeElements.required(), "includeElements must be optional"),
				() -> assertEquals("boolean", includeElements.type()),
				() -> assertFalse(includeElements.description().isBlank()));
	}

	@Test
	@DisplayName("locateForm.includeElements is the Boolean wrapper so an omitted argument stays null instead of coercing to false")
	void locateForm_includeElementsIsWrapperType() {
		Method m = findToolMethod("locateForm");
		assertNotNull(m);
		assertEquals(Boolean.class, m.getParameterTypes()[1]);
	}

	@Test
	@DisplayName("locateForm returns String")
	void locateForm_returnsString() {
		Method m = findToolMethod("locateForm");
		assertNotNull(m);
		assertEquals(String.class, m.getReturnType());
	}

	@Test
	@DisplayName("locateForm(null, true) returns the exact query error")
	void locateForm_nullQuery() {
		assertEquals("Error: query parameter is required", new ServoyTestingServer().locateForm(null, true));
	}

	@Test
	@DisplayName("locateForm with a blank query returns the exact query error")
	void locateForm_blankQuery() {
		assertEquals("Error: query parameter is required", new ServoyTestingServer().locateForm("   ", null));
	}

	// -----------------------------------------------------------------------
	// Model-seam driven guards (mock the Servoy model boundary).
	//
	// These exercise ONLY the server's own no-active-project / no-active-solution
	// guards, reached through the protected getServoyModel() seam. The Servoy core
	// is stubbed to return null/empty at the boundary; nothing of the core's real
	// behaviour is under test (that belongs to servoy-eclipse / integrationpde).
	// -----------------------------------------------------------------------

	/** A {@link ServoyTestingServer} whose model seam returns the supplied mock. */
	private static ServoyTestingServer serverWith(IDeveloperServoyModel model) {
		return new ServoyTestingServer() {
			@Override
			protected IDeveloperServoyModel getServoyModel() {
				return model;
			}
		};
	}

	/**
	 * A server whose model seam and discovery seam return the supplied values: no
	 * reflection, no workspace.
	 */
	private static ServoyTestingServer serverDiscovering(IDeveloperServoyModel model, List<String> discovered) {
		return new ServoyTestingServer() {
			@Override
			protected IDeveloperServoyModel getServoyModel() {
				return model;
			}

			@Override
			protected CypressTestDiscoveryService getTestDiscovery() {
				return new CypressTestDiscoveryService() {
					@Override
					public List<String> discoverAllTestForms() {
						return discovered;
					}
				};
			}
		};
	}

	/**
	 * A model whose active solution is {@code solutionName} and whose modules carry
	 * the given names.
	 */
	private static IDeveloperServoyModel modelWithSolutionAndModules(String solutionName, String... moduleNames) {
		IDeveloperServoyModel model = modelWithSolution(solutionName);
		ServoyProject[] modules = new ServoyProject[moduleNames.length];
		for (int i = 0; i < moduleNames.length; i++) {
			ServoyProject module = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			lenient().when(module.getSolution()).thenReturn(solution);
			lenient().when(solution.getName()).thenReturn(moduleNames[i]);
			modules[i] = module;
		}
		lenient().when(model.getModulesOfActiveProject()).thenReturn(modules);
		return model;
	}

	/** A model whose active project is {@code null}. */
	private static IDeveloperServoyModel modelWithNoActiveProject() {
		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		when(model.getActiveProject()).thenReturn(null);
		return model;
	}

	@Nested
	@DisplayName("createTestFile guards")
	class CreateTestFileGuards {
		@Test
		@DisplayName("createTestFile with TARGET and no active project reports no active project")
		void targetNoActiveProject() {
			String result = serverWith(modelWithNoActiveProject()).createTestFile("test_utils.js", "TARGET");
			assertEquals("Error: No active project found. Please open a Servoy solution.", result);
		}

		@Test
		@DisplayName("createTestFile with TARGET (any case) and no active project still guards")
		void targetCaseInsensitive() {
			String result = serverWith(modelWithNoActiveProject()).createTestFile("utils_test.js", "target");
			assertEquals("Error: No active project found. Please open a Servoy solution.", result);
		}

		@Test
		@DisplayName("createTestFile rejects a null file name before touching the model")
		void nullFileName() {
			// The extension validation runs before the model is consulted.
			String result = serverWith(modelWithNoActiveProject()).createTestFile(null, "TARGET");
			assertTrue(result.startsWith("Error: Test file name must end with '.js'"), "got: " + result);
		}

		@Test
		@DisplayName("createTestFile rejects a name that neither starts with test_ nor ends with _test")
		void badConvention() {
			String result = serverWith(modelWithNoActiveProject()).createTestFile("utils.js", "TARGET");
			assertTrue(result.contains("must follow the convention 'test_*.js' or '*_test.js'"), "got: " + result);
		}
	}

	@Nested
	@DisplayName("addTestMethod guards")
	class AddTestMethodGuards {
		@Test
		@DisplayName("addTestMethod with no active project reports no active project")
		void noActiveProject() {
			String result = serverWith(modelWithNoActiveProject()).addTestMethod("test_utils.js", "test_it",
					"doAssertSomething();");
			assertEquals("Error: No active project found. Please open a Servoy solution.", result);
		}
	}

	@Nested
	@DisplayName("generateCypressE2ETest guards")
	class GenerateCypressE2ETestGuards {
		@Test
		@DisplayName("generateCypressE2ETest with no active project reports no active Servoy project")
		void noActiveProject() {
			String result = serverWith(modelWithNoActiveProject()).generateCypressE2ETest("dialogform1", "a scenario",
					null, null, null, null, null, null, null, null);
			assertEquals("Error: No active Servoy project found.", result);
		}

		@Test
		@DisplayName("generateCypressE2ETest with an active project but no editing solution reports no active Servoy project")
		void noEditingSolution() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			ServoyProject project = mock(ServoyProject.class);
			when(model.getActiveProject()).thenReturn(project);
			when(project.getEditingSolution()).thenReturn(null);
			String result = serverWith(model).generateCypressE2ETest("dialogform1", "a scenario", null, null, null,
					null, null, null, null, null);
			assertEquals("Error: No active Servoy project found.", result);
		}
	}

	// -----------------------------------------------------------------------
	// Pure private/package helpers reached via reflection - no model needed.
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("extractFunctionName (private static)")
	class ExtractFunctionName {
		private String extractFunctionName(String code) throws Exception {
			Method m = ServoyTestingServer.class.getDeclaredMethod("extractFunctionName", String.class);
			m.setAccessible(true);
			return (String) m.invoke(null, code);
		}

		@Test
		@DisplayName("extracts the name of a declared function")
		void extracts() throws Exception {
			assertEquals("calcTotal", extractFunctionName("function calcTotal(a, b) { return a + b; }"));
		}

		@Test
		@DisplayName("returns null for null input or code with no function")
		void noFunction() throws Exception {
			org.junit.jupiter.api.Assertions.assertNull(extractFunctionName(null));
			org.junit.jupiter.api.Assertions.assertNull(extractFunctionName("var x = 1;"));
		}
	}

	@Nested
	@DisplayName("extractTestParameters (private static)")
	class ExtractTestParameters {
		private String[] extractTestParameters(String code, String functionName) throws Exception {
			Method m = ServoyTestingServer.class.getDeclaredMethod("extractTestParameters", String.class, String.class);
			m.setAccessible(true);
			return (String[]) m.invoke(null, code, functionName);
		}

		@Test
		@DisplayName("splits and trims the parameter list of the named function")
		void splitsParams() throws Exception {
			assertArrayEquals(new String[] { "price", "qty" },
					extractTestParameters("function total(price, qty) { }", "total"));
		}

		@Test
		@DisplayName("returns an empty array for a no-arg function")
		void emptyParams() throws Exception {
			assertEquals(0, extractTestParameters("function go() { }", "go").length);
		}

		@Test
		@DisplayName("returns an empty array when code or function name is null or the function is absent")
		void nullOrMissing() throws Exception {
			assertEquals(0, extractTestParameters(null, "go").length);
			assertEquals(0, extractTestParameters("function go(a) {}", null).length);
			assertEquals(0, extractTestParameters("function other(a) {}", "go").length);
		}
	}

	@Nested
	@DisplayName("injectLoginIntoBeforeEach (private)")
	class InjectLogin {
		private String injectLoginIntoBeforeEach(ServoyTestingServer server, String content) throws Exception {
			Method m = ServoyTestingServer.class.getDeclaredMethod("injectLoginIntoBeforeEach", String.class);
			m.setAccessible(true);
			return (String) m.invoke(server, content);
		}

		@Test
		@DisplayName("inserts cy.login() before the cy.visit in beforeEach")
		void injects() throws Exception {
			String content = "describe('x', () => {\n  beforeEach(() => {\n    cy.visit('/');\n  });\n});\n";
			String out = injectLoginIntoBeforeEach(new ServoyTestingServer(), content);
			assertTrue(out.contains("cy.login();"), "should inject cy.login(): " + out);
			assertTrue(out.contains("cy.visit('"), "should keep the visit call: " + out);
			assertTrue(out.indexOf("cy.login();") < out.indexOf("cy.visit('"), "login must come before visit");
		}

		@Test
		@DisplayName("returns the content unchanged when there is no matching beforeEach visit")
		void unchanged() throws Exception {
			String content = "describe('x', () => {\n  it('does', () => {});\n});\n";
			assertEquals(content, injectLoginIntoBeforeEach(new ServoyTestingServer(), content));
		}
	}

	@Nested
	@DisplayName("discoverCypressHelpers (private)")
	class DiscoverCypressHelpers {
		private String discoverCypressHelpers(ServoyTestingServer server, Path dir) throws Exception {
			Method m = ServoyTestingServer.class.getDeclaredMethod("discoverCypressHelpers", Path.class);
			m.setAccessible(true);
			return (String) m.invoke(server, dir);
		}

		@Test
		@DisplayName("returns null when the support directory does not exist")
		void missingDir(@TempDir Path tmp) throws Exception {
			assertEquals(null, discoverCypressHelpers(new ServoyTestingServer(), tmp.resolve("nope")));
		}

		@Test
		@DisplayName("includes real command files and skips e2e.js and comment-only files")
		void collectsCommands(@TempDir Path tmp) throws Exception {
			Files.writeString(tmp.resolve("commands.js"), "Cypress.Commands.add('login', () => {});\n",
					StandardCharsets.UTF_8);
			Files.writeString(tmp.resolve("e2e.js"), "import './commands';\n", StandardCharsets.UTF_8);
			Files.writeString(tmp.resolve("empty.js"), "// just a comment\n\n", StandardCharsets.UTF_8);
			String result = discoverCypressHelpers(new ServoyTestingServer(), tmp);
			assertNotNull(result);
			assertTrue(result.contains("Cypress.Commands.add"), "should include the real command file: " + result);
			assertTrue(result.contains("commands.js"), "should label the file: " + result);
			assertFalse(result.contains("import './commands'"), "should skip e2e.js: " + result);
			assertFalse(result.contains("just a comment"), "should skip comment-only files: " + result);
		}
	}

	@Nested
	@DisplayName("resolveE2EFileName edge cases")
	class ResolveE2EFileNameEdges {
		@Test
		@DisplayName("a bare name with no extension is normalized to .cy.ts")
		void bareName() {
			assertEquals("flow.cy.ts", ServoyTestingServer.resolveE2EFileName("flow", "ignored"));
		}
	}

	// =======================================================================
	// Delegate-collaborator pass-through / error marshalling.
	//
	// ServoyTestingServer is a thin @McpServer wrapper: each tool calls one of
	// its private-final collaborator services inside a try/catch, returning the
	// delegate's String on the happy path and "Error: " + message when it throws.
	// These tests reflection-swap the collaborators with Mockito mocks (no
	// production change, no visibility widening) and drive ONLY the server's own
	// branches - the tool-argument -> delegate-call mapping, the pass-through of
	// the delegate's result, and the exception -> "Error:" marshalling that must
	// never let an exception escape a tool. The collaborators' real behaviour
	// (the JSUnit run, the Cypress render, the DLTK/model work) needs the
	// workbench / a running client and stays in the integrationpde suite.
	//
	// None of these tests touch ServoyModelManager or ResourcesPlugin: the tools
	// that resolve the active solution themselves are reached through the
	// overridable getServoyModel() seam, with a mocked project/solution.
	// =======================================================================

	/** Replaces one private field of the given server with the supplied value. */
	private static void setField(ServoyTestingServer target, String name, Object value) {
		try {
			Field f = ServoyTestingServer.class.getDeclaredField(name);
			f.setAccessible(true);
			f.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("failed to set field '" + name + "'", e);
		}
	}

	/**
	 * A model whose active project -> solution -> name chain resolves to the given
	 * solution name.
	 */
	private static IDeveloperServoyModel modelWithSolution(String solutionName) {
		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		ServoyProject project = mock(ServoyProject.class);
		Solution solution = mock(Solution.class);
		lenient().when(model.getActiveProject()).thenReturn(project);
		lenient().when(project.getSolution()).thenReturn(solution);
		lenient().when(solution.getName()).thenReturn(solutionName);
		return model;
	}

	@Nested
	@DisplayName("JSUnit runner pass-through")
	class JsUnitRunnerDelegation {
		@Test
		@DisplayName("runJsUnitTests forwards scopeOrAll/timeout and returns the runner result")
		void runJsUnitTestsPassesThrough() {
			JSUnitRunnerService runner = mock(JSUnitRunnerService.class);
			when(runner.runTests("test_utils", 60)).thenReturn("3 passed");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "jsunitRunner", runner);

			assertEquals("3 passed", server.runJsUnitTests("test_utils", 60));
			verify(runner).runTests("test_utils", 60);
		}

		@Test
		@DisplayName("runJsUnitTests marshals a thrown exception into 'Error: <message>'")
		void runJsUnitTestsWrapsException() {
			JSUnitRunnerService runner = mock(JSUnitRunnerService.class);
			when(runner.runTests(anyString(), anyInt())).thenThrow(new RuntimeException("no app server"));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "jsunitRunner", runner);

			assertEquals("Error: no app server", server.runJsUnitTests("ALL", 120));
		}

		@Test
		@DisplayName("runTestMethod forwards method/scope/timeout and returns the runner result")
		void runTestMethodPassesThrough() {
			JSUnitRunnerService runner = mock(JSUnitRunnerService.class);
			when(runner.runTestMethod("testCalc", "ALL", 30)).thenReturn("testCalc PASSED");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "jsunitRunner", runner);

			assertEquals("testCalc PASSED", server.runTestMethod("testCalc", "ALL", 30));
			verify(runner).runTestMethod("testCalc", "ALL", 30);
		}

		@Test
		@DisplayName("runTestMethod marshals a thrown exception into 'Error: <message>'")
		void runTestMethodWrapsException() {
			JSUnitRunnerService runner = mock(JSUnitRunnerService.class);
			when(runner.runTestMethod(anyString(), eq("ALL"), anyInt())).thenThrow(new RuntimeException("boom"));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "jsunitRunner", runner);

			assertEquals("Error: boom", server.runTestMethod("testCalc", "ALL", 30));
		}
	}

	@Nested
	@DisplayName("JSUnit coverage pass-through")
	class CoverageDelegation {
		@Test
		@DisplayName("getJSUnitCoverageReport forwards the path and returns the service result")
		void coverageReportPassesThrough() {
			JSUnitCoverageService coverage = mock(JSUnitCoverageService.class);
			when(coverage.getCoverageReport("/tmp/cov.json")).thenReturn("coverage: 80%");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "coverageService", coverage);

			assertEquals("coverage: 80%", server.getJSUnitCoverageReport("/tmp/cov.json"));
			verify(coverage).getCoverageReport("/tmp/cov.json");
		}

		@Test
		@DisplayName("getJSUnitCoverageReport marshals a thrown exception into 'Error: <message>'")
		void coverageReportWrapsException() {
			JSUnitCoverageService coverage = mock(JSUnitCoverageService.class);
			when(coverage.getCoverageReport(null)).thenThrow(new RuntimeException("no report"));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "coverageService", coverage);

			assertEquals("Error: no report", server.getJSUnitCoverageReport(null));
		}

		@Test
		@DisplayName("suggestTestsFromCoverage forwards path/maxFunctions and returns the service result")
		void suggestTestsPassesThrough() {
			JSUnitCoverageService coverage = mock(JSUnitCoverageService.class);
			when(coverage.suggestTests("/tmp/cov.json", 5)).thenReturn("suggestions...");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "coverageService", coverage);

			assertEquals("suggestions...", server.suggestTestsFromCoverage("/tmp/cov.json", 5));
			verify(coverage).suggestTests("/tmp/cov.json", 5);
		}

		@Test
		@DisplayName("suggestTestsFromCoverage marshals a thrown exception into 'Error: <message>'")
		void suggestTestsWrapsException() {
			JSUnitCoverageService coverage = mock(JSUnitCoverageService.class);
			when(coverage.suggestTests(anyString(), anyInt())).thenThrow(new RuntimeException("parse failed"));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "coverageService", coverage);

			assertEquals("Error: parse failed", server.suggestTestsFromCoverage("/tmp/cov.json", 20));
		}
	}

	@Nested
	@DisplayName("Form-preview pass-through")
	class FormPreviewDelegation {
		@Test
		@DisplayName("screenshotForm forwards formName/waitSeconds and returns the service result")
		void screenshotPassesThrough() {
			FormPreviewService preview = mock(FormPreviewService.class);
			when(preview.screenshotForm("orders", 5)).thenReturn("Screenshot saved: /x.png");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "formPreview", preview);

			assertEquals("Screenshot saved: /x.png", server.screenshotForm("orders", 5));
			verify(preview).screenshotForm("orders", 5);
		}

		@Test
		@DisplayName("screenshotForm marshals a thrown exception into 'Error: <message>'")
		void screenshotWrapsException() {
			FormPreviewService preview = mock(FormPreviewService.class);
			when(preview.screenshotForm(anyString(), anyInt())).thenThrow(new RuntimeException("no browser"));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "formPreview", preview);

			assertEquals("Error: no browser", server.screenshotForm("orders", 10));
		}

		@Test
		@DisplayName("checkNGClientStatus returns the service result")
		void checkStatusPassesThrough() {
			FormPreviewService preview = mock(FormPreviewService.class);
			when(preview.checkNGClientStatus()).thenReturn("Servoy Developer is running.");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "formPreview", preview);

			assertEquals("Servoy Developer is running.", server.checkNGClientStatus());
			verify(preview).checkNGClientStatus();
		}

		@Test
		@DisplayName("checkNGClientStatus marshals a thrown exception into 'Error: <message>'")
		void checkStatusWrapsException() {
			FormPreviewService preview = mock(FormPreviewService.class);
			when(preview.checkNGClientStatus()).thenThrow(new RuntimeException("model gone"));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "formPreview", preview);

			assertEquals("Error: model gone", server.checkNGClientStatus());
		}
	}

	@Nested
	@DisplayName("showFormInBrowser branches (seamed model + mocked collaborators)")
	class ShowFormInBrowserDelegation {
		@Test
		@DisplayName("when the spec already exists, the preview result is returned unchanged and no spec is generated")
		void existingSpecReturnedUnchanged() {
			FormPreviewService preview = mock(FormPreviewService.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			FormSpecModelBuilder builder = mock(FormSpecModelBuilder.class);
			when(preview.showFormInBrowser("orders", true)).thenReturn("Opened form 'orders'");
			when(gen.specExists("orders", "sol")).thenReturn(true);
			ServoyTestingServer server = new ServoyTestingServer() {
				@Override
				protected IDeveloperServoyModel getServoyModel() {
					return modelWithSolution("sol");
				}
			};
			setField(server, "formPreview", preview);
			setField(server, "specGenerator", gen);
			setField(server, "specModelBuilder", builder);

			assertEquals("Opened form 'orders'", server.showFormInBrowser("orders"));
			verify(gen, never()).generateSpec(anyString(), org.mockito.ArgumentMatchers.any(),
					org.mockito.ArgumentMatchers.anyList());
		}

		@Test
		@DisplayName("when no spec exists, a generated spec is appended to the preview result")
		void missingSpecIsGeneratedAndAppended() {
			FormPreviewService preview = mock(FormPreviewService.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			FormSpecModelBuilder builder = mock(FormSpecModelBuilder.class);
			when(preview.showFormInBrowser("orders", true)).thenReturn("Opened form 'orders'");
			when(gen.specExists("orders", "sol")).thenReturn(false);
			// FormSpecModel is final (not mockable); a null model drives the same append
			// path.
			when(builder.build("orders")).thenReturn(null);
			when(gen.generateSpec("orders", null, java.util.List.of())).thenReturn("Created spec");
			ServoyTestingServer server = new ServoyTestingServer() {
				@Override
				protected IDeveloperServoyModel getServoyModel() {
					return modelWithSolution("sol");
				}
			};
			setField(server, "formPreview", preview);
			setField(server, "specGenerator", gen);
			setField(server, "specModelBuilder", builder);

			String result = server.showFormInBrowser("orders");
			assertTrue(result.contains("Opened form 'orders'"), "keeps the preview result: " + result);
			assertTrue(result.contains("Created spec"), "appends the generated spec: " + result);
		}

		@Test
		@DisplayName("an Error result from the preview is returned without attempting spec generation")
		void previewErrorNotAppended() {
			FormPreviewService preview = mock(FormPreviewService.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			when(preview.showFormInBrowser("orders", true)).thenReturn("Error: no project");
			ServoyTestingServer server = new ServoyTestingServer() {
				@Override
				protected IDeveloperServoyModel getServoyModel() {
					return modelWithSolution("sol");
				}
			};
			setField(server, "formPreview", preview);
			setField(server, "specGenerator", gen);

			assertEquals("Error: no project", server.showFormInBrowser("orders"));
			verify(gen, never()).specExists(anyString(), anyString());
		}

		@Test
		@DisplayName("generateSpecWithModel falls back to a null model when the builder throws")
		void specModelBuilderFailureFallsBack() {
			FormPreviewService preview = mock(FormPreviewService.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			FormSpecModelBuilder builder = mock(FormSpecModelBuilder.class);
			when(preview.showFormInBrowser("orders", true)).thenReturn("Opened form 'orders'");
			when(gen.specExists("orders", "sol")).thenReturn(false);
			when(builder.build("orders")).thenThrow(new RuntimeException("cannot build model"));
			when(gen.generateSpec("orders", null, java.util.List.of())).thenReturn("Basic spec");
			ServoyTestingServer server = new ServoyTestingServer() {
				@Override
				protected IDeveloperServoyModel getServoyModel() {
					return modelWithSolution("sol");
				}
			};
			setField(server, "formPreview", preview);
			setField(server, "specGenerator", gen);
			setField(server, "specModelBuilder", builder);

			String result = server.showFormInBrowser("orders");
			assertTrue(result.contains("Basic spec"), "uses the basic (null-model) spec: " + result);
			verify(gen).generateSpec("orders", null, java.util.List.of());
		}
	}

	@Nested
	@DisplayName("generateFormSpec pass-through")
	class GenerateFormSpecDelegation {
		@Test
		@DisplayName("generateFormSpec builds the model and returns the generated spec")
		void generatesWithModel() {
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			FormSpecModelBuilder builder = mock(FormSpecModelBuilder.class);
			// FormSpecModel is a final class (not mockable); the builder returns its real
			// result, so a null model is enough to drive the build -> generateSpec
			// hand-off.
			when(builder.build("orders")).thenReturn(null);
			when(gen.generateSpec("orders", null, java.util.List.of())).thenReturn("Created spec for orders");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "specGenerator", gen);
			setField(server, "specModelBuilder", builder);

			assertEquals("Created spec for orders", server.generateFormSpec("orders", null));
			verify(builder).build("orders");
			verify(gen).generateSpec("orders", null, java.util.List.of());
		}

		@Test
		@DisplayName("generateFormSpec marshals a thrown exception into 'Error: <message>'")
		void wrapsException() {
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			FormSpecModelBuilder builder = mock(FormSpecModelBuilder.class);
			when(builder.build("orders")).thenReturn(null);
			when(gen.generateSpec("orders", null, java.util.List.of())).thenThrow(new RuntimeException("gen failed"));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "specGenerator", gen);
			setField(server, "specModelBuilder", builder);

			assertEquals("Error: gen failed", server.generateFormSpec("orders", null));
		}
	}

	@Nested
	@DisplayName("Cypress form/E2E test runs pass-through (specRunner mocked)")
	class CypressRunDelegation {
		@Test
		@DisplayName("testForm runs the form Cypress spec when it already exists and returns the runner result")
		void testFormExistingSpec() {
			FormSpecRunner runner = mock(FormSpecRunner.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			when(gen.specExists("orders", "sol")).thenReturn(true);
			when(runner.runFormCypressTests("orders", true)).thenReturn("1 passing");
			ServoyTestingServer server = new ServoyTestingServer() {
				@Override
				protected IDeveloperServoyModel getServoyModel() {
					return modelWithSolution("sol");
				}
			};
			setField(server, "specRunner", runner);
			setField(server, "specGenerator", gen);

			assertEquals("1 passing", server.testForm("orders"));
			verify(runner).runFormCypressTests("orders", true);
		}

		@Test
		@DisplayName("testForm returns the generation error and never runs the spec when generation fails")
		void testFormGenerationErrorShortCircuits() {
			FormSpecRunner runner = mock(FormSpecRunner.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			FormSpecModelBuilder builder = mock(FormSpecModelBuilder.class);
			when(gen.specExists("orders", "sol")).thenReturn(false);
			when(builder.build("orders")).thenReturn(null);
			when(gen.generateSpec("orders", null, java.util.List.of())).thenReturn("Error: cannot generate");
			ServoyTestingServer server = new ServoyTestingServer() {
				@Override
				protected IDeveloperServoyModel getServoyModel() {
					return modelWithSolution("sol");
				}
			};
			setField(server, "specRunner", runner);
			setField(server, "specGenerator", gen);
			setField(server, "specModelBuilder", builder);

			assertEquals("Error: cannot generate", server.testForm("orders"));
			verify(runner, never()).runFormCypressTests(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
		}

		@Test
		@DisplayName("testForm marshals a thrown exception into 'Error: <message>'")
		void testFormWrapsException() {
			FormSpecRunner runner = mock(FormSpecRunner.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			when(gen.specExists("orders", "sol")).thenReturn(true);
			when(runner.runFormCypressTests("orders", true)).thenThrow(new RuntimeException("no client"));
			ServoyTestingServer server = new ServoyTestingServer() {
				@Override
				protected IDeveloperServoyModel getServoyModel() {
					return modelWithSolution("sol");
				}
			};
			setField(server, "specRunner", runner);
			setField(server, "specGenerator", gen);

			assertEquals("Error: no client", server.testForm("orders"));
		}

		@Test
		@DisplayName("showAndTest runs the form Cypress spec in headed mode (openBrowser=false)")
		void showAndTestRunsHeaded() {
			FormSpecRunner runner = mock(FormSpecRunner.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			when(gen.specExists("orders", "sol")).thenReturn(true);
			when(runner.runFormCypressTests("orders", false)).thenReturn("2 passing");
			ServoyTestingServer server = new ServoyTestingServer() {
				@Override
				protected IDeveloperServoyModel getServoyModel() {
					return modelWithSolution("sol");
				}
			};
			setField(server, "specRunner", runner);
			setField(server, "specGenerator", gen);

			assertEquals("2 passing", server.showAndTest("orders"));
			verify(runner).runFormCypressTests("orders", false);
		}

		@Test
		@DisplayName("showAndTest generates a missing spec then runs it headed")
		void showAndTestGeneratesMissingSpec() {
			FormSpecRunner runner = mock(FormSpecRunner.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			FormSpecModelBuilder builder = mock(FormSpecModelBuilder.class);
			when(gen.specExists("orders", "sol")).thenReturn(false);
			when(builder.build("orders")).thenReturn(null);
			when(gen.generateSpec("orders", null, java.util.List.of())).thenReturn("Created spec");
			when(runner.runFormCypressTests("orders", false)).thenReturn("2 passing");
			ServoyTestingServer server = new ServoyTestingServer() {
				@Override
				protected IDeveloperServoyModel getServoyModel() {
					return modelWithSolution("sol");
				}
			};
			setField(server, "specRunner", runner);
			setField(server, "specGenerator", gen);
			setField(server, "specModelBuilder", builder);

			assertEquals("2 passing", server.showAndTest("orders"));
			verify(gen).generateSpec("orders", null, java.util.List.of());
			verify(runner).runFormCypressTests("orders", false);
		}

		@Test
		@DisplayName("showAndTest returns the generation error and never runs the spec when generation fails")
		void showAndTestGenerationErrorShortCircuits() {
			FormSpecRunner runner = mock(FormSpecRunner.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			FormSpecModelBuilder builder = mock(FormSpecModelBuilder.class);
			when(gen.specExists("orders", "sol")).thenReturn(false);
			when(builder.build("orders")).thenReturn(null);
			when(gen.generateSpec("orders", null, java.util.List.of())).thenReturn("Error: cannot generate");
			ServoyTestingServer server = new ServoyTestingServer() {
				@Override
				protected IDeveloperServoyModel getServoyModel() {
					return modelWithSolution("sol");
				}
			};
			setField(server, "specRunner", runner);
			setField(server, "specGenerator", gen);
			setField(server, "specModelBuilder", builder);

			assertEquals("Error: cannot generate", server.showAndTest("orders"));
			verify(runner, never()).runFormCypressTests(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
		}

		@Test
		@DisplayName("showAndTestE2E marshals a thrown exception into 'Error: <message>'")
		void showAndTestE2EWrapsException() {
			FormSpecRunner runner = mock(FormSpecRunner.class);
			when(runner.runE2ECypressTests("order_detail", false)).thenThrow(new RuntimeException("cypress gone"));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "specRunner", runner);

			assertEquals("Error: cypress gone", server.showAndTestE2E("order_detail"));
		}

		@Test
		@DisplayName("testE2E forwards the target form to the E2E runner and returns its result")
		void testE2EPassesThrough() {
			FormSpecRunner runner = mock(FormSpecRunner.class);
			when(runner.runE2ECypressTests("order_detail", true)).thenReturn("E2E passed");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "specRunner", runner);

			assertEquals("E2E passed", server.testE2E("order_detail"));
			verify(runner).runE2ECypressTests("order_detail", true);
		}

		@Test
		@DisplayName("testE2E marshals a thrown exception into 'Error: <message>'")
		void testE2EWrapsException() {
			FormSpecRunner runner = mock(FormSpecRunner.class);
			when(runner.runE2ECypressTests(anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
					.thenThrow(new RuntimeException("cypress missing"));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "specRunner", runner);

			assertEquals("Error: cypress missing", server.testE2E("order_detail"));
		}

		@Test
		@DisplayName("showAndTestE2E runs the E2E spec in headed mode (headless=false)")
		void showAndTestE2ERunsHeaded() {
			FormSpecRunner runner = mock(FormSpecRunner.class);
			when(runner.runE2ECypressTests("order_detail", false)).thenReturn("E2E headed passed");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "specRunner", runner);

			assertEquals("E2E headed passed", server.showAndTestE2E("order_detail"));
			verify(runner).runE2ECypressTests("order_detail", false);
		}
	}

	@Nested
	@DisplayName("executeTestSetup / executeTestTeardown pass-through (specRunner mocked)")
	class DatabaseSetupDelegation {
		@Test
		@DisplayName("executeTestSetup parses the column-values JSON and forwards the map to the runner")
		void setupParsesJsonAndDelegates() {
			FormSpecRunner runner = mock(FormSpecRunner.class);
			when(runner.executeTestSetup(eq("db"), eq("orders"), org.mockito.ArgumentMatchers.anyMap()))
					.thenReturn("1 row inserted");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "specRunner", runner);

			assertEquals("1 row inserted",
					server.executeTestSetup("db", "orders", "{\"customerid\":\"CYPRS\",\"city\":\"TestCity\"}"));
		}

		@Test
		@DisplayName("executeTestTeardown forwards server/table/column/value and returns the runner result")
		void teardownPassesThrough() {
			FormSpecRunner runner = mock(FormSpecRunner.class);
			when(runner.executeTestTeardown("db", "orders", "city", "TestCity")).thenReturn("3 rows deleted");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "specRunner", runner);

			assertEquals("3 rows deleted", server.executeTestTeardown("db", "orders", "city", "TestCity"));
			verify(runner).executeTestTeardown("db", "orders", "city", "TestCity");
		}
	}

	@Nested
	@DisplayName("findForm / getFormInteractiveElements / locateForm pass-through")
	class SolutionQueryDelegation {
		@Test
		@DisplayName("findForm forwards the query to the solution service and returns its result")
		void findFormPassesThrough() {
			ServoySolutionService solution = mock(ServoySolutionService.class);
			when(solution.findForm("customer")).thenReturn("Found: customers");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "solutionService", solution);

			assertEquals("Found: customers", server.findForm("customer"));
			verify(solution).findForm("customer");
		}

		@Test
		@DisplayName("findForm marshals a thrown exception into 'Error: <message>'")
		void findFormWrapsException() {
			ServoySolutionService solution = mock(ServoySolutionService.class);
			when(solution.findForm(anyString())).thenThrow(new RuntimeException("no solution"));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "solutionService", solution);

			assertEquals("Error: no solution", server.findForm("customer"));
		}

		@Test
		@DisplayName("getFormInteractiveElements forwards the trimmed form name and clamped maxElements")
		void elementsPassesThrough() {
			FormInteractiveElementsService elements = mock(FormInteractiveElementsService.class);
			when(elements.getElements(eq("orders"), eq(true), anyInt())).thenReturn("elements...");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "elementsService", elements);

			assertEquals("elements...", server.getFormInteractiveElements("  orders  ", true, 50));
			verify(elements).getElements(eq("orders"), eq(true), anyInt());
		}

		@Test
		@DisplayName("getFormInteractiveElements marshals a thrown exception into 'Error: <message>'")
		void elementsWrapsException() {
			FormInteractiveElementsService elements = mock(FormInteractiveElementsService.class);
			when(elements.getElements(anyString(), org.mockito.ArgumentMatchers.anyBoolean(), anyInt()))
					.thenThrow(new RuntimeException("no form"));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "elementsService", elements);

			assertEquals("Error: no form", server.getFormInteractiveElements("orders", false, 100));
		}

		@Test
		@DisplayName("locateForm forwards the query and the includeElements flag to the locator service")
		void locatePassesThrough() throws Exception {
			FormLocatorService locator = mock(FormLocatorService.class);
			when(locator.locate("query performance tab", true)).thenReturn("resolved");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "locatorService", locator);

			assertEquals("resolved", server.locateForm("query performance tab", true));
			verify(locator).locate("query performance tab", true);
		}

		@Test
		@DisplayName("locateForm with a null includeElements defaults to true (the documented default)")
		void locateNullIncludeElementsDefaultsTrue() throws Exception {
			FormLocatorService locator = mock(FormLocatorService.class);
			when(locator.locate("q", true)).thenReturn("resolved");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "locatorService", locator);

			assertEquals("resolved", server.locateForm("q", null));
			verify(locator).locate("q", true);
		}

		@Test
		@DisplayName("locateForm with includeElements=false forwards false")
		void locateFalseIncludeElements() throws Exception {
			FormLocatorService locator = mock(FormLocatorService.class);
			when(locator.locate("q", false)).thenReturn("resolved, no elements");
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "locatorService", locator);

			assertEquals("resolved, no elements", server.locateForm("q", false));
			verify(locator).locate("q", false);
		}

		@Test
		@DisplayName("locateForm marshals a thrown exception into 'Error: <message>'")
		void locateWrapsException() throws Exception {
			FormLocatorService locator = mock(FormLocatorService.class);
			when(locator.locate(anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
					.thenThrow(new RuntimeException("locator blew up"));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "locatorService", locator);

			assertEquals("Error: locator blew up", server.locateForm("q", true));
		}
	}

	@Nested
	@DisplayName("Navigation-graph JSON building (navigationService mocked)")
	class NavigationGraphDelegation {
		private NavigationEdge edge(String from, String to) {
			return new NavigationEdge.Builder().from(from).to(to).containerName("tabs").containerType("TabPanel")
					.confidence("static").tabName("tab1").tabIndex(0).build();
		}

		@Test
		@DisplayName("getFormNavigationGraph returns the full graph JSON with edgeCount/formCount and the serialized edges")
		void fullGraphJson() throws Exception {
			FormNavigationGraphService nav = mock(FormNavigationGraphService.class);
			NavigationGraph graph = mock(NavigationGraph.class);
			when(nav.buildFullGraph()).thenReturn(graph);
			when(nav.getMainFormName()).thenReturn("main");
			when(graph.getAllEdges()).thenReturn(List.of(edge("main", "orders")));
			when(graph.getAllFormNames()).thenReturn(java.util.Set.of("main", "orders"));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "navigationService", nav);

			String json = server.getFormNavigationGraph(null, false);
			com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(json);
			assertEquals("main", root.get("mainForm").asText());
			assertEquals(1, root.get("edgeCount").asInt());
			assertEquals(2, root.get("formCount").asInt());
			assertEquals("orders", root.get("graph").get(0).get("to").asText());
		}

		@Test
		@DisplayName("getFormNavigationGraph summaryOnly returns a compact parent->children map")
		void summaryOnlyGraph() throws Exception {
			FormNavigationGraphService nav = mock(FormNavigationGraphService.class);
			NavigationGraph graph = mock(NavigationGraph.class);
			when(nav.buildFullGraph()).thenReturn(graph);
			when(nav.getMainFormName()).thenReturn("main");
			when(graph.getAllEdges()).thenReturn(List.of(edge("main", "orders")));
			when(graph.getAllFormNames()).thenReturn(java.util.Set.of("main", "orders"));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "navigationService", nav);

			String json = server.getFormNavigationGraph(null, true);
			com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(json);
			assertEquals(1, root.get("edgeCount").asInt());
			assertNotNull(root.get("graph").get("main"), "summary graph must map the parent form to its children");
			assertEquals("orders", root.get("graph").get("main").get(0).asText());
		}

		@Test
		@DisplayName("getFormNavigationGraph with a formName that has no edges reports targetFormExists and a reachability")
		void subgraphWithReachability() throws Exception {
			FormNavigationGraphService nav = mock(FormNavigationGraphService.class);
			NavigationGraph graph = mock(NavigationGraph.class);
			when(nav.buildFullGraph()).thenReturn(graph);
			when(nav.getMainFormName()).thenReturn("main");
			when(graph.getSubgraphEdges("main", "orphan")).thenReturn(Collections.emptyList());
			when(graph.getAllFormNames()).thenReturn(java.util.Set.of("main"));
			when(graph.hasForm("orphan")).thenReturn(false);
			lenient().when(graph.findPath(anyString(), anyString())).thenReturn(Collections.emptyList());
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "navigationService", nav);

			String json = server.getFormNavigationGraph("orphan", false);
			com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(json);
			assertEquals("orphan", root.get("targetForm").asText());
			assertFalse(root.get("targetFormExists").asBoolean());
			assertEquals("unknownForm", root.get("reachability").asText());
		}

		@Test
		@DisplayName("getFormNavigationGraph marshals a thrown exception into 'Error: <message>'")
		void graphWrapsException() {
			FormNavigationGraphService nav = mock(FormNavigationGraphService.class);
			when(nav.buildFullGraph()).thenThrow(new RuntimeException("no solution"));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "navigationService", nav);

			assertEquals("Error: no solution", server.getFormNavigationGraph(null, false));
		}

		@Test
		@DisplayName("getNavigationPath returns the ordered steps JSON for a found path")
		void navigationPathFound() throws Exception {
			FormNavigationGraphService nav = mock(FormNavigationGraphService.class);
			NavigationGraph graph = mock(NavigationGraph.class);
			when(nav.buildFullGraph()).thenReturn(graph);
			when(nav.getMainFormName()).thenReturn("main");
			when(graph.findPath("main", "orders")).thenReturn(List.of(edge("main", "orders")));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "navigationService", nav);

			String json = server.getNavigationPath("orders", null);
			com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(json);
			assertEquals("main", root.get("mainForm").asText());
			assertEquals("orders", root.get("pathTo").asText());
			assertEquals("orders", root.get("steps").get(0).get("to").asText());
		}

		@Test
		@DisplayName("getNavigationPath reports an error when no path is found")
		void navigationPathNotFound() {
			FormNavigationGraphService nav = mock(FormNavigationGraphService.class);
			NavigationGraph graph = mock(NavigationGraph.class);
			when(nav.buildFullGraph()).thenReturn(graph);
			when(nav.getMainFormName()).thenReturn("main");
			when(graph.findPath("main", "orphan")).thenReturn(Collections.emptyList());
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "navigationService", nav);

			String result = server.getNavigationPath("orphan", null);
			assertTrue(result.startsWith("Error: No navigation path found"), "got: " + result);
		}

		@Test
		@DisplayName("getNavigationPath reports an error when no main form can be determined and fromForm is omitted")
		void navigationPathNoMainForm() {
			FormNavigationGraphService nav = mock(FormNavigationGraphService.class);
			NavigationGraph graph = mock(NavigationGraph.class);
			when(nav.buildFullGraph()).thenReturn(graph);
			when(nav.getMainFormName()).thenReturn(null);
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "navigationService", nav);

			String result = server.getNavigationPath("orders", null);
			assertTrue(result.contains("Could not determine the main form"), "got: " + result);
		}

		@Test
		@DisplayName("getNavigationPath honours an explicit fromForm as the start")
		void navigationPathExplicitFromForm() throws Exception {
			FormNavigationGraphService nav = mock(FormNavigationGraphService.class);
			NavigationGraph graph = mock(NavigationGraph.class);
			when(nav.buildFullGraph()).thenReturn(graph);
			when(graph.findPath("start", "orders")).thenReturn(List.of(edge("start", "orders")));
			ServoyTestingServer server = new ServoyTestingServer();
			setField(server, "navigationService", nav);

			String json = server.getNavigationPath("orders", "start");
			com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(json);
			assertEquals("start", root.get("mainForm").asText());
		}
	}

	// =======================================================================
	// SVY-21663: listFormTests / runAllFormTests
	//
	// Two new tools surface CypressTestDiscoveryService.discoverAllTestForms()
	// (the cy-form form-test listing) over MCP, plus a package-visible static
	// toFormName(entry) helper mapping a discovered spec base name to the
	// runner argument. These tests cover the tool presence, the @Tool
	// description obligations (the folder split, the read-only note, the
	// real-data-change / NG-client warnings, the listE2ETests cross-pointer),
	// the toFormName contract, and the formatting / guard branches reachable
	// by reflection-swapping the collaborators (testDiscovery, specGenerator,
	// specRunner, formPreview) and seaming the model - no workbench. The real
	// cy-form discovery path (ResourcesPlugin inside CypressTestDiscoveryService)
	// stays to the integration suite / manual verification.
	// =======================================================================

	@Test
	@DisplayName("listFormTests is registered")
	void listFormTests_isRegistered() {
		assertTrue(hasToolNamed("listFormTests"), "ServoyTestingServer must have a 'listFormTests' tool");
	}

	@Test
	@DisplayName("runAllFormTests is registered")
	void runAllFormTests_isRegistered() {
		assertTrue(hasToolNamed("runAllFormTests"), "ServoyTestingServer must have a 'runAllFormTests' tool");
	}

	@Test
	@DisplayName("listFormTests and runAllFormTests take no parameters")
	void listAndRunFormTests_haveNoParams() {
		Method list = findToolMethod("listFormTests");
		Method run = findToolMethod("runAllFormTests");
		assertNotNull(list, "listFormTests tool not found");
		assertNotNull(run, "runAllFormTests tool not found");
		org.junit.jupiter.api.Assertions.assertAll(
				() -> assertEquals(0, list.getParameterCount(), "listFormTests must have 0 parameters"),
				() -> assertEquals(0, run.getParameterCount(), "runAllFormTests must have 0 parameters"));
	}

	@Nested
	@DisplayName("SVY-21663 @Tool description obligations")
	class FormTestDescriptions {
		@Test
		@DisplayName("listFormTests description names the cy-form folder, is read-only and points to the run tools")
		void listFormTests_description() {
			String desc = findToolMethod("listFormTests").getAnnotation(Tool.class).description();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(desc.contains("cy-form"), "must name the cy-form folder: " + desc),
					() -> assertTrue(desc.contains("cypress/cy-form"), "must name the cypress/cy-form path: " + desc),
					() -> assertTrue(desc.contains("listE2ETests"), "must contrast with listE2ETests: " + desc),
					() -> assertTrue(desc.contains("cypress/e2e"),
							"must name the e2e folder to make the split explicit: " + desc),
					() -> assertTrue(desc.contains("testForm") && desc.contains("showAndTest"),
							"must point to the single-form run tools: " + desc),
					() -> assertTrue(desc.contains("runAllFormTests"),
							"must point to runAllFormTests for running all: " + desc));
		}

		@Test
		@DisplayName("runAllFormTests description names cy-form and warns about real data changes and the NG client")
		void runAllFormTests_description() {
			String desc = findToolMethod("runAllFormTests").getAnnotation(Tool.class).description();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(desc.contains("cy-form"), "must name the cy-form folder: " + desc),
					() -> assertTrue(desc.contains("CHANGE DATA") || desc.contains("change data"),
							"must warn that handlers can change data: " + desc),
					() -> assertTrue(desc.contains("handler"), "must warn that real event handlers execute: " + desc),
					() -> assertTrue(desc.contains("NG client"), "must warn the NG client must be running: " + desc),
					() -> assertTrue(desc.contains("checkNGClientStatus"),
							"must point to checkNGClientStatus: " + desc));
		}

		@Test
		@DisplayName("listE2ETests description cross-points to listFormTests and still reads only cypress/e2e")
		void listE2ETests_crossPointer() {
			String desc = findToolMethod("listE2ETests").getAnnotation(Tool.class).description();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(desc.contains("listFormTests"),
							"listE2ETests must point to listFormTests for form tests: " + desc),
					() -> assertTrue(desc.contains("cy-form"),
							"listE2ETests must name the cy-form folder in the cross-pointer: " + desc),
					() -> assertTrue(desc.contains("cypress/e2e"),
							"listE2ETests must still state it reads cypress/e2e: " + desc));
		}
	}

	@Nested
	@DisplayName("SVY-21663 toFormName (package-visible static, via reflection)")
	class ToFormName {
		private String toFormName(String entry) throws Exception {
			Method m = ServoyTestingServer.class.getDeclaredMethod("toFormName", String.class);
			m.setAccessible(true);
			return (String) m.invoke(null, entry);
		}

		@ParameterizedTest
		@DisplayName("returns the discovered entry unchanged (base-name -> runner-arg contract)")
		@ValueSource(strings = { "mysolution.myform", "mysolution.other", "someform", "sol.form.with.dots",
				"test_webcomponents2.orders" })
		void returnsEntryUnchanged(String entry) throws Exception {
			assertEquals(entry, toFormName(entry),
					"toFormName must pass the discovered entry through unchanged so runFormCypressTests "
							+ "resolves the same spec discoverAllTestForms enumerated");
		}

		@Test
		@DisplayName("a null entry is returned unchanged (null)")
		void nullEntry() throws Exception {
			org.junit.jupiter.api.Assertions.assertNull(toFormName(null));
		}
	}

	@Nested
	@DisplayName("SVY-21663 listFormTests formatting / empty (collaborators mocked)")
	class ListFormTestsFormatting {
		@Test
		@DisplayName("empty discovery returns the 'no form tests' message naming the resolved cy-form folder")
		void emptyNamesResolvedFolder(@TempDir Path tmp) {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			when(discovery.discoverFormTests(any())).thenReturn(Collections.emptyList());
			Path cyForm = tmp.resolve("cy-form");
			when(gen.getFormSpecDir()).thenReturn(cyForm);
			ServoyTestingServer server = serverWith(modelWithNoActiveProject());
			setField(server, "testDiscovery", discovery);
			setField(server, "specGenerator", gen);

			String result = server.listFormTests();
			assertEquals("No form tests found in " + cyForm + ". Use generateFormSpec to create a form test.", result);
		}

		@Test
		@DisplayName("empty discovery with an unresolvable folder still names cypress/cy-form in the message")
		void emptyUnresolvedFolderNamesLiteralPath() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			when(discovery.discoverFormTests(any())).thenReturn(Collections.emptyList());
			when(gen.getFormSpecDir()).thenReturn(null);
			ServoyTestingServer server = serverWith(modelWithNoActiveProject());
			setField(server, "testDiscovery", discovery);
			setField(server, "specGenerator", gen);

			String result = server.listFormTests();
			assertTrue(result.startsWith("No form tests found in"), "must be the no-form-tests message: " + result);
			assertTrue(result.contains("cypress/cy-form/"),
					"must still name the cy-form folder when the path is unresolvable: " + result);
		}

		@Test
		@DisplayName("populated discovery lists one line per form with its resolved spec file name")
		void listsEntriesWithSpecFileNames(@TempDir Path tmp) {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			Path cyForm = tmp.resolve("cy-form");
			when(discovery.discoverFormTests(any())).thenReturn(List.of("mysolution.myform", "mysolution.other"));
			when(gen.getFormSpecDir()).thenReturn(cyForm);
			when(gen.findExistingSpecFile("mysolution.myform", "mysolution"))
					.thenReturn(cyForm.resolve("mysolution.myform.spec.cy.js"));
			when(gen.findExistingSpecFile("mysolution.other", "mysolution"))
					.thenReturn(cyForm.resolve("mysolution.other.spec.cy.ts"));
			ServoyTestingServer server = serverWith(modelWithSolution("mysolution"));
			setField(server, "testDiscovery", discovery);
			setField(server, "specGenerator", gen);

			String result = server.listFormTests();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.contains("**Form Tests** (2 files)"), "must show the count: " + result),
					() -> assertTrue(result.contains("Directory: " + cyForm),
							"must show the resolved directory: " + result),
					() -> assertTrue(result.contains("- **mysolution.myform** → `mysolution.myform.spec.cy.js`"),
							"must list the first form with its .spec.cy.js file: " + result),
					() -> assertTrue(result.contains("- **mysolution.other** → `mysolution.other.spec.cy.ts`"),
							"must list the second form with its .spec.cy.ts file: " + result),
					() -> assertTrue(result.contains("runAllFormTests"), "must point to the run tools: " + result));
		}

		@Test
		@DisplayName("a single discovered form uses the singular 'file' and falls back to the raw base name when no file resolves")
		void singularAndFallbackToBaseName(@TempDir Path tmp) {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			when(discovery.discoverFormTests(any())).thenReturn(List.of("sol.lonely"));
			when(gen.getFormSpecDir()).thenReturn(tmp.resolve("cy-form"));
			// no on-disk file resolves for this entry -> the raw base name is shown
			when(gen.findExistingSpecFile("sol.lonely", "sol")).thenReturn(null);
			ServoyTestingServer server = serverWith(modelWithSolution("sol"));
			setField(server, "testDiscovery", discovery);
			setField(server, "specGenerator", gen);

			String result = server.listFormTests();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.contains("**Form Tests** (1 file)"),
							"must use the singular 'file' for one entry: " + result),
					() -> assertTrue(result.contains("- **sol.lonely** → `sol.lonely`"),
							"must fall back to the raw base name when no file resolves: " + result));
		}

		@Test
		@DisplayName("listFormTests marshals a thrown exception into 'Error: <message>'")
		void wrapsException() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			when(discovery.discoverFormTests(any())).thenThrow(new RuntimeException("discovery blew up"));
			ServoyTestingServer server = serverWith(modelWithNoActiveProject());
			setField(server, "testDiscovery", discovery);

			assertEquals("Error: discovery blew up", server.listFormTests());
		}

		@Test
		@DisplayName("specs present but the cy-form folder cannot be resolved: still listed, no Directory line")
		void listsEntriesWithoutDirectoryLineWhenFolderUnresolved() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			when(discovery.discoverFormTests(any())).thenReturn(List.of("sol.only"));
			when(gen.getFormSpecDir()).thenReturn(null);
			when(gen.findExistingSpecFile("sol.only", "sol")).thenReturn(Path.of("sol.only.spec.cy.js"));
			ServoyTestingServer server = serverWith(modelWithSolution("sol"));
			setField(server, "testDiscovery", discovery);
			setField(server, "specGenerator", gen);

			String result = server.listFormTests();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.contains("**Form Tests** (1 file)"), "must still list the form: " + result),
					() -> assertFalse(result.contains("Directory:"),
							"must omit the Directory line when the folder is unresolved: " + result),
					() -> assertTrue(result.contains("- **sol.only** → `sol.only.spec.cy.js`"),
							"must still show the spec file name: " + result));
		}

		@Test
		@DisplayName("no active project: spec files are resolved with a null solution name")
		void resolvesSpecFileWithNullSolutionWhenNoActiveProject() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			// an unprefixed spec has no owner, so it stays in scope when no active solution
			// can be resolved
			when(discovery.discoverFormTests(any())).thenReturn(List.of("legacy"));
			when(gen.getFormSpecDir()).thenReturn(Path.of("cy-form"));
			when(gen.findExistingSpecFile("legacy", null)).thenReturn(Path.of("legacy.spec.cy.ts"));
			ServoyTestingServer server = serverWith(modelWithNoActiveProject());
			setField(server, "testDiscovery", discovery);
			setField(server, "specGenerator", gen);

			String result = server.listFormTests();
			assertTrue(result.contains("- **legacy** → `legacy.spec.cy.ts`"),
					"must resolve the spec with a null solution name: " + result);
			verify(gen).findExistingSpecFile("legacy", null);
		}

		@Test
		@DisplayName("active project without a solution: spec files are resolved with a null solution name")
		void resolvesSpecFileWithNullSolutionWhenProjectHasNoSolution() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			ServoyProject project = mock(ServoyProject.class);
			when(model.getActiveProject()).thenReturn(project);
			when(project.getSolution()).thenReturn(null);
			when(discovery.discoverFormTests(any())).thenReturn(List.of("legacy"));
			when(gen.getFormSpecDir()).thenReturn(Path.of("cy-form"));
			when(gen.findExistingSpecFile("legacy", null)).thenReturn(Path.of("legacy.spec.cy.js"));
			ServoyTestingServer server = serverWith(model);
			setField(server, "testDiscovery", discovery);
			setField(server, "specGenerator", gen);

			String result = server.listFormTests();
			assertTrue(result.contains("`legacy.spec.cy.js`"), "must still resolve the spec file: " + result);
			verify(gen).findExistingSpecFile("legacy", null);
		}

		@Test
		@DisplayName("the model throwing while resolving the active solution does not break the listing")
		void listsEntriesWhenModelThrows() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenThrow(new RuntimeException("model unavailable"));
			when(discovery.discoverFormTests(any())).thenReturn(List.of("legacy"));
			when(gen.getFormSpecDir()).thenReturn(Path.of("cy-form"));
			when(gen.findExistingSpecFile("legacy", null)).thenReturn(Path.of("legacy.spec.cy.js"));
			ServoyTestingServer server = serverWith(model);
			setField(server, "testDiscovery", discovery);
			setField(server, "specGenerator", gen);

			String result = server.listFormTests();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.startsWith("**Form Tests** (1 file)"),
							"must not turn the model failure into an error: " + result),
					() -> assertTrue(result.contains("`legacy.spec.cy.js`"), "must still list the spec: " + result));
		}

		@Test
		@DisplayName("a failure resolving one spec file falls back to the raw base name")
		void fallsBackToBaseNameWhenSpecLookupThrows() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			when(discovery.discoverFormTests(any())).thenReturn(List.of("sol.one", "sol.two"));
			when(gen.getFormSpecDir()).thenReturn(Path.of("cy-form"));
			when(gen.findExistingSpecFile("sol.one", "sol")).thenThrow(new RuntimeException("lookup failed"));
			when(gen.findExistingSpecFile("sol.two", "sol")).thenReturn(Path.of("sol.two.spec.cy.js"));
			ServoyTestingServer server = serverWith(modelWithSolution("sol"));
			setField(server, "testDiscovery", discovery);
			setField(server, "specGenerator", gen);

			String result = server.listFormTests();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.contains("- **sol.one** → `sol.one`"),
							"must fall back to the raw base name for the failing lookup: " + result),
					() -> assertTrue(result.contains("- **sol.two** → `sol.two.spec.cy.js`"),
							"must keep resolving the other forms: " + result));
		}
	}

	@Nested
	@DisplayName("SVY-21663 only the active solution and its modules are listed and run")
	class ActiveSolutionScope {
		@Test
		@DisplayName("listFormTests lists the active solution's specs and leaves out other solutions' specs")
		void listsOnlyActiveSolutionSpecs() {
			String result = serverDiscovering(modelWithSolution("sol"), List.of("sol.a", "other.b", "sol.c"))
					.listFormTests();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.contains("**Form Tests** (2 files)"),
							"must count only the in-scope specs: " + result),
					() -> assertTrue(result.contains("**sol.a**") && result.contains("**sol.c**"),
							"must list the active solution's specs: " + result),
					() -> assertFalse(result.contains("other.b"), "must not list another solution's spec: " + result));
		}

		@Test
		@DisplayName("specs of the active solution's modules are listed too")
		void listsModuleSpecs() {
			String result = serverDiscovering(modelWithSolutionAndModules("main", "modA", "modB"),
					List.of("main.f1", "modA.f2", "other.f3")).listFormTests();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.contains("**Form Tests** (2 files)"),
							"must count main and module specs: " + result),
					() -> assertTrue(result.contains("**main.f1**") && result.contains("**modA.f2**"),
							"must list the main and the module spec: " + result),
					() -> assertFalse(result.contains("other.f3"),
							"must not list a spec outside the solution: " + result));
		}

		@Test
		@DisplayName("a module with the same name as the active solution does not duplicate its specs")
		void moduleNamedLikeActiveSolutionIsNotDuplicated() {
			String result = serverDiscovering(modelWithSolutionAndModules("sol", "sol"), List.of("sol.a"))
					.listFormTests();
			assertTrue(result.contains("**Form Tests** (1 file)"), "must list the spec once: " + result);
		}

		@Test
		@DisplayName("a null module and a module without a solution are ignored")
		void ignoresNullAndSolutionlessModules() {
			IDeveloperServoyModel model = modelWithSolution("sol");
			ServoyProject solutionless = mock(ServoyProject.class);
			lenient().when(model.getModulesOfActiveProject()).thenReturn(new ServoyProject[] { null, solutionless });

			String result = serverDiscovering(model, List.of("sol.a", "x.b")).listFormTests();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.contains("**Form Tests** (1 file)"),
							"must list only the active solution: " + result),
					() -> assertTrue(result.contains("**sol.a**"), "must list the active solution's spec: " + result),
					() -> assertFalse(result.contains("x.b"), "must not list the other spec: " + result));
		}

		@Test
		@DisplayName("a spec without a solution prefix stays in scope, because its owner cannot be told")
		void keepsUnprefixedSpecs() {
			String result = serverDiscovering(modelWithSolution("sol"), List.of("legacy", "other.b")).listFormTests();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.contains("**Form Tests** (1 file)"),
							"must keep the unprefixed spec: " + result),
					() -> assertTrue(result.contains("**legacy**"), "must list the unprefixed spec: " + result),
					() -> assertFalse(result.contains("other.b"), "must not list another solution's spec: " + result));
		}

		@Test
		@DisplayName("specs exist but none belong to the active solution: the message says so and is not 'folder empty'")
		void messageWhenNoSpecBelongsToActiveSolution() {
			String result = serverDiscovering(modelWithSolution("sol"), List.of("other.b", "other.c")).listFormTests();
			org.junit.jupiter.api.Assertions
					.assertAll(
							() -> assertTrue(
									result.startsWith(
											"No form tests found for the active solution 'sol' or its modules."),
									"must name the active solution: " + result),
							() -> assertTrue(result.contains("2 form test(s) of other solutions"),
									"must say how many other specs were left out: " + result),
							() -> assertFalse(result.startsWith("No form tests found in"),
									"must not read like an empty folder: " + result));
		}

		@Test
		@DisplayName("no active solution: prefixed specs are out of scope and the message asks to activate a solution")
		void messageWhenNoActiveSolution() {
			String result = serverDiscovering(modelWithNoActiveProject(), List.of("sol.a")).listFormTests();
			assertTrue(result.startsWith("No active Servoy solution, so no form tests are in scope. 1 form test(s)"),
					"must say there is no active solution: " + result);
		}

		@Test
		@DisplayName("the model failing while resolving the active solution gives the same 'no active solution' message")
		void messageWhenModelThrows() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenThrow(new RuntimeException("model unavailable"));

			String result = serverDiscovering(model, List.of("sol.a")).listFormTests();
			assertTrue(result.startsWith("No active Servoy solution"),
					"must not turn the failure into an error: " + result);
		}

		@Test
		@DisplayName("an empty folder still says 'No form tests found in <folder>'")
		void emptyFolderMessageIsUnchanged() {
			String result = serverDiscovering(modelWithSolution("sol"), Collections.emptyList()).listFormTests();
			assertTrue(result.startsWith("No form tests found in "), "must keep the empty-folder message: " + result);
		}

		@Test
		@DisplayName("runAllFormTests stops with the same message when no spec belongs to the active solution")
		void runAllStopsWhenNothingInScope() {
			String result = serverDiscovering(modelWithSolution("sol"), List.of("other.b")).runAllFormTests();
			org.junit.jupiter.api.Assertions
					.assertAll(
							() -> assertTrue(
									result.startsWith(
											"No form tests found for the active solution 'sol' or its modules."),
									"must name the active solution: " + result),
							() -> assertTrue(result.contains("1 form test(s) of other solutions"),
									"must say what was left out: " + result));
		}

		private ServoyTestingServer serverWithDiscovery(IDeveloperServoyModel model, CypressTestDiscoveryService discovery) {
			return new ServoyTestingServer() {
				@Override
				protected IDeveloperServoyModel getServoyModel() {
					return model;
				}

				@Override
				protected CypressTestDiscoveryService getTestDiscovery() {
					return discovery;
				}
			};
		}

		@Test
		@DisplayName("listFormTests asks discovery for exactly the active solution, then its modules")
		void listPassesActiveSolutionThenModules() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			when(discovery.discoverFormTests(any())).thenReturn(List.of("main.f1"));

			serverWithDiscovery(modelWithSolutionAndModules("main", "modA", "modB"), discovery).listFormTests();

			verify(discovery).discoverFormTests(
					org.mockito.ArgumentMatchers.argThat(names -> List.of("main", "modA", "modB").equals(new ArrayList<>(names))));
		}

		@Test
		@DisplayName("runAllFormTests asks discovery for the same scope as listFormTests")
		void runAllPassesActiveSolutionThenModules() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);

			serverWithDiscovery(modelWithSolutionAndModules("main", "modA"), discovery).runAllFormTests();

			verify(discovery).discoverFormTests(
					org.mockito.ArgumentMatchers.argThat(names -> List.of("main", "modA").equals(new ArrayList<>(names))));
		}

		@Test
		@DisplayName("with no active solution discovery gets an empty scope, not null")
		void noActiveSolutionPassesEmptyScope() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);

			serverWithDiscovery(modelWithNoActiveProject(), discovery).listFormTests();

			verify(discovery).discoverFormTests(org.mockito.ArgumentMatchers.argThat(names -> names != null && names.isEmpty()));
		}

		@Test
		@DisplayName("the module lookup failing keeps the active solution in scope")
		void moduleLookupFailureKeepsActiveSolution() {
			IDeveloperServoyModel model = modelWithSolution("sol");
			lenient().when(model.getModulesOfActiveProject()).thenThrow(new RuntimeException("modules unavailable"));

			String result = serverDiscovering(model, List.of("sol.a", "other.b")).listFormTests();

			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.contains("**sol.a**"), "must still list the active solution's spec: " + result),
					() -> assertFalse(result.contains("other.b"), "must not list another solution's spec: " + result));
		}

		@Test
		@DisplayName("the 'other solutions' message names the cy-form folder and offers generateFormSpec")
		void otherSolutionsMessageNamesFolder() {
			String result = serverDiscovering(modelWithSolution("sol"), List.of("other.b")).listFormTests();

			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.contains("cy-form"), "must name the folder: " + result),
					() -> assertTrue(result.contains("were not listed"), "must say the other specs were left out: " + result),
					() -> assertTrue(result.contains("generateFormSpec"), "must point to generateFormSpec: " + result));
		}

		@Test
		@DisplayName("the 'no active solution' message tells the caller to activate one")
		void noActiveSolutionMessageAsksToActivate() {
			String result = serverDiscovering(modelWithNoActiveProject(), List.of("sol.a", "sol.b")).listFormTests();

			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.contains("2 form test(s) exist in"), "must count the specs: " + result),
					() -> assertTrue(result.contains("activate a solution"), "must ask to activate a solution: " + result));
		}
	}

	@Nested
	@DisplayName("SVY-21663 runAllFormTests guards / aggregation (collaborators mocked)")
	class RunAllFormTests {
		@Test
		@DisplayName("empty discovery returns the 'no form tests' message and never runs or checks the NG client")
		void emptyReturnsNoFormTestsAndRunsNothing() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			FormSpecGenerator gen = mock(FormSpecGenerator.class);
			FormSpecRunner runner = mock(FormSpecRunner.class);
			FormPreviewService preview = mock(FormPreviewService.class);
			when(discovery.discoverFormTests(any())).thenReturn(Collections.emptyList());
			when(gen.getFormSpecDir()).thenReturn(null);
			ServoyTestingServer server = serverWith(modelWithNoActiveProject());
			setField(server, "testDiscovery", discovery);
			setField(server, "specGenerator", gen);
			setField(server, "specRunner", runner);
			setField(server, "formPreview", preview);

			String result = server.runAllFormTests();
			assertTrue(result.startsWith("No form tests found in"), "must be the no-form-tests message: " + result);
			verify(runner, never()).runFormCypressTests(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
			verify(preview, never()).checkNGClientStatus();
		}

		@Test
		@DisplayName("with forms present but the NG client not running, it reports the NG-client guard and runs nothing")
		void ngClientNotRunningShortCircuits() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			FormSpecRunner runner = mock(FormSpecRunner.class);
			FormPreviewService preview = mock(FormPreviewService.class);
			when(discovery.discoverFormTests(any())).thenReturn(List.of("sol.form1"));
			when(preview.checkNGClientStatus()).thenReturn("Servoy Developer is NOT running.");
			ServoyTestingServer server = serverWith(modelWithSolution("sol"));
			setField(server, "testDiscovery", discovery);
			setField(server, "specRunner", runner);
			setField(server, "formPreview", preview);

			String result = server.runAllFormTests();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.startsWith("NG client not running:"),
							"must report the NG-client guard: " + result),
					() -> assertTrue(result.contains("Servoy Developer is NOT running."),
							"must include the underlying status: " + result));
			verify(runner, never()).runFormCypressTests(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
		}

		@Test
		@DisplayName("with the NG client ready, every discovered form is run headless (entry passed unchanged) and aggregated")
		void runsEveryFormHeadlessAndAggregates() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			FormSpecRunner runner = mock(FormSpecRunner.class);
			FormPreviewService preview = mock(FormPreviewService.class);
			when(discovery.discoverFormTests(any())).thenReturn(List.of("sol.form1", "sol.form2"));
			when(preview.checkNGClientStatus()).thenReturn("Servoy Developer is running.");
			when(runner.runFormCypressTests("sol.form1", true)).thenReturn("form1: 1 passing");
			when(runner.runFormCypressTests("sol.form2", true)).thenReturn("form2: 2 passing");
			ServoyTestingServer server = serverWith(modelWithSolution("sol"));
			setField(server, "testDiscovery", discovery);
			setField(server, "specRunner", runner);
			setField(server, "formPreview", preview);

			String result = server.runAllFormTests();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.contains("**Run All Form Tests** (2 forms)"),
							"must show the form count header: " + result),
					() -> assertTrue(result.contains("## sol.form1"), "must have a section per form: " + result),
					() -> assertTrue(result.contains("## sol.form2"), "must have a section per form: " + result),
					() -> assertTrue(result.contains("form1: 1 passing") && result.contains("form2: 2 passing"),
							"must include each form's runner result: " + result));
			// entry passed through unchanged (toFormName contract), headless (true)
			verify(runner).runFormCypressTests("sol.form1", true);
			verify(runner).runFormCypressTests("sol.form2", true);
		}

		@Test
		@DisplayName("a failure running one form is captured in that form's section and the loop continues to the next")
		void perFormFailureIsCapturedAndLoopContinues() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			FormSpecRunner runner = mock(FormSpecRunner.class);
			FormPreviewService preview = mock(FormPreviewService.class);
			when(discovery.discoverFormTests(any())).thenReturn(List.of("sol.bad", "sol.good"));
			when(preview.checkNGClientStatus()).thenReturn("Servoy Developer is running.");
			when(runner.runFormCypressTests("sol.bad", true)).thenThrow(new RuntimeException("run failed"));
			when(runner.runFormCypressTests("sol.good", true)).thenReturn("good: 1 passing");
			ServoyTestingServer server = serverWith(modelWithSolution("sol"));
			setField(server, "testDiscovery", discovery);
			setField(server, "specRunner", runner);
			setField(server, "formPreview", preview);

			String result = server.runAllFormTests();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.contains("## sol.bad"),
							"must still have the failing form's section: " + result),
					() -> assertTrue(result.contains("Error: run failed"),
							"must capture the per-form failure in its section: " + result),
					() -> assertTrue(result.contains("## sol.good") && result.contains("good: 1 passing"),
							"must continue to the next form after a failure: " + result));
			verify(runner).runFormCypressTests("sol.good", true);
		}

		@Test
		@DisplayName("runAllFormTests marshals a top-level thrown exception into 'Error: <message>'")
		void wrapsTopLevelException() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			when(discovery.discoverFormTests(any())).thenThrow(new RuntimeException("discovery exploded"));
			ServoyTestingServer server = serverWith(modelWithSolution("sol"));
			setField(server, "testDiscovery", discovery);

			assertEquals("Error: discovery exploded", server.runAllFormTests());
		}

		@Test
		@DisplayName("a single discovered form uses the singular 'form' in the report header")
		void singularFormLabelForOneForm() {
			CypressTestDiscoveryService discovery = mock(CypressTestDiscoveryService.class);
			FormSpecRunner runner = mock(FormSpecRunner.class);
			FormPreviewService preview = mock(FormPreviewService.class);
			when(discovery.discoverFormTests(any())).thenReturn(List.of("sol.only"));
			when(preview.checkNGClientStatus()).thenReturn("Servoy Developer is running.");
			when(runner.runFormCypressTests("sol.only", true)).thenReturn("only: 1 passing");
			ServoyTestingServer server = serverWith(modelWithSolution("sol"));
			setField(server, "testDiscovery", discovery);
			setField(server, "specRunner", runner);
			setField(server, "formPreview", preview);

			String result = server.runAllFormTests();
			org.junit.jupiter.api.Assertions.assertAll(
					() -> assertTrue(result.contains("**Run All Form Tests** (1 form)"),
							"must use the singular 'form' for one entry: " + result),
					() -> assertFalse(result.contains("(1 forms)"), "must not pluralise a single form: " + result),
					() -> assertTrue(result.contains("## sol.only") && result.contains("only: 1 passing"),
							"must still run and report the form: " + result));
		}
	}
}
