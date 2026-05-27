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
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 */
package com.servoy.eclipse.servoypilot.tools.testgeneration;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.developer.mcp.services.JSUnitRunnerService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tool that triggers JSUnit test execution in the active Servoy solution and returns pass/fail results.
 */
public interface IRunJsUnitTestsTool
{
	@Tool("Runs JSUnit tests for the active Servoy solution and returns pass/fail results with failure traces. " +
		"Use this to verify tests pass after creating or modifying test files, or to identify which tests are currently failing. " +
		"Returns a markdown summary with counts and detailed failure/error traces.")
	default String runJsUnitTests(
		@P("What to test: a scope/file name (e.g. 'test_utils' or 'test_utils.js'), a form name (e.g. 'tab1' or 'forms/tab1.js'), a module name (e.g. 'calculations_module'), 'MODULES' to run all tests across every module of the active solution, or 'ALL' to run every test in the solution (including all modules)") String scopeOrAll,
		@P("Maximum seconds to wait for the test run to complete. Use 60 for a single scope or form, 120 for a full solution run.") int timeoutSeconds)
	{
		try
		{
			return new JSUnitRunnerService().runTests(scopeOrAll, timeoutSeconds);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error running JSUnit tests", e);
			return "Error: " + e.getMessage();
		}
	}
}
