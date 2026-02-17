package com.servoy.eclipse.servoypilot.context;

import java.util.Optional;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

import com.servoy.eclipse.servoypilot.context.dto.CodeContext;
import com.servoy.eclipse.servoypilot.context.dto.SelectionInfo;
import com.servoy.eclipse.servoypilot.util.DebugUtils;

/**
 * Handler for Servoy AI context menu commands.
 * Handles: Debug, Review, Generate Docs, Generate Tests
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
					case "com.servoy.eclipse.servoypilot.context.debug":
						handleDebug(selectionInfo);
						break;
					case "com.servoy.eclipse.servoypilot.context.review":
						handleReview(selectionInfo);
						break;
					case "com.servoy.eclipse.servoypilot.context.generateDocs":
						handleGenerateDocs(selectionInfo);
						break;
					case "com.servoy.eclipse.servoypilot.context.generateTests":
						handleGenerateTests(selectionInfo);
						break;
					default:
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

	private String getCommandName(String commandId)
	{
		if (commandId.endsWith(".debug")) return "Debug";
		if (commandId.endsWith(".review")) return "Review";
		if (commandId.endsWith(".generateDocs")) return "Generate Docs";
		if (commandId.endsWith(".generateTests")) return "Generate Tests";
		return commandId;
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
			DebugUtils.debug("[GENERATE DOCS] Error: " + context.getErrorMessage());
			return;
		}
		
		if (context.isEmpty())
		{
			DebugUtils.debug("[GENERATE DOCS] No API context found in selection.");
			return;
		}
		
		// TODO: Use this context to generate JSDoc comments
		DebugUtils.debug("[GENERATE DOCS] Next step: Generate JSDoc based on context above");
	}

	private void handleGenerateTests(SelectionInfo selection)
	{
		DebugUtils.debug("[GENERATE TESTS] Generating unit tests for selected code...");
		DebugUtils.debug("[GENERATE TESTS] This will create test cases.");
		// TODO: Implement test generation
	}
}
