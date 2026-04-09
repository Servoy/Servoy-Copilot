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
package com.servoy.eclipse.servoypilot.tools.documentation;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.chatview.parts.FileModificationTracker;
import com.servoy.eclipse.servoypilot.context.SelectionTracker;
import com.servoy.eclipse.servoypilot.dto.DocumentationItem;
import com.servoy.eclipse.servoypilot.exceptions.ValidationException;
import com.servoy.eclipse.servoypilot.services.DocumentationValidatorService;
import com.servoy.eclipse.servoypilot.services.FilePathResolver;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IApplyDocumentationsTool
{
	@Tool("Writes JSDoc documentation items to a Servoy JavaScript file. " +
		"Supports INSERT (no existing JSDoc) and REPLACE (existing JSDoc). " +
		"Items are applied bottom-to-top automatically to preserve line numbers. " +
		"UUID values in @properties lines are automatically restored if accidentally changed.")
	default String applyDocumentations(
		@P("Workspace-relative file path (e.g., '/svyPilotTest/utils.js')") String filePath,
		@P("List of documentation items — each specifies startLine, endLine, startSentence, endSentence, and the jsdoc string. " +
			"INSERT: startLine==endLine, startSentence and endSentence are empty strings. " +
			"REPLACE: startLine/endLine cover the existing JSDoc block, startSentence='/**', endSentence='*/'") List<DocumentationItem> items)
	{
		if (filePath != null && !filePath.isBlank() && items != null && !items.isEmpty())
		{
			try
			{
				IFile file = FilePathResolver.getInstance().resolveFile(filePath);
				if (file == null || !file.exists())
				{
					String notFound = FilePathResolver.getInstance().buildNotFoundMessage(filePath);
					return "\n\n" + notFound;
				}

				String resolvedPath = file.getFullPath().toString();
				ServoyLog.logInfo("Applying " + items.size() + " documentation items to: " + resolvedPath);

				long promptTimestamp = SelectionTracker.getInstance().getPromptTimestamp();
				long fileTimestamp = file.getLocalTimeStamp();

				if (promptTimestamp == 0 || fileTimestamp <= promptTimestamp)
				{
					String originalContent = new String(file.getContents().readAllBytes(), StandardCharsets.UTF_8);
					FileModificationTracker.getInstance().notifyFileModified(resolvedPath, originalContent);

					List<String> lineList = new ArrayList<>();
					for (String line : originalContent.split("\r\n|\r|\n", -1))
					{
						lineList.add(line);
					}

					List<DocumentationItem> sortedItems = new ArrayList<>(items);
					sortedItems.sort((a, b) -> Integer.compare(b.startLine(), a.startLine()));

					List<String> errors = new ArrayList<>();
					int successCount = 0;
					DocumentationValidatorService validator = new DocumentationValidatorService();
					DocumentationToolsHelper helper = DocumentationToolsHelper.getInstance();

					for (DocumentationItem item : sortedItems)
					{
						try
						{
							if (item.startLine() >= 0 && item.endLine() < lineList.size())
							{
								if (item.isInsert())
								{
									String indentation = helper.extractIndentation(lineList.get(item.startLine()));
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

										String indentation = helper.extractIndentation(lineList.get(item.startLine()));
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

					StringBuilder newContent = new StringBuilder();
					for (int i = 0; i < lineList.size(); i++)
					{
						if (i > 0)
						{
							newContent.append("\n");
						}
						newContent.append(lineList.get(i));
					}

					file.setContents(
						new ByteArrayInputStream(newContent.toString().getBytes(StandardCharsets.UTF_8)),
						true, false, null);

					helper.clearEditorSelection(file, 0);

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
}
