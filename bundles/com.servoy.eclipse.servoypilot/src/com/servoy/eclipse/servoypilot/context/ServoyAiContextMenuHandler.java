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
package com.servoy.eclipse.servoypilot.context;

import java.util.Optional;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.ai.AssistantType;
import com.servoy.eclipse.servoypilot.context.dto.CodeContext;
import com.servoy.eclipse.servoypilot.context.dto.SelectionInfo;
import com.servoy.eclipse.servoypilot.services.CodeContextService;
import com.servoy.eclipse.servoypilot.util.ChatViewActivator;

/**
 * Handler for Servoy AI context menu commands.
 * Handles: Debug, Review, Generate Docs, Generate Tests, Explain
 * 
 * Currently placeholder implementation with debug output.
 */
public class ServoyAiContextMenuHandler extends AbstractHandler
{
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException
	{
		String commandId = event.getCommand().getId();
		IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);

		if (window != null)
		{
			// Get current selection
			SelectionTracker tracker = SelectionTracker.getInstance();
			Optional<SelectionInfo> selection = tracker.getCurrentSelection();

			if (selection.isPresent())
			{
				SelectionInfo selectionInfo = selection.get();

				// Handle specific commands
				switch (commandId)
				{
					case "com.servoy.eclipse.servoypilot.context.debug" :
						handleDebug(selectionInfo);
						break;
					case "com.servoy.eclipse.servoypilot.context.review" :
						handleReview(selectionInfo);
						break;
					case "com.servoy.eclipse.servoypilot.context.generateDocs" :
						handleGenerateDocs(selectionInfo);
						break;
					case "com.servoy.eclipse.servoypilot.context.generateTests" :
						handleGenerateTests(selectionInfo);
						break;
					case "com.servoy.eclipse.servoypilot.context.explain" :
						handleExplain(selectionInfo);
						break;
					default :
						ServoyLog.logInfo("Unknown command: " + commandId);
				}
			}
		}

