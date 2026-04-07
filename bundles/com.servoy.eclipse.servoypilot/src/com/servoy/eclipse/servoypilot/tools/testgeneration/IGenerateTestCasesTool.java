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

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IGenerateTestCasesTool
{
	@Tool("Suggests test cases for a function based on its signature and logic. " +
		"Returns structured list of test scenarios covering happy path, edge cases, and error conditions. " +
		"Does NOT create files - only provides suggestions for test coverage.")
	default String generateTestCases(
		@P("Source code or function signature to analyze") String sourceCode,
		@P("Function name to generate test cases for") String functionName)
	{
		try
		{
			String[] params = extractParameters(sourceCode, functionName);

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
			ServoyLog.logError("Error generating test cases for: " + functionName, e);
			return "Error: " + e.getMessage();
		}
	}

	private static String[] extractParameters(String code, String functionName)
	{
		if (code == null) return new String[0];

		String searchPattern = "function " + functionName + "(";
		int funcIndex = code.indexOf(searchPattern);

		if (funcIndex >= 0)
		{
			int paramStart = funcIndex + searchPattern.length();
			int paramEnd = code.indexOf(")", paramStart);

			if (paramEnd > paramStart)
			{
				String paramsString = code.substring(paramStart, paramEnd).trim();
				if (!paramsString.isEmpty())
				{
					return paramsString.split(",\\s*");
				}
			}
		}

		return new String[0];
	}
}
