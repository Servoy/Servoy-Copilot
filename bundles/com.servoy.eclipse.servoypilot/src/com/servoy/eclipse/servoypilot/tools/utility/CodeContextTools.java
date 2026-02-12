package com.servoy.eclipse.servoypilot.tools.utility;

import java.util.Optional;

import com.servoy.eclipse.servoypilot.context.CodeContextService;
import com.servoy.eclipse.servoypilot.context.SelectionTracker;
import com.servoy.eclipse.servoypilot.context.dto.CodeContext;
import com.servoy.eclipse.servoypilot.context.dto.SelectionInfo;

import dev.langchain4j.agent.tool.P;
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
	 * Gets code context for the current editor selection.
	 * Analyzes selected JavaScript code and extracts type information and documentation.
	 */
	@Tool("Analyzes the currently selected code in the editor and extracts API context (types, documentation). " +
		"Use this when you need to understand what Servoy APIs, components, or services are being used in selected code.")
	public String getCodeContext()
	{
		try
		{
			SelectionTracker tracker = SelectionTracker.getInstance();
			Optional<SelectionInfo> selection = tracker.getCurrentSelection();

			if (selection.isPresent())
			{
				SelectionInfo selectionInfo = selection.get();
				if (selectionInfo.hasSelection())
				{
					CodeContextService service = CodeContextService.getInstance();
					CodeContext context = service.getCodeContext(selectionInfo);

					if (!context.hasError())
					{
						if (!context.isEmpty())
						{
							// Return formatted context for LLM
							return context.getFormattedXML();
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			return "Error getting code context: " + e.getMessage();
		}
		return "No code context available";
	}

	/**
	 * Gets code context for a specific file (entire file).
	 * TODO: Phase 4 - implement full file analysis
	 */
	@Tool("Analyzes an entire JavaScript file and extracts API context. " +
		"Useful when you need to understand all APIs used in a file without requiring a selection.")
	public String getCodeContextForFile(
		@P(value = "Full path to the JavaScript file to analyze", required = true) String filePath)
	{
		// TODO Phase 4: Implement
		return "Full file analysis not yet implemented. Use getCodeContext() with a selection instead.";
	}

	/**
	 * Gets code context for a specific range in a file.
	 * TODO: Phase 4 - implement range-based analysis
	 */
	@Tool("Analyzes a specific range of code in a JavaScript file and extracts API context. " +
		"Useful for programmatic analysis of specific code sections.")
	public String getCodeContextForSelection(
		@P(value = "Full path to the JavaScript file", required = true) String filePath,
		@P(value = "Start offset of the selection", required = true) int offset,
		@P(value = "Length of the selection", required = true) int length)
	{
		// TODO Phase 4: Implement
		return "Range-based analysis not yet implemented. Use getCodeContext() with an editor selection instead.";
	}
}
