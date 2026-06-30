package com.servoy.eclipse.developer.mcp.servers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;

public class ServoyTestingServerTest {
	@Test
	public void testServoyTestingServer_hasCorrectAnnotation() {
		com.servoy.eclipse.developer.mcp.annotations.McpServer ann = ServoyTestingServer.class
				.getAnnotation(com.servoy.eclipse.developer.mcp.annotations.McpServer.class);
		assertNotNull("ServoyTestingServer must have @McpServer annotation", ann);
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
		assertTrue("ServoyTestingServer must be registered in McpServerBuiltins", found);
	}

	@Test
	public void testServoyTestingServer_hasTestFormTool() {
		assertTrue("ServoyTestingServer must have a 'testForm' tool", hasToolNamed("testForm"));
	}

	@Test
	public void testServoyTestingServer_hasShowAndTestTool() {
		assertTrue("ServoyTestingServer must have a 'showAndTest' tool", hasToolNamed("showAndTest"));
	}

	@Test
	public void testServoyTestingServer_hasGenerateFormSpecTool() {
		assertTrue("ServoyTestingServer must have a 'generateFormSpec' tool", hasToolNamed("generateFormSpec"));
	}

	@Test
	public void testServoyTestingServer_hasRunJsUnitTestsTool() {
		assertTrue("ServoyTestingServer must have a 'runJsUnitTests' tool", hasToolNamed("runJsUnitTests"));
	}

	@Test
	public void testServoyTestingServer_hasShowFormInBrowserTool() {
		assertTrue("ServoyTestingServer must have a 'showFormInBrowser' tool", hasToolNamed("showFormInBrowser"));
	}

	@Test
	public void testServoyTestingServer_hasScreenshotFormTool() {
		assertTrue("ServoyTestingServer must have a 'screenshotForm' tool", hasToolNamed("screenshotForm"));
	}

	@Test
	public void testServoyTestingServer_hasCheckNGClientStatusTool() {
		assertTrue("ServoyTestingServer must have a 'checkNGClientStatus' tool", hasToolNamed("checkNGClientStatus"));
	}

	@Test
	public void testServoyTestingServer_allToolsHaveDescriptions() {
		List<Method> toolMethods = Arrays.stream(ServoyTestingServer.class.getMethods())
				.filter(m -> m.isAnnotationPresent(Tool.class)).collect(Collectors.toList());

		for (Method m : toolMethods) {
			Tool tool = m.getAnnotation(Tool.class);
			assertFalse("Tool '" + tool.name() + "' must have a non-empty description", tool.description().isEmpty());
		}
	}

	@Test
	public void testServoyTestingServer_allToolsReturnString() {
		List<Method> toolMethods = Arrays.stream(ServoyTestingServer.class.getMethods())
				.filter(m -> m.isAnnotationPresent(Tool.class)).collect(Collectors.toList());

		for (Method m : toolMethods) {
			assertEquals("Tool method '" + m.getName() + "' must return String", String.class, m.getReturnType());
		}
	}

	@Test
	public void testServoyTestingServer_testFormHasFormNameParam() {
		Method method = findToolMethod("testForm");
		assertNotNull(method);
		assertEquals("testForm must have 1 parameter", 1, method.getParameterCount());
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull("testForm parameter must have @ToolParam", param);
		assertFalse("testForm @ToolParam must have description", param.description().isEmpty());
	}

	@Test
	public void testServoyTestingServer_showAndTestHasFormNameParam() {
		Method method = findToolMethod("showAndTest");
		assertNotNull(method);
		assertEquals("showAndTest must have 1 parameter", 1, method.getParameterCount());
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull("showAndTest parameter must have @ToolParam", param);
	}

	@Test
	public void testServoyTestingServer_generateFormSpecHasFormNameParam() {
		Method method = findToolMethod("generateFormSpec");
		assertNotNull(method);
		assertEquals("generateFormSpec must have 1 parameter", 1, method.getParameterCount());
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull("generateFormSpec parameter must have @ToolParam", param);
	}

