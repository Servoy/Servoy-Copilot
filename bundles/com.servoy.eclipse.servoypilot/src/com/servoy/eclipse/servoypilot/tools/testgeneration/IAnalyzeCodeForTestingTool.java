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

import org.eclipse.core.resources.IFile;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.dto.FileStructure;
import com.servoy.eclipse.servoypilot.dto.SymbolInfo;
import com.servoy.eclipse.servoypilot.services.FilePathResolver;
import com.servoy.eclipse.servoypilot.services.FileStructureService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IAnalyzeCodeForTestingTool
{
	@Tool("Analyzes selected code to identify testable units and extract function information. " +
		"Returns function signatures, parameters, return types, and source file name needed for test generation.")
	default String analyzeCodeForTesting(
		@P("Selected code or function name to analyze") String selection)
	{
		try
		{
			if (selection == null || selection.isBlank())
			{
				return "Error: Selection parameter is required";
			}

			FilePathResolver resolver = FilePathResolver.getInstance();
			IFile file = resolver.resolveFile(selection);

			if (file != null && file.exists())
			{
				FileStructureService service = FileStructureService.getInstance();
				FileStructure structure = service.analyzeFile(file);

				long functionCount = structure.getSymbols().stream()
					.filter(s -> s.getType() == SymbolInfo.SymbolType.FUNCTION)
					.count();

				return "**Code Analysis for Testing:**\n\n" +
					"Source file: " + file.getName() + "\n" +
					"Total functions: " + functionCount + "\n\n" +
					structure.toFormattedString();
			}

			String functionName = extractFunctionName(selection);

			if (functionName != null)
			{
				return "**Inline Code Analysis:**\n\n" +
					"Detected function: " + functionName + "\n" +
					"Code snippet:\n```javascript\n" + selection + "\n```\n\n" +
					"Recommendation: Provide full file path for better analysis.";
			}

			return "**Inline Code Analysis:**\n\n" +
				"Code snippet provided (no clear function detected):\n```javascript\n" + selection + "\n```\n\n" +
				"Recommendation: Select a complete function or provide file path for better analysis.";
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error analyzing code for testing", e);
			return "Error: " + e.getMessage();
		}
	}

	private static String extractFunctionName(String code)
	{
		if (code == null) return null;

		int funcIndex = code.indexOf("function ");
		if (funcIndex >= 0)
		{
			int nameStart = funcIndex + 9;
			int nameEnd = code.indexOf("(", nameStart);
			if (nameEnd > nameStart)
			{
				return code.substring(nameStart, nameEnd).trim();
			}
		}

		return null;
	}
}
