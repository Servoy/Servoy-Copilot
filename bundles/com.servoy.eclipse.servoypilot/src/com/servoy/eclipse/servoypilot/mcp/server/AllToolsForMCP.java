package com.servoy.eclipse.servoypilot.mcp.server;

import java.util.Map;

import com.servoy.eclipse.servoypilot.tools.codecontext.IAnalyzeFileStructureTool;
import com.servoy.eclipse.servoypilot.tools.codecontext.ICodeContextTool;
import com.servoy.eclipse.servoypilot.tools.codecontext.IGetCodeChunkTool;
import com.servoy.eclipse.servoypilot.tools.codecontext.IResolveIdentifierTypeTool;
import com.servoy.eclipse.servoypilot.tools.component.bootstrap.IButtonComponentTool;
import com.servoy.eclipse.servoypilot.tools.component.bootstrap.ILabelComponentTool;
import com.servoy.eclipse.servoypilot.tools.core.IDatabaseTool;
import com.servoy.eclipse.servoypilot.tools.core.IDeleteFormsTool;
import com.servoy.eclipse.servoypilot.tools.core.IDeleteRelationsTool;
import com.servoy.eclipse.servoypilot.tools.core.IDeleteStyleTool;
import com.servoy.eclipse.servoypilot.tools.core.IDeleteValueListsTool;
import com.servoy.eclipse.servoypilot.tools.core.IGetFormsTool;
import com.servoy.eclipse.servoypilot.tools.core.IGetRelationsTool;
import com.servoy.eclipse.servoypilot.tools.core.IGetStylesTool;
import com.servoy.eclipse.servoypilot.tools.core.IGetValueListsTool;
import com.servoy.eclipse.servoypilot.tools.core.IOpenFormTool;
import com.servoy.eclipse.servoypilot.tools.core.IOpenRelationTool;
import com.servoy.eclipse.servoypilot.tools.core.IOpenStyleTool;
import com.servoy.eclipse.servoypilot.tools.core.IOpenValueListTool;
import com.servoy.eclipse.servoypilot.tools.core.ITargetTool;
import com.servoy.eclipse.servoypilot.tools.testgeneration.IJSUnitCoverageTool;
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

public class AllToolsForMCP
{
	public static Map<ToolSpecification, ToolExecutor> getTools()
	{
		return ToolComposer.from(
			// Code analysis
			IAnalyzeFileStructureTool.class,
			IGetCodeChunkTool.class,
			IResolveIdentifierTypeTool.class,
			ICodeContextTool.class,
			// Eclipse workspace
			IFileSearchRegExpTool.class,
			IFileSearchTool.class,
			IFindFilesTool.class,
			IGetFileInfoTool.class,
			IGetFileOutlineTool.class,
			IGetProblemsTool.class,
			IReadFileContextTool.class,
			IReadFileLinesTool.class,
			IReadFileRangesTool.class,
			IReadFileTool.class,
			IReadFunctionTool.class,
			ISearchAndReplaceTool.class,
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
			// core Utility
			IDatabaseTool.class,
			ITargetTool.class,

			// JSUnit coverage
			IJSUnitCoverageTool.class,

			// tools retrieval package
			IWebFetchTool.class);
	}
}
