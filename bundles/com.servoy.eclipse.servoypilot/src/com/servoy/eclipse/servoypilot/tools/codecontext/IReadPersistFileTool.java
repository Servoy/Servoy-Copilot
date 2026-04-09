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
package com.servoy.eclipse.servoypilot.tools.codecontext;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IReadPersistFileTool
{
	@Tool("""
Reads the content of a .rel, .val or .dbi file in the workspace. These files are usually json based and not too big, so the full content is returned.

Use this when you want to get the full content of a relation, valuelist or database information file.
""")
	default String readPersistFile(
@P(value = "File path relative to workspace or project (e.g., 'projectName/relations/<relation_name>.rel' or 'projectName/valuelists/<valuelist_name>.val')", required = true) String filePath)
		throws Exception
	{
		return CodeContextToolsHelper.getInstance().readPersistFileImpl(filePath);
	}
}
