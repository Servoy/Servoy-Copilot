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
import com.servoy.eclipse.servoypilot.tools.core.UIThreadHelper;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IAddTestMethodTool
{
	@Tool("Adds a test method to an existing test file. " +
		"Test method name must start with 'test_'. " +
		"Generates proper @properties annotation with UUID automatically.")
	default String addTestMethod(
		@P("Test file name (e.g., 'test_utils.js')") String testFileName,
		@P("Test method name (must start with 'test_', e.g., 'test_calculateTotal_withDiscount')") String testMethodName,
		@P("Complete test function body (without function declaration or @properties)") String testCode)
	{
		return UIThreadHelper.syncExec("addTestMethod", () -> {
			try
			{
				com.servoy.eclipse.model.nature.ServoyProject targetProject = TargetService.getCurrentTargetProject();
				if (targetProject == null)
				{
					return "Error: No active project found. Please open a Servoy solution.";
				}

				String solutionName = targetProject.getProject().getName();
				return TestFileService.getInstance().addTestMethod(testFileName, testMethodName, testCode, solutionName);
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error adding test method: " + testMethodName, e);
				return "Error: " + e.getMessage();
			}
		});
	}
}
