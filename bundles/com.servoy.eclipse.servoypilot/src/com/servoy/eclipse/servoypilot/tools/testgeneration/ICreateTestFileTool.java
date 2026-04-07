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
import com.servoy.eclipse.servoypilot.services.TargetService;
import com.servoy.eclipse.servoypilot.services.TestFileService;
import com.servoy.eclipse.servoypilot.tools.utility.UIThreadHelper;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface ICreateTestFileTool
{
	@Tool("Creates a new test file (JavaScript scope) in the solution root directory. " +
		"File name must follow convention: test_functionName.js or test_fileName.js")
	default String createTestFile(
		@P("Test file name (e.g., 'test_utils.js' or 'test_calculateTotal.js')") String testFileName,
		@P("Solution name (use TARGET to get current target, or provide specific solution name)") String solutionName)
	{
		return UIThreadHelper.syncExec("createTestFile", () -> {
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
					com.servoy.eclipse.model.nature.ServoyProject targetProject = TargetService.getCurrentTargetProject();
					if (targetProject != null)
					{
						actualSolutionName = targetProject.getProject().getName();
					}
					else
					{
						return "Error: No active project found. Please open a Servoy solution.";
					}
				}

				return TestFileService.getInstance().createTestFile(testFileName, actualSolutionName);
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error creating test file: " + testFileName, e);
				return "Error: " + e.getMessage();
			}
		});
	}
}
