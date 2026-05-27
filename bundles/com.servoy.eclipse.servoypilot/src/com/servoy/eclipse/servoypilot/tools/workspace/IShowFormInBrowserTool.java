package com.servoy.eclipse.servoypilot.tools.workspace;

import com.servoy.eclipse.developer.mcp.services.FormPreviewService;
import com.servoy.eclipse.model.util.ServoyLog;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IShowFormInBrowserTool
{
	@Tool("Opens a specific Servoy form in an external browser for preview/testing. " +
		"Bypasses authentication and shows the form directly without requiring login. " +
		"Use this to visually inspect a form or to prepare for running Playwright tests against it. " +
		"Returns the URL that was opened.")
	default String showFormInBrowser(
		@P("The name of the form to show in the browser (e.g. 'mainForm', 'orderDetails')") String formName)
	{
		try
		{
			return new FormPreviewService().showFormInBrowser(formName);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in showFormInBrowser tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool("Takes a screenshot of a specific Servoy form rendered in a headless browser. " +
		"Bypasses authentication and captures the form as it appears at runtime. " +
		"Returns the file path of the saved screenshot PNG. " +
		"Use this to visually verify form layout, check element positioning, or capture the current state of a form.")
	default String screenshotForm(
		@P("The name of the form to screenshot (e.g. 'mainForm', 'orderDetails')") String formName,
		@P("How many seconds to wait for the form to fully render before taking the screenshot. Use 5 for simple forms, 10 for complex ones.") int waitSeconds)
	{
		try
		{
			return new FormPreviewService().screenshotForm(formName, waitSeconds);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in screenshotForm tool", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool("Checks whether the NG client is currently running and returns the base URL if available. " +
		"Use this before running form tests to verify the runtime is ready.")
	default String checkNGClientStatus()
	{
		try
		{
			return new FormPreviewService().checkNGClientStatus();
		}
		catch (Exception e)
		{
			return "Error checking NG client status: " + e.getMessage();
		}
	}
}
