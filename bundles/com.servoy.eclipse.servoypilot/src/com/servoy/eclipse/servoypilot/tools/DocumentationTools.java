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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.chatview.parts.FileModificationTracker;
import com.servoy.eclipse.servoypilot.context.SelectionTracker;
import com.servoy.eclipse.servoypilot.context.dto.CodeContext;
import com.servoy.eclipse.servoypilot.context.dto.SelectionInfo;
import com.servoy.eclipse.servoypilot.exceptions.ValidationException;
import com.servoy.eclipse.servoypilot.services.CodeContextService;
import com.servoy.eclipse.servoypilot.services.documentation.DocumentationValidator;
import com.servoy.eclipse.servoypilot.services.documentation.JSDocManipulator;
import com.servoy.eclipse.servoypilot.tools.dto.DocumentationItem;

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
	@Tool("Get the current editor selection (or entire file if no selection) - no additional documentation")
	public String getCurrentSelection()
	{
		try
		{
			// Get current selection from tracker
			SelectionTracker tracker = SelectionTracker.getInstance();
			Optional<SelectionInfo> selectionOpt = tracker.getCurrentSelection();

			if (selectionOpt.isPresent())
			{
				SelectionInfo selection = selectionOpt.get();

				// Get code text
				CodeContextService contextService = CodeContextService.getInstance();
				String codeText = contextService.getCodeText(selection);

				// Calculate content hash for change detection
				String contentHash = Integer.toString(codeText.hashCode());

				// Convert file path to workspace-relative
				String workspacePath = convertToWorkspacePath(selection.getFilePath());
				if (workspacePath == null)
				{
					return "Error: Could not convert file path to workspace-relative format";
				}

				// Build response with line numbers
				StringBuilder response = new StringBuilder();
				response.append("FILE: ").append(workspacePath).append("\n");
				response.append("START_LINE: ").append(selection.getStartLine()).append("\n");
				response.append("END_LINE: ").append(selection.getEndLine()).append("\n");
				response.append("TOTAL_LINES: ").append(selection.getEndLine() - selection.getStartLine() + 1).append("\n");
				response.append("CONTENT_HASH: ").append(contentHash).append("\n");
				response.append("\n--- CODE ---\n");
				
				// Add line numbers to code
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

	@Tool("Retrieve API documentation for specific identifiers in the current selection")
	public String getDocumentationForIdentifiers(
		@P("Array of identifier names to look up (e.g., ['foundset', 'record', 'plugins.ngdesktop'])") String[] identifiers)
	{
			if (identifiers != null && identifiers.length > 0)
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
				CodeContextService contextService = CodeContextService.getInstance();

				// Get code context with filter - only extract docs for requested identifiers
				CodeContext context = contextService.getCodeContext(selection, identifiers);

				if (context.hasError())
				{
					return "Error extracting context: " + context.getErrorMessage();
				}

				// Build response with documentation for requested identifiers
				StringBuilder response = new StringBuilder();
				response.append("--- DOCUMENTATION FOR: ");
				for (int i = 0; i < identifiers.length; i++)
				{
					if (i > 0)
					{
						response.append(", ");
					}
					response.append(identifiers[i]);
				}
				response.append(" ---\n\n");

				// Filter context to match requested identifiers
				int foundCount = 0;
				for (String requestedId : identifiers)
				{
					boolean found = false;

					// Extract base identifier from requested ID (before last dot)
					// e.g., "databaseManager.getFoundSet" → "databaseManager"
					String baseRequestedId = requestedId;
					int lastDotIndex = requestedId.lastIndexOf('.');
					if (lastDotIndex > 0)
					{
						baseRequestedId = requestedId.substring(0, lastDotIndex);
					}

					// Search through all identifiers in context
					for (var identifierContext : context.getIdentifiers())
					{
						// Match by base identifier name
						if (identifierContext.getName().equals(requestedId) ||
							identifierContext.getName().equals(baseRequestedId))
						{
							// Add documentation for this identifier
							String xml = identifierContext.toFormattedXML();
							if (xml != null && !xml.trim().isEmpty())
							{
								response.append(xml).append("\n");
								found = true;
								foundCount++;
								break;
							}
						}
					}

					// If not found, report it
					if (!found)
					{
						response.append("<type>").append(requestedId).append(": NOT FOUND</type>\n");
						response.append("<description>No documentation available for this identifier</description>\n\n");
					}
				}

				response.append("--- END DOCUMENTATION ---\n\n");
				response.append("Found documentation for ").append(foundCount).append(" out of ").append(identifiers.length).append(" identifiers.");

				return response.toString();
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error getting documentation for identifiers", e);
				return "Error: " + e.getMessage();
			}
		}
		else
		{
			StringBuilder response = new StringBuilder();
			response.append("--- `START DOCUMENTATION ");
			response.append("Error: no identifier provided ");
			response.append("--- END DOCUMENTATION ---\n\n");
			return response.toString();
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

	/**
	 * Apply JSDoc documentation items using line-based positioning.
	 * Supports insert and replace operations with validation.
	 * 
	 * @param filePath Workspace-relative file path
	 * @param expectedHash Content hash from getCurrentSelection() for change detection
	 * @param items List of documentation items (line range + jsdoc)
	 * @return Success message or error message
	 */
	@Tool("Apply JSDoc documentation using line-based positioning")
	public String applyDocumentations(
		@P("Workspace-relative file path") String filePath,
		@P("Content hash from getCurrentSelection()") String expectedHash,
		@P("List of documentation items (line range + jsdoc)") List<DocumentationItem> items)
	{
		// Validation
		if (filePath == null || filePath.isBlank())
		{
			return "Error: File path is required";
		}

		if (expectedHash == null || expectedHash.isBlank())
		{
			return "Error: Content hash is required for change detection";
		}

		if (items == null || items.isEmpty())
		{
			return "Error: No documentation items provided";
		}

		try
		{
			ServoyLog.logInfo("Applying " + items.size() + " documentation items to: " + filePath);

			// Get file
			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(filePath));
			if (!file.exists())
			{
				return "Error: File does not exist: " + filePath;
			}

			// Get selection info for hash validation
			SelectionTracker tracker = SelectionTracker.getInstance();
			Optional<SelectionInfo> selectionOpt = tracker.getCurrentSelection();
			if (!selectionOpt.isPresent())
			{
				return "Error: No active selection available";
			}
			SelectionInfo selection = selectionOpt.get();

			// Read current content
			String originalContent = new String(file.getContents().readAllBytes(), StandardCharsets.UTF_8);

			// Extract selection text for hash comparison
			int selStart = selection.getOffset();
			int selEnd = Math.min(selStart + selection.getLength(), originalContent.length());
			String selectionText = originalContent.substring(selStart, selEnd);

			// CHANGE DETECTION
			String currentHash = Integer.toString(selectionText.hashCode());
			if (!currentHash.equals(expectedHash))
			{
				return "ERROR: File has been modified since documentation was generated. " +
					"Please try again.\n(Expected hash: " + expectedHash + ", Current hash: " + currentHash + ")";
			}

			// Backup original
			FileModificationTracker.getInstance().notifyFileModified(filePath, originalContent);

			// Process items with line-based approach
			List<String> errors = new ArrayList<>();
			int successCount = 0;
			DocumentationValidator validator = new DocumentationValidator();

			// Split content into lines
			String[] lines = originalContent.split("\r\n|\r|\n", -1);
			List<String> lineList = new ArrayList<>();
			for (String line : lines)
			{
				lineList.add(line);
			}

			// Sort items bottom-to-top to avoid line number shifts
			List<DocumentationItem> sortedItems = new ArrayList<>(items);
			sortedItems.sort((a, b) -> Integer.compare(b.startLine(), a.startLine()));

			for (DocumentationItem item : sortedItems)
			{
				try
				{
					// Validate line range
					if (item.startLine() < 0 || item.endLine() >= lineList.size())
					{
						String error = "Line range out of bounds: " + item.startLine() + "-" + item.endLine() + 
							" (file has " + lineList.size() + " lines)";
						errors.add(error);
						ServoyLog.logInfo(error);
						continue;
					}

					if (item.isInsert())
					{
						// INSERT operation
						// Extract indentation from target line
						String targetLine = lineList.get(item.startLine());
						String indentation = extractIndentation(targetLine);

						// Format JSDoc with indentation
						String[] jsdocLines = item.jsdoc().split("\n");
						List<String> formattedLines = new ArrayList<>();
						for (String jsdocLine : jsdocLines)
						{
							formattedLines.add(indentation + jsdocLine);
						}

						// Insert JSDoc lines before target line
						lineList.addAll(item.startLine(), formattedLines);
						successCount++;
					}
					else
					{
						// REPLACE operation with validation
						// Validate start sentence
						String startLineContent = lineList.get(item.startLine()).trim();
						if (!startLineContent.startsWith(item.startSentence()))
						{
							String error = "Start validation failed at line " + item.startLine() + 
								": expected start with '" + item.startSentence() + "' but got '" + 
								startLineContent.substring(0, Math.min(20, startLineContent.length())) + "...'";
							errors.add(error);
							ServoyLog.logInfo(error);
							continue;
						}

						// Validate end sentence
						String endLineContent = lineList.get(item.endLine()).trim();
						if (!endLineContent.endsWith(item.endSentence()))
						{
							String error = "End validation failed at line " + item.endLine() + 
								": expected end with '" + item.endSentence() + "' but got '..." + 
								endLineContent.substring(Math.max(0, endLineContent.length() - 20)) + "'";
							errors.add(error);
							ServoyLog.logInfo(error);
							continue;
						}

						// Extract original UUIDs from replaced range
						StringBuilder replacedContent = new StringBuilder();
						for (int i = item.startLine(); i <= item.endLine(); i++)
						{
							replacedContent.append(lineList.get(i)).append("\n");
						}
						List<String> originalUUIDs = validator.extractUUIDs(replacedContent.toString());

						// Restore UUIDs in new JSDoc
						String fixedJSDoc = validator.restoreUUIDs(item.jsdoc(), originalUUIDs);

						// Extract indentation from first line in range
						String firstLine = lineList.get(item.startLine());
						String indentation = extractIndentation(firstLine);

						// Format JSDoc with indentation
						String[] jsdocLines = fixedJSDoc.split("\n");
						List<String> formattedLines = new ArrayList<>();
						for (String jsdocLine : jsdocLines)
						{
							formattedLines.add(indentation + jsdocLine);
						}

						// Remove old lines
						for (int i = item.endLine(); i >= item.startLine(); i--)
						{
							lineList.remove(i);
						}

						// Insert new JSDoc
						lineList.addAll(item.startLine(), formattedLines);

						// Validate JSDoc syntax
						try
						{
							validator.validateJSDocSyntax(fixedJSDoc);
							successCount++;
						}
						catch (ValidationException ve)
						{
							String error = "JSDoc validation failed for lines " + item.startLine() + "-" + item.endLine() + 
								": " + ve.getMessage();
							errors.add(error);
							ServoyLog.logInfo(error);
						}
					}
				}
				catch (Exception e)
				{
					String error = "Failed to process lines " + item.startLine() + "-" + item.endLine() + ": " + e.getMessage();
					errors.add(error);
					ServoyLog.logError(error, e);
				}
			}

			// Rebuild content from lines
			StringBuilder newContent = new StringBuilder();
			for (int i = 0; i < lineList.size(); i++)
			{
				if (i > 0)
				{
					newContent.append("\n");
				}
				newContent.append(lineList.get(i));
			}

			// Write modified content
			file.setContents(
				new ByteArrayInputStream(newContent.toString().getBytes(StandardCharsets.UTF_8)),
				true,
				false,
				null);

			// Clear selection in editor after modifications
			clearEditorSelection(file, selStart);

			// Build response
			if (!errors.isEmpty())
			{
				ServoyLog.logInfo("Partial success: Applied " + successCount + "/" + items.size() + " items, " + errors.size() + " errors");
				StringBuilder response = new StringBuilder();
				response.append("Partial success: Applied ").append(successCount).append(" out of ").append(items.size())
					.append(" documentation items.\n\nErrors encountered:\n");
				for (String error : errors)
				{
					response.append("  - ").append(error).append("\n");
				}
				return response.toString();
			}

			ServoyLog.logInfo("Successfully applied " + successCount + " documentation items to: " + filePath);
			return String.format("Success: Applied %d documentation items to %s", successCount, filePath);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error applying documentations to " + filePath, e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Extract indentation (leading whitespace) from a line.
	 */
	private String extractIndentation(String line)
	{
		if (line == null || line.isEmpty())
		{
			return "";
		}

		int i = 0;
		while (i < line.length() && Character.isWhitespace(line.charAt(i)))
		{
			i++;
		}

		return line.substring(0, i);
	}

	/**
	 * Clear selection in the active editor and set cursor to original selection start.
	 * This prevents the selection from spanning newly added documentation.
	 */
	private void clearEditorSelection(IFile file, int originalOffset)
	{
		Display.getDefault().asyncExec(() -> {
			try
			{
				IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				if (window == null)
				{
					return;
				}

				IWorkbenchPage page = window.getActivePage();
				if (page == null)
				{
					return;
				}

				IEditorPart editor = page.getActiveEditor();
				if (editor == null)
				{
					return;
				}

				// Check if this editor is for our file
				if (editor.getEditorInput() instanceof FileEditorInput)
				{
					FileEditorInput fileInput = (FileEditorInput)editor.getEditorInput();
					if (fileInput.getFile().equals(file))
					{
						// Get text editor
						ITextEditor textEditor = editor.getAdapter(ITextEditor.class);
						if (textEditor != null)
						{
							var selectionProvider = textEditor.getSelectionProvider();
							if (selectionProvider != null)
							{
								// Set cursor to original selection start (no selection, just cursor position)
								selectionProvider.setSelection(new TextSelection(originalOffset, 0));
							}
						}
					}
				}
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error clearing editor selection", e);
			}
		});
	}
}
