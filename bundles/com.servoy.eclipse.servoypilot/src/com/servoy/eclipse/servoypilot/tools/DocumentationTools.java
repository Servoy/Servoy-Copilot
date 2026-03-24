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
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.dltk.core.DLTKCore;
import org.eclipse.dltk.core.ISourceModule;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

import com.servoy.eclipse.debug.script.TypeCreator;
import com.servoy.eclipse.debug.script.TypeProviderFactory;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.chatview.parts.FileModificationTracker;
import com.servoy.eclipse.servoypilot.context.SelectionTracker;
import com.servoy.eclipse.servoypilot.context.dto.CodeContext;
import com.servoy.eclipse.servoypilot.context.dto.SelectionInfo;
import com.servoy.eclipse.servoypilot.exceptions.ValidationException;
import com.servoy.eclipse.servoypilot.services.CodeContextService;
import com.servoy.eclipse.servoypilot.services.FilePathResolver;
import com.servoy.eclipse.servoypilot.services.documentation.DocumentationValidator;
import com.servoy.eclipse.servoypilot.tools.dto.DocumentationItem;
import com.servoy.j2db.documentation.scripting.docs.FormElements;
import org.eclipse.dltk.javascript.typeinfo.model.Type;
import org.eclipse.dltk.javascript.typeinfo.model.Member;
import org.eclipse.dltk.javascript.typeinfo.model.Method;
import org.eclipse.dltk.javascript.typeinfo.model.Property;
import org.eclipse.dltk.javascript.typeinfo.model.Parameter;
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

	@Tool("Retrieve API documentation for specific identifiers in the current selection or specified file")
	public String getDocumentationForIdentifiers(
		@P("Array of identifier names to look up (e.g., ['foundset', 'record', 'plugins.ngdesktop'])") String[] identifiers,
		@P("Optional file path (form name, scope name, or full path) - if provided, works without active editor") String filePath)
	{
		if (identifiers != null && identifiers.length > 0)
		{
			try
			{
				SelectionInfo selection = null;
				
				// If filePath provided, create SelectionInfo programmatically (no editor needed)
				if (filePath != null && !filePath.trim().isEmpty())
				{
					selection = createSelectionInfoFromFile(filePath);
					if (selection == null)
					{
						return "Error: Could not open file: " + filePath;
					}
				}
				else
				{
					SelectionTracker tracker = SelectionTracker.getInstance();
					Optional<SelectionInfo> selectionOpt = tracker.getCurrentSelection();

					if (!selectionOpt.isPresent())
					{
						return "Error: No active editor or selection available. Provide filePath parameter to work without active editor.";
					}

					selection = selectionOpt.get();
				}
				
				CodeContextService contextService = CodeContextService.getInstance();
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
					String baseRequestedId = requestedId;
					int lastDotIndex = requestedId.lastIndexOf('.');
					if (lastDotIndex > 0)
					{
						baseRequestedId = requestedId.substring(0, lastDotIndex);
					}

					// Search through all identifiers in context
					for (var identifierContext : context.getIdentifiers())
					{
						if (identifierContext.getName().equals(requestedId) ||
							identifierContext.getName().equals(baseRequestedId))
						{
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

	/**
	 * Creates SelectionInfo from a file path without requiring SelectionTracker.
	 * Used when tools are called without an active editor.
	 * 
	 * @param pathOrName file path, form name, or scope name
	 * @return SelectionInfo for entire file, or null if file not found
	 */
	private SelectionInfo createSelectionInfoFromFile(String pathOrName)
	{
		if (pathOrName == null || pathOrName.trim().isEmpty())
		{
			return null;
		}

		try
		{
			// Resolve file using FilePathResolver (supports form names, scope names)
			FilePathResolver resolver = FilePathResolver.getInstance();
			IFile file = resolver.resolveFile(pathOrName);

			if (file != null && file.exists())
			{
				// Get ISourceModule for DLTK parsing
				ISourceModule module = (ISourceModule)DLTKCore.create(file);
				if (module != null)
				{
					// Read entire file content
					String source = module.getSource();
					if (source != null)
					{
						// Calculate total lines
						int totalLines = source.split("\r\n|\r|\n", -1).length;

						// Create SelectionInfo for entire file
						Optional<SelectionInfo> selectionOpt = SelectionInfo.create(
							file.getFullPath().toString(),
							0, // offset
							source.length(), // length
							source, // text
							module,
							0, // startLine
							totalLines - 1, // endLine
							true // isFullFileSelected
						);

						if (selectionOpt.isPresent())
						{
							return selectionOpt.get();
						}
					}
				}
			}

			System.out.println("ERROR: File not found or could not be read: " + pathOrName);
			return null;
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error creating SelectionInfo from file: " + pathOrName, e);
			return null;
		}
	}

	@Tool("List available members (methods and properties) for a Servoy API type. " +
		"Returns lightweight signatures without full documentation. " +
		"Use regex filter to narrow results (e.g., 'get.*' for getters, 'show.*|hide.*' for show/hide methods).")
	public String getAvailableMembersForType(
		@P("Type name (e.g., 'application', 'databaseManager', 'controller', 'JSApplication')") String typeName,
		@P("Regex filter for member names (default '*' = all members). Examples: 'get.*', 'is.*', 'show.*|hide.*'") String memberFilter)
	{
		System.out.println("\n========== getAvailableMembersForType CALLED ==========");
		System.out.println("Type name: " + typeName);
		System.out.println("Member filter: " + (memberFilter != null ? "'" + memberFilter + "'" : "null (default to *)"));

		if (typeName == null || typeName.trim().isEmpty())
		{
			return "Error: typeName parameter is required";
		}

		try
		{
			// Get TypeCreator instance
			TypeCreator typeCreator = TypeProviderFactory.getTypeProvider().getTypeCreator();
			if (typeCreator == null)
			{
				System.out.println("ERROR: TypeCreator instance not available");
				return "Error: TypeCreator not available";
			}

			// Resolve type via TypeCreator
			Type type = typeCreator.findType(null, typeName);

			// If not found, try scriptingName mapping
			if (type == null)
			{
				String scriptingName = mapClassNameToScriptingName(typeName);
				if (scriptingName != null && !scriptingName.equals(typeName))
				{
					System.out.println("Type '" + typeName + "' not found, trying scriptingName: " + scriptingName);
					type = typeCreator.findType(null, scriptingName);
				}
			}

			if (type == null)
			{
				System.out.println("ERROR: Type not found: " + typeName);
				return "Error: Type '" + typeName + "' not found. Try using scriptingName like 'application' instead of 'JSApplication'.";
			}

			System.out.println("Type resolved: " + type.getName() + " (total members: " + type.getMembers().size() + ")");

			// Prepare regex pattern (default to match all)
			String filter = (memberFilter != null && !memberFilter.trim().isEmpty()) ? memberFilter.trim() : "*";
			Pattern pattern = filter.equals("*") ? null : Pattern.compile(filter, Pattern.CASE_INSENSITIVE);

			// Collect and filter members
			List<Member> methods = new ArrayList<>();
			List<Member> properties = new ArrayList<>();

			for (Member member : type.getMembers())
			{
				String memberName = member.getName();
				
				// Apply filter
				if (pattern != null)
				{
					if (!pattern.matcher(memberName).matches())
					{
						continue; // Skip non-matching members
					}
				}

				if (member instanceof Method)
				{
					methods.add(member);
				}
				else if (member instanceof Property)
				{
					properties.add(member);
				}
			}

			int totalFiltered = methods.size() + properties.size();
			System.out.println("Filtered members: " + totalFiltered + " (methods: " + methods.size() + ", properties: " + properties.size() + ")");

			// Check threshold
			final int THRESHOLD = 50;
			boolean truncated = totalFiltered > THRESHOLD;

			// Build response
			StringBuilder response = new StringBuilder();
			response.append("=== AVAILABLE MEMBERS FOR TYPE: ").append(type.getName()).append(" ===\n\n");

			if (!filter.equals("*"))
			{
				response.append("Filter: ").append(filter).append("\n");
			}
			response.append("Total found: ").append(totalFiltered).append(" members\n\n");

			// Methods section
			if (!methods.isEmpty())
			{
				response.append("METHODS (").append(methods.size()).append("):\n");
				int count = 0;
				for (Member method : methods)
				{
					if (truncated && count >= THRESHOLD)
					{
						break;
					}
					response.append("  - ").append(formatMemberSignature(method)).append("\n");
					count++;
				}
				response.append("\n");
			}

			// Properties section
			if (!properties.isEmpty())
			{
				response.append("PROPERTIES (").append(properties.size()).append("):\n");
				int count = methods.size(); // Continue counting from methods
				for (Member property : properties)
				{
					if (truncated && count >= THRESHOLD)
					{
						break;
					}
					response.append("  - ").append(formatMemberSignature(property)).append("\n");
					count++;
				}
				response.append("\n");
			}

			// Add truncation warning
			if (truncated)
			{
				response.append("[WARNING: ").append(totalFiltered).append(" members found, showing first ").append(THRESHOLD);
				response.append(". Use memberFilter with regex like 'get.*', 'show.*', or 'is.*' to narrow results]\n");
			}

			System.out.println("Returning " + (truncated ? THRESHOLD : totalFiltered) + " member signatures");
			return response.toString();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error getting available members for type: " + typeName, e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool("Get full documentation for a specific member (method or property) of a Servoy API type. " +
		"Works without any file or editor context - queries TypeCreator directly.")
	public String getDocumentationForTypeMember(
		@P("Type name (e.g., 'application', 'databaseManager', 'controller', 'JSApplication')") String typeName,
		@P("Member name (case-insensitive, e.g., 'closeSolution', 'getName', 'enabled')") String memberName)
	{
		System.out.println("\n========== getDocumentationForTypeMember CALLED ==========");
		System.out.println("Type name: " + typeName);
		System.out.println("Member name: " + memberName);

		if (typeName == null || typeName.trim().isEmpty())
		{
			return "Error: typeName parameter is required";
		}

		if (memberName == null || memberName.trim().isEmpty())
		{
			return "Error: memberName parameter is required";
		}

		try
		{
			// Get TypeCreator instance
			TypeCreator typeCreator = TypeProviderFactory.getTypeProvider().getTypeCreator();
			if (typeCreator == null)
			{
				System.out.println("ERROR: TypeCreator instance not available");
				return "Error: TypeCreator not available";
			}

			// Resolve type via TypeCreator
			Type type = typeCreator.findType(null, typeName);

			// If not found, try scriptingName mapping
			if (type == null)
			{
				String scriptingName = mapClassNameToScriptingName(typeName);
				if (scriptingName != null && !scriptingName.equals(typeName))
				{
					System.out.println("Type '" + typeName + "' not found, trying scriptingName: " + scriptingName);
					type = typeCreator.findType(null, scriptingName);
				}
			}

			if (type == null)
			{
				System.out.println("ERROR: Type not found: " + typeName);
				return "Error: Type '" + typeName + "' not found";
			}

			System.out.println("Type resolved: " + type.getName());

			// Search for member (case-insensitive)
			List<Member> matchingMembers = new ArrayList<>();
			for (Member member : type.getMembers())
			{
				if (member.getName().equalsIgnoreCase(memberName))
				{
					matchingMembers.add(member);
				}
			}

			if (matchingMembers.isEmpty())
			{
				System.out.println("ERROR: Member '" + memberName + "' not found in type: " + type.getName());
				return "Error: Member '" + memberName + "' not found in type '" + type.getName() + "'";
			}

			System.out.println("Found " + matchingMembers.size() + " matching member(s)");

			// Build response with full documentation
			StringBuilder response = new StringBuilder();
			response.append("=== DOCUMENTATION FOR: ").append(type.getName()).append(".").append(memberName).append(" ===\n\n");

			if (matchingMembers.size() > 1)
			{
				response.append("[Note: ").append(matchingMembers.size()).append(" overloads found]\n\n");
			}

			// Format each overload
			int overloadNum = 1;
			for (Member member : matchingMembers)
			{
				if (matchingMembers.size() > 1)
				{
					response.append("--- OVERLOAD ").append(overloadNum).append(" of ").append(matchingMembers.size()).append(" ---\n");
				}

				response.append(formatMemberDocumentation(member, type.getName()));
				response.append("\n");

				overloadNum++;
			}

			System.out.println("Returning documentation for " + matchingMembers.size() + " overload(s)");
			return response.toString();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error getting documentation for member: " + typeName + "." + memberName, e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Maps Java class names to @ServoyDocumented scriptingName values.
	 * Only needed for global Servoy API objects registered via ScriptObjectRegistry.
	 */
	private String mapClassNameToScriptingName(String className)
	{
		if (className == null)
		{
			return null;
		}

		return switch (className)
		{
			case "JSApplication" -> "application";
			case "JSDatabaseManager" -> "databaseManager";
			case "JSSecurity" -> "security";
			case "JSI18N" -> "i18n";
			case "JSUtils" -> "utils";
			case "JSForm" -> "controller";
			case "JSEventsManager" -> "eventsManager";
			case "JSSolutionModel" -> "solutionModel";
			default -> null;
		};
	}

	/**
	 * Formats a member signature without full documentation (lightweight).
	 * Used by getAvailableMembersForType.
	 */
	private String formatMemberSignature(Member member)
	{
		if (member == null)
		{
			return "";
		}

		StringBuilder sb = new StringBuilder();
		sb.append(member.getName());

		if (member instanceof Method method)
		{
			sb.append("(");
			List<Parameter> params = method.getParameters();
			if (params != null && !params.isEmpty())
			{
				for (int i = 0; i < params.size(); i++)
				{
					Parameter param = params.get(i);
					sb.append(param.getName());
					if (param.getType() != null)
					{
						sb.append(":").append(param.getType().getName());
					}
					if (i < params.size() - 1)
					{
						sb.append(", ");
					}
				}
			}
			sb.append(")");

			// Add return type if available
			if (method.getType() != null)
			{
				sb.append(": ").append(method.getType().getName());
			}
		}
		else if (member instanceof Property property)
		{
			// Add property type if available
			if (property.getType() != null)
			{
				sb.append(": ").append(property.getType().getName());
			}
		}

		return sb.toString();
	}

	/**
	 * Formats full documentation for a member (used by getDocumentationForTypeMember).
	 * Includes signature, description, parameters, and return type.
	 */
	private String formatMemberDocumentation(Member member, String typeName)
	{
		if (member == null)
		{
			return "";
		}

		StringBuilder sb = new StringBuilder();

		// Signature
		sb.append("SIGNATURE: ").append(typeName).append(".").append(formatMemberSignature(member)).append("\n\n");

		// Description
		String description = member.getDescription();
		if (description != null && !description.trim().isEmpty())
		{
			sb.append("DESCRIPTION:\n").append(description).append("\n\n");
		}

		// For methods, add detailed parameter and return type info
		if (member instanceof Method method)
		{
			List<Parameter> params = method.getParameters();
			if (params != null && !params.isEmpty())
			{
				sb.append("PARAMETERS:\n");
				for (Parameter param : params)
				{
					sb.append("  - ").append(param.getName());
					if (param.getType() != null)
					{
						sb.append(" (").append(param.getType().getName()).append(")");
					}
					sb.append("\n");
				}
				sb.append("\n");
			}

			if (method.getType() != null)
			{
				sb.append("RETURNS: ").append(method.getType().getName()).append("\n");
			}
		}

		// Deprecation info
		if (member.isDeprecated())
		{
			sb.append("\n[DEPRECATED]");
			if (description != null && description.toLowerCase().contains("deprecated"))
			{
				// Description already mentions deprecation
			}
			else
			{
				sb.append(" This member is deprecated.");
			}
			sb.append("\n");
		}

		return sb.toString();
	}
}
