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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.chatview.parts.FileModificationTracker;
import com.servoy.eclipse.servoypilot.context.SelectionTracker;
import com.servoy.eclipse.servoypilot.context.dto.CodeContext;
import com.servoy.eclipse.servoypilot.context.dto.SelectionInfo;
import com.servoy.eclipse.servoypilot.services.CodeContextService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * AI tools for documentation generation workflow.
 * 
 * Provides tools to:
 * 1. Retrieve current editor selection with code and API documentation
 * 2. Apply generated JSDoc documentation back to the file
 */
public class DocumentationTools
{
	@Tool("Get the current editor selection (or entire file if no selection) with code and API documentation context")
	public String getCurrentSelection()
	{
		try
		{
			// Get current selection from tracker
			SelectionTracker tracker = SelectionTracker.getInstance();
			Optional<SelectionInfo> selectionOpt = tracker.getCurrentSelection();

			if (!selectionOpt.isPresent())
			{
				return "Error: No active editor or selection available";
			}

			SelectionInfo selection = selectionOpt.get();

			// Get code context (identifiers + API documentation)
			CodeContextService contextService = CodeContextService.getInstance();
			CodeContext context = contextService.getCodeContext(selection);

			if (context.hasError())
			{
				return "Error extracting context: " + context.getErrorMessage();
			}

			// Get code text
			String codeText = contextService.getCodeText(selection);

			// Convert file path to workspace-relative
			String workspacePath = convertToWorkspacePath(selection.getFilePath());
			if (workspacePath == null)
			{
				return "Error: Could not convert file path to workspace-relative format";
			}

			// Build response with all information
			StringBuilder response = new StringBuilder();
			response.append("FILE: ").append(workspacePath).append("\n");
			response.append("OFFSET: ").append(selection.getOffset()).append("\n");
			response.append("LENGTH: ").append(selection.getLength()).append("\n");
			response.append("\n--- CODE ---\n");
			response.append(codeText);
			response.append("\n--- END CODE ---\n\n");

			// Add API documentation context if available
			String xmlContext = context.getFormattedXML();
			if (xmlContext != null && !xmlContext.trim().isEmpty())
			{
				response.append("--- API DOCUMENTATION ---\n");
				response.append(xmlContext);
				response.append("\n--- END API DOCUMENTATION ---\n");
			}

			return response.toString();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error getting current selection", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Convert absolute file path to workspace-relative path
	 */
	private String convertToWorkspacePath(String absolutePath)
	{
		if (absolutePath != null)
		{
			// Check if already workspace-relative
			if (absolutePath.startsWith("/") && !absolutePath.startsWith("//"))
			{
				IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(absolutePath));
				if (file != null && file.exists())
				{
					return absolutePath;
				}
			}

			// Try converting from absolute path
			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(new Path(absolutePath));
			if (file != null)
			{
				return file.getFullPath().toString();
			}
		}
		return null;
	}

	@Tool("Apply generated JSDoc documentation to the current selection or file")
	public String applyDocumentation(
		@P("Workspace-relative file path (e.g., /ProjectName/forms/myForm.js)") String filePath,
		@P("Selection start offset (0 for full file)") int selectionOffset,
		@P("Selection length (file length for full file)") int selectionLength,
		@P("Modified content with JSDoc documentation") String modifiedContent)
	{
		if (filePath == null || filePath.trim().isEmpty())
		{
			return "Error: File path is required";
		}

		if (selectionOffset < 0 || selectionLength < 0)
		{
			return "Error: Invalid selection range (offset=" + selectionOffset + ", length=" + selectionLength + ")";
		}

		if (modifiedContent == null)
		{
			return "Error: Modified content is required";
		}

		try
		{
			// Get file from workspace
			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(filePath));
			if (!file.exists())
			{
				return "Error: File does not exist: " + filePath;
			}

			// Read current content
			String currentContent = new String(file.getContents().readAllBytes(), StandardCharsets.UTF_8);

			// Backup original content (only once per file)
			FileModificationTracker.getInstance().notifyFileModified(filePath, currentContent);

			// Apply modification
			String newContent;
			if (selectionOffset == 0 && selectionLength >= currentContent.length())
			{
				// Full file replacement
				newContent = modifiedContent;
			}
			else
			{
				// Replace selection range
				if (selectionOffset + selectionLength > currentContent.length())
				{
					return "Error: Selection range exceeds file length (file=" + currentContent.length() +
						", selection end=" + (selectionOffset + selectionLength) + ")";
				}

				String before = currentContent.substring(0, selectionOffset);
				String after = currentContent.substring(selectionOffset + selectionLength);
				newContent = before + modifiedContent + after;
			}

			// Write back to file
			file.setContents(
				new ByteArrayInputStream(newContent.getBytes(StandardCharsets.UTF_8)),
				true,
				false,
				null);

			ServoyLog.logInfo("Documentation applied to file: " + filePath +
				" (offset=" + selectionOffset + ", length=" + selectionLength + ")");

			return "Success: Documentation applied to " + filePath;
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error applying documentation to file: " + filePath, e);
			return "Error: " + e.getMessage();
		}
	}
}