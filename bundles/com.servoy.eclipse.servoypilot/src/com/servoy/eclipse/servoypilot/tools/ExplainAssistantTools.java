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

import java.util.Map;

import com.servoy.eclipse.servoypilot.tools.eclipse.IFileSearchRegExpTool;
import com.servoy.eclipse.servoypilot.tools.eclipse.IFileSearchTool;
import com.servoy.eclipse.servoypilot.tools.eclipse.IFindFilesTool;
import com.servoy.eclipse.servoypilot.tools.eclipse.IGetProblemsTool;
import com.servoy.eclipse.servoypilot.tools.eclipse.ISearchAndReplaceTool;
import com.servoy.eclipse.servoypilot.tools.filereading.IGetFileInfoTool;
import com.servoy.eclipse.servoypilot.tools.filereading.IGetFileOutlineTool;
import com.servoy.eclipse.servoypilot.tools.filereading.IReadFileContextTool;
import com.servoy.eclipse.servoypilot.tools.filereading.IReadFileLinesTool;
import com.servoy.eclipse.servoypilot.tools.filereading.IReadFileRangesTool;
import com.servoy.eclipse.servoypilot.tools.filereading.IReadFileTool;
import com.servoy.eclipse.servoypilot.tools.filereading.IReadFunctionTool;
import com.servoy.eclipse.servoypilot.tools.utility.IKnowledgeTool;
import com.servoy.eclipse.servoypilot.tools.utility.IWebFetchTool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * Declares the tool set for the ExplainAssistant.
 *
 * Uses ToolComposer to build tool registrations directly from the tool interfaces,
 * keeping @Tool/@P annotations as a single source of truth on the interfaces.
 */
public class ExplainAssistantTools
{
	public static Map<ToolSpecification, ToolExecutor> getTools()
	{
		return ToolComposer.from(
			// File reading
			IReadFileTool.class,
			IReadFileLinesTool.class,
			IReadFileContextTool.class,
			IReadFileRangesTool.class,
			IReadFunctionTool.class,
			IGetFileOutlineTool.class,
			IGetFileInfoTool.class,
			// Eclipse workspace
			IFileSearchTool.class,
			IFileSearchRegExpTool.class,
			IFindFilesTool.class,
			ISearchAndReplaceTool.class,
			IGetProblemsTool.class,
			// Knowledge and web
			IKnowledgeTool.class,
			IWebFetchTool.class
		);
	}
}
