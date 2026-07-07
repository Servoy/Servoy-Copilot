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

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.eclipse.dltk.compiler.problem.DefaultProblem;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.services.CodeEditingService;
import com.servoy.eclipse.developer.mcp.services.JsCodeValidatorService;
import com.servoy.eclipse.developer.mcp.services.ServoySolutionService;

/**
 * MCP server providing generic file-editing tools for the Servoy Developer MCP endpoint.
 * <p>
 * Endpoint: {@code /mcp/servoy-coder}
 * </p>
 * <p>
 * Excluded (Java/JDT-only): {@code formatFile}, {@code refactorRenameJavaType},
 * {@code refactorMoveJavaType}, {@code refactorRenamePackage}, {@code organizeImports},
 * {@code organizeImportsInPackage}.
 * </p>
 * <p>
 * Post-write behaviour for {@code .js} files:
 * <ul>
 *   <li>SVY-21203: {@code createFile} rejects paths whose parent directory is named
 *       {@code scopes} (case-insensitive) — Servoy scope files must live at the module root.</li>
 *   <li>SVY-21113: all write tools validate the resulting JS content via
 *       {@link JsCodeValidatorService} and append any syntax problems to the response.</li>
 * </ul>
 * </p>
 */
@Creatable
@McpServer(name = "servoy-coder")
public class ServoyCoderServer
{
	@Inject
	private CodeEditingService codeEditingService;

	private final ServoySolutionService solutionService = new ServoySolutionService();
	private final JsCodeValidatorService jsValidator = new JsCodeValidatorService();

	/** Default constructor - required by E4 DI (ContextInjectionFactory.make). */
	public ServoyCoderServer() { }

	/** Testing constructor - initialises services directly without E4 DI. */
	public ServoyCoderServer(CodeEditingService codeEditingService)
	{
		this.codeEditingService = codeEditingService;
	}

