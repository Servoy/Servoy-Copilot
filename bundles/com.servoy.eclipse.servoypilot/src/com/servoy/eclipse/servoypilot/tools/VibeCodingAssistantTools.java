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

import com.servoy.eclipse.servoypilot.tools.codeanalysis.IAnalyzeFileStructureTool;
import com.servoy.eclipse.servoypilot.tools.codeanalysis.IGetCodeChunkTool;
import com.servoy.eclipse.servoypilot.tools.codeanalysis.IResolveIdentifierTypeTool;
import com.servoy.eclipse.servoypilot.tools.component.bootstrap.button.IButtonComponentTool;
import com.servoy.eclipse.servoypilot.tools.component.bootstrap.label.ILabelComponentTool;
import com.servoy.eclipse.servoypilot.tools.core.forms.IDeleteFormsTool;
import com.servoy.eclipse.servoypilot.tools.core.forms.IGetFormsTool;
import com.servoy.eclipse.servoypilot.tools.core.forms.IOpenFormTool;
import com.servoy.eclipse.servoypilot.tools.core.relation.IDeleteRelationsTool;
import com.servoy.eclipse.servoypilot.tools.core.relation.IGetRelationsTool;
import com.servoy.eclipse.servoypilot.tools.core.relation.IOpenRelationTool;
import com.servoy.eclipse.servoypilot.tools.core.style.IDeleteStyleTool;
import com.servoy.eclipse.servoypilot.tools.core.style.IGetStylesTool;
import com.servoy.eclipse.servoypilot.tools.core.style.IOpenStyleTool;
import com.servoy.eclipse.servoypilot.tools.core.valuelist.IDeleteValueListsTool;
import com.servoy.eclipse.servoypilot.tools.core.valuelist.IGetValueListsTool;
import com.servoy.eclipse.servoypilot.tools.core.valuelist.IOpenValueListTool;
import com.servoy.eclipse.servoypilot.tools.eclipse.IFileSearchRegExpTool;
import com.servoy.eclipse.servoypilot.tools.eclipse.IFileSearchTool;
import com.servoy.eclipse.servoypilot.tools.eclipse.IFindFilesTool;
import com.servoy.eclipse.servoypilot.tools.eclipse.IGetProblemsTool;
import com.servoy.eclipse.servoypilot.tools.eclipse.ISearchAndReplaceTool;
import com.servoy.eclipse.servoypilot.tools.utility.IDatabaseTool;
import com.servoy.eclipse.servoypilot.tools.utility.IKnowledgeTool;
import com.servoy.eclipse.servoypilot.tools.utility.ITargetTool;

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