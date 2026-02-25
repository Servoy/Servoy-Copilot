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

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.chatview.parts.FileModificationTracker;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * AI tools for applying generated documentation to source files.
 * 
 * Handles file backup, content replacement (selection or full file), and modification tracking.
 */
public class DocumentationTools
{
	@Tool("Apply generated documentation to a file or selection range")
	public String applyDocumentation(
		@P("Workspace-relative file path (e.g., /ProjectName/forms/myForm.js)") String filePath,
		@P("Selection start offset (0 for full file)") int selectionOffset,
		@P("Selection length (file length for full file)") int selectionLength,
		@P("Modified content to apply (documentation + code)") String modifiedContent)
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
