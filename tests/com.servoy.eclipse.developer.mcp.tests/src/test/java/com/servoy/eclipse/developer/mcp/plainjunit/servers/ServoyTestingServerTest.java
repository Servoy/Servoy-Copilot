package com.servoy.eclipse.developer.mcp.plainjunit.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServer;

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
		Method method = findToolMethod("generateFormSpec");
		assertNotNull(method);
		assertEquals(1, method.getParameterCount(), "generateFormSpec must have 1 parameter");
		ToolParam param = method.getParameters()[0].getAnnotation(ToolParam.class);
		assertNotNull(param, "generateFormSpec parameter must have @ToolParam");
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
	public void testGenerateCypressE2ETest_hasNineParams() {
		Method m = findToolMethod("generateCypressE2ETest");
		assertNotNull(m, "generateCypressE2ETest tool not found");
		assertEquals(9, m.getParameterCount(), "Expected 9 parameters");
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
}
