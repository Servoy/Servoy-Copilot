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
package com.servoy.eclipse.servoypilot.mcp.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.servoy.eclipse.servoypilot.Activator;
import com.servoy.eclipse.servoypilot.ai.AssistantType;
import com.servoy.eclipse.servoypilot.mcp.client.McpServerConnectionService.McpServerResult;
import com.servoy.eclipse.servoypilot.preferences.McpConfiguration;
import com.servoy.eclipse.servoypilot.preferences.McpConfiguration.McpServerConfig;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * Builds the MCP tool map for a specific {@link AssistantType}.
 *
 * Called at {@code ServoyAiModel} build time (lazy). For each enabled MCP server
 * whose tools are allowed for the given agent, this class retrieves (or lazily
 * triggers) a connection via {@link McpServerConnectionService} and adds the
 * enabled tools to the result map.
 *
 * The returned map is in the same format as built-in tools
 * ({@code Map<ToolSpecification, ToolExecutor>}) and is safe to merge directly
 * into the existing tool map passed to {@code AiServices.builder().tools()}.
 */
public class McpToolsProvider
{

	/**
	 * Returns the MCP tool map for the given assistant type.
	 * Triggers lazy connections to servers as needed.
	 */
	public static Map<ToolSpecification, ToolExecutor> getToolsForAssistant(AssistantType assistantType)
	{
		McpConfiguration config = new McpConfiguration();
		McpServerConnectionService connectionService = Activator.getDefault().getMcpServerConnectionService();
		Map<ToolSpecification, ToolExecutor> tools = new HashMap<>();

		List<McpServerConfig> servers = config.getConfiguredServers();
		for (McpServerConfig serverCfg : servers)
		{
			if (!config.isServerEnabled(serverCfg.name))
			{
				continue;
			}

			// Lazy connect
			McpServerResult result = connectionService.getOrConnect(serverCfg.name);
			if (!result.success)
			{
				// Server failed — skip it for this agent (the error is visible in the UI)
				continue;
			}

			for (ToolSpecification toolSpec : result.tools)
			{
				String toolName = toolSpec.name();
				if (!config.isToolEnabled(serverCfg.name, toolName))
				{
					continue;
				}
				if (!config.isToolEnabledForAgent(serverCfg.name, toolName, assistantType))
				{
					continue;
				}

				ToolExecutor executor = connectionService.getToolExecutor(serverCfg.name, toolSpec);
				if (executor != null)
				{
					tools.put(toolSpec, executor);
				}
			}
		}

		return tools;
	}
}