	@Tool(name = "createFile",
		description = "Create and open a new file in a specified project. Ensure the file doesn't already exist. "
			+ "IMPORTANT for Servoy scope files: scope .js files must be placed directly at the solution/module root "
			+ "(e.g. 'globals.js'), never inside a 'scopes/' subdirectory.",
		type = "object")
	public String createFile(
		@ToolParam(name = "projectName", description = "The name of the project where the file should be created", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the file relative to the project root. Do not include project name!", required = true) String filePath,
		@ToolParam(name = "content", description = "The content to write to the file", required = true) String content)
	{
		String guard = guardScopePath(filePath);
		if (guard != null) return guard;
		String result = codeEditingService.createFile(projectName, filePath, content);
		return appendJsValidation(result, filePath, content);
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
		int lineNum = Optional.ofNullable(line).map(Integer::parseInt).orElse(1);
		String result = codeEditingService.insertIntoFile(projectName, filePath, content, lineNum);
		return appendJsValidation(result, filePath, codeEditingService.readFileContent(projectName, filePath));
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
		Integer startLineNum = Optional.ofNullable(startLine).map(Integer::parseInt).orElse(null);
		Integer endLineNum = Optional.ofNullable(endLine).map(Integer::parseInt).orElse(null);
		String result = codeEditingService.replaceStringInFile(projectName, filePath, oldString, newString, startLineNum, endLineNum);
		return appendJsValidation(result, filePath, codeEditingService.readFileContent(projectName, filePath));
	}

	@Tool(name = "undoEdit",
		description = "Undoes the last edit operation by restoring a file from its backup (Eclipse Local History).",
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
		description = "Deletes a file from the specified project. "
			+ "If the path points to a Servoy artifact in the active solution or its modules, "
			+ "the deletion is routed through the Servoy repository so referential consistency is preserved: "
			+ "'forms/<name>.frm' deletes the form (and its '.js' script companion), "
			+ "'relations/<name>.rel' deletes the relation, "
			+ "'valuelists/<name>.val' deletes the valuelist. "
			+ "Deleting a form's '.js' file directly is rejected; delete the matching '.frm' instead. "
			+ "For all other paths the file is removed as a plain workspace resource.",
		type = "object")
	public String deleteFile(
		@ToolParam(name = "projectName", description = "The name of the project containing the file. Ignored when the path targets a Servoy artifact (.frm/.rel/.val); the active solution + modules are used.", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the file relative to the project root. Do not include project name!", required = true) String filePath)
	{
		if (filePath == null || filePath.isBlank())
			return "Error: filePath parameter is required";

		String normalized = filePath.replace('\\', '/').replaceFirst("^/+", "");
		String lower = normalized.toLowerCase();

		// Reject orphaning a form's .js companion
		if (lower.endsWith(".js") && lower.startsWith("forms/"))
		{
			String base = normalized.substring("forms/".length(), normalized.length() - ".js".length());
			return "Error: '" + filePath + "' is the script companion of form '" + base
				+ "'. Delete 'forms/" + base + ".frm' instead to remove both files together.";
		}

		if (lower.endsWith(".frm"))
			return solutionService.deleteForms(java.util.List.of(stripExtension(normalized, ".frm")));
		if (lower.endsWith(".rel"))
			return solutionService.deleteRelations(java.util.List.of(stripExtension(normalized, ".rel")));
		if (lower.endsWith(".val"))
			return solutionService.deleteValueLists(java.util.List.of(stripExtension(normalized, ".val")));

		return codeEditingService.deleteFile(projectName, filePath);
	}

	private static String stripExtension(String path, String ext)
	{
		int slash = path.lastIndexOf('/');
		String file = slash < 0 ? path : path.substring(slash + 1);
		return file.substring(0, file.length() - ext.length());
	}

	@Tool(name = "replaceFileContent",
		description = "Replaces the entire content of a file with new content.",
		type = "object")
	public String replaceFileContent(
		@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
		@ToolParam(name = "filePath", description = "The path to the file relative to the project root. Do not include project name!", required = true) String filePath,
		@ToolParam(name = "content", description = "The new content to write to the file", required = true) String content)
	{
		String result = codeEditingService.replaceFileContent(projectName, filePath, content);
		return appendJsValidation(result, filePath, content);
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
		int startLineNum = Integer.parseInt(startLine);
		int endLineNum = Integer.parseInt(endLine);
		String result = codeEditingService.deleteLinesInFile(projectName, filePath, startLineNum, endLineNum);
		return appendJsValidation(result, filePath, codeEditingService.readFileContent(projectName, filePath));
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
		String result = codeEditingService.applyPatch(projectName, filePath, patch);
		return appendJsValidation(result, filePath, codeEditingService.readFileContent(projectName, filePath));
	}

	// --- Helpers ---

	/**
	 * Guards against creating a Servoy scope file inside a {@code scopes/} subdirectory.
	 * <p>
	 * In Servoy, scope {@code .js} files must always sit at the module/solution root.
	 * There is no {@code scopes/} directory. This check catches common AI mistakes where
	 * the agent creates e.g. {@code Scopes/globals.js} instead of {@code globals.js}.
	 * </p>
	 *
	 * @param filePath the path supplied to {@code createFile}
	 * @return an {@code Error:} message if the path is invalid, or {@code null} if acceptable
	 */
	static String guardScopePath(String filePath)
	{
		if (filePath == null) return null;
		String normalized = filePath.replace('\\', '/').replaceFirst("^/+", "");
		if (!normalized.toLowerCase().endsWith(".js")) return null;

		int lastSlash = normalized.lastIndexOf('/');
		if (lastSlash < 0) return null; // file is at root — correct

		String parentPath = normalized.substring(0, lastSlash);
		for (String segment : parentPath.split("/"))
		{
			if ("scopes".equalsIgnoreCase(segment))
			{
				String fileName = normalized.substring(lastSlash + 1);
				return "Error: Servoy scope files must be placed directly at the solution/module root, "
					+ "not inside a 'scopes/' subdirectory. "
					+ "Use '" + fileName + "' (at the project root) instead of '" + normalized + "'.";
			}
		}
		return null;
	}

	/**
	 * Validates {@code content} as JavaScript (via DLTK) and appends any syntax problems
	 * to {@code result}. The write is never blocked — warnings are informational so the
	 * agent can self-correct.
	 *
	 * @param result   the tool response string produced by the write operation
	 * @param filePath path of the file that was written (used to decide whether to validate)
	 * @param content  the current full file content to validate; may be {@code null}
	 * @return {@code result} unchanged if there are no problems or the file is not a
	 *         {@code .js} file; otherwise {@code result} with problems appended
	 */
	private String appendJsValidation(String result, String filePath, String content)
	{
		if (result != null && result.startsWith("Error:")) return result;
		if (filePath == null || content == null) return result;
		if (!filePath.toLowerCase().endsWith(".js")) return result;

		List<DefaultProblem> problems = jsValidator.validate(content);
		if (problems.isEmpty()) return result;

		StringBuilder sb = new StringBuilder(result != null ? result : "");
		sb.append("\n\nJavaScript syntax issues detected:");
		for (DefaultProblem p : problems)
			sb.append("\n  Line ").append(p.getSourceLineNumber()).append(": ").append(p.getMessage());
		return sb.toString();
	}
}
