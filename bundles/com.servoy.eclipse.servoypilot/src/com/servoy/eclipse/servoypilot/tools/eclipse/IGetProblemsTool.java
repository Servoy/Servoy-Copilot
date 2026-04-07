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
package com.servoy.eclipse.servoypilot.tools.eclipse;

import java.util.Optional;

import com.servoy.eclipse.servoypilot.tools.ResourceService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IGetProblemsTool
{
	@Tool("Gets all problems (errors and warnings) from the Eclipse Problems view. Use this to identify compilation errors, warnings, and other issues in the workspace.")
	default String getProblems(
		@P(value = "Filter by severity: 'ERROR', 'WARNING', 'INFO', or omit for all", required = false) String severity,
		@P(value = "Optional project name to limit results to a specific project", required = false) String projectName,
		@P(value = "Optional file pattern (glob) to filter files, e.g., '*.js'", required = false) String filePattern,
		@P(value = "Maximum number of results to return (default: 100)", required = false) String maxResults)
	{
		Integer limit = Optional.ofNullable(maxResults).map(Integer::parseInt).orElse(100);
		return String.join("\n", ResourceService.getInstance().getProblems(severity, projectName, filePattern, limit));
	}
}
