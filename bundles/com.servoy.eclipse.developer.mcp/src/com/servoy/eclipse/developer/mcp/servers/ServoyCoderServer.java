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

import java.util.Optional;

import jakarta.inject.Inject;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.guard.ServoyFileFormatProtectedException;
import com.servoy.eclipse.developer.mcp.guard.ServoyFileGuard;
import com.servoy.eclipse.developer.mcp.services.CodeEditingService;

/**
 * MCP server providing generic file-editing tools for the Servoy Developer MCP endpoint.
 * <p>
 * Endpoint: {@code /svymcp/servoy-coder}
 * </p>
 * <p>
 * Destructive tools ({@code insertIntoFile}, {@code replaceString}, {@code replaceFileContent},
 * {@code deleteLinesInFile}, {@code applyPatch}) call {@link ServoyFileGuard#assertEditable(String)}
 * before performing any write. Servoy structural files ({@code .frm}, {@code .obj}, {@code .tbl},
 * {@code .val}, {@code .rel}, {@code .dbi}) are refused with a JSON-RPC error.
 * </p>
 * <p>
 * {@code undoEdit} is exempt from the guard â it is recovery, not authoring.
 * </p>
 * <p>
 * Excluded (Java/JDT-only): {@code formatFile}, {@code refactorRenameJavaType},
 * {@code refactorMoveJavaType}, {@code refactorRenamePackage}, {@code organizeImports},
 * {@code organizeImportsInPackage}.
 * </p>
 */
@Creatable
@McpServer(name = "servoy-coder")
public class ServoyCoderServer
{
	@Inject
	private CodeEditingService codeEditingService;

	/** Default constructor — required by E4 DI (ContextInjectionFactory.make). */
	public ServoyCoderServer() { }

	/** Testing constructor — initialises services directly without E4 DI. */
	ServoyCoderServer(CodeEditingService codeEditingService)
	{
		this.codeEditingService = codeEditingService;
	}

