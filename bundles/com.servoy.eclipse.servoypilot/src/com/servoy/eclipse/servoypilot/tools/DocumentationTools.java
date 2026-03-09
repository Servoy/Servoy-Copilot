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

				// Build response with code only (no API documentation)
				StringBuilder response = new StringBuilder();
				response.append("FILE: ").append(workspacePath).append("\n");
				response.append("OFFSET: ").append(selection.getOffset()).append("\n");
				response.append("LENGTH: ").append(selection.getLength()).append("\n");
				response.append("CONTENT_HASH: ").append(contentHash).append("\n");
				response.append("\n--- CODE ---\n");
				response.append(codeText);
				response.append("\n--- END CODE ---\n");

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
				// Print requested identifiers to console
				System.out.println("=== DOCUMENTATION LOOKUP REQUESTED ===");
				System.out.println("AI requested documentation for " + identifiers.length + " identifiers:");
				for (int i = 0; i < identifiers.length; i++)
				{
					System.out.println("  [" + (i + 1) + "] " + identifiers[i]);
				}
				System.out.println("======================================");

				// Get current selection from tracker
				SelectionTracker tracker = SelectionTracker.getInstance();
				Optional<SelectionInfo> selectionOpt = tracker.getCurrentSelection();

				if (!selectionOpt.isPresent())
				{
					return "Error: No active editor or selection available";
				}

				SelectionInfo selection = selectionOpt.get();
				CodeContextService contextService = CodeContextService.getInstance();
				// DEBUG: First call getCodeContext WITHOUT filter to see what automatic extraction finds
				// TODO: Comment out or remove this debug block after testing
				System.out.println("\n=== DEBUG: AUTOMATIC IDENTIFIER EXTRACTION (NO FILTER) ===");
				CodeContext autoContext = contextService.getCodeContext(selection, null);
				System.out.println("Automatically extracted identifiers from code:");
				if (autoContext.getIdentifiers() != null && !autoContext.getIdentifiers().isEmpty())
				{
					for (var idCtx : autoContext.getIdentifiers())
					{
						System.out.println("  - " + idCtx.getName() + " [type: " + idCtx.getTypeName() + "] (kind: " + idCtx.getKind() + ")");
					}
					System.out.println("Total automatically extracted: " + autoContext.getIdentifiers().size());
				}
				else
				{
					System.out.println("  (none found)");
				}
				System.out.println("===========================================================\n");
				// END DEBUG

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
				System.out.println("\n=== DOCUMENTATION RETRIEVAL ===");
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
								System.out.println("  ✓ " + requestedId + " → " + xml.length() + " chars");
								break;
							}
						}
					}

					// If not found, report it
					if (!found)
					{
						response.append("<type>").append(requestedId).append(": NOT FOUND</type>\n");
						response.append("<description>No documentation available for this identifier</description>\n\n");
						System.out.println("  ✗ " + requestedId + " → NOT FOUND");
					}
				}
				System.out.println("Total: " + foundCount + "/" + identifiers.length);
				System.out.println("================================\n");

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
	 * Apply JSDoc documentation items using signature-based search.
	 * Works on any file regardless of syntax errors.
	 * 
	 * @param filePath Workspace-relative file path
	 * @param expectedHash Content hash from getCurrentSelection() for change detection
	 * @param items List of documentation items (signature + jsdoc)
	 * @return Success message or error message
	 */
	@Tool("Apply JSDoc documentation using signature-based search")
	public String applyDocumentations(
		@P("Workspace-relative file path") String filePath,
		@P("Content hash from getCurrentSelection()") String expectedHash,
		@P("List of documentation items (signature + jsdoc)") List<DocumentationItem> items)
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
			System.out.println("\n=== APPLY DOCUMENTATIONS (SIGNATURE-BASED) ===");
			System.out.println("File: " + filePath);
			System.out.println("Expected hash: " + expectedHash);
			System.out.println("Number of items: " + items.size());

			// Get file
			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(filePath));
			if (!file.exists())
			{
				return "Error: File does not exist: " + filePath;
			}

			// Get selection info
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

			// Process items with signature search
			List<String> errors = new ArrayList<>();
			int successCount = 0;
			JSDocManipulator manipulator = new JSDocManipulator();
			DocumentationValidator validator = new DocumentationValidator();

			// Use mutable content for updates
			String content = originalContent;

			// Sort items by signature position (bottom-to-top) to avoid offset shifts
			List<DocumentationItem> sortedItems = new ArrayList<>(items);
			final String contentForSort = content;
			sortedItems.sort((a, b) -> {
				int posA = manipulator.findSignaturePosition(contentForSort, a.signature(), selStart, selEnd);
				int posB = manipulator.findSignaturePosition(contentForSort, b.signature(), selStart, selEnd);
				return Integer.compare(posB, posA); // Descending
			});

			for (DocumentationItem item : sortedItems)
			{
				System.out.println("\n--- Processing: " + item.signature() + " ---");

				try
				{
					// Find signature in selection
					int sigPos = manipulator.findSignaturePosition(content, item.signature(), selStart, selEnd);
					if (sigPos < 0)
					{
						String error = "Signature not found in selection: " + item.signature();
						errors.add(error);
						System.out.println("  ✗ " + error);
						continue;
					}

					System.out.println("  ✓ Signature found at position " + sigPos);

					// Find existing JSDoc above signature
					int jsdocStart = manipulator.findJSDocStart(content, sigPos);
					int jsdocEnd = jsdocStart >= 0 ? manipulator.findJSDocEnd(content, jsdocStart) : -1;

					// Extract original UUIDs for restoration
					List<String> originalUUIDs = new ArrayList<>();
					if (jsdocStart >= 0 && jsdocEnd > jsdocStart)
					{
						String originalJSDoc = content.substring(jsdocStart, jsdocEnd);
						originalUUIDs = validator.extractUUIDs(originalJSDoc);
						System.out.println("  Found existing JSDoc with " + originalUUIDs.size() + " UUID(s)");
					}
					else
					{
						System.out.println("  No existing JSDoc found");
					}

					// Restore UUIDs in new JSDoc if AI changed them
					String fixedJSDoc = validator.restoreUUIDs(item.jsdoc(), originalUUIDs);

					// Extract indentation from signature line
					int lineStart = content.lastIndexOf('\n', sigPos) + 1;
					String signatureLine = content.substring(lineStart, Math.min(sigPos + 50, content.length()));
					String indentation = manipulator.extractIndentation(signatureLine);

					// Format JSDoc with indentation
					String[] jsdocLines = fixedJSDoc.split("\n");
					StringBuilder formattedJSDoc = new StringBuilder();
					for (int i = 0; i < jsdocLines.length; i++)
					{
						if (i > 0)
						{
							formattedJSDoc.append("\n");
						}
						formattedJSDoc.append(indentation).append(jsdocLines[i]);
					}

					// Insert or replace JSDoc
					if (jsdocStart >= 0 && jsdocEnd > jsdocStart)
					{
						// Replace existing JSDoc
						content = content.substring(0, jsdocStart) +
							formattedJSDoc.toString() + "\n" +
							content.substring(jsdocEnd);
						System.out.println("  ✓ Replaced existing JSDoc");
					}
					else
					{
						// Insert new JSDoc before signature
						content = content.substring(0, sigPos) +
							formattedJSDoc.toString() + "\n" +
							content.substring(sigPos);
						System.out.println("  ✓ Inserted new JSDoc");
					}

					// Validate the specific JSDoc we just added
					try
					{
						validator.validateJSDocSyntax(fixedJSDoc);
						System.out.println("  ✓ JSDoc syntax valid");
						successCount++;
					}
					catch (ValidationException ve)
					{
						String error = "JSDoc validation failed for " + item.signature() + ": " + ve.getMessage();
						errors.add(error);
						System.out.println("  ✗ " + error);
						// Restore just this JSDoc - continue processing others
					}
				}
				catch (Exception e)
				{
					String error = "Failed to process " + item.signature() + ": " + e.getMessage();
					errors.add(error);
					System.out.println("  ✗ " + error);
				}
			}

			// Write modified content
			file.setContents(
				new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
				true,
				false,
				null);

			// Clear selection in editor after modifications
			clearEditorSelection(file, selStart);

			System.out.println("\n=== RESULTS ===");
			System.out.println("Success: " + successCount + "/" + items.size());
			System.out.println("Errors: " + errors.size());
			System.out.println("===============\n");

			// Build response
			if (!errors.isEmpty())
			{
				StringBuilder response = new StringBuilder();
				response.append("Partial success: Applied ").append(successCount).append(" out of ").append(items.size())
					.append(" documentation items.\n\nErrors encountered:\n");
				for (String error : errors)
				{
					response.append("  - ").append(error).append("\n");
				}
				return response.toString();
			}

			return String.format("Success: Applied %d documentation items to %s", successCount, filePath);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error applying documentations to " + filePath, e);
			return "Error: " + e.getMessage();
		}
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

			// Clear selection in active editor to avoid confusing partial selection
			clearEditorSelection(file, selectionOffset);

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
