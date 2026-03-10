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

import com.servoy.eclipse.servoypilot.ai.AssistantType;
import com.servoy.eclipse.servoypilot.context.dto.CodeContext;
import com.servoy.eclipse.servoypilot.context.dto.SelectionInfo;
import com.servoy.eclipse.servoypilot.services.CodeContextService;
import com.servoy.eclipse.servoypilot.util.ChatViewActivator;
import com.servoy.eclipse.servoypilot.util.DebugUtils;

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
						DebugUtils.debug("Unknown command: " + commandId);
				}
			}
			else
			{
				DebugUtils.debug("ServoyAI Context Menu: No selection available");
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
			public void smallTextSelection(SelectionInfo selection, int lineCount, StringBuilder displayMessage, StringBuilder fullMessage)
			{
				// Small selection - show the actual code in the UI
				displayMessage.append("Please review this code from `").append(selection.getFilePath()).append("`:\n\n");
				displayMessage.append("```javascript\n");
				displayMessage.append(selection.getSelectedText());
				displayMessage.append("\n```");

				// AI gets the same message
				fullMessage.append(displayMessage.toString());
			}

			@Override
			public void largeTextSelection(SelectionInfo selection, int lineCount, StringBuilder displayMessage, StringBuilder fullMessage)
			{
				// Large selection - treat like whole file with chunked reading
				displayMessage.append("Please review the selected code from `").append(selection.getFilePath()).append("` (")
					.append(lineCount).append(" lines)");

				// AI gets large file notice to trigger chunked reading
				fullMessage.append("<large_file_notice>\n");
				fullMessage.append("Please read and review the selected code from `").append(selection.getFilePath()).append("` at offset ")
					.append(selection.getOffset()).append(" (").append(lineCount).append(" lines, ")
					.append(selection.getLength()).append(" characters).\n");
				fullMessage.append("</large_file_notice>");
			}

			@Override
			public void fileSelection(SelectionInfo selection, StringBuilder displayMessage, StringBuilder fullMessage)
			{
				// For whole file - UI shows simple message
				displayMessage.append("Please review the file `").append(selection.getFilePath()).append("`");

				// AI gets instruction with large file notice to trigger chunked reading
				fullMessage.append("<large_file_notice>\n");
				fullMessage.append("Please read and review the file `").append(selection.getFilePath()).append("`.\n");
				fullMessage.append("</large_file_notice>");
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
		DebugUtils.debug("[GENERATE TESTS] Generating unit tests for selected code...");
		DebugUtils.debug("[GENERATE TESTS] This will create test cases.");
		// TODO: Implement test generation
	}

	private void handleExplain(SelectionInfo selection)
	{
		handleSelectionInfo(AssistantType.EXPLAIN, selection, new ISelectionAIHandler()
		{
			@Override
			public void smallTextSelection(SelectionInfo selection, int lineCount, StringBuilder displayMessage, StringBuilder fullMessage)
			{
				// Small selection - show the actual code in the UI
				displayMessage.append("Please explain this code from `").append(selection.getFilePath()).append("`:\n\n");
				displayMessage.append("```javascript\n");
				displayMessage.append(selection.getSelectedText());
				displayMessage.append("\n```");

				// AI gets the same message
				fullMessage.append(displayMessage.toString());
			}

			@Override
			public void largeTextSelection(SelectionInfo selection, int lineCount, StringBuilder displayMessage, StringBuilder fullMessage)
			{
				// Large selection - treat like whole file with chunked reading
				displayMessage.append("Please analyze the selected code from `").append(selection.getFilePath()).append("` (")
					.append(lineCount).append(" lines)");

				// AI gets large file notice to trigger chunked reading
				fullMessage.append("<large_file_notice>\n");
				fullMessage.append("Please read and analyze the selected code from `").append(selection.getFilePath()).append("` at offset ")
					.append(selection.getOffset()).append(" (").append(lineCount).append(" lines, ")
					.append(selection.getLength()).append(" characters).\n");
				fullMessage.append("</large_file_notice>");
			}

			@Override
			public void fileSelection(SelectionInfo selection, StringBuilder displayMessage, StringBuilder fullMessage)
			{
				// For whole file - UI shows simple message
				displayMessage.append("Please analyze the file `").append(selection.getFilePath()).append("`");

				// AI gets instruction with large file notice to trigger chunked reading
				fullMessage.append("<large_file_notice>\n");
				fullMessage.append("Please read and analyze the file `").append(selection.getFilePath()).append("`.\n");
				fullMessage.append("</large_file_notice>");
			}
		});
	}

	private void handleSelectionInfo(AssistantType assistantType, SelectionInfo selection, ISelectionAIHandler selectionAIHandler)
	{
		// Get code context early
		CodeContextService service = CodeContextService.getInstance();
		CodeContext context = service.getCodeContext(selection);

		if (context.hasError() || (context.isEmpty() && !context.getSelectionInfo().getFilePath().contains("Console")))
		{
			return;
		}

		String selectedText = selection.getSelectedText();
		int length = selection.getLength();

		// Build display message (shown in UI)
		StringBuilder displayMessage = new StringBuilder();

		// Build full message for AI (includes hidden context)
		StringBuilder fullMessage = new StringBuilder();

		if (length > 0 && selectedText != null && !selectedText.trim().isEmpty())
		{
			// Count lines in selected text
			int lineCount = selectedText.split("\r\n|\r|\n").length;

			if (lineCount > 100)
			{
				selectionAIHandler.largeTextSelection(selection, lineCount, displayMessage, fullMessage);
			}
			else
			{
				selectionAIHandler.smallTextSelection(selection, lineCount, displayMessage, fullMessage);
			}
		}
		else
		{
			selectionAIHandler.fileSelection(selection, displayMessage, fullMessage);
		}

		// Add context hints for AI only (not shown in UI)
		String contextInfo = context.getFormattedPlainText();
		if (contextInfo != null && !contextInfo.trim().isEmpty() && !contextInfo.contains("No context information"))
		{
			fullMessage.append("\n\n**Context hints:**\n```\n");
			fullMessage.append(contextInfo);
			fullMessage.append("\n```");
		}

		String displayText = displayMessage.toString();
		String fullText = fullMessage.toString();

		// Ensure the chat view is open and switch to the Explain assistant, sending the full message with context
		ChatViewActivator.openAndSwitchToAssistant(assistantType, fullText);
	}

	private interface ISelectionAIHandler
	{
		void smallTextSelection(SelectionInfo selection, int lineCount, StringBuilder displayMessage, StringBuilder fullMessage);

		void largeTextSelection(SelectionInfo selection, int lineCount, StringBuilder displayMessage, StringBuilder fullMessage);

		void fileSelection(SelectionInfo selection, StringBuilder displayMessage, StringBuilder fullMessage);
	}
}
