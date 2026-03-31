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
import org.eclipse.dltk.javascript.typeinfo.model.Member;
import org.eclipse.dltk.javascript.typeinfo.model.Method;
import org.eclipse.dltk.javascript.typeinfo.model.Parameter;
import org.eclipse.dltk.javascript.typeinfo.model.Property;
import org.eclipse.dltk.javascript.typeinfo.model.Type;
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
	@Tool("Returns the currently active code in the editor — either the selected text or the entire file if nothing is selected. " +
		"Returns: FILE (workspace-relative path), START_LINE, END_LINE, TOTAL_LINES, and the code with 0-based line numbers. " +
		"Does NOT return Servoy API documentation — request that separately via getDocumentationForIdentifiers.")
	public String getCurrentSelection()
	{
		System.out.println("[getCurrentSelection] ===== TOOL CALLED =====");
		try
		{
			// Get current selection from tracker
			SelectionTracker tracker = SelectionTracker.getInstance();
			Optional<SelectionInfo> selectionOpt = tracker.getCurrentSelection();

			if (selectionOpt.isPresent())
			{
				SelectionInfo selection = selectionOpt.get();
				System.out.println("[getCurrentSelection] Active selection: file=" + selection.getFilePath() +
					", startLine=" + selection.getStartLine() + ", endLine=" + selection.getEndLine());

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

		System.out.println("[getCurrentSelection] No active editor or selection available");
		return "No active editor or selection available";
	}

	@Tool("Returns Servoy API documentation for a list of identifiers. " +
		"Accepts full method paths (e.g. 'databaseManager.getFoundSet', 'foundset.loadAllRecords') and Servoy types (e.g. JSEvent, JSRecord, QBSelect). " +
		"Returns descriptions, parameter types, and return types for each identifier.")
	public String getDocumentationForIdentifiers(
		@P("Array of full identifier paths to look up (e.g., ['databaseManager.getFoundSet', 'JSRecord', 'plugins.dialogs.showInfoDialog'])") String[] identifiers,
		@P("File path (form name, scope name, or full path) — always provide this when working without an active editor selection") String filePath)
	{
		System.out.println("[getDocumentationForIdentifiers] ===== TOOL CALLED ===== identifiers=" +
			(identifiers == null ? "null" : java.util.Arrays.toString(identifiers)) + ", filePath=" + filePath);
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

				System.out.println("[getDocumentationForIdentifiers] Done: " + foundCount + "/" + identifiers.length + " identifiers found");
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
	 * @param items List of documentation items (line range + jsdoc)
	 * @return Success message or error message
	 */
	@Tool("Writes JSDoc documentation items to a Servoy JavaScript file. " +
		"Supports INSERT (no existing JSDoc) and REPLACE (existing JSDoc). " +
		"Items are applied bottom-to-top automatically to preserve line numbers. " +
		"UUID values in @properties lines are automatically restored if accidentally changed.")
	public String applyDocumentations(
		@P("Workspace-relative file path (e.g., '/svyPilotTest/utils.js')") String filePath,
		@P("List of documentation items — each specifies startLine, endLine, startSentence, endSentence, and the jsdoc string. " +
			"INSERT: startLine==endLine, startSentence and endSentence are empty strings. " +
			"REPLACE: startLine/endLine cover the existing JSDoc block, startSentence='/**', endSentence='*/'") List<DocumentationItem> items)
	{
		System.out.println("[applyDocumentations] ===== TOOL CALLED ===== filePath=" + filePath + ", items=" + (items == null ? "null" : items.size()));

		if (filePath != null && !filePath.isBlank() && items != null && !items.isEmpty())
		{
			try
			{
				// Resolve file using FilePathResolver — same as analyzeFileStructure/getCodeChunk
				// This handles short names ("utils"), scope names, form names, and full paths
				IFile file = FilePathResolver.getInstance().resolveFile(filePath);
				if (file == null || !file.exists())
				{
					String notFound = FilePathResolver.getInstance().buildNotFoundMessage(filePath);
					System.out.println("[applyDocumentations] File not found: " + notFound);
					return "\n\n" + notFound;
				}

				// Use the canonical workspace-relative path from here on
				String resolvedPath = file.getFullPath().toString();
				System.out.println("[applyDocumentations] Resolved '" + filePath + "' → " + resolvedPath);
				ServoyLog.logInfo("Applying " + items.size() + " documentation items to: " + resolvedPath);

				// CHANGE DETECTION: compare file last-modified timestamp against prompt timestamp
				long promptTimestamp = SelectionTracker.getInstance().getPromptTimestamp();
				long fileTimestamp = file.getLocalTimeStamp();
				System.out.println("[applyDocumentations] promptTimestamp=" + promptTimestamp + ", fileTimestamp=" + fileTimestamp);

				if (promptTimestamp == 0 || fileTimestamp <= promptTimestamp)
				{
					String originalContent = new String(file.getContents().readAllBytes(), StandardCharsets.UTF_8);
					FileModificationTracker.getInstance().notifyFileModified(resolvedPath, originalContent);

					// Split content into lines
					List<String> lineList = new ArrayList<>();
					for (String line : originalContent.split("\r\n|\r|\n", -1))
					{
						lineList.add(line);
					}

					// Sort items bottom-to-top to avoid line number shifts
					List<DocumentationItem> sortedItems = new ArrayList<>(items);
					sortedItems.sort((a, b) -> Integer.compare(b.startLine(), a.startLine()));

					List<String> errors = new ArrayList<>();
					int successCount = 0;
					DocumentationValidator validator = new DocumentationValidator();

					for (DocumentationItem item : sortedItems)
					{
						try
						{
							if (item.startLine() >= 0 && item.endLine() < lineList.size())
							{
								if (item.isInsert())
								{
									String indentation = extractIndentation(lineList.get(item.startLine()));
									List<String> formattedLines = new ArrayList<>();
									for (String jsdocLine : item.jsdoc().split("\n"))
									{
										formattedLines.add(indentation + jsdocLine);
									}
									lineList.addAll(item.startLine(), formattedLines);
									successCount++;
								}
								else
								{
									String startLineContent = lineList.get(item.startLine()).trim();
									String endLineContent = lineList.get(item.endLine()).trim();

									if (startLineContent.startsWith(item.startSentence()) && endLineContent.endsWith(item.endSentence()))
									{
										StringBuilder replacedContent = new StringBuilder();
										for (int i = item.startLine(); i <= item.endLine(); i++)
										{
											replacedContent.append(lineList.get(i)).append("\n");
										}
										List<String> originalUUIDs = validator.extractUUIDs(replacedContent.toString());
										String fixedJSDoc = validator.restoreUUIDs(item.jsdoc(), originalUUIDs);

										String indentation = extractIndentation(lineList.get(item.startLine()));
										List<String> formattedLines = new ArrayList<>();
										for (String jsdocLine : fixedJSDoc.split("\n"))
										{
											formattedLines.add(indentation + jsdocLine);
										}
										for (int i = item.endLine(); i >= item.startLine(); i--)
										{
											lineList.remove(i);
										}
										lineList.addAll(item.startLine(), formattedLines);

										try
										{
											validator.validateJSDocSyntax(fixedJSDoc);
											successCount++;
										}
										catch (ValidationException ve)
										{
											String error = "JSDoc validation failed for lines " + item.startLine() + "-" + item.endLine() + ": " +
												ve.getMessage();
											errors.add(error);
											ServoyLog.logInfo(error);
										}
									}
									else
									{
										String error = "Validation failed at lines " + item.startLine() + "-" + item.endLine() +
											": start='" + startLineContent.substring(0, Math.min(20, startLineContent.length())) +
											"' end='" + endLineContent.substring(Math.max(0, endLineContent.length() - 20)) + "'";
										errors.add(error);
										ServoyLog.logInfo(error);
									}
								}
							}
							else
							{
								String error = "Line range out of bounds: " + item.startLine() + "-" + item.endLine() +
									" (file has " + lineList.size() + " lines)";
								errors.add(error);
								ServoyLog.logInfo(error);
							}
						}
						catch (Exception e)
						{
							String error = "Failed to process lines " + item.startLine() + "-" + item.endLine() + ": " + e.getMessage();
							errors.add(error);
							ServoyLog.logError(error, e);
						}
					}

					// Rebuild and write file
					StringBuilder newContent = new StringBuilder();
					for (int i = 0; i < lineList.size(); i++)
					{
						if (i > 0)
						{
							newContent.append("\n");
						}
						newContent.append(lineList.get(i));
					}

					System.out.println(
						"[applyDocumentations] Writing file - successCount=" + successCount + ", errors=" + errors.size() + ", newLines=" + lineList.size());
					file.setContents(
						new ByteArrayInputStream(newContent.toString().getBytes(StandardCharsets.UTF_8)),
						true, false, null);
					System.out.println("[applyDocumentations] File written OK: " + resolvedPath);

					clearEditorSelection(file, 0);

					if (errors.isEmpty())
					{
						ServoyLog.logInfo("Successfully applied " + successCount + " documentation items to: " + resolvedPath);
						return String.format("\n\nSuccess: Applied %d documentation items to %s", successCount, resolvedPath);
					}

					ServoyLog.logInfo("Partial success: Applied " + successCount + "/" + items.size() + " items, " + errors.size() + " errors");
					StringBuilder response = new StringBuilder();
					response.append("\n\nPartial success: Applied ").append(successCount).append(" out of ").append(items.size())
						.append(" documentation items.\n\nErrors encountered:\n");
					for (String error : errors)
					{
						response.append("  - ").append(error).append("\n");
					}
					return response.toString();
				}

				return "\n\nERROR: File was modified after the documentation request was issued. Please re-read the file and try again.";
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error applying documentations to " + filePath, e);
				return "\n\nError: " + e.getMessage();
			}
		}

		return filePath == null || filePath.isBlank()
			? "\n\nError: File path is required"
			: "\n\nError: No documentation items provided";
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

	@Tool("Returns lightweight method and property signatures for a Servoy API type. " +
		"Returns signatures like 'getFoundSet(query): JSFoundSet', 'loadAllRecords(): Boolean'. " +
		"Truncates at 50 members — use memberFilter regex to narrow results: 'get.*' for getters, 'show.*|hide.*' for show/hide.")
	public String getAvailableMembersForType(
		@P("Servoy API type name (e.g., 'application', 'databaseManager', 'JSFoundSet', 'controller')") String typeName,
		@P("Optional regex filter for member names. Examples: 'get.*', 'is.*', 'show.*|hide.*'. Default: all members.") String memberFilter)
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

	@Tool("Returns full documentation for one specific method or property of a Servoy API type — description, all parameters, return type, and overloads. " +
		"Works without any file or editor context.")
	public String getDocumentationForTypeMember(
		@P("Servoy API type name (e.g., 'application', 'databaseManager', 'JSFoundSet')") String typeName,
		@P("Member name to look up — case-insensitive (e.g., 'getFoundSet', 'loadAllRecords', 'showInfoDialog')") String memberName)
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
