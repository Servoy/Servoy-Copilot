/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

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

import com.servoy.eclipse.servoypilot.tools.dto.QuickFixResult;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * @author emera
 */
public class QuickFixTools implements CodeContextTools, GeneratedCodeValidator
{

	@Override
	@Tool("""
		Returns code context around a given line in a Servoy JavaScript file.

		If the surrounding function is small, the full function is returned.
		If the function is large, only lines around the error are returned.

		Use this when you need to inspect the surrounding code.
		""")
	public String codeContext(
		@P(value = "File path relative to workspace or project (e.g., 'forms/myForm.js' or 'projectName/forms/myForm.js')", required = true) String filePath,
		@P("The line number provided in the Context section. Do not guess this value.") int lineNumber,
		@P("The EXACT CharacterOffset provided in the Context section. Do not guess this value.") int characterOffset) throws Exception
	{
		return CodeContextTools.super.codeContext(filePath, lineNumber, characterOffset);
	}

	@Override
	@Tool("""
		Reads the content of a .rel, .val or .dbi file in the workspace. These files are usually json based and not too big, so the full content is returned.

		Use this when you want to get the full content of a relation, valuelist or database information file.
		""")
	public String readPersistFile(
		@P(value = "File path relative to workspace or project (e.g., 'projectName/relations/<relation_name>.rel' or 'projectName/valuelists/<valuelist_name>.val')", required = true) String filePath)
		throws Exception
	{
		return CodeContextTools.super.readPersistFile(filePath);
	}

	@Override
	@Tool("""
		Validates the given quick fix edits by checking if the replacement code for each edit can be parsed as a valid statement.

		Use this tool to validate the quick fix before returning it to the caller. If the validation fails, provide feedback to the AI about which edit is invalid and why.
		""")
	public String validateQuickfixResult(QuickFixResult fix)
	{
		return GeneratedCodeValidator.super.validateQuickfixResult(fix);
	}
}