		return null;
	}

	private void handleDebug(SelectionInfo selection)
	{
		// TODO: Implement actual debug analysis
	}

	private void handleReview(SelectionInfo selection)
	{
		handleSelectionInfo(AssistantType.REVIEW, selection, new ISelectionAIHandler()
		{
			@Override
			public void viewTextSelection(SelectionInfo selection, StringBuilder displayMessage)
			{
				// ignore
			}

			@Override
			public void smallTextSelection(SelectionInfo selection, int lineCount, StringBuilder displayMessage)
			{
				displayMessage.append("Please review this code from `").append(selection.getFilePath()).append("`:\n\n");
				displayMessage.append("```javascript\n");
				displayMessage.append(selection.getSelectedText());
				displayMessage.append("\n```");
			}

			@Override
			public void largeTextSelection(SelectionInfo selection, int lineCount, StringBuilder displayMessage)
			{
				displayMessage.append("Please read and review the selected code from `").append(selection.getFilePath()).append("` at line ")
					.append(selection.getOffset()).append(" (").append(lineCount).append(" lines, ")
					.append(selection.getLength()).append(" characters).\n");
			}

			@Override
			public void fileSelection(SelectionInfo selection, StringBuilder displayMessage)
			{
				displayMessage.append("Please read and review the file `").append(selection.getFilePath()).append("`.\n");
			}
		});
	}

	private void handleGenerateDocs(SelectionInfo selection)
	{
		// Build simple generic message (no code included)
		String displayMessage = "Please generate JSDoc documentation for the current selection.";

		// Open ChatView, switch to Documentation Assistant, and send message
		ChatViewActivator.openAndSwitchToAssistant(
			AssistantType.DOCUMENTATION,
			displayMessage);
	}

	private void handleGenerateTests(SelectionInfo selection)
	{
		// TODO: Implement test generation
	}

	private void handleExplain(SelectionInfo selection)
	{
		// Get code context early
		CodeContextService service = CodeContextService.getInstance();
		CodeContext context = service.getCodeContext(selection);

		if (context.hasError())
		{
			return;
		}

		handleSelectionInfo(AssistantType.EXPLAIN, selection, new ISelectionAIHandler()
		{
			@Override
			public void viewTextSelection(SelectionInfo selection, StringBuilder displayMessage)
			{
				// Error from Console/Error Log - use plain text format
				displayMessage.append("Please analyze this error from `").append(selection.getFilePath()).append("`:\n\n");
				displayMessage.append(selection.getSelectedText());

				// Mark as error for QuickFix integration
				displayMessage.append("\n\n<error_context>\n");
				displayMessage.append("This is an error message that may need a fix.\n");
				displayMessage.append("</error_context>");
			}

			@Override
			public void smallTextSelection(SelectionInfo selection, int lineCount, StringBuilder displayMessage)
			{
				displayMessage.append("Please explain this code from `").append(selection.getFilePath()).append("`:\n\n");
				displayMessage.append("```javascript\n");
				displayMessage.append(selection.getSelectedText());
				displayMessage.append("\n```");
			}

			@Override
			public void largeTextSelection(SelectionInfo selection, int lineCount, StringBuilder displayMessage)
			{
				displayMessage.append("Please read and analyze the selected code from `").append(selection.getFilePath()).append("` at line ")
					.append(selection.getOffset()).append(" (").append(lineCount).append(" lines, ")
					.append(selection.getLength()).append(" characters).\n");
			}

			@Override
			public void fileSelection(SelectionInfo selection, StringBuilder displayMessage)
			{
				displayMessage.append("Please read and analyze the file `").append(selection.getFilePath()).append("`.\n");
			}
		});
	}

	private void handleSelectionInfo(AssistantType assistantType, SelectionInfo selection, ISelectionAIHandler selectionAIHandler)
	{
		// Get file path and selection info
		String filePath = selection.getFilePath();
		String selectedText = selection.getSelectedText();
		boolean isFullFileSelected = selection.isFullFileSelected();

		// Detect if selection is from a view (Console, Error Log) or a file
		boolean isFromView = filePath.startsWith("<") && filePath.endsWith(">");

		// Build display message (shown in UI)
		StringBuilder displayMessage = new StringBuilder();

		if (isFromView && selectedText != null && !selectedText.trim().isEmpty())
		{
			selectionAIHandler.viewTextSelection(selection, displayMessage);
		}
		// Handle code from files (full file or selection)
		else if (selectedText != null && !selectedText.trim().isEmpty())
		{
			// Count lines once for all code paths
			int lineCount = (int)selectedText.lines().count();

			if (lineCount > 100)
			{
				// Large file/selection - use chunked reading with progress messages
				if (isFullFileSelected)
				{
					selectionAIHandler.fileSelection(selection, displayMessage);
				}
				else
				{
					selectionAIHandler.largeTextSelection(selection, lineCount, displayMessage);
				}
			}
			else
			{
				selectionAIHandler.smallTextSelection(selection, lineCount, displayMessage);
			}
		}

		CodeContextService service = CodeContextService.getInstance();
		CodeContext context = service.getCodeContext(selection);
		// Add context hints for AI only (not shown in UI)
		String contextInfo = context.getFormattedPlainText();
		if (contextInfo != null && !contextInfo.trim().isEmpty() && !contextInfo.contains("No context information"))
		{
			displayMessage.append("\n\n**Context hints:**\n```\n");
			displayMessage.append(contextInfo);
			displayMessage.append("\n```");
		}

		// Ensure the chat view is open and switch the assistant, sending the message with context
		ChatViewActivator.openAndSwitchToAssistant(assistantType, displayMessage.toString());
	}

	private interface ISelectionAIHandler
	{
		void viewTextSelection(SelectionInfo selection, StringBuilder displayMessage);

		void smallTextSelection(SelectionInfo selection, int lineCount, StringBuilder displayMessage);

		void largeTextSelection(SelectionInfo selection, int lineCount, StringBuilder displayMessage);

		void fileSelection(SelectionInfo selection, StringBuilder displayMessage);
	}
}
