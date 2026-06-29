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

import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.services.FormNavigationGraphService;
import com.servoy.eclipse.developer.mcp.services.FormPreviewService;
import com.servoy.eclipse.developer.mcp.services.FormSpecGenerator;
import com.servoy.eclipse.developer.mcp.services.FormSpecRunner;
import com.servoy.eclipse.developer.mcp.services.JSUnitRunnerService;
import com.servoy.eclipse.developer.mcp.services.NavigationEdge;
import com.servoy.eclipse.developer.mcp.services.NavigationGraph;
import com.servoy.eclipse.developer.mcp.services.ServoySolutionService;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.util.Settings;

/**
 * MCP server for all testing-related tools: JSUnit test running, form preview, and screenshots.
 */
@Creatable
@McpServer(name = "servoy-test")
public class ServoyTestingServer
{
	private final JSUnitRunnerService jsunitRunner = new JSUnitRunnerService();
	private final FormPreviewService formPreview = new FormPreviewService();
	private final FormSpecGenerator specGenerator = new FormSpecGenerator();
	private final FormSpecRunner specRunner = new FormSpecRunner();
	private final FormNavigationGraphService navigationService = new FormNavigationGraphService();
	private final ServoySolutionService solutionService = new ServoySolutionService();

	public ServoyTestingServer()
	{
	}

	private void ensureTestingMode()
	{
		Settings.getInstance().setProperty("servoy.ngclient.testingMode", "true");
	}

