package com.servoy.eclipse.servoypilot.tools.utility;

import java.util.Optional;

import com.servoy.eclipse.servoypilot.context.CodeContextService;
import com.servoy.eclipse.servoypilot.context.SelectionTracker;
import com.servoy.eclipse.servoypilot.context.dto.CodeContext;
import com.servoy.eclipse.servoypilot.context.dto.SelectionInfo;

import dev.langchain4j.agent.tool.Tool;

/**
 * LLM tools for code context analysis.
 * Exposes CodeContextService functionality to AI agents via function calling.
 * 
 * Phase 4 implementation - provides tools for extracting API context from code.
 */
public class CodeContextTools
{
	/**
	 * Gets code context for the current editor selection or entire file.
	 * If text is selected, analyzes only the selection.
	 * If no selection, analyzes the entire file.
	 */
	@Tool("Analyzes the currently selected code in the editor and extracts API context (types, documentation). " +
		"If no code is selected, analyzes the entire file. " +
		"Use this when you need to understand what Servoy APIs, components, or services are being used in code.")
	public String getCodeContext()
	{
		try
		{
			SelectionTracker tracker = SelectionTracker.getInstance();
			Optional<SelectionInfo> selection = tracker.getCurrentSelection();

			if (selection.isPresent())
			{
				SelectionInfo selectionInfo = selection.get();
				CodeContextService service = CodeContextService.getInstance();
				CodeContext context = service.getCodeContext(selectionInfo);

				if (!context.hasError())
				{
					if (!context.isEmpty())
					{
						return context.getFormattedXML();
					}
					return "No identifiers found in the code.";
				}
				return "Error: " + context.getErrorMessage();
			}
			return "No active editor or file available.";
		}
		catch (Exception e)
		{
			return "Error getting code context: " + e.getMessage();
		}
	}
}
