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

import com.servoy.eclipse.servoypilot.tools.workspace.IFileSearchRegExpTool;
import com.servoy.eclipse.servoypilot.tools.workspace.IFileSearchTool;
import com.servoy.eclipse.servoypilot.tools.workspace.IFindFilesTool;
import com.servoy.eclipse.servoypilot.tools.workspace.IGetFileInfoTool;
import com.servoy.eclipse.servoypilot.tools.workspace.IGetFileOutlineTool;
import com.servoy.eclipse.servoypilot.tools.workspace.IGetProblemsTool;
import com.servoy.eclipse.servoypilot.tools.workspace.IReadFileContextTool;
import com.servoy.eclipse.servoypilot.tools.workspace.IReadFileLinesTool;
import com.servoy.eclipse.servoypilot.tools.workspace.IReadFileRangesTool;
import com.servoy.eclipse.servoypilot.tools.workspace.IReadFileTool;
import com.servoy.eclipse.servoypilot.tools.workspace.IReadFunctionTool;
import com.servoy.eclipse.servoypilot.tools.workspace.ISearchAndReplaceTool;
import com.servoy.eclipse.servoypilot.util.ToolComposer;
import com.servoy.eclipse.tools.retrieval.IWebFetchTool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * Declares the tool set for the ReviewAssistant.
 *
 * Uses ToolComposer to build tool registrations directly from the tool interfaces,
 * keeping @Tool/@P annotations as a single source of truth on the interfaces.
 */
public class ReviewAssistantTools
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
			IWebFetchTool.class);
	}
}
