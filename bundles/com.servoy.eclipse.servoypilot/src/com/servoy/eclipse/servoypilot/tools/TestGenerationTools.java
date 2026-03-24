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
package com.servoy.eclipse.servoypilot.tools;

import org.eclipse.core.resources.IFile;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.services.FilePathResolver;
import com.servoy.eclipse.servoypilot.services.FileStructureService;
import com.servoy.eclipse.servoypilot.services.TargetService;
import com.servoy.eclipse.servoypilot.services.TestFileService;
import com.servoy.eclipse.servoypilot.services.dto.FileStructure;
import com.servoy.eclipse.servoypilot.tools.utility.UIThreadHelper;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tools for JSUnit test generation in Servoy.
 * Provides test file creation, test method addition, and test case suggestions.
 */
public class TestGenerationTools
{
	@Tool("Analyzes selected code to identify testable units and extract function information. " +
		"Returns function signatures, parameters, return types, and source file name needed for test generation.")
	public String analyzeCodeForTesting(
		@P("Selected code or function name to analyze") String selection)
	{
		System.out.println("\n=== TestGenerationTools.analyzeCodeForTesting() called ===");
		System.out.println("Selection parameter: '" + selection + "'");
		
		try
		{
			if (selection == null || selection.isBlank())
			{
				System.out.println("Error: Empty selection provided");
				return "Error: Selection parameter is required";
			}

			// Try to resolve as file path first
			FilePathResolver resolver = FilePathResolver.getInstance();
			IFile file = resolver.resolveFile(selection);

			if (file != null && file.exists())
			{
				System.out.println("Selection resolved as file: " + file.getFullPath());
				
				// Analyze entire file structure
				FileStructureService service = FileStructureService.getInstance();
				FileStructure structure = service.analyzeFile(file);

			// Count functions only (filter out variables)
			long functionCount = structure.getSymbols().stream()
				.filter(s -> s.getType() == com.servoy.eclipse.servoypilot.services.dto.SymbolInfo.SymbolType.FUNCTION)
				.count();

			String result = "**Code Analysis for Testing:**\n\n" +
				"Source file: " + file.getName() + "\n" +
				"Total functions: " + functionCount + "\n\n" +
				structure.toFormattedString();
			
			System.out.println("Analysis complete - returning structure with " + functionCount + " functions");
				System.out.println("=== End TestGenerationTools.analyzeCodeForTesting() ===\n");
				return result;
			}
			
			// If not a file, treat as inline code selection
			System.out.println("Selection is inline code, analyzing...");
			
			// Extract function name from selection if possible
			String functionName = extractFunctionName(selection);
			
			if (functionName != null)
			{
				String result = "**Inline Code Analysis:**\n\n" +
					"Detected function: " + functionName + "\n" +
					"Code snippet:\n```javascript\n" + selection + "\n```\n\n" +
					"Recommendation: Provide full file path for better analysis.";
				
				System.out.println("Detected function: " + functionName);
				System.out.println("=== End TestGenerationTools.analyzeCodeForTesting() ===\n");
				return result;
			}
			
			String result = "**Inline Code Analysis:**\n\n" +
				"Code snippet provided (no clear function detected):\n```javascript\n" + selection + "\n```\n\n" +
				"Recommendation: Select a complete function or provide file path for better analysis.";
			
			System.out.println("No clear function detected in selection");
			System.out.println("=== End TestGenerationTools.analyzeCodeForTesting() ===\n");
			return result;
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error analyzing code for testing", e);
			System.out.println("EXCEPTION: " + e.getMessage());
			System.out.println("=== End TestGenerationTools.analyzeCodeForTesting() ===\n");
			return "Error: " + e.getMessage();
		}
	}

