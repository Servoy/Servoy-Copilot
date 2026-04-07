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
package com.servoy.eclipse.servoypilot.tools.documentation;

import java.util.Optional;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.context.SelectionTracker;
import com.servoy.eclipse.servoypilot.context.dto.SelectionInfo;
import com.servoy.eclipse.servoypilot.services.CodeContextService;

import dev.langchain4j.agent.tool.Tool;

public interface IGetCurrentSelectionTool
{
	@Tool("Returns the currently active code in the editor — either the selected text or the entire file if nothing is selected. " +
		"Returns: FILE (workspace-relative path), START_LINE, END_LINE, TOTAL_LINES, and the code with 0-based line numbers. " +
		"Does NOT return Servoy API documentation — request that separately via getDocumentationForIdentifiers.")
	default String getCurrentSelection()
	{
		try
		{
			SelectionTracker tracker = SelectionTracker.getInstance();
			Optional<SelectionInfo> selectionOpt = tracker.getCurrentSelection();

			if (selectionOpt.isPresent())
			{
				SelectionInfo selection = selectionOpt.get();

				CodeContextService contextService = CodeContextService.getInstance();
				String codeText = contextService.getCodeText(selection);

				String contentHash = Integer.toString(codeText.hashCode());

				String workspacePath = DocumentationToolsHelper.getInstance().convertToWorkspacePath(selection.getFilePath());
				if (workspacePath == null)
				{
					return "Error: Could not convert file path to workspace-relative format";
				}

				StringBuilder response = new StringBuilder();
				response.append("FILE: ").append(workspacePath).append("\n");
				response.append("START_LINE: ").append(selection.getStartLine()).append("\n");
				response.append("END_LINE: ").append(selection.getEndLine()).append("\n");
				response.append("TOTAL_LINES: ").append(selection.getEndLine() - selection.getStartLine() + 1).append("\n");
				response.append("CONTENT_HASH: ").append(contentHash).append("\n");
				response.append("\n--- CODE ---\n");

				String[] lines = codeText.split("\r\n|\r|\n", -1);
				int lineNumber = selection.getStartLine();
				for (String line : lines)
				{
					response.append(lineNumber).append(": ").append(line).append("\n");
					lineNumber++;
				}

				response.append("--- END CODE ---\n");
				return response.toString();
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error getting current selection", e);
			return "Error: " + e.getMessage();
		}

		return "No active editor or selection available";
	}
}