	@Test
	public void testServoyTestingServer_runJsUnitTestsHasTwoParams() {
		Method method = findToolMethod("runJsUnitTests");
		assertNotNull(method);
		assertEquals("runJsUnitTests must have 2 parameters", 2, method.getParameterCount());
	}

	@Test
	public void testServoyTestingServer_screenshotFormHasTwoParams() {
		Method method = findToolMethod("screenshotForm");
		assertNotNull(method);
		assertEquals("screenshotForm must have 2 parameters", 2, method.getParameterCount());
	}

	@Test
	public void testServoyTestingServer_checkNGClientStatusHasNoParams() {
		Method method = findToolMethod("checkNGClientStatus");
		assertNotNull(method);
		assertEquals("checkNGClientStatus must have 0 parameters", 0, method.getParameterCount());
	}

	@Test
	public void testServoyTestingServer_canBeInstantiated() {
		ServoyTestingServer server = new ServoyTestingServer();
		assertNotNull("ServoyTestingServer must be instantiable via no-arg constructor", server);
	}

	@Test
	public void testServoyTestingServer_showFormInBrowserDescriptionMentionsSpec() {
		Method method = findToolMethod("showFormInBrowser");
		assertNotNull(method);
		Tool tool = method.getAnnotation(Tool.class);
		assertTrue("showFormInBrowser description should mention spec auto-generation",
				tool.description().contains("spec"));
	}

	@Test
	public void testServoyTestingServer_hasCreateTestFileTool() {
		assertTrue("ServoyTestingServer must have a 'createTestFile' tool", hasToolNamed("createTestFile"));
	}

	@Test
	public void testServoyTestingServer_hasAddTestMethodTool() {
		assertTrue("ServoyTestingServer must have an 'addTestMethod' tool", hasToolNamed("addTestMethod"));
	}

	@Test
	public void testServoyTestingServer_hasGenerateTestCasesTool() {
		assertTrue("ServoyTestingServer must have a 'generateTestCases' tool", hasToolNamed("generateTestCases"));
	}

	@Test
	public void testServoyTestingServer_hasAnalyzeCodeForTestingTool() {
		assertTrue("ServoyTestingServer must have an 'analyzeCodeForTesting' tool",
				hasToolNamed("analyzeCodeForTesting"));
	}

	@Test
	public void testServoyTestingServer_createTestFileHasTwoParams() {
		Method method = findToolMethod("createTestFile");
		assertNotNull(method);
		assertEquals("createTestFile must have 2 parameters", 2, method.getParameterCount());
	}

	@Test
	public void testServoyTestingServer_addTestMethodHasThreeParams() {
		Method method = findToolMethod("addTestMethod");
		assertNotNull(method);
		assertEquals("addTestMethod must have 3 parameters", 3, method.getParameterCount());
	}

	@Test
	public void testServoyTestingServer_generateTestCasesHasTwoParams() {
		Method method = findToolMethod("generateTestCases");
		assertNotNull(method);
		assertEquals("generateTestCases must have 2 parameters", 2, method.getParameterCount());
	}

	@Test
	public void testServoyTestingServer_analyzeCodeForTestingHasOneParam() {
		Method method = findToolMethod("analyzeCodeForTesting");
		assertNotNull(method);
		assertEquals("analyzeCodeForTesting must have 1 parameter", 1, method.getParameterCount());
	}