	@Tool("Creates a new test file (JavaScript scope) in the solution root directory. " +
		"File name must follow convention: test_functionName.js or test_fileName.js")
	public String createTestFile(
		@P("Test file name (e.g., 'test_utils.js' or 'test_calculateTotal.js')") String testFileName,
		@P("Solution name (use TARGET to get current target, or provide specific solution name)") String solutionName)
	{
		return UIThreadHelper.syncExec("createTestFile", () -> {
			System.out.println("\n=== TestGenerationTools.createTestFile() called ===");
			System.out.println("Test file name: '" + testFileName + "'");
			System.out.println("Solution name: '" + solutionName + "'");

			try
			{
				// Validate test file name
				if (!testFileName.startsWith("test_"))
				{
					System.out.println("Error: File name doesn't start with 'test_'");
					return "Error: Test file name must start with 'test_' (e.g., 'test_utils.js')";
				}

				if (!testFileName.endsWith(".js"))
				{
					System.out.println("Error: File name doesn't end with '.js'");
					return "Error: Test file name must end with '.js' (e.g., 'test_utils.js')";
				}

				// Get actual solution name from TARGET if needed
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
						System.out.println("Error: No active project found");
						return "Error: No active project found. Please open a Servoy solution.";
					}
					System.out.println("Resolved TARGET to: " + actualSolutionName);
				}

				// Create test file using service
				TestFileService service = TestFileService.getInstance();
				String result = service.createTestFile(testFileName, actualSolutionName);

				System.out.println("Result: " + result);
				System.out.println("=== End TestGenerationTools.createTestFile() ===\n");
				return result;
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error creating test file: " + testFileName, e);
				System.out.println("EXCEPTION: " + e.getMessage());
				System.out.println("=== End TestGenerationTools.createTestFile() ===\n");
				return "Error: " + e.getMessage();
			}
		});
	}

	@Tool("Adds a test method to an existing test file. " +
		"Test method name must start with 'test_'. " +
		"Generates proper @properties annotation with UUID automatically.")
	public String addTestMethod(
		@P("Test file name (e.g., 'test_utils.js')") String testFileName,
		@P("Test method name (must start with 'test_', e.g., 'test_calculateTotal_withDiscount')") String testMethodName,
		@P("Complete test function body (without function declaration or @properties)") String testCode)
	{
		return UIThreadHelper.syncExec("addTestMethod", () -> {
			System.out.println("\n=== TestGenerationTools.addTestMethod() called ===");
			System.out.println("Test file: '" + testFileName + "'");
			System.out.println("Test method: '" + testMethodName + "'");

			try
			{
				// Get current target solution
				com.servoy.eclipse.model.nature.ServoyProject targetProject = TargetService.getCurrentTargetProject();
				if (targetProject == null)
				{
					System.out.println("Error: No active project found");
					return "Error: No active project found. Please open a Servoy solution.";
				}
				String solutionName = targetProject.getProject().getName();
				System.out.println("Current solution: " + solutionName);

				// Add test method using service
				TestFileService service = TestFileService.getInstance();
				String result = service.addTestMethod(testFileName, testMethodName, testCode, solutionName);

				System.out.println("Result: " + result);
				System.out.println("=== End TestGenerationTools.addTestMethod() ===\n");
				return result;
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error adding test method: " + testMethodName, e);
				System.out.println("EXCEPTION: " + e.getMessage());
				System.out.println("=== End TestGenerationTools.addTestMethod() ===\n");
				return "Error: " + e.getMessage();
			}
		});
	}

	@Tool("Suggests test cases for a function based on its signature and logic. " +
		"Returns structured list of test scenarios covering happy path, edge cases, and error conditions. " +
		"Does NOT create files - only provides suggestions for test coverage.")
	public String generateTestCases(
		@P("Source code or function signature to analyze") String sourceCode,
		@P("Function name to generate test cases for") String functionName)
	{
		System.out.println("\n=== TestGenerationTools.generateTestCases() called ===");
		System.out.println("Function name: '" + functionName + "'");

		try
		{
			// Analyze the function signature to extract parameters
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

			System.out.println("Generated " + (4 + (params != null && params.length > 0 ? 3 : 0)) + " test case suggestions");
			System.out.println("=== End TestGenerationTools.generateTestCases() ===\n");
			return suggestions.toString();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error generating test cases for: " + functionName, e);
			System.out.println("EXCEPTION: " + e.getMessage());
			System.out.println("=== End TestGenerationTools.generateTestCases() ===\n");
			return "Error: " + e.getMessage();
		}
	}

	// ========== Helper Methods ==========

	/**
	 * Extracts function name from code selection.
	 */
	private String extractFunctionName(String code)
	{
		if (code == null)
		{
			return null;
		}

		// Look for "function functionName(" pattern
		int funcIndex = code.indexOf("function ");
		if (funcIndex >= 0)
		{
			int nameStart = funcIndex + 9; // "function ".length()
			int nameEnd = code.indexOf("(", nameStart);
			if (nameEnd > nameStart)
			{
				return code.substring(nameStart, nameEnd).trim();
			}
		}

		return null;
	}

	/**
	 * Extracts parameter names from function signature.
	 */
	private String[] extractParameters(String code, String functionName)
	{
		if (code == null)
		{
			return new String[0];
		}

		// Look for function declaration
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
