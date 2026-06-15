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
package com.servoy.eclipse.developer.mcp.services;

import java.util.UUID;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Service for creating and managing JSUnit test files in Servoy solutions.
 * Test files are JavaScript scopes created in the solution root directory.
 */
public class TestFileService
{
	private static final TestFileService INSTANCE = new TestFileService();

	private TestFileService()
	{
	}

	public static TestFileService getInstance()
	{
		return INSTANCE;
	}

	/**
	 * Resolves the IProject for the given solution name.
	 * <p>
	 * First queries the Servoy model (finds Servoy-natured projects that are part of
	 * an active solution). If the Servoy model does not know about the name (e.g. in
	 * a test environment where the project has no Servoy nature), falls back to a plain
	 * Eclipse workspace project lookup by project name.
	 *
	 * @param solutionName Solution or project name; may be null
	 * @return IProject that is open and exists, or {@code null} if not found
	 */
	private IProject findProject(String solutionName)
	{
		if (solutionName == null) return null;
		ServoyProject sp = ServoyModelManager.getServoyModelManager().getServoyModel().getServoyProject(solutionName);
		if (sp != null) return sp.getProject();
		// Fallback: plain workspace project (no Servoy nature required - used in test environments)
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(solutionName);
		if (project.exists() && project.isOpen()) return project;
		return null;
	}

	/**
	 * Creates a new test file (JavaScript scope) in the solution root directory.
	 * 
	 * @param testFileName Name of test file (e.g., "test_utils.js")
	 * @param solutionName Name of target solution
	 * @return Path to created file, or error message
	 */
	public String createTestFile(String testFileName, String solutionName)
	{
		try
		{
			IProject project = findProject(solutionName);

			if (project == null)
			{
				return "Error: Solution '" + solutionName + "' not found";
			}

			IFile testFile = project.getFile(testFileName);

			if (testFile.exists())
			{
				return "Error: Test file '" + testFileName + "' already exists";
			}

			// Create basic JavaScript file content
			String fileContent = generateTestFileHeader();

			testFile.create(new java.io.ByteArrayInputStream(fileContent.getBytes("UTF-8")), true, new NullProgressMonitor());

			ServoyLog.logInfo("[TestFileService] Created test file: " + testFileName + " in solution: " + solutionName);

			return "✓ Created test file: " + testFileName + " at " + testFile.getFullPath().toString();
		}
		catch (Exception e)
		{
			ServoyLog.logError("[TestFileService] Error creating test file: " + testFileName, e);
			return "Error creating test file: " + e.getMessage();
		}
	}

	/**
	 * Adds a test method to an existing test file.
	 * 
	 * @param testFileName Name of test file
	 * @param testMethodName Name of test method (must start with "test_")
	 * @param testCode Complete test function code
	 * @param solutionName Target solution name
	 * @return Success message or error
	 */
	public String addTestMethod(String testFileName, String testMethodName, String testCode, String solutionName)
	{
		try
		{
			if (!testMethodName.startsWith("test_"))
			{
				return "Error: Test method name must start with 'test_' (got: " + testMethodName + ")";
			}

			IProject project = findProject(solutionName);

			if (project == null)
			{
				return "Error: Solution '" + solutionName + "' not found";
			}

			IFile testFile = project.getFile(testFileName);

			if (!testFile.exists())
			{
				return "Error: Test file '" + testFileName + "' does not exist. Create it first using createTestFile.";
			}

			// Read existing content
			String existingContent = new String(testFile.getContents().readAllBytes(), "UTF-8");

			// Determine whether the method already exists (for the return message)
			boolean existed = existingContent.contains("function " + testMethodName + "(");

			// Remove all existing occurrences of the method (handles duplicates too)
			String stripped = removeAllOccurrences(existingContent, testMethodName);

			// Normalise trailing blank lines: keep exactly one trailing newline
			while (stripped.endsWith("\n\n"))
			{
				stripped = stripped.substring(0, stripped.length() - 1);
			}
			if (!stripped.endsWith("\n"))
			{
				stripped += "\n";
			}

			// Generate new method block and append
			String methodCode = generateTestMethod(testMethodName, testCode);
			String newContent = stripped + "\n" + methodCode;

			// Write updated content
			testFile.setContents(new java.io.ByteArrayInputStream(newContent.getBytes("UTF-8")), true, true, new NullProgressMonitor());

			String actionWord = existed ? "Updated" : "Added";
			ServoyLog.logInfo("[TestFileService] " + actionWord + " test method: " + testMethodName + " in file: " + testFileName);

			return "✓ " + actionWord + " test method: " + testMethodName + "() in " + testFileName;
		}
		catch (Exception e)
		{
			ServoyLog.logError("[TestFileService] Error adding test method: " + testMethodName, e);
			return "Error adding test method: " + e.getMessage();
		}
	}

	/**
	 * Generates a unique UUID in Servoy format (uppercase with hyphens).
	 * 
	 * @return UUID string
	 */
	private String generateUUID()
	{
		return UUID.randomUUID().toString().toUpperCase();
	}

	/**
	 * Generates test file header comment.
	 * 
	 * @return Header string
	 */
	private String generateTestFileHeader()
	{
		return "/**\n" +
			" * JSUnit test file\n" +
			" * Generated by Servoy Unit Test Assistant\n" +
			" */\n\n";
	}