	@Tool(name = "runJsUnitTests",
		description = "Runs JSUnit tests for the active Servoy solution and returns pass/fail results with failure traces. " +
			"Use this to verify tests pass after creating or modifying test files, or to identify which tests are currently failing. " +
			"Returns a markdown summary with counts and detailed failure/error traces.",
		type = "object")
	public String runJsUnitTests(
		@ToolParam(description = "What to test: a scope/file name (e.g. 'test_utils' or 'test_utils.js'), a form name (e.g. 'tab1' or 'forms/tab1.js'), a module name (e.g. 'calculations_module'), 'MODULES' to run all tests across every module of the active solution, or 'ALL' to run every test in the solution (including all modules)") String scopeOrAll,
		@ToolParam(description = "Maximum seconds to wait for the test run to complete. Use 60 for a single scope or form, 120 for a full solution run.", type = "integer") int timeoutSeconds)
	{
		try
		{
			return jsunitRunner.runTests(scopeOrAll, timeoutSeconds);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error running JSUnit tests", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "showFormInBrowser",
		description = "Opens a specific Servoy form in an external browser for preview/testing. " +
			"Bypasses authentication and shows the form directly without requiring login. " +
			"Auto-generates a .spec.cy.js test file if one doesn't exist for the form. " +
			"Use this to visually inspect a form or to prepare for running Cypress tests against it. " +
			"Returns the URL that was opened.",
		type = "object")
	public String showFormInBrowser(
		@ToolParam(description = "The name of the form to show in the browser (e.g. 'mainForm', 'orderDetails')") String formName)
	{
		return showFormInBrowser(formName, true);
	}

	public String showFormInBrowser(String formName, boolean openBrowser)
	{
		try
		{
			ensureTestingMode();
			String result = formPreview.showFormInBrowser(formName, openBrowser);
			if (!result.startsWith("Error") && !specGenerator.specExists(formName))
			{
				String specResult = specGenerator.generateSpec(formName);
				if (!specResult.startsWith("Error"))
				{
					result += "\n" + specResult;
				}
			}
			return result;
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in showFormInBrowser tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "screenshotForm",
		description = "Takes a screenshot of a specific Servoy form rendered in a headless browser. " +
			"Bypasses authentication and captures the form as it appears at runtime. " +
			"Returns the file path of the saved screenshot PNG. " +
			"Use this to visually verify form layout, check element positioning, or capture the current state of a form.",
		type = "object")
	public String screenshotForm(
		@ToolParam(description = "The name of the form to screenshot (e.g. 'mainForm', 'orderDetails')") String formName,
		@ToolParam(description = "How many seconds to wait for the form to fully render before taking the screenshot. Use 5 for simple forms, 10 for complex ones.", type = "integer") int waitSeconds)
	{
		try
		{
			return formPreview.screenshotForm(formName, waitSeconds);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in screenshotForm tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "checkNGClientStatus",
		description = "Checks whether the NG client is currently running and returns the base URL if available. " +
			"Use this before running form tests to verify the runtime is ready.",
		type = "object")
	public String checkNGClientStatus()
	{
		try
		{
			return formPreview.checkNGClientStatus();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in checkNGClientStatus tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "testForm",
		description = "Runs Cypress-based spec tests against a Servoy form in a headless browser. " +
			"The form must have a .spec.cy.js file (auto-generated by showFormInBrowser if missing). " +
			"Returns pass/fail results for each assertion in the spec. " +
			"Requires the NG client to be running (use checkNGClientStatus first).",
		type = "object")
	public String testForm(
		@ToolParam(description = "The name of the form to test (e.g. 'mainForm', 'orderDetails')") String formName)
	{
		try
		{
			ensureTestingMode();
			if (!specGenerator.specExists(formName))
			{
				String genResult = specGenerator.generateSpec(formName);
				if (genResult.startsWith("Error")) return genResult;
			}
			String result = specRunner.runSpec(formName, true);
			writeToConsole(result);
			return result;
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in testForm tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "showAndTest",
		description = "Opens a Servoy form in a visible browser AND runs Cypress spec tests against it. " +
			"Combines showFormInBrowser + testForm: the form is shown in a real browser window " +
			"and assertions from the .spec.cy.js file are executed. " +
			"Auto-generates the spec file if it doesn't exist. " +
			"Returns pass/fail results.",
		type = "object")
	public String showAndTest(
		@ToolParam(description = "The name of the form to show and test (e.g. 'mainForm', 'orderDetails')") String formName)
	{
		try
		{
			ensureTestingMode();
			if (!specGenerator.specExists(formName))
			{
				String genResult = specGenerator.generateSpec(formName);
				if (genResult.startsWith("Error")) return genResult;
			}
			String result = specRunner.runSpec(formName, false);
			writeToConsole(result);
			return result;
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in showAndTest tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "listE2ETests",
		description = "Lists all Cypress E2E test files (.cy.js) in jenkins-custom/e2e-test-scripts/cypress/e2e/. " +
			"Use this to discover which E2E tests exist before calling testE2E or showAndTestE2E. " +
			"Returns file names, the form name each file targets, and the full path.",
		type = "object")
	public String listE2ETests()
	{
		try
		{
			java.nio.file.Path workspaceRoot = java.nio.file.Paths.get(
				org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString());
			java.nio.file.Path e2eDir = workspaceRoot
				.resolve("jenkins-custom")
				.resolve("e2e-test-scripts")
				.resolve("cypress")
				.resolve("e2e");

			if (!java.nio.file.Files.exists(e2eDir))
			{
				return "No E2E tests found. Directory does not exist: " + e2eDir +
					"\nUse generateCypressE2ETest to create your first E2E test.";
			}

			java.util.List<java.nio.file.Path> specs;
			try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(e2eDir))
			{
				specs = stream
					.filter(p -> p.getFileName().toString().endsWith(".cy.js") || p.getFileName().toString().endsWith(".cy.ts"))
					.sorted()
					.collect(java.util.stream.Collectors.toList());
			}

			if (specs.isEmpty())
			{
				return "No E2E test files found in " + e2eDir +
					"\nUse generateCypressE2ETest to create your first E2E test.";
			}

			StringBuilder sb = new StringBuilder();
			sb.append("**E2E Tests** (").append(specs.size()).append(" file").append(specs.size() == 1 ? "" : "s").append(")\n\n");
			sb.append("Directory: ").append(e2eDir).append("\n\n");
			for (java.nio.file.Path spec : specs)
			{
				String fileName = spec.getFileName().toString();
				// derive form name: strip .cy.js or .cy.ts suffix
				String formName = fileName.replaceAll("\\.cy\\.(js|ts)$", "");
				String relativePath = e2eDir.relativize(spec).toString().replace('\\', '/');
				sb.append("- **").append(formName).append("** → `").append(relativePath).append("`\n");
			}
			sb.append("\nPass the form name (e.g. '").append(
				specs.get(0).getFileName().toString().replaceAll("\\.cy\\.(js|ts)$", ""))
				.append("') to testE2E or showAndTestE2E.");
			return sb.toString();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in listE2ETests tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "testE2E",
		description = "Runs Cypress E2E tests for a Servoy form in headless mode. " +
			"Looks for a .cy.js file in jenkins-custom/e2e-test-scripts/cypress/e2e/ (generated by generateCypressE2ETest). " +
			"Uses the cypress.config.js in jenkins-custom/e2e-test-scripts/. " +
			"Returns pass/fail results for each assertion. " +
			"Requires the NG client to be running (use checkNGClientStatus first).",
		type = "object")
	public String testE2E(
		@ToolParam(description = "The form name to test (e.g. 'order_detail'). Looks for <formName>.cy.js in the E2E directory.") String targetForm)
	{
		try
		{
			ensureTestingMode();
			String result = specRunner.runE2ESpec(targetForm, true);
			writeToConsole(result);
			return result;
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in testE2E tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "showAndTestE2E",
		description = "Runs Cypress E2E tests for a Servoy form in headed (visible browser) mode. " +
			"Looks for a .cy.js file in jenkins-custom/e2e-test-scripts/cypress/e2e/ (generated by generateCypressE2ETest). " +
			"Uses the cypress.config.js in jenkins-custom/e2e-test-scripts/. " +
			"The browser window is visible so you can watch the test execute. " +
			"Returns pass/fail results. " +
			"Requires the NG client to be running (use checkNGClientStatus first).",
		type = "object")
	public String showAndTestE2E(
		@ToolParam(description = "The form name to test (e.g. 'order_detail'). Looks for <formName>.cy.js in the E2E directory.") String targetForm)
	{
		try
		{
			ensureTestingMode();
			String result = specRunner.runE2ESpec(targetForm, false);
			writeToConsole(result);
			return result;
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in showAndTestE2E tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "generateFormSpec",
		description = "Generates Cypress test spec files for a Servoy form. " +
			"Creates two files: a .spec.cy.js in medias/tests/ (Cypress UI assertions with data-cy selectors) " +
			"and a .spec.js in forms/ (Servoy setUp/tearDown scope with DLTK code completion). " +
			"Reads the form's .frm file to extract element names, dataSource, and structure. " +
			"Requires servoy.ngclient.testingMode=true in servoy.properties for data-cy attributes.",
		type = "object")
	public String generateFormSpec(
		@ToolParam(description = "The name of the form to generate a spec for (e.g. 'mainForm', 'orderDetails')") String formName)
	{
		try
		{
			return specGenerator.generateSpec(formName);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in generateFormSpec tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "executeTestSetup",
		description = "Inserts a test record into a database table via JDBC. " +
			"Use this BEFORE running Cypress tests to ensure the form has data to display. " +
			"The record is inserted directly into the database, bypassing Servoy's foundset layer. " +
			"Call executeTestTeardown after testing to clean up. " +
			"Returns success message with row count or error details.",
		type = "object")
	public String executeTestSetup(
		@ToolParam(description = "Database server name (e.g. 'example_data')") String serverName,
		@ToolParam(description = "Table name (e.g. 'orders')") String tableName,
		@ToolParam(description = "Column values as JSON string (e.g. '{\"customerid\":\"CYPRS\",\"shipcity\":\"TestCity\"}')") String columnValuesJson)
	{
		if (columnValuesJson == null || columnValuesJson.isBlank())
			return "Error: columnValues JSON is required.";
		try
		{
			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> columnValues = new com.fasterxml.jackson.databind.ObjectMapper()
				.readValue(columnValuesJson, java.util.LinkedHashMap.class);
			return specRunner.executeTestSetup(serverName, tableName, columnValues);
		}
		catch (Exception e)
		{
			return "Error parsing columnValues JSON: " + e.getMessage();
		}
	}

	@Tool(name = "executeTestTeardown",
		description = "Deletes test records from a database table via JDBC. " +
			"Use this AFTER running Cypress tests to clean up test data created by executeTestSetup. " +
			"Deletes all rows matching the given where clause. " +
			"Returns success message with deleted row count or error details.",
		type = "object")
	public String executeTestTeardown(
		@ToolParam(description = "Database server name (e.g. 'example_data')") String serverName,
		@ToolParam(description = "Table name (e.g. 'orders')") String tableName,
		@ToolParam(description = "Column name for the WHERE clause (e.g. 'shipcity')") String whereColumn,
		@ToolParam(description = "Value to match in the WHERE clause (e.g. 'CYPRESS_TEST_CITY')") String whereValue)
	{
		return specRunner.executeTestTeardown(serverName, tableName, whereColumn, whereValue);
	}

	@Tool(name = "createTestFile",
		description = "Creates a new JSUnit test file (JavaScript scope) in the active solution's root directory. " +
			"File name must follow convention: test_functionName.js or test_fileName.js. " +
			"The file is created with a standard JSUnit header comment.",
		type = "object")
	public String createTestFile(
		@ToolParam(description = "Test file name (e.g., 'test_utils.js' or 'test_calculateTotal.js'). Must start with 'test_' and end with '.js'.") String testFileName,
		@ToolParam(description = "Solution name to create the file in. Use 'TARGET' to use the current active solution.") String solutionName)
	{
		try
		{
			if (!testFileName.startsWith("test_"))
			{
				return "Error: Test file name must start with 'test_' (e.g., 'test_utils.js')";
			}
			if (!testFileName.endsWith(".js"))
			{
				return "Error: Test file name must end with '.js' (e.g., 'test_utils.js')";
			}

			String actualSolutionName = solutionName;
			if ("TARGET".equalsIgnoreCase(solutionName))
			{
				com.servoy.eclipse.model.nature.ServoyProject activeProject =
					com.servoy.eclipse.core.ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
				if (activeProject != null)
				{
					actualSolutionName = activeProject.getProject().getName();
				}
				else
				{
					return "Error: No active project found. Please open a Servoy solution.";
				}
			}

			return com.servoy.eclipse.developer.mcp.services.TestFileService.getInstance()
				.createTestFile(testFileName, actualSolutionName);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in createTestFile tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "addTestMethod",
		description = "Adds or replaces a test method in an existing JSUnit test file. " +
			"If a method with the same name already exists it is replaced (duplicates are also removed). " +
			"Generates proper @properties annotation with UUID automatically.",
		type = "object")
	public String addTestMethod(
		@ToolParam(description = "Test file name (e.g., 'test_utils.js')") String testFileName,
		@ToolParam(description = "Test method name (must start with 'test_', e.g., 'test_calculateTotal_withDiscount')") String testMethodName,
		@ToolParam(description = "Complete test function body (without function declaration or @properties)") String testCode)
	{
		try
		{
			com.servoy.eclipse.model.nature.ServoyProject activeProject =
				com.servoy.eclipse.core.ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null)
			{
				return "Error: No active project found. Please open a Servoy solution.";
			}

			String solutionName = activeProject.getProject().getName();
			return com.servoy.eclipse.developer.mcp.services.TestFileService.getInstance()
				.addTestMethod(testFileName, testMethodName, testCode, solutionName);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in addTestMethod tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "generateTestCases",
		description = "Suggests test cases for a function based on its signature and logic. " +
			"Returns structured list of test scenarios covering happy path, edge cases, and error conditions. " +
			"Does NOT create files - only provides suggestions for test coverage.",
		type = "object")
	public String generateTestCases(
		@ToolParam(description = "Source code or function signature to analyze") String sourceCode,
		@ToolParam(description = "Function name to generate test cases for") String functionName)
	{
		try
		{
			String[] params = extractTestParameters(sourceCode, functionName);

			StringBuilder suggestions = new StringBuilder();
			suggestions.append("**Suggested Test Cases for ").append(functionName).append("():**\n\n");

			if (params != null && params.length > 0)
			{
				suggestions.append("**Function Parameters:** ").append(String.join(", ", params)).append("\n\n");
			}

			suggestions.append("**1. Happy Path Tests:**\n");
			suggestions.append("   - test_").append(functionName).append("_normalCase\n");
			suggestions.append("   - test_").append(functionName).append("_validInputs\n\n");

			suggestions.append("**2. Edge Case Tests:**\n");
			if (params != null && params.length > 0)
			{
				suggestions.append("   - test_").append(functionName).append("_nullParameters\n");
				suggestions.append("   - test_").append(functionName).append("_undefinedParameters\n");
				suggestions.append("   - test_").append(functionName).append("_emptyValues\n");
			}
			suggestions.append("   - test_").append(functionName).append("_boundaryValues\n\n");

			suggestions.append("**3. Error Case Tests:**\n");
			suggestions.append("   - test_").append(functionName).append("_invalidInput\n");
			suggestions.append("   - test_").append(functionName).append("_throwsException\n\n");

			suggestions.append("**4. Specific Scenarios:**\n");
			suggestions.append("   - test_").append(functionName).append("_returnsCorrectType\n");
			suggestions.append("   - test_").append(functionName).append("_handlesSpecialCases\n\n");

			suggestions.append("**Note:** Review function implementation to identify additional specific test cases.");

			return suggestions.toString();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in generateTestCases tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "analyzeCodeForTesting",
		description = "Analyzes selected code to identify testable functions. " +
			"Returns function name and parameters extracted from the code snippet.",
		type = "object")
	public String analyzeCodeForTesting(
		@ToolParam(description = "Selected code snippet to analyze for testable functions") String selection)
	{
		try
		{
			if (selection == null || selection.isBlank())
			{
				return "Error: Selection parameter is required";
			}

			String functionName = extractFunctionName(selection);

			if (functionName != null)
			{
				String[] params = extractTestParameters(selection, functionName);
				StringBuilder result = new StringBuilder();
				result.append("**Code Analysis for Testing:**\n\n");
				result.append("Detected function: ").append(functionName).append("\n");
				if (params.length > 0)
				{
					result.append("Parameters: ").append(String.join(", ", params)).append("\n");
				}
				result.append("\nCode snippet:\n```javascript\n").append(selection).append("\n```\n");
				return result.toString();
			}

			return "**Inline Code Analysis:**\n\n" +
				"Code snippet provided (no clear function detected):\n```javascript\n" + selection + "\n```\n\n" +
				"Recommendation: Select a complete function for better analysis.";
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in analyzeCodeForTesting tool", e);
			return "Error: " + e.getMessage();
		}
	}

	private static String extractFunctionName(String code)
	{
		if (code == null) return null;
		java.util.regex.Matcher matcher = java.util.regex.Pattern
			.compile("function\\s+(\\w+)\\s*\\(")
			.matcher(code);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static String[] extractTestParameters(String code, String functionName)
	{
		if (code == null || functionName == null) return new String[0];
		java.util.regex.Matcher matcher = java.util.regex.Pattern
			.compile("function\\s+" + java.util.regex.Pattern.quote(functionName) + "\\s*\\(([^)]*)")
			.matcher(code);
		if (matcher.find())
		{
			String paramStr = matcher.group(1).trim();
			if (paramStr.isEmpty()) return new String[0];
			return java.util.Arrays.stream(paramStr.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.toArray(String[]::new);
		}
		return new String[0];
	}

	private void writeToConsole(String message)
	{
		try
		{
			org.eclipse.ui.console.MessageConsole console = com.servoy.eclipse.developer.mcp.actions.CypressConsoleUtil.findOrCreateConsole();
			console.clearConsole();
			com.servoy.eclipse.developer.mcp.actions.CypressConsoleUtil.showConsole(console);
			try (org.eclipse.ui.console.MessageConsoleStream stream = console.newMessageStream())
			{
				stream.println(message);
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error writing to Cypress console", e);
		}
	}

	@Tool(name = "findForm",
		description = "Finds Servoy forms by natural-language description, UI title, internal name, or datasource. " +
			"Searches form names, titleText (the visible window/browser title), datasource, and comments. " +
			"Use this when you know a form by its UI label (e.g. 'Query performance popup', 'Customer detail dialog') " +
			"but not its internal programmatic name. " +
			"Returns matching forms sorted by relevance with internal name, title, module, datasource, and layout type. " +
			"Pass the returned internal name to other tools like getFormNavigationGraph, showFormInBrowser, or getSource.",
		type = "object")
	public String findForm(
		@ToolParam(description = "The description, UI title, or partial name to search for (e.g. 'Query performance popup', 'customer', 'orders'). Leave blank to list all forms.") String query)
	{
		try
		{
			return solutionService.findForm(query);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in findForm tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "getFormNavigationGraph", description = "Returns the form navigation graph of the active Servoy solution as JSON. " +
			"Shows which forms contain which other forms via TabPanels, WebComponents with form-typed properties, " +
			"navigators, and script-based navigation (showFormInDialog, form property assignments). " +
			"Each edge includes containerName, containerType, tabName, confidence (static/dynamic), and a suggested cypressSelector. " +
			"Use this to understand the application's navigation structure for Cypress E2E test generation. " +
			"If formName is provided, returns only the subgraph relevant to reaching that form plus the navigation path. " +
			"For large solutions, pass summaryOnly=true to get a compact parent->children map instead of full edge objects.", type = "object")
	public String getFormNavigationGraph(
			@ToolParam(description = "Optional form name to filter the graph to only edges relevant for reaching this form. If omitted, returns the full navigation graph.") String formName,
			@ToolParam(description = "If true, returns a compact summary (parent->children map) instead of full edge objects. Use for large solutions to avoid token limits. Default: false.", type = "boolean") boolean summaryOnly)
	{
		try
		{
			NavigationGraph graph = navigationService.buildFullGraph();
			String mainForm = navigationService.getMainFormName();

			com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

			java.util.List<NavigationEdge> edges;
			if (formName != null && !formName.trim().isEmpty())
			{
				edges = graph.getSubgraphEdges(mainForm != null ? mainForm : "", formName.trim());
			}
			else
			{
				edges = graph.getAllEdges();
			}

			// summaryOnly: return a compact parent -> [children] map
			if (summaryOnly)
			{
				com.fasterxml.jackson.databind.node.ObjectNode summary = mapper.createObjectNode();
				summary.put("mainForm", mainForm != null ? mainForm : "unknown");
				summary.put("edgeCount", edges.size());
				summary.put("formCount", graph.getAllFormNames().size());
				java.util.Map<String, java.util.List<String>> parentToChildren = new java.util.LinkedHashMap<>();
				for (NavigationEdge edge : edges)
				{
					parentToChildren.computeIfAbsent(edge.getFrom(), k -> new java.util.ArrayList<>()).add(edge.getTo());
				}
				com.fasterxml.jackson.databind.node.ObjectNode childrenMap = mapper.createObjectNode();
				for (java.util.Map.Entry<String, java.util.List<String>> entry : parentToChildren.entrySet())
				{
					com.fasterxml.jackson.databind.node.ArrayNode arr = mapper.createArrayNode();
					entry.getValue().stream().distinct().forEach(arr::add);
					childrenMap.set(entry.getKey(), arr);
				}
				summary.set("graph", childrenMap);
				if (formName != null && !formName.trim().isEmpty())
				{
					summary.put("targetForm", formName.trim());
					java.util.List<NavigationEdge> path = graph.findPath(mainForm != null ? mainForm : "", formName.trim());
					com.fasterxml.jackson.databind.node.ArrayNode pathArray = mapper.createArrayNode();
					for (NavigationEdge step : path)
					{
						com.fasterxml.jackson.databind.node.ObjectNode stepNode = mapper.createObjectNode();
						stepNode.put("from", step.getFrom());
						stepNode.put("to", step.getTo());
						stepNode.put("containerType", step.getContainerType());
						if (step.getCypressSelector() != null) stepNode.put("cypressSelector", step.getCypressSelector());
						pathArray.add(stepNode);
					}
					summary.set("pathTo", pathArray);
				}
				return mapper.writeValueAsString(summary);
			}

			// full mode: return compact JSON (not pretty-printed) to stay within token limits
			com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();
			result.put("mainForm", mainForm != null ? mainForm : "unknown");
			result.put("edgeCount", edges.size());
			result.put("formCount", graph.getAllFormNames().size());

			if (formName != null && !formName.trim().isEmpty())
			{
				result.put("targetForm", formName.trim());
				java.util.List<NavigationEdge> path = graph.findPath(mainForm != null ? mainForm : "", formName.trim());
				com.fasterxml.jackson.databind.node.ArrayNode pathArray = mapper.createArrayNode();
				for (NavigationEdge step : path)
				{
					pathArray.add(edgeToJson(mapper, step));
				}
				result.set("pathTo", pathArray);
			}

			com.fasterxml.jackson.databind.node.ArrayNode graphArray = mapper.createArrayNode();
			for (NavigationEdge edge : edges)
			{
				graphArray.add(edgeToJson(mapper, edge));
			}
			result.set("graph", graphArray);

			return mapper.writeValueAsString(result);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in getFormNavigationGraph tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "getNavigationPath", description = "Returns the navigation path (ordered list of steps) from one form to another in the active Servoy solution. " +
			"Each step includes the form, container, action type, and a suggested cypressSelector for generating Cypress E2E navigation commands. " +
			"Use this to generate Cypress test scripts that navigate through the real application instead of using isolated formpreview URLs.", type = "object")
	public String getNavigationPath(
			@ToolParam(description = "The target form name to navigate to (e.g. 'order_detail')") String targetForm,
			@ToolParam(description = "Optional starting form name. If omitted, starts from the solution's first/main form.") String fromForm)
	{
		try
		{
			if (targetForm == null || targetForm.trim().isEmpty())
			{
				return "Error: targetForm parameter is required";
			}

			NavigationGraph graph = navigationService.buildFullGraph();
			String startForm = (fromForm != null && !fromForm.trim().isEmpty()) ? fromForm.trim()
					: navigationService.getMainFormName();

			if (startForm == null)
			{
				return "Error: Could not determine the main form of the active solution. Specify fromForm parameter.";
			}

			java.util.List<NavigationEdge> path = graph.findPath(startForm, targetForm.trim());

			if (path.isEmpty())
			{
				return "Error: No navigation path found from '" + startForm + "' to '" + targetForm.trim() + "'. " +
						"The form may be unreachable via static navigation (only accessible via runtime script calls) or does not exist.";
			}

			com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
			com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();
			result.put("mainForm", startForm);
			result.put("pathTo", targetForm.trim());

			com.fasterxml.jackson.databind.node.ArrayNode stepsArray = mapper.createArrayNode();
			for (NavigationEdge step : path)
			{
				stepsArray.add(edgeToJson(mapper, step));
			}
			result.set("steps", stepsArray);

			return mapper.writeValueAsString(result);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in getNavigationPath tool", e);
			return "Error: " + e.getMessage();
		}
	}

	private com.fasterxml.jackson.databind.node.ObjectNode edgeToJson(com.fasterxml.jackson.databind.ObjectMapper mapper, NavigationEdge edge)
	{
		com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
		node.put("from", edge.getFrom());
		node.put("to", edge.getTo());
		node.put("containerName", edge.getContainerName());
		node.put("containerType", edge.getContainerType());
		node.put("confidence", edge.getConfidence());
		if (edge.getPropertyName() != null) node.put("propertyName", edge.getPropertyName());
		if (edge.getTabName() != null) node.put("tabName", edge.getTabName());
		if (edge.getTabIndex() >= 0) node.put("tabIndex", edge.getTabIndex());
		if (edge.getRelationName() != null) node.put("relationName", edge.getRelationName());
		if (edge.getTrigger() != null) node.put("trigger", edge.getTrigger());
		String selector = edge.getCypressSelector();
		if (selector != null) node.put("cypressSelector", selector);
		return node;
	}

	@Tool(name = "generateCypressE2ETest", description = "Generates a Cypress E2E test file for a target form based on the navigation graph. " +
			"Writes the .cy.js file to jenkins-custom/e2e-test-scripts/cypress/e2e/ in the Servoy workspace, " +
			"which is the standard Servoy Cloud E2E test directory structure. " +
			"Also creates cypress.config.js in jenkins-custom/e2e-test-scripts/ if it does not exist yet. " +
			"The file contains a describe block, a beforeEach that visits the app, and an it block with the navigation steps already filled in from the graph. " +
			"A TODO comment marks where scenario-specific assertions should be added. " +
			"Use getNavigationPath first to understand the path, then call this tool to scaffold the test file.", type = "object")
	public String generateCypressE2ETest(
			@ToolParam(description = "The target form to reach and test (e.g. 'dialogform1').") String targetForm,
			@ToolParam(description = "Plain-English description of what the test should verify. Embedded as a comment in the generated file.") String scenario,
			@ToolParam(description = "Optional: form to start navigation from. Defaults to the solution main form.") String fromForm,
			@ToolParam(description = "Optional: output file name (e.g. 'dialog_flow.cy.js'). Defaults to '<targetForm>.cy.js'.") String outputFileName,
			@ToolParam(description = "Optional: base URL of the running Servoy app (e.g. 'http://localhost:8183'). Defaults to the server's actual port.") String baseUrl)
	{
		try
		{
			com.servoy.eclipse.model.nature.ServoyProject servoyProject =
					com.servoy.eclipse.core.ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (servoyProject == null || servoyProject.getEditingSolution() == null)
			{
				return "Error: No active Servoy project found.";
			}
			String solutionName = servoyProject.getProject().getName();

			// resolve start form
			String startForm = (fromForm != null && !fromForm.isBlank()) ? fromForm
					: navigationService.getMainFormName();
			if (startForm == null) startForm = "mainForm";

			// resolve base URL for cypress.config.js
			String resolvedBaseUrl;
			if (baseUrl != null && !baseUrl.isBlank())
			{
				resolvedBaseUrl = baseUrl.replaceAll("/$", "");
			}
			else
			{
				int port = com.servoy.j2db.server.shared.ApplicationServerRegistry.get().getWebServerPort();
				resolvedBaseUrl = "http://localhost:" + (port > 0 ? port : 8183);
			}

			// resolve file name
			String fileName = (outputFileName != null && !outputFileName.isBlank()) ? outputFileName
					: targetForm + ".cy.js";
			if (!fileName.endsWith(".cy.js") && !fileName.endsWith(".cy.ts"))
				fileName = fileName.replaceAll("\\.js$|\\.ts$", "") + ".cy.js";

			// get navigation path and generate content
			NavigationGraph graph = navigationService.buildFullGraph();
			java.util.List<NavigationEdge> path = graph.findPath(startForm, targetForm);
			String content = navigationService.generateCypressTestContent(solutionName, resolvedBaseUrl, startForm,
					targetForm, scenario, path);

			// resolve workspace root â jenkins-custom lives directly in the Eclipse workspace directory
			java.nio.file.Path workspaceRoot = java.nio.file.Paths.get(
					org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString());
			java.nio.file.Path e2eDir = workspaceRoot
					.resolve("jenkins-custom")
					.resolve("e2e-test-scripts")
					.resolve("cypress")
					.resolve("e2e");
			java.nio.file.Files.createDirectories(e2eDir);

			// write the spec file
			java.nio.file.Path testFilePath = e2eDir.resolve(fileName);
			java.nio.file.Files.writeString(testFilePath, content, java.nio.charset.StandardCharsets.UTF_8);

			// scaffold cypress.config.js if it does not exist
			java.nio.file.Path scriptsDir = workspaceRoot.resolve("jenkins-custom").resolve("e2e-test-scripts");
			java.nio.file.Path configFile = scriptsDir.resolve("cypress.config.js");
			if (!java.nio.file.Files.exists(configFile))
			{
				String configContent = "module.exports = {\n" +
						"  video: true,\n" +
						"  screenshotOnRunFailure: true,\n" +
						"  screenshotsFolder: 'cypress/screenshots',\n" +
						"  videosFolder: 'cypress/videos',\n" +
						"  fixturesFolder: 'cypress/fixtures',\n" +
						"  viewportWidth: 1920,\n" +
						"  viewportHeight: 1080,\n" +
						"  chromeWebSecurity: false,\n" +
						"  e2e: {\n" +
						"    baseUrl: '" + resolvedBaseUrl + "',\n" +
						"    specPattern: '**/*.{cy.js,spec.js,test.js}',\n" +
						"    setupNodeEvents(on, config) {\n" +
						"      // implement node event listeners here\n" +
						"    },\n" +
						"    experimentalStudio: true\n" +
						"  },\n" +
						"};\n";
				java.nio.file.Files.writeString(configFile, configContent, java.nio.charset.StandardCharsets.UTF_8);
			}

			// scaffold cypress/support files if missing
			java.nio.file.Path supportDir = scriptsDir.resolve("cypress").resolve("support");
			java.nio.file.Files.createDirectories(supportDir);
			java.nio.file.Path commandsFile = supportDir.resolve("commands.js");
			if (!java.nio.file.Files.exists(commandsFile))
			{
				java.nio.file.Files.writeString(commandsFile,
						"// Add custom Cypress commands here.\n// See: https://docs.cypress.io/api/cypress-api/custom-commands\n",
						java.nio.charset.StandardCharsets.UTF_8);
			}
			java.nio.file.Path e2eJsFile = supportDir.resolve("e2e.js");
			if (!java.nio.file.Files.exists(e2eJsFile))
			{
				java.nio.file.Files.writeString(e2eJsFile,
						"import './commands';\n",
						java.nio.charset.StandardCharsets.UTF_8);
			}

			String relativePath = workspaceRoot.relativize(testFilePath).toString().replace('\\', '/');
			ServoyLog.logInfo("[ServoyTestingServer] Generated Cypress E2E test: " + testFilePath);
			return "Created: " + relativePath + "\n\n" + content;
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in generateCypressE2ETest tool", e);
			return "Error: " + e.getMessage();
		}
	}
}