	@Tool(name = "createFile",
		description = "Create and open a new file in a specified project. Ensure the file doesn't already exist.",
		type = "object")
	public String createFile(
		@ToolParam(name = "projectName", description = "The name of the project where the file should be created", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the file relative to the project root. Do not include project name!", required = true) String filePath,
		@ToolParam(name = "content", description = "The content to write to the file", required = true) String content)
	{
		try
		{
			return codeEditingService.createFile(projectName, filePath, content);
		}
		catch (ServoyFileFormatProtectedException e)
		{
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	@Tool(name = "insertIntoFile",
		description = "Insert content into a file at a specified line position, using 1-based line indexing. "
			+ "The new content will be inserted BEFORE the specified line, and existing content at that line and below will be shifted down.",
		type = "object")
	public String insertIntoFile(
		@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the file relative to the project root. Do not include project name!", required = true) String filePath,
		@ToolParam(name = "content", description = "The content to insert into the file", required = true) String content,
		@ToolParam(name = "line", description = "The line number before which to insert the text (1-based index). Use line=1 to insert at the beginning.", required = false) String line)
	{
		try
		{
			int lineNum = Optional.ofNullable(line).map(Integer::parseInt).orElse(1);
			return codeEditingService.insertIntoFile(projectName, filePath, content, lineNum);
		}
		catch (ServoyFileFormatProtectedException e)
		{
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	@Tool(name = "replaceString",
		description = "Find and replace a specific string in a file, with optional line range for targeted replacement.",
		type = "object")
	public String replaceString(
		@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the file relative to the project root. Do not include project name!", required = true) String filePath,
		@ToolParam(name = "oldString", description = "The text to replace (must match exactly, including whitespace and indentation)", required = true) String oldString,
		@ToolParam(name = "newString", description = "The new text to insert in place of the old text", required = true) String newString,
		@ToolParam(name = "startLine", description = "Optional line number to start searching from (1-based index)", required = false) String startLine,
		@ToolParam(name = "endLine", description = "Optional line number to end searching at (1-based index)", required = false) String endLine)
	{
		try
		{
			Integer startLineNum = Optional.ofNullable(startLine).map(Integer::parseInt).orElse(null);
			Integer endLineNum = Optional.ofNullable(endLine).map(Integer::parseInt).orElse(null);
			return codeEditingService.replaceStringInFile(projectName, filePath, oldString, newString, startLineNum, endLineNum);
		}
		catch (ServoyFileFormatProtectedException e)
		{
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	@Tool(name = "undoEdit",
		description = "Undoes the last edit operation by restoring a file from its backup (Eclipse Local History). "
			+ "This tool is exempt from the Servoy file-format guard â it is recovery, not authoring.",
		type = "object")
	public String undoEdit(
		@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the file relative to the project root. Do not include project name!", required = true) String filePath)
	{
		return codeEditingService.undoEdit(projectName, filePath);
	}

	@Tool(name = "createDirectories",
		description = "Creates a directory structure (recursively) in the specified project.",
		type = "object")
	public String createDirectories(
		@ToolParam(name = "projectName", description = "The name of the project where directories should be created", required = true) String projectName,
		@ToolParam(name = "directoryPath", description = "The path of directories to create, relative to the project root. Do not include project name!", required = true) String directoryPath)
	{
		return codeEditingService.createDirectories(projectName, directoryPath);
	}

	@Tool(name = "renameFile",
		description = "Renames a file in the specified project.",
		type = "object")
	public String renameFile(
		@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the file relative to the project root. Do not include project name!", required = true) String filePath,
		@ToolParam(name = "newFileName", description = "The new name for the file", required = true) String newFileName)
	{
		return codeEditingService.renameFile(projectName, filePath, newFileName);
	}

	@Tool(name = "moveResource",
		description = "Moves a file or folder to a different location within the project.",
		type = "object")
	public String moveResource(
		@ToolParam(name = "projectName", description = "The name of the project containing the resource", required = true) String projectName,
		@ToolParam(name = "sourcePath", description = "The path to the file or folder relative to the project root", required = true) String sourcePath,
		@ToolParam(name = "targetPath", description = "The target directory path relative to the project root where the resource should be moved to", required = true) String targetPath)
	{
		return codeEditingService.moveResource(projectName, sourcePath, targetPath);
	}

	@Tool(name = "deleteFile",
		description = "Deletes a file from the specified project.",
		type = "object")
	public String deleteFile(
		@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the file relative to the project root. Do not include project name!", required = true) String filePath)
	{
		return codeEditingService.deleteFile(projectName, filePath);
	}

	@Tool(name = "replaceFileContent",
		description = "Replaces the entire content of a file with new content.",
		type = "object")
	public String replaceFileContent(
		@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the file relative to the project root. Do not include project name!", required = true) String filePath,
		@ToolParam(name = "content", description = "The new content to write to the file", required = true) String content)
	{
		try
		{
			return codeEditingService.replaceFileContent(projectName, filePath, content);
		}
		catch (ServoyFileFormatProtectedException e)
		{
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	@Tool(name = "deleteLinesInFile",
		description = "Deletes a range of lines in a file, using 1-based line indexing.",
		type = "object")
	public String deleteLinesInFile(
		@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the file relative to the project root. Do not include project name!", required = true) String filePath,
		@ToolParam(name = "startLine", description = "The line number to start deletion from (1-based index)", required = true) String startLine,
		@ToolParam(name = "endLine", description = "The line number to end deletion at (inclusive, 1-based index)", required = true) String endLine)
	{
		try
		{
			int startLineNum = Integer.parseInt(startLine);
			int endLineNum = Integer.parseInt(endLine);
			return codeEditingService.deleteLinesInFile(projectName, filePath, startLineNum, endLineNum);
		}
		catch (ServoyFileFormatProtectedException e)
		{
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	@Tool(name = "applyPatch",
		description = "Applies a unified diff patch to a file. The patch should be in standard unified diff format with @@ hunk headers. "
			+ "Context lines are used for fuzzy matching, so the patch can be applied even if line numbers have shifted. "
			+ "This is more reliable than replaceString for multi-hunk edits.",
		type = "object")
	public String applyPatch(
		@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the file relative to the project root. Do not include project name!", required = true) String filePath,
		@ToolParam(name = "patch", description = "The unified diff content to apply. Should contain @@ hunk headers and lines prefixed with ' ' (context), '-' (remove), or '+' (add). File headers (--- and +++) are optional.", required = true) String patch)
	{
		try
		{
			return codeEditingService.applyPatch(projectName, filePath, patch);
		}
		catch (ServoyFileFormatProtectedException e)
		{
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	// --- Dummy tools (JDT-only in Eclipse IDE, not available in Servoy Developer) ---

	private static final String JDT_NOT_AVAILABLE =
		"Not available in Servoy Developer MCP: this tool requires JDT (Java Development Tools) " +
		"which is not present in the Servoy Developer runtime. Use the Eclipse IDE MCP endpoint instead.";

	@Tool(name = "formatFile",
		description = "Formats an entire Java file using Eclipse's code formatter. NOT AVAILABLE in Servoy Developer — requires JDT.",
		type = "object")
	public String formatFile(
		@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the Java file relative to the project root", required = true) String filePath)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}

	@Tool(name = "refactorRenameJavaType",
		description = "Renames a Java class/interface/enum using Eclipse's refactoring mechanism. NOT AVAILABLE in Servoy Developer — requires JDT.",
		type = "object")
	public String refactorRenameJavaType(
		@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the Java file relative to the project root", required = true) String filePath,
		@ToolParam(name = "newTypeName", description = "The new name for the Java type", required = true) String newTypeName)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}

	@Tool(name = "refactorMoveJavaType",
		description = "Moves a Java class/interface/enum to a different package using Eclipse's refactoring mechanism. NOT AVAILABLE in Servoy Developer — requires JDT.",
		type = "object")
	public String refactorMoveJavaType(
		@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the Java file relative to the project root", required = true) String filePath,
		@ToolParam(name = "targetPackage", description = "The fully qualified target package name", required = true) String targetPackage)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}

	@Tool(name = "refactorRenamePackage",
		description = "Renames a Java package using Eclipse's refactoring mechanism. NOT AVAILABLE in Servoy Developer — requires JDT.",
		type = "object")
	public String refactorRenamePackage(
		@ToolParam(name = "projectName", description = "The name of the project containing the package", required = true) String projectName,
		@ToolParam(name = "packageName", description = "The current fully qualified package name", required = true) String packageName,
		@ToolParam(name = "newPackageName", description = "The new package name", required = true) String newPackageName)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}

	@Tool(name = "organizeImports",
		description = "Organizes imports in a Java file using Eclipse's organize imports mechanism. NOT AVAILABLE in Servoy Developer — requires JDT.",
		type = "object")
	public String organizeImports(
		@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the Java file relative to the project root", required = true) String filePath)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}

	@Tool(name = "organizeImportsInPackage",
		description = "Organizes imports in all Java files within a package. NOT AVAILABLE in Servoy Developer — requires JDT.",
		type = "object")
	public String organizeImportsInPackage(
		@ToolParam(name = "projectName", description = "The name of the project containing the package", required = true) String projectName,
		@ToolParam(name = "packageName", description = "The fully qualified package name", required = true) String packageName)
	{
		throw new RuntimeException(JDT_NOT_AVAILABLE);
	}
}
