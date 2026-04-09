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

import java.util.Optional;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.context.SelectionTracker;
import com.servoy.eclipse.servoypilot.dto.CodeContext;
import com.servoy.eclipse.servoypilot.dto.SelectionInfo;
import com.servoy.eclipse.servoypilot.services.CodeContextService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IGetDocumentationForIdentifiersTool
{
	@Tool("Returns Servoy API documentation for a list of identifiers. " +
		"Accepts full method paths (e.g. 'databaseManager.getFoundSet', 'foundset.loadAllRecords') and Servoy types (e.g. JSEvent, JSRecord, QBSelect). " +
		"Returns descriptions, parameter types, and return types for each identifier.")
	default String getDocumentationForIdentifiers(
		@P("Array of full identifier paths to look up (e.g., ['databaseManager.getFoundSet', 'JSRecord', 'plugins.dialogs.showInfoDialog'])") String[] identifiers,
		@P("File path (form name, scope name, or full path) — always provide this when working without an active editor selection") String filePath)
	{
		if (identifiers != null && identifiers.length > 0)
		{
			try
			{
				SelectionInfo selection = null;

				if (filePath != null && !filePath.trim().isEmpty())
				{
					selection = DocumentationToolsHelper.getInstance().createSelectionInfoFromFile(filePath);
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

				int foundCount = 0;
				for (String requestedId : identifiers)
				{
					boolean found = false;
					String baseRequestedId = requestedId;
					int lastDotIndex = requestedId.lastIndexOf('.');
					if (lastDotIndex > 0)
					{
						baseRequestedId = requestedId.substring(0, lastDotIndex);
					}

					for (var identifierContext : context.getIdentifiers())
					{
						if (identifierContext.getName().equals(requestedId) || identifierContext.getName().equals(baseRequestedId))
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

		StringBuilder response = new StringBuilder();
		response.append("--- `START DOCUMENTATION ");
		response.append("Error: no identifier provided ");
		response.append("--- END DOCUMENTATION ---\n\n");
		return response.toString();
	}
}
