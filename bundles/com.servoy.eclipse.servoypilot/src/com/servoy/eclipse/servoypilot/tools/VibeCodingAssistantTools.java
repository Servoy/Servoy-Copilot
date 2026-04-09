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

import com.servoy.eclipse.servoypilot.tools.codecontext.IAnalyzeFileStructureTool;
import com.servoy.eclipse.servoypilot.tools.codecontext.IGetCodeChunkTool;
import com.servoy.eclipse.servoypilot.tools.codecontext.IResolveIdentifierTypeTool;
import com.servoy.eclipse.servoypilot.tools.component.bootstrap.IButtonComponentTool;
import com.servoy.eclipse.servoypilot.tools.component.bootstrap.ILabelComponentTool;
import com.servoy.eclipse.servoypilot.tools.core.IDatabaseTool;
import com.servoy.eclipse.servoypilot.tools.core.IDeleteFormsTool;
import com.servoy.eclipse.servoypilot.tools.core.IGetFormsTool;
import com.servoy.eclipse.servoypilot.tools.core.IOpenFormTool;
import com.servoy.eclipse.servoypilot.tools.core.IDeleteRelationsTool;
import com.servoy.eclipse.servoypilot.tools.core.IGetRelationsTool;
import com.servoy.eclipse.servoypilot.tools.core.IOpenRelationTool;
import com.servoy.eclipse.servoypilot.tools.core.IDeleteStyleTool;
import com.servoy.eclipse.servoypilot.tools.core.IGetStylesTool;
import com.servoy.eclipse.servoypilot.tools.core.IOpenStyleTool;
import com.servoy.eclipse.servoypilot.tools.core.IDeleteValueListsTool;
import com.servoy.eclipse.servoypilot.tools.core.IGetValueListsTool;
import com.servoy.eclipse.servoypilot.tools.core.IOpenValueListTool;
import com.servoy.eclipse.servoypilot.tools.core.ITargetTool;
import com.servoy.eclipse.servoypilot.tools.workspace.IFileSearchRegExpTool;
import com.servoy.eclipse.servoypilot.tools.workspace.IFileSearchTool;
import com.servoy.eclipse.servoypilot.tools.workspace.IFindFilesTool;
import com.servoy.eclipse.servoypilot.tools.workspace.IGetProblemsTool;
import com.servoy.eclipse.servoypilot.tools.workspace.ISearchAndReplaceTool;
import com.servoy.eclipse.servoypilot.util.ToolComposer;
import com.servoy.eclipse.tools.retrieval.IKnowledgeTool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * Declares the tool set for the VibeCodingAssistant.
 *
 * Uses ToolComposer to build tool registrations directly from the tool interfaces,
 * keeping @Tool/@P annotations as a single source of truth on the interfaces.
 */
public class VibeCodingAssistantTools
{
	public static Map<ToolSpecification, ToolExecutor> getTools()
	{
		return ToolComposer.from(
			// Code analysis
			IAnalyzeFileStructureTool.class,
			IGetCodeChunkTool.class,
			IResolveIdentifierTypeTool.class,
			// Eclipse workspace
			IFileSearchTool.class,
			IFileSearchRegExpTool.class,
			IFindFilesTool.class,
			ISearchAndReplaceTool.class,
			IGetProblemsTool.class,
			// Core Servoy: forms
			IGetFormsTool.class,
			IOpenFormTool.class,
			IDeleteFormsTool.class,
			// Core Servoy: relations
			IGetRelationsTool.class,
			IOpenRelationTool.class,
			IDeleteRelationsTool.class,
			// Core Servoy: valuelists
			IGetValueListsTool.class,
			IOpenValueListTool.class,
			IDeleteValueListsTool.class,
			// Core Servoy: styles
			IGetStylesTool.class,
			IOpenStyleTool.class,
			IDeleteStyleTool.class,
			// Components
			IButtonComponentTool.class,
			ILabelComponentTool.class,
			// Utility
			IDatabaseTool.class,
			ITargetTool.class,
			IKnowledgeTool.class
		);
	}
}