	@Test
	public void testServoyTestingServer_createTestFile_validatesPrefix() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.createTestFile("invalid_name.js", "someSolution");
		assertTrue("createTestFile must reject names not starting with 'test_'",
				result.contains("Error") && result.contains("test_"));
	}

	@Test
	public void testServoyTestingServer_createTestFile_validatesExtension() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.createTestFile("test_something.txt", "someSolution");
		assertTrue("createTestFile must reject names not ending with '.js'",
				result.contains("Error") && result.contains(".js"));
	}

	@Test
	public void testServoyTestingServer_generateTestCases_returnsMarkdown() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.generateTestCases("function calculateTotal(price, qty) { return price * qty; }",
				"calculateTotal");
		assertTrue("generateTestCases must return markdown with function name", result.contains("calculateTotal"));
		assertTrue("generateTestCases must include happy path section", result.contains("Happy Path"));
		assertTrue("generateTestCases must include edge cases section", result.contains("Edge Case"));
	}

	@Test
	public void testServoyTestingServer_generateTestCases_extractsParams() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.generateTestCases("function add(a, b) { return a + b; }", "add");
		assertTrue("generateTestCases must list parameters", result.contains("a") && result.contains("b"));
		assertTrue("generateTestCases must suggest null parameter tests", result.contains("nullParameters"));
	}

	@Test
	public void testServoyTestingServer_analyzeCodeForTesting_detectsFunction() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.analyzeCodeForTesting("function myHelper(x, y) { return x + y; }");
		assertTrue("analyzeCodeForTesting must detect function name", result.contains("myHelper"));
		assertTrue("analyzeCodeForTesting must list parameters", result.contains("x") && result.contains("y"));
	}

	@Test
	public void testServoyTestingServer_analyzeCodeForTesting_handlesNoFunction() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.analyzeCodeForTesting("var x = 42;");
		assertTrue("analyzeCodeForTesting must indicate no function detected", result.contains("no clear function"));
	}

	@Test
	public void testServoyTestingServer_analyzeCodeForTesting_rejectsNull() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.analyzeCodeForTesting(null);
		assertTrue("analyzeCodeForTesting must return error for null", result.contains("Error"));
	}

	@Test
	public void testServoyTestingServer_analyzeCodeForTesting_rejectsEmpty() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.analyzeCodeForTesting("   ");
		assertTrue("analyzeCodeForTesting must return error for blank", result.contains("Error"));
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
	public void testGenerateFormSpec_descriptionMentionsSpecCy() {
		Method m = findToolMethod("generateFormSpec");
		assertNotNull(m);
		Tool tool = m.getAnnotation(Tool.class);
		assertTrue(tool.description().contains(".spec.cy.js"));
		// SVY-21171: description must reference the new workspace-relative location
		assertTrue("generateFormSpec description must reference the new e2e-form location",
			tool.description().contains("jenkins-custom/e2e-test-scripts/cypress/e2e-form"));
		assertTrue("generateFormSpec description must no longer reference medias/tests",
			!tool.description().contains("medias/tests"));
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
		assertTrue("generateTestCases should return error for null code: " + result,
				result.contains("Error") || result.contains("no clear function"));
	}

	@Test
	public void testGenerateTestCases_emptyCode_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.generateTestCases("", "funcName");
		assertNotNull(result);
		assertTrue("generateTestCases should return error for empty code: " + result,
				result.contains("Error") || result.contains("no clear function"));
	}

	@Test
	public void testGenerateTestCases_nullFunctionName_returnsResult() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.generateTestCases("function doWork(x) { return x * 2; }", null);
		assertNotNull(result);
		assertTrue("generateTestCases should return suggestions: " + result,
				result.contains("Suggested Test Cases") || result.contains("test_"));
	}

	@Test
	public void testGenerateTestCases_multipleParams() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.generateTestCases("function process(a, b, c) { return a + b + c; }", "process");
		assertNotNull(result);
		assertTrue("Should list all params", result.contains("a") && result.contains("b") && result.contains("c"));
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
		assertTrue("ServoyTestingServer must have an 'executeTestSetup' tool", hasToolNamed("executeTestSetup"));
	}

	@Test
	public void testExecuteTestSetup_hasCorrectParams() {
		Method m = findToolMethod("executeTestSetup");
		assertNotNull("executeTestSetup method must exist", m);
		assertEquals("executeTestSetup must have 3 parameters", 3, m.getParameterCount());

		java.lang.reflect.Parameter[] params = m.getParameters();
		assertTrue("first param must have @ToolParam", params[0].isAnnotationPresent(ToolParam.class));
		assertTrue("second param must have @ToolParam", params[1].isAnnotationPresent(ToolParam.class));
		assertTrue("third param must have @ToolParam", params[2].isAnnotationPresent(ToolParam.class));

		assertEquals("first param type should be String", String.class, params[0].getType());
		assertEquals("second param type should be String", String.class, params[1].getType());
		assertEquals("third param type should be Map", java.util.Map.class, params[2].getType());
	}

	@Test
	public void testExecuteTestSetup_nullServerName_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestSetup(null, "table", "{\"col\":\"val\"}");
		assertNotNull(result);
		assertTrue("Should return error for null serverName: " + result, result.contains("Error"));
	}

	@Test
	public void testExecuteTestSetup_nullTableName_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestSetup("server", null, "{\"col\":\"val\"}");
		assertNotNull(result);
		assertTrue("Should return error for null tableName: " + result, result.contains("Error"));
	}

	@Test
	public void testExecuteTestSetup_nullColumnValues_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestSetup("server", "table", null);
		assertNotNull(result);
		assertTrue("Should return error for null columnValues: " + result, result.contains("Error"));
	}

	@Test
	public void testExecuteTestSetup_emptyColumnValues_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestSetup("server", "table", "");
		assertNotNull(result);
		assertTrue("Should return error for empty columnValues: " + result, result.contains("Error"));
	}

	@Test
	public void testExecuteTestSetup_invalidJson_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestSetup("server", "table", "not valid json");
		assertNotNull(result);
		assertTrue("Should return error for invalid JSON: " + result, result.contains("Error"));
	}

	@Test
	public void testExecuteTestSetup_invalidServer_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestSetup("nonexistent_xyz_999", "table", "{\"col\":\"val\"}");
		assertNotNull(result);
		assertTrue("Should return error for invalid server: " + result, result.contains("Error"));
	}

	// -----------------------------------------------------------------------
	// executeTestTeardown tool tests
	// -----------------------------------------------------------------------

	@Test
	public void testServoyTestingServer_hasExecuteTestTeardownTool() {
		assertTrue("ServoyTestingServer must have an 'executeTestTeardown' tool", hasToolNamed("executeTestTeardown"));
	}

	@Test
	public void testExecuteTestTeardown_hasCorrectParams() {
		Method m = findToolMethod("executeTestTeardown");
		assertNotNull("executeTestTeardown method must exist", m);
		assertEquals("executeTestTeardown must have 4 parameters", 4, m.getParameterCount());

		java.lang.reflect.Parameter[] params = m.getParameters();
		assertTrue("first param must have @ToolParam", params[0].isAnnotationPresent(ToolParam.class));
		assertTrue("second param must have @ToolParam", params[1].isAnnotationPresent(ToolParam.class));
		assertTrue("third param must have @ToolParam", params[2].isAnnotationPresent(ToolParam.class));
		assertTrue("fourth param must have @ToolParam", params[3].isAnnotationPresent(ToolParam.class));

		for (java.lang.reflect.Parameter p : params) {
			assertEquals("all params should be String", String.class, p.getType());
		}
	}

	@Test
	public void testExecuteTestTeardown_nullServerName_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestTeardown(null, "table", "col", "val");
		assertNotNull(result);
		assertTrue("Should return error for null serverName: " + result, result.contains("Error"));
	}

	@Test
	public void testExecuteTestTeardown_nullTableName_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestTeardown("server", null, "col", "val");
		assertNotNull(result);
		assertTrue("Should return error for null tableName: " + result, result.contains("Error"));
	}

	@Test
	public void testExecuteTestTeardown_nullWhereColumn_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestTeardown("server", "table", null, "val");
		assertNotNull(result);
		assertTrue("Should return error for null whereColumn: " + result, result.contains("Error"));
	}

	@Test
	public void testExecuteTestTeardown_nullWhereValue_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestTeardown("server", "table", "col", null);
		assertNotNull(result);
		assertTrue("Should return error for null whereValue: " + result, result.contains("Error"));
	}

	@Test
	public void testExecuteTestTeardown_invalidServer_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.executeTestTeardown("nonexistent_xyz_999", "table", "col", "val");
		assertNotNull(result);
		assertTrue("Should return error for invalid server: " + result, result.contains("Error"));
	}

	@Test
	public void testServoyTestingServer_hasGenerateCypressE2ETestTool() {
		assertTrue("Expected generateCypressE2ETest tool", hasToolNamed("generateCypressE2ETest"));
	}

	@Test
	public void testGenerateCypressE2ETest_hasFiveParams() {
		Method m = findToolMethod("generateCypressE2ETest");
		assertNotNull("generateCypressE2ETest tool not found", m);
		assertEquals("Expected 5 parameters", 5, m.getParameterCount());
	}

	@Test
	public void testGenerateCypressE2ETest_descriptionMentionsMedias() {
		Method m = findToolMethod("generateCypressE2ETest");
		assertNotNull(m);
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue("Description should mention medias/e2e", desc.contains("medias/e2e"));
	}

	@Test
	public void testGenerateCypressE2ETest_returnsString() {
		Method m = findToolMethod("generateCypressE2ETest");
		assertNotNull(m);
		assertEquals("Return type should be String", String.class, m.getReturnType());
	}

	// -----------------------------------------------------------------------
	// getFormNavigationGraph tool tests (SVY-21169)
	// -----------------------------------------------------------------------

	@Test
	public void testServoyTestingServer_hasGetFormNavigationGraphTool() {
		assertTrue("Expected getFormNavigationGraph tool", hasToolNamed("getFormNavigationGraph"));
	}

	@Test
	public void testServoyTestingServer_hasGetNavigationPathTool() {
		assertTrue("Expected getNavigationPath tool", hasToolNamed("getNavigationPath"));
	}

	@Test
	public void testGetFormNavigationGraph_hasOneParam() {
		Method m = findToolMethod("getFormNavigationGraph");
		assertNotNull("getFormNavigationGraph tool not found", m);
		assertEquals("Expected 1 parameter (optional formName)", 1, m.getParameterCount());
		assertTrue("Parameter must have @ToolParam", m.getParameters()[0].isAnnotationPresent(ToolParam.class));
	}

	@Test
	public void testGetNavigationPath_hasTwoParams() {
		Method m = findToolMethod("getNavigationPath");
		assertNotNull("getNavigationPath tool not found", m);
		assertEquals("Expected 2 parameters (targetForm, fromForm)", 2, m.getParameterCount());
		assertTrue("First parameter must have @ToolParam", m.getParameters()[0].isAnnotationPresent(ToolParam.class));
		assertTrue("Second parameter must have @ToolParam", m.getParameters()[1].isAnnotationPresent(ToolParam.class));
	}

	@Test
	public void testGetFormNavigationGraph_descriptionMentionsCypress() {
		Method m = findToolMethod("getFormNavigationGraph");
		assertNotNull(m);
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue("Description should mention Cypress", desc.contains("Cypress"));
	}

	@Test
	public void testGetNavigationPath_descriptionMentionsCypress() {
		Method m = findToolMethod("getNavigationPath");
		assertNotNull(m);
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue("Description should mention Cypress", desc.contains("Cypress"));
	}

	@Test
	public void testGetFormNavigationGraph_returnsString() {
		Method m = findToolMethod("getFormNavigationGraph");
		assertNotNull(m);
		assertEquals("Return type should be String", String.class, m.getReturnType());
	}

	@Test
	public void testGetNavigationPath_returnsString() {
		Method m = findToolMethod("getNavigationPath");
		assertNotNull(m);
		assertEquals("Return type should be String", String.class, m.getReturnType());
	}

	@Test
	public void testGetNavigationPath_nullTarget_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.getNavigationPath(null, null);
		assertNotNull(result);
		assertTrue("Should return error for null targetForm: " + result, result.contains("Error"));
	}

	@Test
	public void testGetNavigationPath_emptyTarget_returnsError() {
		ServoyTestingServer server = new ServoyTestingServer();
		String result = server.getNavigationPath("  ", null);
		assertNotNull(result);
		assertTrue("Should return error for blank targetForm: " + result, result.contains("Error"));
	}
}
