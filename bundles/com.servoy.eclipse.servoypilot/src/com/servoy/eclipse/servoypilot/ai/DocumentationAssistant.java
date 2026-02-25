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
package com.servoy.eclipse.servoypilot.ai;

public interface DocumentationAssistant extends IAssistant
{
	@Override
	default AssistantType getType()
	{
		return AssistantType.DOCUMENTATION;
	}

	@Override
	default String getDisplayName()
	{
		return AssistantType.DOCUMENTATION.getDisplayName();
	}

	/**
	 * Builds the complete user prompt for documentation generation.
	 * 
	 * @param codeText the selected code (or full file content) to document
	 * @param xmlContext the extracted API documentation context in XML format
	 * @param workspaceFilePath workspace-relative file path (e.g., /ProjectName/forms/myForm.js)
	 * @param selectionOffset selection start offset
	 * @param selectionLength selection length
	 * @return the complete user prompt combining code, context, and tool parameters
	 */
	default String buildPrompt(String codeText, String xmlContext, String workspaceFilePath, int selectionOffset, int selectionLength)
	{
		StringBuilder prompt = new StringBuilder();
		prompt.append("Please generate JSDoc documentation for the following code:\n\n");
		prompt.append("```javascript\n");
		prompt.append(codeText);
		prompt.append("\n```\n\n");
		
		if (xmlContext != null && !xmlContext.trim().isEmpty())
		{
			prompt.append("API Documentation Context:\n");
			prompt.append(xmlContext);
			prompt.append("\n\n");
		}
		
		prompt.append("Generate comprehensive JSDoc comments for all functions in the code above.\n\n");
		prompt.append("When done, call applyDocumentation with these parameters:\n");
		prompt.append("- filePath: ").append(workspaceFilePath).append("\n");
		prompt.append("- selectionOffset: ").append(selectionOffset).append("\n");
		prompt.append("- selectionLength: ").append(selectionLength).append("\n");
		
		return prompt.toString();
	}
}