	/**
	 * Generates a test method with proper @properties annotation.
	 * 
	 * @param methodName Test method name
	 * @param testCode Test function body (or complete function - will be extracted)
	 * @return Complete method declaration
	 */
	private String generateTestMethod(String methodName, String testCode)
	{
		String uuid = generateUUID();

		// Defensive: Extract body if AI passed complete function declaration
		String bodyOnly = extractFunctionBody(testCode, methodName);

		StringBuilder sb = new StringBuilder();
		sb.append("/**\n");
		sb.append(" * @properties={typeid:24,uuid:\"").append(uuid).append("\"}\n");
		sb.append(" */\n");
		sb.append("function ").append(methodName).append("() {\n");
		sb.append(bodyOnly);
		if (!bodyOnly.endsWith("\n"))
		{
			sb.append("\n");
		}
		sb.append("}\n");

		return sb.toString();
	}

	/**
	 * Extracts function body from testCode parameter.
	 * Defensive method to handle cases where AI passes complete function declaration
	 * instead of just the body.
	 * 
	 * @param testCode Either function body only, or complete function declaration
	 * @param methodName Expected method name (for validation)
	 * @return Function body only
	 */
	private String extractFunctionBody(String testCode, String methodName)
	{
		if (testCode == null || testCode.trim().isEmpty())
		{
			return testCode;
		}

		String trimmed = testCode.trim();

		// Check if testCode starts with function declaration
		// Pattern: "function methodName() {" or "function methodName(){"
		if (trimmed.startsWith("function "))
		{
			// Find the opening brace
			int openBraceIndex = trimmed.indexOf('{');
			if (openBraceIndex == -1)
			{
				// No opening brace found, return as-is (malformed, but let it fail naturally)
				return testCode;
			}

			// Find the matching closing brace (last occurrence, assuming balanced braces)
			int closeBraceIndex = trimmed.lastIndexOf('}');
			if (closeBraceIndex == -1 || closeBraceIndex <= openBraceIndex)
			{
				// No closing brace found or invalid position, return as-is
				return testCode;
			}

			// Extract content between braces
			String body = trimmed.substring(openBraceIndex + 1, closeBraceIndex);

			// Preserve original indentation style
			return body;
		}

		// testCode is already body-only, return as-is
		return testCode;
	}

	/**
	 * Removes all occurrences of a named function (and its immediately preceding JSDoc block)
	 * from the given content. Processes from last to first so index positions stay valid
	 * throughout the loop.
	 *
	 * @param content    Full file content
	 * @param methodName Function name to remove (without "function " prefix)
	 * @return Content with all occurrences removed
	 */
	private String removeAllOccurrences(String content, String methodName)
	{
		String functionDecl = "function " + methodName + "(";
		String result = content;
		int searchFrom = result.length();

		while (true)
		{
			int funcIndex = result.lastIndexOf(functionDecl, searchFrom);
			if (funcIndex == -1) break;

			// Locate start of the preceding JSDoc block (/** … */) if it is adjacent
			int blockStart = funcIndex;
			int jsdocEnd = result.lastIndexOf("*/", funcIndex);
			if (jsdocEnd != -1)
			{
				int jsdocStart = result.lastIndexOf("/**", jsdocEnd);
				if (jsdocStart != -1)
				{
					// Only claim the jsdoc if there is nothing but whitespace between it and the function
					String between = result.substring(jsdocEnd + 2, funcIndex);
					if (between.trim().isEmpty())
					{
						blockStart = jsdocStart;
					}
				}
			}

			// Find the opening brace of the function body
			int openBrace = result.indexOf('{', funcIndex + functionDecl.length());
			if (openBrace == -1) break; // malformed - stop to avoid infinite loop

			// Walk braces to find the matching closing brace
			int depth = 0;
			int closeIndex = -1;
			for (int i = openBrace; i < result.length(); i++)
			{
				char c = result.charAt(i);
				if (c == '{') depth++;
				else if (c == '}')
				{
					depth--;
					if (depth == 0)
					{
						closeIndex = i;
						break;
					}
				}
			}
			if (closeIndex == -1) break; // malformed - stop

			// Consume one trailing newline after the closing brace
			int blockEnd = closeIndex + 1;
			if (blockEnd < result.length() && result.charAt(blockEnd) == '\n')
			{
				blockEnd++;
			}

			result = result.substring(0, blockStart) + result.substring(blockEnd);

			// Next search must stay before the position we just removed
			searchFrom = blockStart - 1;
			if (searchFrom < 0) break;
		}

		return result;
	}

	/**
	 * Checks if a test file exists.
	 * 
	 * @param testFileName Test file name
	 * @param solutionName Solution name
	 * @return true if file exists
	 */
	public boolean testFileExists(String testFileName, String solutionName)
	{
		try
		{
			ServoyProject servoyProject = ServoyModelManager.getServoyModelManager().getServoyModel().getServoyProject(solutionName);

			if (servoyProject == null)
			{
				return false;
			}

			IProject project = servoyProject.getProject();
			IFile testFile = project.getFile(testFileName);

			return testFile.exists();
		}
		catch (Exception e)
		{
			ServoyLog.logError("[TestFileService] Error checking test file existence: " + testFileName, e);
			return false;
		}
	}
}
