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
import com.servoy.eclipse.developer.mcp.services.FormPreviewService;
import com.servoy.eclipse.developer.mcp.services.FormSpecGenerator;
import com.servoy.eclipse.developer.mcp.services.FormSpecRunner;
import com.servoy.eclipse.developer.mcp.services.JSUnitRunnerService;
import com.servoy.eclipse.model.util.ServoyLog;

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

	public ServoyTestingServer()
	{
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
			"Auto-generates a .spec.js test file if one doesn't exist for the form. " +
			"Use this to visually inspect a form or to prepare for running Playwright tests against it. " +
			"Returns the URL that was opened.",
		type = "object")
	public String showFormInBrowser(
		@ToolParam(description = "The name of the form to show in the browser (e.g. 'mainForm', 'orderDetails')") String formName)
	{
		try
		{
			String result = formPreview.showFormInBrowser(formName);
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
		description = "Runs Playwright-based spec tests against a Servoy form in a headless browser. " +
			"The form must have a .spec.js file (auto-generated by showFormInBrowser if missing). " +
			"Returns pass/fail results for each assertion in the spec. " +
			"Requires the NG client to be running (use checkNGClientStatus first).",
		type = "object")
	public String testForm(
		@ToolParam(description = "The name of the form to test (e.g. 'mainForm', 'orderDetails')") String formName)
	{
		try
		{
			if (!specGenerator.specExists(formName))
			{
				String genResult = specGenerator.generateSpec(formName);
				if (genResult.startsWith("Error")) return genResult;
			}
			return specRunner.runSpec(formName, true);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in testForm tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "showAndTest",
		description = "Opens a Servoy form in a visible browser AND runs Playwright spec tests against it. " +
			"Combines showFormInBrowser + testForm: the form is shown in a real browser window " +
			"and assertions from the .spec.js file are executed. " +
			"Auto-generates the spec file if it doesn't exist. " +
			"Returns pass/fail results.",
		type = "object")
	public String showAndTest(
		@ToolParam(description = "The name of the form to show and test (e.g. 'mainForm', 'orderDetails')") String formName)
	{
		try
		{
			if (!specGenerator.specExists(formName))
			{
				String genResult = specGenerator.generateSpec(formName);
				if (genResult.startsWith("Error")) return genResult;
			}
			return specRunner.runSpec(formName, false);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in showAndTest tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "generateFormSpec",
		description = "Generates a Playwright test spec file (.spec.js) for a Servoy form. " +
			"Reads the form's .frm file to extract element names, dataSource, and structure, " +
			"then creates a skeleton spec with basic assertions (form loads, fields visible, buttons clickable). " +
			"The spec file is saved alongside the .frm file in the forms/ directory.",
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
}
