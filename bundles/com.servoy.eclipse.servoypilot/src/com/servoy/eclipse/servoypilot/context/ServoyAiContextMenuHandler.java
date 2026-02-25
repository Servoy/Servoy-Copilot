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
import com.servoy.eclipse.servoypilot.chatview.parts.ChatView;
import com.servoy.eclipse.servoypilot.context.dto.CodeContext;
import com.servoy.eclipse.servoypilot.context.dto.SelectionInfo;
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
		DebugUtils.debug("[DEBUG] Analyzing code for debugging assistance...");
		DebugUtils.debug("[DEBUG] This will help identify issues in the selected code.");
		// TODO: Implement actual debug analysis
	}

	private void handleReview(SelectionInfo selection)
	{
		DebugUtils.debug("[REVIEW] Reviewing code for quality and best practices...");
		DebugUtils.debug("[REVIEW] This will provide suggestions for improvement.");
		// TODO: Implement code review functionality
	}

	private void handleGenerateDocs(SelectionInfo selection)
	{
		// Get code context using the visitor
		CodeContextService service = CodeContextService.getInstance();
		CodeContext context = service.getCodeContext(selection);

		if (context.hasError())
		{
			return;
		}

		if (context.isEmpty())
		{
			return;
		}

		// Get current solution name and create documentation-specific memory ID
		String solutionName = getCurrentSolutionName();
		String memoryId = solutionName + "-documentation";

		// Call documentation assistant with XML-formatted context
		String xmlContext = context.getFormattedXML();

		// TODO: Handle TokenStream response
		// - Collect tokens into StringBuilder
		// - Create temp file with generated documentation
		// - Show comparison editor (GitHub Copilot style)
		// - Update view with file entry
		com.servoy.eclipse.servoypilot.Activator.getDefault().getDocumentationAssistant().executeRequest(memoryId, xmlContext);
	}

	private void handleGenerateTests(SelectionInfo selection)
	{
		DebugUtils.debug("[GENERATE TESTS] Generating unit tests for selected code...");
		DebugUtils.debug("[GENERATE TESTS] This will create test cases.");
		// TODO: Implement test generation
	}

	private void handleExplain(SelectionInfo selection)
	{
		// Get code context early
		CodeContextService service = CodeContextService.getInstance();
		CodeContext context = service.getCodeContext(selection);

		if (context.hasError() || context.isEmpty())
		{
			return;
		}

		// Get file path and selection info
		String filePath = selection.getFilePath();
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
				// Large selection - treat like whole file with chunked reading
				displayMessage.append("Please analyze the selected code from `").append(filePath).append("` (")
					.append(lineCount).append(" lines)");
				
				// AI gets large file notice to trigger chunked reading
				fullMessage.append("<large_file_notice>\n");
				fullMessage.append("Please read and analyze the selected code from `").append(filePath).append("` at offset ")
					.append(selection.getOffset()).append(" (").append(lineCount).append(" lines, ")
					.append(length).append(" characters).\n");
				fullMessage.append("</large_file_notice>");
			}
			else
			{
				// Small selection - show the actual code in the UI
				displayMessage.append("Please explain this code from `").append(filePath).append("`:\n\n");
				displayMessage.append("```javascript\n");
				displayMessage.append(selectedText);
				displayMessage.append("\n```");
				
				// AI gets the same message
				fullMessage.append(displayMessage.toString());
			}
		}
		else
		{
			// For whole file - UI shows simple message
			displayMessage.append("Please analyze the file `").append(filePath).append("`");
			
			// AI gets instruction with large file notice to trigger chunked reading
			fullMessage.append("<large_file_notice>\n");
			fullMessage.append("Please read and analyze the file `").append(filePath).append("`.\n");
			fullMessage.append("</large_file_notice>");
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
		
		// Ensure the chat view is open and visible
		if (!ChatViewActivator.openAndActivateChatView())
		{
			DebugUtils.debug("[EXPLAIN] Failed to open chat view");
			return;
		}

		// Get ChatView instance
		ChatView chatView = ChatViewActivator.getChatView();
		if (chatView == null)
		{
			DebugUtils.debug("[EXPLAIN] Failed to get ChatView instance");
			return;
		}
		
		// Schedule the switch and message send on the UI thread with proper sequencing
		org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
			// Ensure assistant selector is populated
			chatView.getPresenter().populateAssistantSelector();
			
			// Switch to Explain assistant (will clear view if switching from another)
			chatView.getPresenter().switchToAssistant(AssistantType.EXPLAIN);
			
			// Schedule message sending after assistant switch completes
			org.eclipse.swt.widgets.Display.getCurrent().timerExec(150, () -> {
				// Send the message - display text in UI, full text (with context) to AI
				chatView.getPresenter().onSendUserMessageWithContext(displayText, fullText);
			});
		});
	}

	/**
	 * Get the current active solution name
	 * @return solution name or "default" if none active
	 */
	private String getCurrentSolutionName()
	{
		try
		{
			com.servoy.eclipse.model.extensions.IServoyModel servoyModel = com.servoy.eclipse.model.ServoyModelFinder.getServoyModel();
			if (servoyModel != null && servoyModel.getActiveProject() != null)
			{
				return servoyModel.getActiveProject().getProject().getName();
			}
		}
		catch (Exception e)
		{
			// Fallback to default if error
		}
		return "default";
	}
}
